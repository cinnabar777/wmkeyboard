package com.wasimaster.wmkeyboard.core.emoji

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo

/**
 * One downloadable emoji dictionary from the wmkeyboard-data repo
 * (https://github.com/wasi-master/wmkeyboard-data): the names and search
 * keywords one language uses for the emoji the app already carries, so emoji
 * search works in it.
 *
 * The payload is a gzipped JSON array of
 * `{ emoji, name, keywords[], category }`, derived from Unicode CLDR
 * annotations via KDE/kemoji. Banglish is the one pack built rather than
 * imported: the Bengali pack's keywords in the Latin spellings people chat
 * in, with the English ones alongside (`scripts/build_bn_rom_emoji.py` in the
 * data repo). Unlike a word list it is small enough to take
 * whole — the largest is 112 KB compressed — so there is no size tier and no
 * early abort.
 */
data class EmojiDictEntry(
    /** [com.wasimaster.wmkeyboard.core.script.LanguageRegistry] id it serves. */
    val languageId: String,
    /** How many emoji the pack names; shown before downloading. */
    val emojiCount: Int,
    /** Compressed size of the file — the progress denominator. */
    val approxGzBytes: Long,
    /** File stem in the data repo; equal to [languageId] unless the repo
     * spells it differently (`no` for Norwegian Bokmål). */
    val repoCode: String = languageId,
    /** Folder in the data repo; equal to [repoCode] unless the pack lives
     * with a parent language's files (Banglish `bn_rom` sits in `bn/`). */
    val repoDir: String = repoCode,
) {
    /** The pack's file name in the data repo, and in a language pack. */
    val fileName: String get() = "${repoCode}_emoji.json.gz"

    /** The data repository, wherever [ServiceRepo.DATA] points. */
    val url: String
        get() = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl("data/$repoDir/$fileName")
}

/**
 * The downloadable emoji dictionary for every language the registry knows and
 * the data repo covers.
 *
 * 126 of the repo's 142: the codes it spells differently are remapped (`no` ->
 * `nb`), its exact duplicates are dropped (`fil` == `tl`, `pt_br` == `pt`,
 * `zh_cn` == `zh`), and so are the languages the app has no entry for
 * (`blo`, `bs`, `ccp`, `quc`, `rhg`, `zh_tw`) and the seven packs the upstream
 * data leaves all but empty — `bgn`, `ceb`, `ckb`, `mni`, `su`, `syr` and
 * `vec` carry between 1 and 16 emoji, so offering them would be offering a
 * download that changes nothing.
 *
 * Counts and sizes are display and progress hints, not checksums, so drift
 * against a newer repo is harmless. Regenerate with
 * `tools/emoji/generate_dict_catalog.py`.
 */
object EmojiDictCatalog {

    private fun entry(
        languageId: String,
        emojiCount: Int,
        approxGzBytes: Long,
        repoCode: String = languageId,
        repoDir: String = repoCode,
    ) = EmojiDictEntry(languageId, emojiCount, approxGzBytes, repoCode, repoDir)

    val entries: List<EmojiDictEntry> = listOf(
        entry("af", 3943, 86736L),
        entry("ak", 3681, 75323L),
        entry("am", 3835, 89872L),
        entry("ar", 3943, 93848L),
        entry("as", 3942, 77419L),
        entry("ast", 281, 3472L),
        entry("az", 3943, 78968L),
        entry("ba", 3912, 77229L),
        entry("be", 3943, 78236L),
        entry("bew", 288, 3915L),
        entry("bg", 3943, 87434L),
        entry("bn", 3943, 93862L),
        entry("bn_rom", 3943, 129656L, repoDir = "bn"),
        entry("br", 1400, 24287L),
        entry("ca", 3943, 86524L),
        entry("chr", 3940, 74620L),
        entry("cs", 3943, 109075L),
        entry("cv", 3940, 95078L),
        entry("cy", 3943, 63603L),
        entry("da", 3679, 71389L),
        entry("de", 3943, 83984L),
        entry("dsb", 3940, 97755L),
        entry("el", 3943, 94053L),
        entry("en", 3943, 74253L),
        entry("es", 3943, 70182L),
        entry("et", 3943, 71748L),
        entry("eu", 3943, 67277L),
        entry("fa", 3943, 86596L),
        entry("ff", 361, 6747L),
        entry("fi", 3943, 73520L),
        entry("fo", 3927, 66728L),
        entry("fr", 3943, 85077L),
        entry("frr", 3678, 47137L),
        entry("ga", 3943, 85625L),
        entry("gd", 3942, 73124L),
        entry("gl", 3943, 68431L),
        entry("gu", 3943, 106288L),
        entry("ha", 3822, 92158L),
        entry("he", 3943, 76026L),
        entry("hi", 3942, 93378L),
        entry("hr", 3943, 78905L),
        entry("hsb", 3940, 103281L),
        entry("hu", 3943, 82701L),
        entry("hy", 3943, 70845L),
        entry("ia", 377, 5908L),
        entry("id", 3941, 77496L),
        entry("ig", 3679, 72637L),
        entry("is", 3943, 70646L),
        entry("it", 3943, 89154L),
        entry("ja", 3943, 79807L),
        entry("jv", 3941, 67928L),
        entry("ka", 3943, 77232L),
        entry("kab", 2093, 29086L),
        entry("kk", 3943, 78499L),
        entry("kl", 1200, 25934L),
        entry("km", 3943, 79041L),
        entry("kn", 3943, 112234L),
        entry("ko", 3943, 88946L),
        entry("kok", 3940, 77482L),
        entry("ku", 310, 3794L),
        entry("ky", 3943, 71370L),
        entry("lb", 2799, 49050L),
        entry("lij", 2721, 35422L),
        entry("lo", 3942, 77528L),
        entry("lt", 3943, 78526L),
        entry("lv", 3943, 75605L),
        entry("mi", 3069, 47369L),
        entry("mk", 3943, 87066L),
        entry("ml", 3943, 85672L),
        entry("mn", 3943, 71808L),
        entry("mr", 3942, 98346L),
        entry("ms", 3943, 73867L),
        entry("mt", 2798, 49920L),
        entry("my", 3943, 81271L),
        entry("nb", 3943, 70273L, repoCode = "no"),
        entry("ne", 3835, 76941L),
        entry("nl", 3943, 79110L),
        entry("nn", 2092, 34195L),
        entry("nso", 2798, 49293L),
        entry("oc", 496, 6278L),
        entry("om", 3535, 74074L),
        entry("or", 3835, 78665L),
        entry("pa", 3943, 94714L),
        entry("pap", 180, 2121L),
        entry("pcm", 3940, 77020L),
        entry("pl", 3943, 82513L),
        entry("ps", 3943, 76103L),
        entry("pt", 3943, 80636L),
        entry("qu", 3567, 61404L),
        entry("rm", 3912, 85300L),
        entry("ro", 3943, 91820L),
        entry("ru", 3943, 93725L),
        entry("rw", 2799, 50768L),
        entry("sat", 572, 8341L),
        entry("sc", 1742, 41887L),
        entry("sd", 3943, 74087L),
        entry("shn", 3905, 82634L),
        entry("si", 3943, 77379L),
        entry("sk", 3943, 91612L),
        entry("sl", 3940, 78910L),
        entry("so", 3943, 73094L),
        entry("sq", 3943, 79832L),
        entry("sr", 3943, 81391L),
        entry("sv", 3941, 70592L),
        entry("sw", 3941, 75333L),
        entry("ta", 3943, 93893L),
        entry("te", 3943, 111806L),
        entry("tg", 2799, 54470L),
        entry("th", 3943, 79573L),
        entry("ti", 3940, 83124L),
        entry("tk", 3943, 68595L),
        entry("tl", 3941, 78350L),
        entry("tn", 2798, 50195L),
        entry("to", 3939, 54099L),
        entry("tr", 3943, 81049L),
        entry("ug", 2799, 49711L),
        entry("uk", 3943, 96207L),
        entry("ur", 3943, 95863L),
        entry("uz", 3943, 74529L),
        entry("vi", 3943, 75145L),
        entry("wo", 2846, 51503L),
        entry("xh", 2799, 50391L),
        entry("yo", 3943, 73511L),
        entry("yue", 3943, 63927L),
        entry("zh", 3943, 80214L),
        entry("zu", 3835, 67604L),
    )

    init {
        check(entries.map { it.languageId }.toSet().size == entries.size) {
            "one emoji dictionary per language"
        }
    }

    fun forLanguage(langId: String): EmojiDictEntry? =
        entries.firstOrNull { it.languageId == langId }

    /** True when [langId] has a dictionary worth offering. */
    fun has(langId: String): Boolean = forLanguage(langId) != null

    /** The entry whose pack is called [name], gzip or not (`de_emoji.json.gz`, `de_emoji.json`). */
    fun byFileName(name: String): EmojiDictEntry? {
        val gz = if (name.endsWith(".json")) "$name.gz" else name
        return entries.firstOrNull { it.fileName == gz }
    }
}
