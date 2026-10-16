package no.nav.tsm.ocr.template.gcp

import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import java.io.File
import java.time.Instant

/** The buckets syfosmpapirmottak uploads scanned documents to (`TSM_OCR_BUCKET`). Any bucket name can be typed. */
internal val KNOWN_BUCKETS = listOf("ocr-papirsykmelding-bucket-prod", "ocr-papirsykmelding-bucket-dev")

/** One object in the bucket, with the metadata shown in the list and the details panel. */
data class BucketFile(
    val name: String,
    val size: Long,
    val created: Instant?,
    val updated: Instant? = created,
    val contentType: String? = null,
    val md5: String? = null,
    val crc32c: String? = null,
    val generation: Long? = null,
    val storageClass: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    val fileName: String get() = name.substringAfterLast('/')
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
    val isPdf: Boolean get() = extension == "pdf" || contentType.equals("application/pdf", ignoreCase = true)
    val reference: DocumentReference? get() = DocumentReference.parse(name)
}

/** syfosmpapirmottak names objects `<journalpostId>_<dokumentInfoId>_<filUUID>.<filType>`. */
data class DocumentReference(val journalpostId: String, val dokumentInfoId: String, val fileUuid: String) {
    companion object {
        private val pattern = Regex("""(\d+)_(\d+)_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(\.[^.]+)?""")

        fun parse(name: String): DocumentReference? =
            pattern.matchEntire(name.substringAfterLast('/'))?.destructured?.let { (journalpost, dokumentInfo, uuid) ->
                DocumentReference(journalpost, dokumentInfo, uuid)
            }
    }
}

/** Read-only access to a bucket. Implementations are called off the EDT. */
interface BucketSource {
    val bucket: String

    /** Object listing, one page at a time, in the bucket's (lexicographic) order. */
    fun pages(prefix: String): Sequence<List<BucketFile>>

    fun download(name: String): ByteArray

    fun location(name: String): String = "gs://$bucket/$name"
}

class LocalFolderSource(
    private val folder: File,
    override val bucket: String = "local-${folder.name}",
    private val include: (BucketFile) -> Boolean = { true },
) : BucketSource {

    override fun pages(prefix: String): Sequence<List<BucketFile>> = sequence {
        val root = folder.absoluteFile
        yield(root.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }
            .map { file ->
                val modified = file.lastModified().takeIf { it > 0 }
                BucketFile(file.relativeTo(root).invariantSeparatorsPath, file.length(),
                    created = modified?.let(Instant::ofEpochMilli), generation = modified)
            }
            .filter { it.name.startsWith(prefix) && include(it) }
            .sortedBy { it.name }
            .toList())
    }

    override fun download(name: String): ByteArray = resolve(name).readBytes()

    override fun location(name: String): String = resolve(name).path

    private fun resolve(name: String): File {
        val root = folder.canonicalFile
        return root.resolve(name).canonicalFile.also { require(it.startsWith(root)) { "$name is not in $folder" } }
    }
}

internal fun findTestResources(): File? =
    listOf(File("template/src/test/resources"), File("src/test/resources")).firstOrNull { it.isDirectory }?.absoluteFile

/** Uses Application Default Credentials: `gcloud auth application-default login`. */
class GcsBucketSource(override val bucket: String, private val storage: Storage = defaultStorage) : BucketSource {

    override fun pages(prefix: String): Sequence<List<BucketFile>> = sequence {
        val options = buildList {
            add(Storage.BlobListOption.pageSize(PAGE_SIZE))
            add(Storage.BlobListOption.fields(*LISTED_FIELDS))
            if (prefix.isNotEmpty()) add(Storage.BlobListOption.prefix(prefix))
        }
        var page = storage.list(bucket, *options.toTypedArray())
        while (true) {
            yield(page.values.filterNot { it.isDirectory }.map { it.toBucketFile() })
            if (!page.hasNextPage()) break
            page = page.nextPage
        }
    }

    override fun download(name: String): ByteArray = storage.readAllBytes(BlobId.of(bucket, name))

    private fun Blob.toBucketFile() = BucketFile(
        name = name,
        size = size ?: 0L,
        created = createTimeOffsetDateTime?.toInstant(),
        updated = updateTimeOffsetDateTime?.toInstant(),
        contentType = contentType,
        md5 = md5,
        crc32c = crc32c,
        generation = generation,
        storageClass = storageClass?.name(),
        metadata = metadata.orEmpty().mapNotNull { (key, value) -> value?.let { key to it } }.toMap(),
    )

    companion object {
        private const val PAGE_SIZE = 1000L
        private val LISTED_FIELDS = arrayOf(
            Storage.BlobField.NAME, Storage.BlobField.SIZE, Storage.BlobField.TIME_CREATED, Storage.BlobField.UPDATED,
            Storage.BlobField.CONTENT_TYPE, Storage.BlobField.MD5HASH, Storage.BlobField.CRC32C,
            Storage.BlobField.GENERATION, Storage.BlobField.STORAGE_CLASS, Storage.BlobField.METADATA,
        )

        /** Created on first use, off the EDT: looking up credentials reads files and may call the metadata server. */
        private val defaultStorage: Storage by lazy { StorageOptions.getDefaultInstance().service }
    }
}

/** Turns credential and permission failures into a short, actionable message. */
internal fun describeGcpError(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.toList()
    // Token errors carry the whole HTTP response as the message; the first line is enough.
    val message = chain.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }?.lineSequence()?.first()
        ?: error.javaClass.simpleName
    val code = chain.filterIsInstance<StorageException>().firstOrNull()?.code
    val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
    val unauthenticated = code == 401 || listOf("invalid_grant", "reauth", "application default credentials", "invalid_rapt")
        .any { it in text }
    return when {
        unauthenticated -> "Google Cloud credentials are missing or expired.\nRun: gcloud auth application-default login"
        code == 403 -> "$message\nYour account has no access to this bucket (check gcloud auth / IAM)."
        code == 404 -> "$message\nThe bucket or file does not exist."
        else -> message
    }
}
