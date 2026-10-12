package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.core.endpoints.RepoLocation
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The network half of [ImportLink]: turning a resolved target into the one
 * file to import.
 *
 * [ImportLink] reads the address, this reads what the address answers with. A
 * release, a gist or a repository folder is a list of files, and which of them
 * is worth importing is decided on the names in that list against the
 * extensions the importer knows — the caller passes them in, because the
 * importer owns that list and lives in `:app`.
 *
 * Every request here is a GET that writes to a capped temporary file, and the
 * whole object is blocking: call it on IO. Nothing it does installs anything.
 * It ends with a file in the cache and the file importer's own dialog is what
 * asks about it.
 */
object LinkImport {

    /** One file a link turned out to offer. */
    data class Candidate(
        val name: String,
        val url: String,
        /** What the listing said the size is, or 0 when it said nothing. */
        val bytes: Long = 0L,
        /** Whether the fetch has to carry the GitHub token. */
        val auth: Boolean = false,
        /** Whether this goes through nightly.link, which the dialog has to say. */
        val viaNightlyLink: Boolean = false,
    )

    enum class Failure {
        /** No web address in the shared text. */
        NOT_A_LINK,

        /** A plain http address. */
        INSECURE,

        /** The address answered, and held nothing that can be imported. */
        NOTHING_FOUND,

        /** Offline, refused, or a non-2xx answer. */
        NETWORK,
    }

    sealed interface Outcome {
        /** Exactly one thing to fetch; the confirm dialog names it. */
        data class One(val candidate: Candidate) : Outcome

        /**
         * Several. [exact] is false when none of them is one of the importer's
         * own formats and the list is everything that was there instead, which
         * the picker says out loud.
         */
        data class Several(val candidates: List<Candidate>, val exact: Boolean) : Outcome

        /** An addon repository, which goes to the add-repository flow instead. */
        data class Repository(val manifestUrl: String, val name: String) : Outcome

        data class Failed(val reason: Failure) : Outcome
    }

    /** Ceiling on anything fetched through a link. */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** A listing longer than this is not a listing worth reading. */
    private const val MAX_LIST_BYTES = 2L * 1024 * 1024

    /** Files offered when none of them is one of ours. */
    private const val MAX_CANDIDATES = 25

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * What [shared] offers, having asked the network whatever the address could
     * not answer on its own. Blocking.
     *
     * [importable] is the set of extensions worth offering first, without
     * leading dots. [token] is the optional GitHub token; blank is the normal
     * case and only changes what an artifact link can do.
     */
    fun resolve(shared: String, importable: Set<String>, token: String, cacheDir: File): Outcome =
        when (val target = ImportLink.resolve(shared)) {
            is ImportLink.Target.None -> Outcome.Failed(Failure.NOT_A_LINK)
            is ImportLink.Target.Insecure -> Outcome.Failed(Failure.INSECURE)
            is ImportLink.Target.File -> Outcome.One(Candidate(target.name, target.url))
            is ImportLink.Target.Artifact -> Outcome.One(artifactCandidate(target, token))
            is ImportLink.Target.Listing -> listing(target, importable, token, cacheDir)
            // Not files at an address. The link importer hands a pack link to
            // the pack preview before it ever asks this.
            is ImportLink.Target.SignalStickers,
            is ImportLink.Target.TelegramStickers,
            -> Outcome.Failed(Failure.NOTHING_FOUND)
        }

    /**
     * An artifact's download, through the token when there is one and through
     * nightly.link when there is not. GitHub's own endpoint answers 403 to an
     * anonymous request even on a public repository, so there is no third way.
     */
    private fun artifactCandidate(target: ImportLink.Target.Artifact, token: String): Candidate {
        val name = "${target.repo}-artifact-${target.id}.zip"
        return if (token.isBlank()) {
            Candidate(name, ImportLink.nightlyLinkUrl(target), viaNightlyLink = true)
        } else {
            Candidate(name, ImportLink.artifactApiUrl(target), auth = true)
        }
    }

    private fun listing(
        target: ImportLink.Target.Listing,
        importable: Set<String>,
        token: String,
        cacheDir: File,
    ): Outcome {
        // A repository address is an addon repository before it is a folder of
        // files: the manifest is the thing its author meant to be found.
        target.manifestProbe?.let { probe ->
            val text = fetchText(probe, token, cacheDir)
            val manifest = text?.let { AddonRepoCodec.decode(it) }
            if (manifest != null) return Outcome.Repository(probe, manifest.repo.name)
        }

        if (target.apiUrl.isBlank()) return Outcome.Failed(Failure.NOTHING_FOUND)
        val body = fetchText(target.apiUrl, token, cacheDir) ?: return Outcome.Failed(Failure.NETWORK)
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return Outcome.Failed(Failure.NETWORK)

        val all = readCandidates(element, target)
        if (all.isEmpty()) return Outcome.Failed(Failure.NOTHING_FOUND)

        val ours = all.filter { candidate -> importable.any { candidate.name.endsWith(".$it", true) } }
        return when {
            ours.size == 1 -> Outcome.One(ours.single())
            ours.isNotEmpty() -> Outcome.Several(ours, exact = true)
            // Nothing of ours in there. The names are still worth showing — an
            // export renamed on the way out is the common case, and the bytes
            // are what decide the format anyway. Never fetched on its own,
            // though, however few there are: a release with one file in it is
            // usually a release of something else, and downloading an APK
            // because it was the only thing there is not what was asked for.
            else -> Outcome.Several(all.take(MAX_CANDIDATES), exact = false)
        }
    }

    private fun readCandidates(
        element: JsonElement,
        target: ImportLink.Target.Listing,
    ): List<Candidate> = when (target.shape) {
        ImportLink.ListingShape.RELEASE_ASSETS -> releaseAssets(element)
        ImportLink.ListingShape.RELEASE_LIST ->
            (element as? JsonArray).orEmpty().flatMap(::releaseAssets)
        ImportLink.ListingShape.GITLAB_RELEASE -> gitlabAssets(element)
        ImportLink.ListingShape.GITLAB_RELEASE_LIST ->
            (element as? JsonArray).orEmpty().flatMap(::gitlabAssets)
        ImportLink.ListingShape.GIST -> gistFiles(element)
        ImportLink.ListingShape.CONTENTS -> contents(element)
        ImportLink.ListingShape.GITLAB_TREE -> gitlabTree(element, target.location)
        ImportLink.ListingShape.NONE -> emptyList()
    }

    /** GitHub and Forgejo both answer a release this way. */
    private fun releaseAssets(element: JsonElement): List<Candidate> =
        (element as? JsonObject)?.get("assets").let { it as? JsonArray }.orEmpty()
            .mapNotNull { entry ->
                val asset = entry as? JsonObject ?: return@mapNotNull null
                val name = asset.string("name") ?: return@mapNotNull null
                val url = asset.string("browser_download_url") ?: return@mapNotNull null
                Candidate(name, url, asset.number("size"))
            }

    private fun gitlabAssets(element: JsonElement): List<Candidate> {
        val links = (element as? JsonObject)?.get("assets")?.let { it as? JsonObject }
            ?.get("links").let { it as? JsonArray }.orEmpty()
        return links.mapNotNull { entry ->
            val link = entry as? JsonObject ?: return@mapNotNull null
            val name = link.string("name") ?: return@mapNotNull null
            val url = link.string("url")?.takeIf { it.startsWith("https://", true) } ?: return@mapNotNull null
            Candidate(name, url)
        }
    }

    private fun gistFiles(element: JsonElement): List<Candidate> =
        (element as? JsonObject)?.get("files")?.let { it as? JsonObject }.orEmpty().values
            .mapNotNull { entry ->
                val gistFile = entry as? JsonObject ?: return@mapNotNull null
                val name = gistFile.string("filename") ?: return@mapNotNull null
                val url = gistFile.string("raw_url") ?: return@mapNotNull null
                Candidate(name, url, gistFile.number("size"))
            }

    /** A GitHub or Forgejo directory listing. Folders are not descended into. */
    private fun contents(element: JsonElement): List<Candidate> =
        (element as? JsonArray).orEmpty().mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            if (RepoLocation.isDirectoryType(item.string("type"))) return@mapNotNull null
            val name = item.string("name") ?: return@mapNotNull null
            val url = item.string("download_url") ?: return@mapNotNull null
            Candidate(name, url, item.number("size"))
        }

    /**
     * GitLab's tree listing names no download address, so each entry's raw URL
     * is built from the path it came back with — which is also why the
     * location travels on the target.
     */
    private fun gitlabTree(element: JsonElement, location: RepoLocation?): List<Candidate> {
        if (location == null) return emptyList()
        return (element as? JsonArray).orEmpty().mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            if (RepoLocation.isDirectoryType(item.string("type"))) return@mapNotNull null
            val name = item.string("name") ?: return@mapNotNull null
            val path = item.string("path") ?: return@mapNotNull null
            Candidate(name, location.rawUrl(path))
        }
    }

    // ---- transfers -------------------------------------------------------

    /**
     * Downloads [candidate] into [target]. Returns false on any failure, with
     * the partial file removed.
     */
    fun download(
        candidate: Candidate,
        target: File,
        token: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Boolean = try {
        if (candidate.auth) {
            downloadWithToken(candidate.url, target, token, onProgress)
        } else {
            ToolHttp.download(
                url = candidate.url,
                target = target,
                maxBytes = MAX_BYTES,
                onProgress = onProgress,
                source = NetSource.LINK_IMPORT,
                route = NetLog.pathOf(candidate.url),
                // A file in a private repository is served to the token and to
                // nobody else. [authFor] is what keeps it off every other host.
                headers = authFor(candidate.url, token),
            )
        }
        target.isFile && target.length() > 0
    } catch (_: Exception) {
        target.delete()
        false
    }

    /**
     * GitHub's artifact endpoint answers a redirect to storage, and that
     * storage refuses a request that carries an `Authorization:` header as
     * well as its own signature. So the redirect is read rather than followed,
     * and the second request goes out bare.
     */
    private fun downloadWithToken(
        url: String,
        target: File,
        token: String,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.LINK_IMPORT, "GET", url, route = NetLog.pathOf(url))
        val location = try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            netCall.status = connection.responseCode
            when (val status = connection.responseCode) {
                in 300..399 -> connection.getHeaderField("Location").orEmpty()
                in 200..299 -> ""
                else -> throw java.io.IOException("HTTP $status")
            }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
        if (location.isBlank()) {
            ToolHttp.download(
                url = url,
                target = target,
                maxBytes = MAX_BYTES,
                onProgress = onProgress,
                source = NetSource.LINK_IMPORT,
                route = NetLog.pathOf(url),
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/vnd.github+json",
                ),
            )
            return
        }
        if (!location.startsWith("https://", ignoreCase = true)) {
            throw java.io.IOException("insecure redirect")
        }
        ToolHttp.download(
            location, target, maxBytes = MAX_BYTES, onProgress = onProgress,
            source = NetSource.LINK_IMPORT, route = NetLog.pathOf(location),
        )
    }

    /**
     * The token's headers, for the hosts the token belongs to and no others.
     *
     * A link can name any host on the internet, and this walks listings on
     * whatever forge the address pointed at. Attaching the user's GitHub
     * credential to a request aimed at somebody else's server would hand it
     * to them, so the header is decided by the host being asked rather than
     * by whether a token exists.
     */
    private fun authFor(url: String, token: String): Map<String, String> {
        if (token.isBlank()) return emptyMap()
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase().orEmpty()
        val mine = host == "api.github.com" || host == "github.com" ||
            host.endsWith(".githubusercontent.com")
        return if (mine) mapOf("Authorization" to "Bearer $token") else emptyMap()
    }

    /** A capped GET of something small enough to hold as a string. */
    private fun fetchText(url: String, token: String, cacheDir: File): String? {
        val temp = File(cacheDir, "link_${System.nanoTime()}.json")
        return try {
            ToolHttp.download(
                url = url,
                target = temp,
                maxBytes = MAX_LIST_BYTES,
                headers = authFor(url, token),
                source = NetSource.LINK_IMPORT,
                route = NetLog.pathOf(url),
            )
            temp.readText().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    private const val TIMEOUT_MS = 20_000

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
}
