package com.wasimaster.wmkeyboard.app.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The asset name is the only thing that says which version a file is, so every
 * rule about reading it is worth a test: a name that parses wrongly offers the
 * user the wrong download, and one that fails to parse offers nothing at all.
 */
class ReleaseAssetsTest {

    @Test
    fun `reads every name the release workflow produces`() {
        val parsed = ReleaseAssets.parseAssetName("wmkeyboard-0.5.3-vc14-full-arm64-v8a.apk")
        assertEquals("0.5.3", parsed?.versionName)
        assertEquals(14, parsed?.versionCode)
        assertEquals("full", parsed?.flavor)
        assertEquals("arm64-v8a", parsed?.abi)
    }

    @Test
    fun `reads the lite and universal names too`() {
        for (name in RELEASE_ASSETS) {
            assertTrue(name, ReleaseAssets.parseAssetName(name) != null)
        }
    }

    @Test
    fun `a hyphenated pre-release version splits at the right hyphen`() {
        val parsed = ReleaseAssets.parseAssetName("wmkeyboard-0.6.0-beta.1-vc15-lite-x86_64.apk")
        assertEquals("0.6.0-beta.1", parsed?.versionName)
        assertEquals(15, parsed?.versionCode)
        assertEquals("lite", parsed?.flavor)
    }

    @Test
    fun `refuses anything that is not one of our APKs`() {
        assertNull(ReleaseAssets.parseAssetName("SHA256SUMS.txt"))
        assertNull(ReleaseAssets.parseAssetName("wmkeyboard-0.5.3-full-arm64-v8a.apk"))
        assertNull(ReleaseAssets.parseAssetName("wmkeyboard-0.5.3-vc14-pro-arm64-v8a.apk"))
        assertNull(ReleaseAssets.parseAssetName("wmkeyboard-0.5.3-vc14-full-mips.apk"))
        assertNull(ReleaseAssets.parseAssetName("other-0.5.3-vc14-full-arm64-v8a.apk"))
    }

    @Test
    fun `never reads the no-internet build as an update for a regular one`() {
        assertNull(ReleaseAssets.parseAssetName("wmkeyboard-0.5.12-vc30-full-intl-nointernet-arm64-v8a.apk"))
        assertNull(ReleaseAssets.parseAssetName("wmkeyboard-0.5.12-vc30-full-intl-nointernet-universal.apk"))
    }

    @Test
    fun `takes the first ABI the device prefers`() {
        val picked = ReleaseAssets.pickAsset(
            release = release(),
            flavor = "full",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertEquals("wmkeyboard-0.5.3-vc14-full-arm64-v8a.apk", picked?.name)
    }

    @Test
    fun `a 32-bit device gets the 32-bit build, not the one listed first`() {
        val picked = ReleaseAssets.pickAsset(
            release = release(),
            flavor = "full",
            supportedAbis = listOf("armeabi-v7a"),
        )
        assertEquals("wmkeyboard-0.5.3-vc14-full-armeabi-v7a.apk", picked?.name)
    }

    @Test
    fun `an ABI with no split of its own falls back to the universal build`() {
        val picked = ReleaseAssets.pickAsset(
            release = release(),
            flavor = "full",
            // 32-bit x86, which the workflow does not build a split for.
            supportedAbis = listOf("x86"),
        )
        assertEquals("wmkeyboard-0.5.3-vc14-full-universal.apk", picked?.name)
    }

    @Test
    fun `never offers the other edition`() {
        val release = release(names = listOf("wmkeyboard-0.5.3-vc14-lite-arm64-v8a.apk"))
        assertNull(ReleaseAssets.pickAsset(release, "full", listOf("arm64-v8a")))
    }

    @Test
    fun `picks the newest release that has a file for this device`() {
        val candidate = ReleaseAssets.chooseCandidate(
            releases = listOf(
                // Newest, but built for the other edition only.
                release(tag = "v0.6.0", names = listOf("wmkeyboard-0.6.0-vc15-lite-arm64-v8a.apk")),
                release(),
            ),
            installedVersionCode = 13,
            flavor = "full",
            supportedAbis = listOf("arm64-v8a"),
            includePrereleases = false,
        )
        assertEquals(14, candidate?.versionCode)
        assertEquals("0.5.3", candidate?.versionName)
    }

    @Test
    fun `never offers the version already installed, or an older one`() {
        for (installed in listOf(14, 15)) {
            assertNull(
                "installed $installed",
                ReleaseAssets.chooseCandidate(
                    releases = listOf(release()),
                    installedVersionCode = installed,
                    flavor = "full",
                    supportedAbis = listOf("arm64-v8a"),
                    includePrereleases = false,
                ),
            )
        }
    }

    @Test
    fun `pre-releases are only offered when asked for`() {
        val releases = listOf(release(tag = "v0.6.0-beta.1", prerelease = true, code = 15))
        val without = ReleaseAssets.chooseCandidate(
            releases, 14, "full", listOf("arm64-v8a"), includePrereleases = false,
        )
        val with = ReleaseAssets.chooseCandidate(
            releases, 14, "full", listOf("arm64-v8a"), includePrereleases = true,
        )
        assertNull(without)
        assertEquals(15, with?.versionCode)
    }

    @Test
    fun `a draft is never offered`() {
        val candidate = ReleaseAssets.chooseCandidate(
            releases = listOf(release(code = 15, draft = true)),
            installedVersionCode = 14,
            flavor = "full",
            supportedAbis = listOf("arm64-v8a"),
            includePrereleases = true,
        )
        assertNull(candidate)
    }

    @Test
    fun `an asset served over plain http is not a candidate`() {
        val release = GithubRelease(
            tagName = "v0.5.3",
            assets = listOf(
                GithubAsset(
                    name = "wmkeyboard-0.5.3-vc14-full-arm64-v8a.apk",
                    size = 1,
                    digest = "sha256:${"a".repeat(64)}",
                    browserDownloadUrl = "http://example.invalid/x.apk",
                ),
            ),
        )
        assertNull(
            ReleaseAssets.chooseCandidate(
                listOf(release), 13, "full", listOf("arm64-v8a"), includePrereleases = false,
            ),
        )
    }

    @Test
    fun `reads the names carrying a languages build, as releases since 0_5_10 do`() {
        val parsed = ReleaseAssets.parseAssetName("wmkeyboard-0.5.11-vc23-full-intl-arm64-v8a.apk")
        assertEquals("0.5.11", parsed?.versionName)
        assertEquals(23, parsed?.versionCode)
        assertEquals("full", parsed?.flavor)
        assertEquals("intl", parsed?.languages)
        assertEquals("arm64-v8a", parsed?.abi)
        assertEquals("en", ReleaseAssets.parseAssetName("wmkeyboard-0.5.11-vc23-lite-en-universal.apk")?.languages)
        // Before the split every build was English only.
        assertEquals("en", ReleaseAssets.parseAssetName("wmkeyboard-0.5.3-vc14-full-arm64-v8a.apk")?.languages)
        assertNull(ReleaseAssets.parseAssetName("wmkeyboard-0.5.11-vc23-full-de-arm64-v8a.apk"))
        assertNull(ReleaseAssets.parseAssetName("wmkeyboard-0.5.11-vc23-full-intl-mapping.txt.gz"))
    }

    @Test
    fun `an update stays in its own languages build`() {
        val release = split(code = 23)
        for (languages in listOf("en", "intl")) {
            val picked = ReleaseAssets.pickAsset(release, "full", listOf("arm64-v8a"), languages)
            assertEquals("wmkeyboard-0.5.11-vc23-full-$languages-arm64-v8a.apk", picked?.name)
        }
    }

    @Test
    fun `the move to every language takes the same version, never an older one`() {
        fun choose(installed: Int, allowSame: Boolean) = ReleaseAssets.chooseCandidate(
            releases = listOf(split(code = 23)),
            installedVersionCode = installed,
            flavor = "lite",
            supportedAbis = listOf("arm64-v8a"),
            includePrereleases = false,
            languages = "intl",
            allowSameVersion = allowSame,
        )
        assertEquals("wmkeyboard-0.5.11-vc23-lite-intl-arm64-v8a.apk", choose(23, allowSame = true)?.assetName)
        assertNull(choose(23, allowSame = false))
        assertNull(choose(24, allowSame = true))
    }

    @Test
    fun `reads a sha256 digest and refuses any other kind`() {
        val hex = "b".repeat(64)
        assertEquals(hex, ReleaseAssets.sha256Of("sha256:$hex"))
        assertEquals(hex, ReleaseAssets.sha256Of("sha256:${hex.uppercase()}"))
        assertNull(ReleaseAssets.sha256Of(null))
        assertNull(ReleaseAssets.sha256Of(hex))
        assertNull(ReleaseAssets.sha256Of("sha512:$hex"))
        assertNull(ReleaseAssets.sha256Of("sha256:nothex"))
    }

    @Test
    fun `reads one file's line out of a checksums file`() {
        val hex = "c".repeat(64)
        val body = """
            ${"d".repeat(64)}  wmkeyboard-0.5.3-vc14-lite-arm64-v8a.apk
            $hex  wmkeyboard-0.5.3-vc14-full-arm64-v8a.apk
        """.trimIndent()
        assertEquals(hex, ReleaseAssets.sha256From(body, "wmkeyboard-0.5.3-vc14-full-arm64-v8a.apk"))
        assertNull(ReleaseAssets.sha256From(body, "wmkeyboard-0.5.3-vc14-full-x86_64.apk"))
    }

    @Test
    fun `reads GitHub's timestamps, and refuses to guess at a broken one`() {
        assertEquals(0L, ReleaseAssets.publishedAtMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_757_231_612_000L, ReleaseAssets.publishedAtMillis("2025-09-07T07:53:32Z"))
        assertNull(ReleaseAssets.publishedAtMillis(null))
        assertNull(ReleaseAssets.publishedAtMillis(""))
        assertNull(ReleaseAssets.publishedAtMillis("last Tuesday"))
    }

    @Test
    fun `staleness is unknown rather than zero when there is no date`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(-1, ReleaseAssets.stalenessDays(null, 10 * day))
        assertEquals(3, ReleaseAssets.stalenessDays(7 * day, 10 * day))
        // A release dated in the future is a clock disagreeing, not a
        // negatively old release.
        assertEquals(-1, ReleaseAssets.stalenessDays(20 * day, 10 * day))
    }

    private companion object {
        /** The eight APKs a release actually ships, by name. */
        val RELEASE_ASSETS = listOf(
            "wmkeyboard-0.5.3-vc14-full-arm64-v8a.apk",
            "wmkeyboard-0.5.3-vc14-full-armeabi-v7a.apk",
            "wmkeyboard-0.5.3-vc14-full-x86_64.apk",
            "wmkeyboard-0.5.3-vc14-full-universal.apk",
            "wmkeyboard-0.5.3-vc14-lite-arm64-v8a.apk",
            "wmkeyboard-0.5.3-vc14-lite-armeabi-v7a.apk",
            "wmkeyboard-0.5.3-vc14-lite-x86_64.apk",
            "wmkeyboard-0.5.3-vc14-lite-universal.apk",
        )

        /** A release as the workflow names it since the languages flavour: four builds per ABI. */
        fun split(code: Int): GithubRelease = release(
            tag = "v0.5.11",
            code = code,
            names = listOf("full", "lite").flatMap { flavor ->
                listOf("intl", "en").flatMap { languages ->
                    listOf("arm64-v8a", "universal").map { abi ->
                        "wmkeyboard-0.5.11-vc$code-$flavor-$languages-$abi.apk"
                    }
                }
            },
        )

        fun release(
            tag: String = "v0.5.3",
            code: Int = 14,
            prerelease: Boolean = false,
            draft: Boolean = false,
            names: List<String>? = null,
        ): GithubRelease {
            val version = tag.removePrefix("v")
            val assets = names ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64", "universal")
                .map { "wmkeyboard-$version-vc$code-full-$it.apk" }
            return GithubRelease(
                tagName = tag,
                prerelease = prerelease,
                draft = draft,
                publishedAt = "2026-09-07T07:40:12Z",
                htmlUrl = "https://github.com/wasi-master/WMKeyboard/releases/tag/$tag",
                assets = assets.map { name ->
                    GithubAsset(
                        name = name,
                        size = 1_000,
                        digest = "sha256:${"a".repeat(64)}",
                        browserDownloadUrl =
                            "https://github.com/wasi-master/WMKeyboard/releases/download/$tag/$name",
                    )
                },
            )
        }
    }
}
