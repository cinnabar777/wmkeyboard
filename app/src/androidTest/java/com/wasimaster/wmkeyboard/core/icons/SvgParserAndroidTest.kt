package com.wasimaster.wmkeyboard.core.icons

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [SvgParser] on the platform it actually runs on.
 *
 * This exists because of a bug the JVM tests could not see. The parser used to
 * ask `SAXParserFactory` to disable Apache's `disallow-doctype-decl` feature;
 * the desktop JVM's Xerces knows that feature, and Android's SAX implementation
 * throws `SAXNotRecognizedException` for anything but the two `namespaces`
 * features. The call sat inside a `runCatching`, so on a real phone *every*
 * icon silently failed to parse — which meant every icon pack import failed —
 * while the whole JVM suite stayed green.
 *
 * Anything that asks the XML stack for behaviour therefore needs a test on the
 * device, not just on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class SvgParserAndroidTest {

    private val strokeIcon = """
        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"
             fill="none" stroke="currentColor" stroke-width="2">
          <rect width="8" height="4" x="8" y="2" rx="1" />
          <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
        </svg>
    """.trimIndent()

    @Test
    fun parsesAPlainStrokeIcon() {
        val doc = SvgParser.parse(strokeIcon)
        assertNotNull("the SVG parser returns nothing on this device", doc)
        assertTrue("no drawable paths came out", doc!!.paths.isNotEmpty())
        assertTrue("an uncoloured icon should follow the theme", doc.monochrome)
    }

    @Test
    fun refusesADoctype() {
        // The format documents this: packs are untrusted input and an external
        // entity here would be a file-disclosure hole.
        val withDoctype = """
            <!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path d="M0 0h24v24H0z" />
            </svg>
        """.trimIndent()
        assertNull(SvgParser.parse(withDoctype))
    }

    @Test
    fun importsAPackEndToEnd() {
        // The whole path an addon install takes, on the device: read the
        // archive, parse each SVG, register the pack.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "icontest_${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = IconPackStore(dir)
            val bytes = buildPack()
            val result = IconPackFile.import(bytes.inputStream(), store, defaultName = "Test icons")
            assertTrue("import said: $result", result is IconImportResult.Imported)
            val pack = (result as IconImportResult.Imported).pack
            assertTrue("no slots survived", pack.slots.isNotEmpty())
            // And the parsed form the keyboard renders from is populated.
            assertTrue("nothing parsed back out", store.docs(pack.id).isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A minimal but real `.wmicons` archive. */
    private fun buildPack(): ByteArray {
        val slot = "key.backspace"
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(IconPackFile.MANIFEST))
            zip.write(
                """
                {"format":"${IconPackFile.FORMAT}","version":1,
                 "pack":{"id":"t","name":"Test","version":"1.0.0","slots":["$slot"]}}
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("icons/$slot.svg"))
            zip.write(strokeIcon.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
