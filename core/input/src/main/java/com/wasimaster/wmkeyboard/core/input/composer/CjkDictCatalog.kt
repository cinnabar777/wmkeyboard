package com.wasimaster.wmkeyboard.core.input.composer

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.input.R
import java.io.File

/**
 * One downloadable CJK conversion-dictionary pack. These tables are large and
 * useful only to a minority, so they are NOT bundled — the pack is the only
 * source. Until it is downloaded the composer types the raw reading with no
 * candidates. Checksum-verified after download (a corrupt table is worse than
 * none); see [ConversionDictionary].
 */
data class CjkDictPack(
    /** Stable key — storage subdir and the bundled asset base name (`pinyin`). */
    val id: String,
    /** Owning language id (`zh` / `ja`) — gates which detail screen shows it. */
    val langId: String,
    /** Row title for the pack; the UI layer resolves it. */
    @StringRes val displayNameRes: Int,
    /** One-line description for the pack row; the UI layer resolves it. */
    @StringRes val descriptionRes: Int,
    /**
     * The pack's path inside the data repository. Blank until the maintainer
     * fills it in. A mirror has to serve the same bytes: [sha256] is checked.
     */
    val path: String,
    /** Approximate size — preflight space check and progress fallback. */
    val sizeBytes: Long,
    /** Lowercase-hex SHA-256 of the downloaded file; verified before it goes live. */
    val sha256: String,
    /** On-disk file name for the downloaded pack. */
    val fileName: String,
) {
    /** Where the pack is fetched from: the data repository, wherever [ServiceRepo.DATA] points. */
    val url: String get() = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl(path)

    /** A pack can only be offered once it has a path and a checksum. */
    val available: Boolean get() = path.isNotBlank() && sha256.isNotBlank()
}

/**
 * The curated CJK dictionary packs, one per input scheme: CC-CEDICT-derived
 * Hanzi for Pinyin, a Mozc-derived kana→kanji table for Japanese, and the
 * jyutping/stroke/cangjie tables from their sources in the data repo. A pack
 * whose URL or checksum is blank is [CjkDictPack.available] == false and the
 * settings row says so.
 */
object CjkDictCatalog {

    val packs: List<CjkDictPack> = listOf(
        CjkDictPack(
            id = "pinyin",
            langId = "zh",
            displayNameRes = R.string.core_input_cjk_pack_pinyin_title,
            descriptionRes = R.string.core_input_cjk_pack_pinyin_subtitle,
            path = "cjk/pinyin.tsv",
            sizeBytes = 2_489_699L,
            sha256 = "8baab4c758499272e36dba4bda4253317a4f93bb88bcf386ea86196c50d73715",
            fileName = "pinyin.tsv",
        ),
        CjkDictPack(
            id = "ja_kana",
            langId = "ja",
            displayNameRes = R.string.core_input_cjk_pack_ja_kana_title,
            descriptionRes = R.string.core_input_cjk_pack_ja_kana_subtitle,
            path = "cjk/ja_kana.tsv",
            sizeBytes = 41_531_397L,
            sha256 = "189214b81968c857d7cb020c52fc087ee44918ab28534194f79ea66f45c17a70",
            fileName = "ja_kana.tsv",
        ),
        CjkDictPack(
            id = "stroke",
            langId = "zh",
            displayNameRes = R.string.core_input_cjk_pack_stroke_title,
            descriptionRes = R.string.core_input_cjk_pack_stroke_subtitle,
            path = "cjk/stroke.tsv",
            sizeBytes = 445_686L,
            sha256 = "b3bb4669cf8e411fd63b3a15e07e32db84ed1f2cfe09fb67066866ebe9b2278c",
            fileName = "stroke.tsv",
        ),
        CjkDictPack(
            id = "cangjie",
            langId = "zh",
            displayNameRes = R.string.core_input_cjk_pack_cangjie_title,
            descriptionRes = R.string.core_input_cjk_pack_cangjie_subtitle,
            path = "cjk/cangjie.tsv",
            sizeBytes = 351_337L,
            sha256 = "77a5a4c054019e3a3ea875e86a37cb08b71ce29392b6680208bb0c5749feb25d",
            fileName = "cangjie.tsv",
        ),
        CjkDictPack(
            id = "jyutping",
            langId = "yue",
            displayNameRes = R.string.core_input_cjk_pack_jyutping_title,
            descriptionRes = R.string.core_input_cjk_pack_jyutping_subtitle,
            path = "cjk/jyutping.tsv",
            sizeBytes = 3_416_241L,
            sha256 = "cc0666179df615846328ed1e86b621919553d8ff30905f30d0b6ff45315bcfba",
            fileName = "jyutping.tsv",
        ),
    )

    init {
        check(packs.map { it.id }.toSet().size == packs.size) { "pack ids must be unique" }
    }

    fun byId(id: String): CjkDictPack? = packs.firstOrNull { it.id == id }

    /** The pack published as [name] (`pinyin.tsv`), by the file name in the data repo. */
    fun byFileName(name: String): CjkDictPack? =
        packs.firstOrNull { it.available && it.path.substringAfterLast('/') == name }

    fun forLang(langId: String): List<CjkDictPack> = packs.filter { it.langId == langId }
}

/**
 * File layout for downloaded packs: `filesDir/cjk_dicts/<id>/<fileName>` with a
 * `<fileName>.part` while in flight. The atomic-rename + checksum contract means
 * a final (non-.part) file exists only if its download completed and verified,
 * so presence == valid, exactly as [com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore].
 */
object CjkDictStore {

    private fun dictsDir(filesDir: File) = File(filesDir, "cjk_dicts")

    fun packDir(filesDir: File, pack: CjkDictPack): File = File(dictsDir(filesDir), pack.id)

    fun packFile(filesDir: File, pack: CjkDictPack): File =
        File(packDir(filesDir, pack), pack.fileName)

    fun partFile(filesDir: File, pack: CjkDictPack): File =
        File(packDir(filesDir, pack), "${pack.fileName}.part")

    fun isDownloaded(filesDir: File, pack: CjkDictPack): Boolean = packFile(filesDir, pack).isFile

    fun delete(filesDir: File, pack: CjkDictPack) {
        packDir(filesDir, pack).deleteRecursively()
    }

    /** The downloaded pack file for [id] if present, else null (no candidates until downloaded). */
    fun downloadedFileFor(filesDir: File, id: String): File? =
        CjkDictCatalog.byId(id)?.let { packFile(filesDir, it).takeIf(File::isFile) }

    /**
     * A monotonically-changing token summarising which packs are on disk. The
     * service compares it against the token it last loaded from and re-parses the
     * conversion tables only when it differs — so a pack finished in Settings goes
     * live the next time a field is focused, without re-parsing on every keypress.
     */
    fun stateToken(filesDir: File): Int =
        CjkDictCatalog.packs.fold(0) { acc, pack ->
            acc * 31 + if (isDownloaded(filesDir, pack)) 1 else 0
        }
}
