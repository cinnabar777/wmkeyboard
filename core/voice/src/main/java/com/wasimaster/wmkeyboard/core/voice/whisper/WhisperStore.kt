package com.wasimaster.wmkeyboard.core.voice.whisper

import java.io.File

/**
 * File layout for offline Whisper models. Each catalog entry downloads into
 * `filesDir/whisper/<id>/` as two files — the `.tflite` model and its
 * `filters_vocab_*.bin` — with a `<name>.part` while a file is in flight.
 *
 * Atomic-rename contract: a final (non-.part) file exists only once its
 * download finished with the expected byte count, so a model counts as
 * downloaded exactly when **both** final files are present.
 */
object WhisperStore {

    fun rootDir(filesDir: File): File = File(filesDir, "whisper")

    /**
     * Where an imported `filters_vocab_*.bin` waits for a model to go with.
     * One vocab serves many models, and the user may bring it before the
     * graph, so it cannot go into a model's directory yet: a directory holding
     * one of its two files reads as a download half done.
     */
    fun vocabPool(filesDir: File): File = File(rootDir(filesDir), VOCAB_POOL)

    private const val VOCAB_POOL = ".vocab"

    fun modelDir(filesDir: File, model: WhisperModel): File =
        File(rootDir(filesDir), model.id)

    fun modelFile(filesDir: File, model: WhisperModel): File =
        File(modelDir(filesDir, model), model.modelFile)

    fun vocabFile(filesDir: File, model: WhisperModel): File =
        File(modelDir(filesDir, model), model.vocabFile)

    /** Both files (model, vocab) that must exist for the model to be usable. */
    fun requiredFiles(filesDir: File, model: WhisperModel): List<File> =
        listOf(modelFile(filesDir, model), vocabFile(filesDir, model))

    fun isDownloaded(filesDir: File, model: WhisperModel): Boolean =
        requiredFiles(filesDir, model).all { it.isFile } && !isStale(filesDir, model)

    /**
     * The model file on disk is a graph this entry no longer uses — one of its
     * [WhisperModel.retiredModelBytes] — so it must be fetched again rather
     * than run.
     */
    fun isStale(filesDir: File, model: WhisperModel): Boolean {
        if (model.retiredModelBytes.isEmpty()) return false
        val file = modelFile(filesDir, model)
        return file.isFile && file.length() in model.retiredModelBytes
    }

    /**
     * Deletes a [isStale] model file. The vocab beside it is kept: it did not
     * change, and the download skips a file that is already in place.
     */
    fun purgeStale(filesDir: File, model: WhisperModel) {
        if (isStale(filesDir, model)) modelFile(filesDir, model).delete()
    }

    /** True while a partial download for either file is on disk. */
    fun hasPartial(filesDir: File, model: WhisperModel): Boolean =
        requiredFiles(filesDir, model).any { File(it.parentFile, "${it.name}.part").isFile } ||
            // final model present but vocab still missing (interrupted between files)
            (!isDownloaded(filesDir, model) && requiredFiles(filesDir, model).any { it.isFile })

    /** Bytes already on disk for this model, finished and partial. */
    fun bytesOnDisk(filesDir: File, model: WhisperModel): Long =
        modelDir(filesDir, model).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * Resolves the whisperModelId setting to a downloaded model, or null when
     * nothing is selected or the files are gone.
     */
    fun selectedModel(filesDir: File, whisperModelId: String): WhisperModel? {
        if (whisperModelId.isBlank()) return null
        val model = WhisperCatalog.byId(whisperModelId) ?: return null
        return model.takeIf { isDownloaded(filesDir, it) }
    }

    /** Every catalog model whose files are both on disk. */
    fun downloadedModels(filesDir: File): List<WhisperModel> =
        WhisperCatalog.models.filter { isDownloaded(filesDir, it) }

    /** The one downloaded model's id when exactly one exists — the "only model" fallback. */
    fun soleDownloadedId(filesDir: File): String? =
        downloadedModels(filesDir).map { it.id }.singleOrNull()

    /**
     * The effective model to run: the explicit selection if downloaded, else
     * the sole downloaded model. Null when none are ready.
     */
    fun effectiveModel(filesDir: File, whisperModelId: String): WhisperModel? =
        selectedModel(filesDir, whisperModelId)
            ?: soleDownloadedId(filesDir)?.let { WhisperCatalog.byId(it) }

    /**
     * The model that should transcribe [languageId] — the language of the layout
     * being typed in, so dictation follows the keyboard the way the system
     * recognizer's locale does.
     *
     * In order: the model the user pinned to this language; a single-language
     * graph for it that is already downloaded (downloading `base.de` is itself
     * the statement that German should use it); the fallback [defaultModelId];
     * then the best of whatever else is on disk. The last step can return a model
     * that does not cover the language at all — dictating then produces wrong
     * words rather than nothing, which the settings screen warns about, and is
     * still better than a mic that silently does nothing.
     */
    fun modelForLanguage(
        filesDir: File,
        languageId: String,
        defaultModelId: String,
        modelByLang: Map<String, String>,
    ): WhisperModel? =
        pickForLanguage(downloadedModels(filesDir), languageId, defaultModelId, modelByLang)

    /**
     * [modelForLanguage] over an already-known downloaded set — the settings screen
     * tracks that set in state, so routing can be shown per language without
     * walking the filesystem on every recomposition.
     */
    fun pickForLanguage(
        downloaded: List<WhisperModel>,
        languageId: String,
        defaultModelId: String,
        modelByLang: Map<String, String>,
    ): WhisperModel? {
        if (downloaded.isEmpty()) return null
        val code = WhisperLanguages.codeForLanguage(languageId)

        modelByLang[languageId]
            ?.let { id -> downloaded.firstOrNull { it.id == id } }
            ?.let { if (code == null || it.covers(code)) return it }

        if (code != null) {
            WhisperCatalog.singleLanguageFor(code)
                .filter { model -> downloaded.any { it.id == model.id } }
                .maxByOrNull { it.sizeBytes }
                ?.let { return it }
        }

        downloaded.firstOrNull { it.id == defaultModelId }
            ?.let { if (code == null || it.covers(code)) return it }

        if (code != null) {
            WhisperCatalog.rankedFor(code, downloaded).firstOrNull()?.let { return it }
        }
        return downloaded.firstOrNull { it.id == defaultModelId } ?: downloaded.first()
    }

    /** Directories left by models dropped from the catalog — offered for cleanup. */
    fun orphanDirs(filesDir: File): List<File> {
        val known = WhisperCatalog.models.map { it.id }.toSet()
        return rootDir(filesDir).listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in known && it.name != VOCAB_POOL }
    }

    fun orphanBytes(filesDir: File): Long =
        orphanDirs(filesDir).sumOf { dir ->
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }

    fun deleteOrphans(filesDir: File) {
        orphanDirs(filesDir).forEach { it.deleteRecursively() }
    }

    fun delete(filesDir: File, model: WhisperModel) {
        modelDir(filesDir, model).deleteRecursively()
    }

    fun totalBytesUsed(filesDir: File): Long =
        rootDir(filesDir).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
