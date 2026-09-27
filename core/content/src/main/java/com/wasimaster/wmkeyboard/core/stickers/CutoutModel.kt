package com.wasimaster.wmkeyboard.core.stickers

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import java.io.File

/**
 * The background remover the app downloads for itself, for a device where
 * Google Play services cannot supply one: U²-Net-P, the small salient-object
 * network (Qin et al. 2020, Apache-2.0), as a LiteRT graph.
 *
 * It is here and not beside the code that runs it because the storage screen
 * lists the file in both flavours, and only the full one can run it.
 *
 * The graph is the `u2netp.onnx` that rembg publishes, converted with onnx2tf,
 * and checked against that file output for output: the largest difference on
 * any of its seven maps is 3e-5. [SHA256] is what makes the address safe to
 * leave configurable: [ServiceRepo.DATA] can point at anyone's mirror, and a
 * file that is not this exact file is deleted instead of being loaded.
 */
object CutoutModel {

    const val FILE_NAME = "u2netp.tflite"

    /** Inside the data repository. */
    const val REPO_PATH = "models/cutout/$FILE_NAME"

    const val SIZE_BYTES = 4_575_464L

    const val SHA256 = "558136589a47e97f53944ac3f65ba56f4efb5f6bae8e92b573e7c463d20fdb58"

    /** The square the network was trained at, and the only one it takes. */
    const val INPUT_SIDE = 320

    /**
     * The fused map among the graph's seven outputs. The other six are the
     * per-stage side outputs the network is trained through, coarser each one,
     * and the converter left all seven in no particular order, so this is
     * asked for by name and never by position.
     */
    const val OUTPUT_NAME = "1959"

    const val INPUT_NAME = "input.1"

    const val SIGNATURE = "serving_default"

    val url: String get() = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl(REPO_PATH)

    fun dir(filesDir: File): File = File(filesDir, "cutout")

    fun file(filesDir: File): File = File(dir(filesDir), FILE_NAME)

    fun partFile(filesDir: File): File = File(dir(filesDir), "$FILE_NAME.part")

    /**
     * Whether the model is on disk. The length is the whole test: the digest
     * was checked before the file was given this name, and hashing five
     * megabytes each time the editor opens would buy nothing.
     */
    fun isDownloaded(filesDir: File): Boolean = file(filesDir).let { it.isFile && it.length() == SIZE_BYTES }

    /**
     * Installs the model from a copy the user fetched some other way. Held to
     * the same [SHA256] a download is, so only this exact graph is ever
     * loaded. Returns false, having kept nothing, for any other file.
     */
    fun install(filesDir: File, source: File): Boolean {
        if (source.length() != SIZE_BYTES) return false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != SHA256) return false
        val part = partFile(filesDir)
        dir(filesDir).mkdirs()
        try {
            source.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            file(filesDir).delete()
            return part.renameTo(file(filesDir))
        } finally {
            part.delete()
        }
    }
}
