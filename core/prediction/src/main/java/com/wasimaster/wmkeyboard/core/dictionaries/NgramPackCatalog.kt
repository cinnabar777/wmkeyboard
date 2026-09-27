package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo

/**
 * One downloadable n-gram pack: corpus bigram/trigram counts for a language,
 * hosted in the data repo next to its wordlists. Files are gzip text, one
 * `word... count` line each, count-descending — the same shape as every
 * other list the repo ships.
 */
class NgramPackEntry(
    /** Language id the pack serves ([com.wasimaster.wmkeyboard.core.language] registry). */
    val languageId: String,
    /** Directory under `data/` in the repo (languages share one, e.g. `bn`). */
    val repoCode: String,
    /** File stem, e.g. `bn_rom_bigrams` -> `data/bn/bn_rom_bigrams.txt.gz`. */
    val bigramStem: String,
    val trigramStem: String,
    /**
     * Roughly what the two files cost to fetch, compressed, for the prompt
     * that asks before downloading them.
     *
     * The *transferred* share, not the published size: the lists are
     * count-descending and the read stops at
     * [NgramPackDownloadManager]'s caps, so English sends about a quarter of
     * its bigram file and an eighth of its trigram one. A pack whose lists are
     * shorter than the caps is simply its full size.
     */
    val approxGzBytes: Long,
) {
    fun bigramUrl(): String = url(bigramStem)
    fun trigramUrl(): String = url(trigramStem)

    /** The two files' names in the data repo, and in a language pack. */
    val bigramFileName: String get() = "$bigramStem.txt.gz"
    val trigramFileName: String get() = "$trigramStem.txt.gz"

    /** The data repository, wherever [ServiceRepo.DATA] points. */
    private fun url(stem: String): String =
        ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl("data/$repoCode/$stem.txt.gz")
}

/**
 * Every language with a published n-gram pack. Small on purpose: entries are
 * added as the data repo grows them (currently English, Bengali and romanized
 * Bengali).
 */
object NgramPackCatalog {

    val entries: List<NgramPackEntry> = listOf(
        NgramPackEntry(
            languageId = "en",
            repoCode = "en",
            bigramStem = "en_bigrams",
            trigramStem = "en_trigrams",
            approxGzBytes = 1_100_000,
        ),
        // Bengali script, for all three of its layouts. The words are spelled
        // the way `bn_full` spells them, which is NFC — and NFC leaves a nukta
        // decomposed (য + U+09BC), because U+09DF and its two siblings are
        // Unicode composition exclusions. Probhat and Jatiya type it that way
        // too. Avro does not: it commits the precomposed U+09DF, so a word it
        // transliterated will miss these pairs until the context word is
        // normalized on the way in.
        NgramPackEntry(
            languageId = "bn",
            repoCode = "bn",
            bigramStem = "bn_bigrams",
            trigramStem = "bn_trigrams",
            approxGzBytes = 1_820_000,
        ),
        // Banglish: bn_rom is its own first-class language (Latin script,
        // plain QWERTY, transliterated wordlist), so its romanized pairs
        // match that language's typing directly — the ordinary pipeline,
        // nothing Avro-specific. The files live under the repo's bn/ dir.
        NgramPackEntry(
            languageId = "bn_rom",
            repoCode = "bn",
            bigramStem = "bn_rom_bigrams",
            trigramStem = "bn_rom_trigrams",
            approxGzBytes = 315_000,
        ),
    )

    fun forLanguage(languageId: String): NgramPackEntry? =
        entries.firstOrNull { it.languageId == languageId }

    /** The pack [name] (gzip or not) belongs to, and whether it is the trigram half. */
    fun byFileName(name: String): Pair<NgramPackEntry, Boolean>? {
        val gz = if (name.endsWith(".txt")) "$name.gz" else name
        for (entry in entries) {
            if (entry.bigramFileName == gz) return entry to false
            if (entry.trigramFileName == gz) return entry to true
        }
        return null
    }
}
