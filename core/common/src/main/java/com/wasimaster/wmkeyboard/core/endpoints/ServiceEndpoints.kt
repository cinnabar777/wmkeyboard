package com.wasimaster.wmkeyboard.core.endpoints

import com.wasimaster.wmkeyboard.config.BuildConfig

/**
 * The part of the settings a service belongs to, for grouping the fields on
 * the Servers screen. The labels live in `:app`.
 */
enum class ServiceGroup { DOWNLOADS, TRANSLATE, SEARCH, MEDIA, PHOTOS, WIKIPEDIA, DICTIONARY, VOCABULARY, WEATHER, CURRENCY, AI, UPDATES }

/**
 * Every network API the keyboard calls, and the base address it calls by
 * default.
 *
 * On the F-Droid build each one can be pointed somewhere else. F-Droid marks an
 * app **TetheredNet** when it "depends entirely on a service which is impossible
 * (or not easy) to replace", and waives that when "there is a simple
 * configuration option that allows pointing the app to a running instance of an
 * alternative, publicly available, self-hostable server solution". Wikipedia's
 * own app carries the mark because wikipedia.org is hardcoded, even though
 * MediaWiki is free software. So every service here is overridable there,
 * including the free ones, and including the keyed commercial ones (for a proxy
 * or a compatible clone).
 *
 * [default] is a base address with no trailing slash: each client appends the
 * same path it always has. `{lang}` in [WIKIPEDIA] is replaced by the language.
 *
 * The three services that were already settable on that build (LibreTranslate,
 * SearXNG, a MediaWiki for photos and GIFs) keep their own fields, because a
 * blank one there means "not configured" rather than "the default".
 */
enum class ServiceEndpoint(
    /** Stable on disk. Never store [name]. */
    val id: String,
    val default: String,
    val group: ServiceGroup,
) {
    TRANSLATE_GOOGLE("translate_google", "https://translate.googleapis.com", ServiceGroup.TRANSLATE),
    TRANSLATE_CLOUD("translate_cloud", "https://translation.googleapis.com", ServiceGroup.TRANSLATE),
    BRAVE_SEARCH("brave_search", "https://api.search.brave.com", ServiceGroup.SEARCH),
    TAVILY("tavily", "https://api.tavily.com", ServiceGroup.SEARCH),
    KLIPY("klipy", "https://api.klipy.com", ServiceGroup.MEDIA),
    GIPHY("giphy", "https://api.giphy.com", ServiceGroup.MEDIA),

    /**
     * Signal's sticker storage. `cdn-ca` and not `cdn`: the two serve the same
     * objects, and `cdn.signal.org` answers with a certificate from Signal's
     * own authority, which only Signal's apps trust.
     */
    SIGNAL_STICKER_CDN("signal_sticker_cdn", "https://cdn-ca.signal.org", ServiceGroup.MEDIA),
    UNSPLASH("unsplash", "https://api.unsplash.com", ServiceGroup.PHOTOS),
    PEXELS("pexels", "https://api.pexels.com", ServiceGroup.PHOTOS),
    WIKIPEDIA("wikipedia", "https://{lang}.wikipedia.org", ServiceGroup.WIKIPEDIA),
    DICTIONARY_API("dictionary_api", "https://api.dictionaryapi.dev", ServiceGroup.DICTIONARY),

    /** Synonyms for a held word (#321). */
    DATAMUSE("datamuse", "https://api.datamuse.com", ServiceGroup.DICTIONARY),
    KAIKKI("kaikki", "https://kaikki.org", ServiceGroup.VOCABULARY),
    WIKTIONARY("wiktionary", "https://en.wiktionary.org", ServiceGroup.VOCABULARY),
    OPEN_METEO("open_meteo", "https://api.open-meteo.com", ServiceGroup.WEATHER),
    OPEN_METEO_GEOCODING("open_meteo_geocoding", "https://geocoding-api.open-meteo.com", ServiceGroup.WEATHER),
    CURRENCY_API("currency_api", "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest", ServiceGroup.CURRENCY),
    CURRENCY_API_MIRROR("currency_api_mirror", "https://latest.currency-api.pages.dev", ServiceGroup.CURRENCY),
    FRANKFURTER("frankfurter", "https://api.frankfurter.dev", ServiceGroup.CURRENCY),
    ER_API("er_api", "https://open.er-api.com", ServiceGroup.CURRENCY),
    COINBASE("coinbase", "https://api.coinbase.com", ServiceGroup.CURRENCY),
    COINGECKO("coingecko", "https://api.coingecko.com", ServiceGroup.CURRENCY),
    ANTHROPIC("anthropic", "https://api.anthropic.com", ServiceGroup.AI),
    OPENAI("openai", "https://api.openai.com", ServiceGroup.AI),
    GEMINI("gemini", "https://generativelanguage.googleapis.com", ServiceGroup.AI),
    XAI("xai", "https://api.x.ai", ServiceGroup.AI),
    DEEPSEEK("deepseek", "https://api.deepseek.com", ServiceGroup.AI),
    KEYMAN_API("keyman_api", "https://api.keyman.com", ServiceGroup.DOWNLOADS),
    KEYMAN_DOWNLOADS("keyman_downloads", "https://downloads.keyman.com", ServiceGroup.DOWNLOADS),
    ANIMATED_EMOJI("animated_emoji", "https://fonts.gstatic.com/s/e/notoemoji/latest", ServiceGroup.DOWNLOADS),
    FDROID_REPOSITORY("fdroid_repository", "https://f-droid.org", ServiceGroup.UPDATES),
    ;

    companion object {
        fun of(id: String): ServiceEndpoint? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A git repository the keyboard downloads files from, and where it lives by
 * default. Overridable on the F-Droid build like [ServiceEndpoint], through a
 * [RepoLocation] on any [GitForge].
 */
enum class ServiceRepo(
    /** Stable on disk. Never store [name]. */
    val id: String,
    val default: RepoLocation,
) {
    /** Word lists, n-gram packs, emoji and CJK dictionaries, vocabulary packs. */
    DATA("data", RepoLocation(GitForge.GITHUB, "github.com", "wasi-master", "wmkeyboard-data")),

    /** The add-on repository offered on a fresh install, and the Noto emoji font. */
    ADDONS("addons", RepoLocation(GitForge.GITHUB, "github.com", "wasi-master", "wmkeyboard-addon-repository")),

    /** The key-sound repository offered on a fresh install. */
    SOUNDS("sounds", RepoLocation(GitForge.GITHUB, "github.com", "wasi-master", "wmkeyboard-monkeytype-sounds")),

    /** The Espanso Hub's package repository, for `hub.espanso.org/<package>` links. */
    ESPANSO_HUB("espanso_hub", RepoLocation(GitForge.GITHUB, "github.com", "espanso", "hub", "main")),
    ;

    companion object {
        fun of(id: String): ServiceRepo? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Where each service actually is right now: the user's override on the F-Droid
 * build, the default everywhere else.
 *
 * A process-wide holder rather than a parameter, because the addresses are read
 * deep inside download managers and catalog entries that never see the
 * settings. The settings repository publishes into it every time it decodes the
 * settings, so any part of the app that reads settings has also brought this up
 * to date. Same shape as `PlayServices`.
 */
object ServiceEndpoints {

    private class Overrides(val bases: Map<String, String>, val repos: Map<String, RepoLocation>)

    @Volatile
    private var current = Overrides(emptyMap(), emptyMap())

    /** Replaces every override. Keys are [ServiceEndpoint.id] and [ServiceRepo.id]. */
    fun update(bases: Map<String, String>, repos: Map<String, RepoLocation>) {
        val next = current
        if (next.bases == bases && next.repos == repos) return
        current = Overrides(bases, repos)
    }

    /**
     * Honours the overrides on every build, not only the F-Droid one. Set by
     * the JVM docs screenshots (app/src/docShots), which point the tools at a
     * local stand-in server; nothing in the app sets it.
     */
    @Volatile
    var overridesEverywhere: Boolean = false

    /** The base address to call for [endpoint], with no trailing slash. */
    fun base(endpoint: ServiceEndpoint): String =
        resolveBase(endpoint, current.bases[endpoint.id], BuildConfig.ENABLE_FDROID || overridesEverywhere)

    /** [ServiceEndpoint.WIKIPEDIA] for one language. */
    fun wikipedia(lang: String): String = expandLang(base(ServiceEndpoint.WIKIPEDIA), lang)

    /** Where [repo] is fetched from. */
    fun repo(repo: ServiceRepo): RepoLocation =
        resolveRepo(repo, current.repos[repo.id], BuildConfig.ENABLE_FDROID)

    /**
     * An override is used only on the F-Droid build, and only when it reads as
     * an `http(s)://` address. Anything else (blank, a half-typed host) falls
     * back to the default rather than breaking the tool.
     */
    fun resolveBase(endpoint: ServiceEndpoint, override: String?, fdroid: Boolean): String {
        if (!fdroid) return endpoint.default
        val value = override?.trim()?.trimEnd('/').orEmpty()
        return if (isUsableBase(value)) value else endpoint.default
    }

    fun resolveRepo(repo: ServiceRepo, override: RepoLocation?, fdroid: Boolean): RepoLocation =
        if (fdroid && override != null && override.isComplete) override else repo.default

    /**
     * `http://` is accepted as well as `https://`: a self-hosted server on the
     * local network rarely has a certificate, and the app already permits
     * cleartext. Repositories are stricter, see [RepoLocation.isComplete].
     */
    fun isUsableBase(value: String): Boolean {
        val scheme = value.substringBefore("://", "")
        if (!scheme.equals("https", ignoreCase = true) && !scheme.equals("http", ignoreCase = true)) return false
        val rest = value.substringAfter("://")
        return rest.isNotEmpty() && !rest.startsWith("/") && rest.none { it.isWhitespace() }
    }

    fun expandLang(base: String, lang: String): String =
        base.replace("{lang}", lang.trim().ifEmpty { "en" }.lowercase())
}

/** [RepoLocation] as plain strings, for storage. Unknown forges read back as null. */
fun RepoLocation.toFields(): Map<String, String> =
    mapOf("forge" to forge.id, "host" to host, "owner" to owner, "repo" to repo, "ref" to ref)

fun repoLocationFromFields(fields: Map<String, String>): RepoLocation? {
    val forge = GitForge.of(fields["forge"].orEmpty()) ?: return null
    return RepoLocation(
        forge = forge,
        host = fields["host"].orEmpty(),
        owner = fields["owner"].orEmpty(),
        repo = fields["repo"].orEmpty(),
        ref = fields["ref"].orEmpty(),
    )
}
