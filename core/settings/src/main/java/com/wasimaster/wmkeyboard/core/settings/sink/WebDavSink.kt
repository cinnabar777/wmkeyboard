package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * A [BackupSink] over a WebDAV collection: Nextcloud, ownCloud, or any of the
 * other servers that speak it.
 *
 * OkHttp rather than the `HttpURLConnection` the rest of the app uses, and not
 * by preference: `HttpURLConnection` validates the request method against a
 * fixed list and throws `ProtocolException` for `PROPFIND` and `MOVE`, which
 * are the two that make this a WebDAV client rather than a file uploader. The
 * library is already in the APK as Coil's network engine, so declaring it costs
 * nothing but the line in the version catalog.
 *
 * **HTTPS only**, with one exception. The credentials go in an
 * `Authorization: Basic` header, which is the password in base64 and nothing
 * more. The app permits cleartext traffic for the local-model tools, so the
 * platform will not stop this; [readiness] does. The exception is an address
 * on a Tailscale network ([isTailnet]), where the plain HTTP travels inside
 * Tailscale's WireGuard tunnel and Taildrive offers nothing else.
 *
 * No user name and no password sends no `Authorization` at all, for a server
 * that asks for none. Taildrive is one: being on the tailnet is the permission.
 *
 * Unlike the SAF sink this one gets a real atomic install: `MOVE` is part of the
 * protocol rather than an optional capability, so a backup is written to an
 * [AutoBackupNaming.UPLOAD_SUFFIX] name and moved into place. Not `.part`, the
 * suffix every other sink stages under: Nextcloud refuses that one. The caller still verifies before it
 * rotates, because a server that accepted every byte can still have written
 * them somewhere that is not there any more.
 */
class WebDavSink(
    baseUrl: String,
    private val user: String,
    private val password: String,
) : BackupSink {

    /** Always ends in a slash, so a file name can simply be appended. */
    private val base: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    override val id: String get() = ID

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(InternetGate)
            .addNetworkInterceptor(NetLogInterceptor(NetSource.BACKUP, NetLogInterceptor.PATH) { BackupTraffic.unattended })
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private fun url(name: String): String = base + URLEncoder.encode(name, "UTF-8")
        // URLEncoder is built for form bodies, where a space is '+'. In a path
        // it has to be %20, and a literal '+' has to survive as '+'.
        .replace("+", "%20")

    private fun request(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .apply {
            if (user.isNotEmpty() || password.isNotEmpty()) header("Authorization", Credentials.basic(user, password))
        }

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            if (!base.startsWith("https://", ignoreCase = true) && !isTailnet(base)) {
                // Basic auth over cleartext is the password in plain sight on
                // every hop. Refused here rather than warned about.
                throw BackupSinkException(SinkError.NOT_CONFIGURED)
            }
            // Depth 0: ask about the collection itself and nothing in it. The
            // point is to fail before a bundle has been built, so it must not
            // pull a directory listing to do it.
            try {
                propfindSelf(base)
            } catch (missing: BackupSinkException) {
                if (missing.reason != SinkError.TARGET_MISSING) throw missing
                // A preset names a folder the user may not have made yet.
                makeCollection(base, MKCOL_DEPTH)
                propfindSelf(base)
            }
        }
    }

    private fun propfindSelf(url: String) {
        call(
            request(url)
                .header("Depth", "0")
                .method("PROPFIND", EMPTY_XML.toRequestBody(XML_MEDIA_TYPE))
                .build(),
        ) { }
    }

    /**
     * MKCOL on [url], making up to [depth] missing parents first. A server
     * answers 409 when the parent is missing, and 405 when the collection is
     * already there, which is also fine.
     */
    private fun makeCollection(url: String, depth: Int) {
        val response = try {
            client.newCall(request(url).method("MKCOL", null).build()).execute()
        } catch (failure: Throwable) {
            throw BackupSinkException(SinkError.IO, failure)
        }
        val code = response.use { it.code }
        if (code !in 200..299) BackupLog.d("webdav MKCOL: HTTP $code")
        when {
            code in 200..299 || code == HTTP_METHOD_NOT_ALLOWED -> Unit
            code == HTTP_CONFLICT && depth > 0 -> {
                val parent = url.trimEnd('/').substringBeforeLast('/') + "/"
                if (parent.length <= "https://x/".length) throw BackupSinkException(SinkError.TARGET_MISSING)
                makeCollection(parent, depth - 1)
                makeCollection(url, 0)
            }
            else -> throw BackupSinkException(statusError(code))
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            readiness().getOrThrow()

            // Buffered rather than streamed. OkHttp can stream a request body,
            // but a WebDAV PUT that fails partway is a half-file on the server,
            // and knowing the length up front lets the server reject an
            // over-quota upload before any of it is sent.
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            val partName = name + AutoBackupNaming.UPLOAD_SUFFIX
            val partUrl = url(partName)

            call(
                request(partUrl)
                    .put(bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
                    .build(),
            ) { }

            val finalUrl = url(name)
            try {
                call(
                    request(partUrl)
                        .header("Destination", finalUrl)
                        // A stale file of the same name is ours and older, so
                        // replacing it is the intent rather than a collision.
                        .header("Overwrite", "T")
                        .method("MOVE", null)
                        .build(),
                ) { }
            } catch (failure: Throwable) {
                runCatching { call(request(partUrl).delete().build()) { } }
                throw failure
            }

            SinkEntry(
                id = finalUrl,
                name = name,
                sizeBytes = bytes.size.toLong(),
                modifiedAtMs = 0L,
            )
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            val xml = call(
                request(base)
                    .header("Depth", "1")
                    .method("PROPFIND", PROPS_XML.toRequestBody(XML_MEDIA_TYPE))
                    .build(),
            ) { it.body?.string().orEmpty() }

            WebDavListing.parse(xml)
                .filter { !it.isCollection && AutoBackupNaming.isListed(it.name) }
                .map { entry ->
                    SinkEntry(
                        // The href, resolved against the base, is what addresses
                        // the file. Servers return it absolute or path-only and
                        // both have to work.
                        id = resolve(entry.href),
                        name = entry.name,
                        sizeBytes = entry.sizeBytes,
                        modifiedAtMs = entry.modifiedAtMs,
                    )
                }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCancellable {
                // The whole body, not the live stream: the response has to be
                // closed here, and a stream handed to the caller would outlive
                // it. Bundles are already read into a String downstream.
                val bytes = call(request(entry.id).get().build()) {
                    it.body?.bytes() ?: ByteArray(0)
                }
                bytes.inputStream()
            }
        }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            call(request(entry.id).delete().build(), allowMissing = true) { }
        }
    }

    /** Turns a path-only href into something addressable, and leaves URLs alone. */
    private fun resolve(href: String): String =
        if (href.startsWith("http://", ignoreCase = true) ||
            href.startsWith("https://", ignoreCase = true)
        ) {
            href
        } else {
            val origin = Regex("^https://[^/]+").find(base)?.value ?: return href
            origin + href
        }

    /**
     * Runs [request] and maps every way it can fail onto a [SinkError].
     *
     * [allowMissing] is for delete, which has to be idempotent: rotation reruns
     * after a failure, so a file that is already gone is the outcome it wanted.
     */
    private fun <T> call(
        request: Request,
        allowMissing: Boolean = false,
        read: (Response) -> T,
    ): T {
        val response = try {
            client.newCall(request).execute()
        } catch (failure: Throwable) {
            throw BackupSinkException(SinkError.IO, failure)
        }
        response.use {
            if (it.isSuccessful) return read(it)
            if (allowMissing && it.code == HTTP_NOT_FOUND) return read(it)
            // The method and the status, never the URL: it carries the user
            // name on most services. A bare IO in the log says nothing.
            BackupLog.w("webdav ${request.method}: HTTP ${it.code}")
            throw BackupSinkException(statusError(it.code))
        }
    }

    private fun statusError(code: Int): SinkError = when (code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> SinkError.PERMISSION_LOST
        HTTP_NOT_FOUND, HTTP_CONFLICT -> SinkError.TARGET_MISSING
        HTTP_PAYLOAD_TOO_LARGE, HTTP_INSUFFICIENT_STORAGE -> SinkError.OUT_OF_SPACE
        else -> SinkError.IO
    }

    companion object {
        const val ID = "webdav"

        /**
         * Whether [url] is an `http` address on a Tailscale network, where
         * plain HTTP is allowed. Taildrive's local server at
         * `100.100.100.100`, a tailnet peer's address (`100.64.0.0/10`, or
         * `fd7a:115c:a1e0::/48` over IPv6), or a MagicDNS name under `ts.net`.
         * Tailscale encrypts everything between those end to end.
         *
         * A hand-picked `100.64.x.x` on a network without Tailscale would pass
         * too. That range is carrier-grade NAT space, not a home network's, and
         * the user typed the address; the check is there to stop a mistake,
         * not someone set on sending in clear.
         */
        fun isTailnet(url: String): Boolean {
            val parsed = url.trim().toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "http") return false
            val host = parsed.host.lowercase()
            if (host.endsWith(".ts.net")) return true
            if (host.startsWith("fd7a:115c:a1e0:")) return true
            val octets = host.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || host.count { it == '.' } != 3) return false
            return octets[0] == 100 && octets[1] in 64..127
        }

        private const val USER_AGENT = "WMKeyboard"

        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 30L
        private const val WRITE_TIMEOUT_S = 120L

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val MKCOL_DEPTH = 3
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_INSUFFICIENT_STORAGE = 507

        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaTypeOrNull()

        private const val EMPTY_XML = ""

        /**
         * Only the three properties this sink reads. Asking for `allprop` makes
         * a server with large collections do real work, and returns a document
         * many times the size for nothing.
         */
        private val PROPS_XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:getcontentlength/>
                <d:getlastmodified/>
                <d:resourcetype/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}
