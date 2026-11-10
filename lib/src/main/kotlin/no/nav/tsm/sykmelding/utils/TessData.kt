package no.nav.tsm.sykmelding.utils

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.tesseract.TessBaseAPI

internal object TessData {
    fun engine(language: String, mode: Int): TessBaseAPI {
        val model = (TessData::class.java.getResourceAsStream("/tessdata/$language.traineddata")
            ?: error("Missing /tessdata/$language.traineddata on the classpath")).use { it.readBytes() }
        val api = TessBaseAPI()
        val noConfigs: PointerPointer<BytePointer>? = null
        val started = BytePointer(model.size.toLong()).use { data ->
            data.put(model, 0, model.size)
            BytePointer(language).use { name -> api.Init(data, model.size, name, mode, noConfigs, 0, null, null, false, null) == 0 }
        }
        if (!started) {
            api.close()
            error("Could not start Tesseract with the model $language")
        }
        return api
    }
}
