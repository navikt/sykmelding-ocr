package no.nav.tsm.ocr.template.gcp

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloaded bucket files, kept in a folder under the system's temporary folder that only the current user can read,
 * so a file that is opened again — in the preview, a test, "Create template from…", another bucket window or after
 * restarting the app — is read from disk instead of downloaded again.
 *
 * Files are stored as `<bucket>/<object name>@g<generation>.<extension>`, so a re-uploaded object (new generation) is
 * downloaded again, and a cached file whose size differs from the object's is replaced. Files not used for [maxAge]
 * are deleted when the cache is created; beyond [maxBytes], the least recently used are deleted. [clear] deletes all
 * but the files in use ([using]). Safe to use from several threads: the same object is downloaded once at a time.
 */
class DownloadCache(
    root: File = DEFAULT_ROOT,
    private val maxAge: Duration = DEFAULT_MAX_AGE,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The folder the files are in; a fresh private one for this session if [root] is not the current user's. */
    val directory: File = prepare(root)

    private val downloading = ConcurrentHashMap<File, CompletableFuture<File>>()
    private val inUse = ConcurrentHashMap<File, Int>()
    /** About how many bytes the cache holds; checked against [maxBytes] after each download. */
    private val size = AtomicLong()

    data class Contents(val files: Int, val bytes: Long)

    /** What [clear] did: [deleted] files, and [kept] files that were in use. */
    data class Cleared(val deleted: Int, val kept: Int)

    init {
        runCatching { cleanUp() }
    }

    /** The downloaded copy of [file] from [bucket], if there is one; does not download. */
    fun cachedFile(bucket: String, file: BucketFile): File? = location(bucket, file).takeIf { isValid(it, file) }

    /**
     * The files downloaded from [bucket], named as in the bucket and sorted by name; does not download. Only the name,
     * size and generation are known. Files whose object name had to be changed to store them (names with '/' or other
     * characters than letters, digits, '.', '_' and '-') cannot be named, so they are left out.
     */
    fun downloads(bucket: String): List<BucketFile> {
        val folder = directory.resolve(safeName(bucket))
        val stored = folder.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
        return stored.mapNotNull { file ->
            val (stem, kind, number, extension) = STORED_NAME.matchEntire(file.name)?.destructured ?: return@mapNotNull null
            BucketFile(stem + extension, file.length(), created = null, generation = if (kind == "g") number.toLongOrNull() else null)
                .takeIf { location(bucket, it) == file }
        }.sortedBy { it.name }
    }

    /** The downloaded copy of [file], downloaded first if needed. Do not call on the EDT. */
    fun get(source: BucketSource, file: BucketFile): File {
        val target = location(source.bucket, file)
        if (isValid(target, file)) return target.also(::touch)
        val mine = CompletableFuture<File>()
        val running = downloading.putIfAbsent(target, mine)
        if (running != null) {
            return try { running.get() } catch (error: ExecutionException) { throw error.cause ?: error }
        }
        try {
            if (isValid(target, file)) return target.also(::touch).also { mine.complete(it) }
            val bytes = source.download(file.name)
            write(target, bytes)
            mine.complete(target)
            if (size.addAndGet(bytes.size.toLong()) > maxBytes) runCatching { evict(maxBytes * 9 / 10) }
            return target
        } catch (error: Throwable) {
            mine.completeExceptionally(error)
            throw error
        } finally {
            downloading.remove(target, mine)
        }
    }

    /** Runs [use] with the downloaded copy of [file]; [clear] and eviction leave it alone until [use] returns. */
    fun <T> using(source: BucketSource, file: BucketFile, use: (File) -> T): T {
        val target = location(source.bucket, file)
        inUse.merge(target, 1, Int::plus)
        try {
            return use(get(source, file))
        } finally {
            inUse.computeIfPresent(target) { _, count -> (count - 1).takeIf { it > 0 } }
        }
    }

    fun contents(): Contents = cachedFiles().let { files -> Contents(files.size, files.sumOf { it.length() }) }

    /** Deletes every downloaded file that is not in use or being downloaded. */
    fun clear(): Cleared {
        var deleted = 0
        var kept = 0
        for (file in cachedFiles()) {
            if (inUse.containsKey(file) || downloading.containsKey(file)) kept++ else if (file.delete()) deleted++
        }
        size.set(contents().bytes)
        return Cleared(deleted, kept)
    }

    /** Where [file] from [bucket] is kept. */
    internal fun location(bucket: String, file: BucketFile): File {
        val local = SaveFolders.localName(file.name)
        val dot = local.lastIndexOf('.')
        val extension = if (dot <= 0) null else local.substring(dot + 1).lowercase().takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
        val stem = if (extension == null) local else local.substring(0, dot)
        val safeStem = safeName(stem)
        val version = file.generation?.let { "g$it" } ?: "s${file.size}"
        // Names that had to be changed get a hash of the real name, so two objects never share a file.
        val unique = if (safeStem != stem || local != file.name) "-" + hash(file.name) else ""
        return directory.resolve(safeName(bucket)).resolve("$safeStem@$version$unique" + (extension?.let { ".$it" } ?: ""))
    }

    /**
     * Deletes leftover partial downloads, files not used for [maxAge], and then the least recently used files until
     * the cache holds at most [maxBytes]. Returns how many files were deleted.
     */
    internal fun cleanUp(): Int {
        val now = clock()
        var deleted = 0
        bucketFolders().flatMap { folder -> folder.listFiles()?.filter { it.isFile && it.name.startsWith(".") }.orEmpty() }
            .filter { now - it.lastModified() > STALE_PART_MILLIS }
            .forEach { if (it.delete()) deleted++ }
        val (old, recent) = cachedFiles().partition { now - it.lastModified() > maxAge.toMillis() }
        old.forEach { if (it.delete()) deleted++ }
        size.set(recent.sumOf { it.length() })
        return deleted + evict(maxBytes)
    }

    /** Deletes the least recently used files that are not in use until the cache holds at most [limit] bytes. */
    private fun evict(limit: Long): Int {
        val files = cachedFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var deleted = 0
        for (file in files) {
            if (total <= limit) break
            if (inUse.containsKey(file) || downloading.containsKey(file)) continue
            val length = file.length()
            if (file.delete()) { deleted++; total -= length }
        }
        size.set(total)
        return deleted
    }

    /** The buckets files have been downloaded from (their folders here), sorted; offered in the bucket field. */
    fun bucketNames(): List<String> = bucketFolders().map { it.name }.filterNot { it.startsWith("local-") }.sorted()

    private fun bucketFolders(): List<File> = directory.listFiles()?.filter { it.isDirectory }.orEmpty()

    /** Downloaded files; names starting with "." are downloads in progress. */
    private fun cachedFiles(): List<File> =
        bucketFolders().flatMap { folder -> folder.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty() }

    private fun isValid(target: File, file: BucketFile) = target.isFile && (file.size <= 0 || target.length() == file.size)

    /** Marks [file] as used now, for [maxAge] and eviction; at most once a minute. */
    private fun touch(file: File) {
        val now = clock()
        if (now - file.lastModified() > 60_000) file.setLastModified(now)
    }

    /** Via a temporary file in the same folder, so a failed download never leaves part of a file. */
    private fun write(target: File, bytes: ByteArray) {
        val folder = target.parentFile.toPath()
        createPrivateDirectory(folder)
        val temporary = Files.createTempFile(folder, ".download-", ".part")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        val DEFAULT_ROOT = File(System.getProperty("java.io.tmpdir"), "sykmelding-ocr-downloads")
        val DEFAULT_MAX_AGE: Duration = Duration.ofDays(7)
        const val DEFAULT_MAX_BYTES = 4L shl 30

        private const val MAX_STEM = 150
        private const val STALE_PART_MILLIS = 60 * 60 * 1000L
        private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
        /** A name [location] gives when it keeps the object name: `<name>@g<generation>` or `@s<size>`, then the extension. */
        private val STORED_NAME = Regex("""([^@]+)@([gs])(\d+)(\.[a-z0-9]{1,10})?""")
        private val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

        /** Shared by every bucket window of the app; created, and cleaned up, on first use. */
        val shared: DownloadCache by lazy { DownloadCache() }

        /** [root], made owner-only; a fresh private folder for this session if it belongs to someone else or cannot be used. */
        private fun prepare(root: File): File {
            val path = root.toPath().toAbsolutePath().normalize()
            return try {
                createPrivateDirectory(path)
                if (posix) {
                    val me = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
                    check(Files.getOwner(path, NOFOLLOW_LINKS) == me) { "$path belongs to another user" }
                }
                path.toFile()
            } catch (_: Exception) {
                Files.createTempDirectory("sykmelding-ocr-downloads-").toFile()
            }
        }

        /** Creates [path] readable by the current user only (on POSIX file systems), or makes an existing folder so. */
        private fun createPrivateDirectory(path: Path) {
            check(!Files.isSymbolicLink(path)) { "$path is a link" }
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                path.parent?.let { Files.createDirectories(it) }
                try {
                    if (posix) Files.createDirectory(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY)) else Files.createDirectory(path)
                } catch (_: FileAlreadyExistsException) {
                    // Created by another thread or process meanwhile.
                }
                check(Files.isDirectory(path, NOFOLLOW_LINKS)) { "$path is not a folder" }
            }
            if (posix && Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != OWNER_ONLY) Files.setPosixFilePermissions(path, OWNER_ONLY)
        }

        /** Letters, digits, '.', '_' and '-' only, not starting with '.', at most [MAX_STEM] characters. */
        private fun safeName(text: String): String =
            text.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-") it else '_' }.joinToString("")
                .take(MAX_STEM).trimStart('.').ifEmpty { "_" }

        private fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    }
}

/**
 * [online]'s files listed from [cache] instead of the bucket (see [DownloadCache.downloads]), for when the bucket cannot
 * be listed: expired credentials, no network. A listed file deleted from the cache since is downloaded from [online].
 */
internal class DownloadedFilesSource(val online: BucketSource, private val cache: DownloadCache) : BucketSource {
    override val bucket: String get() = online.bucket

    override fun pages(prefix: String): Sequence<List<BucketFile>> = sequence {
        yield(cache.downloads(bucket).filter { it.name.startsWith(prefix) })
    }

    override fun download(name: String): ByteArray = online.download(name)
}
