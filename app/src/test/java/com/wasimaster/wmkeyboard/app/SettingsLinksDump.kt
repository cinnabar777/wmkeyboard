package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.DefaultKeyboardModes
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Writes the settings search index out as JSON for the documentation site.
 *
 * The docs' `<SettingsPath>` chip turns into a `wmkeyboard://` link on an
 * Android phone, and to do that it has to know which screen, or which row on
 * which screen, a breadcrumb like "Typing / Automatic corrections" names. The
 * search index is the one place that already knows every addressable screen
 * and row, with the route that opens it and the resource name `?setting=`
 * expects, so the docs read a dump of it rather than keeping a second table.
 *
 * This is a generator dressed as a test, because [XmlSearchStrings] is what
 * lets the real index be built on a plain JVM. It is skipped unless
 * `WM_SETTINGS_LINKS_OUT` names the file to write, which
 * `docs/scripts/extract_settings_links.sh` does. Rerun that script whenever
 * `SettingsSearch.kt` or a settings title changes.
 *
 * A row drawn on a screen that takes an argument carries that screen's
 * pattern as `pattern`, so the link builder can offer it for any value. The
 * mode editor is the one such screen, and the modes that ship with the app
 * have ids every install shares, so each of them is written out as a screen of
 * its own, `mode_edit/mode_browser` under Keyboard modes, with a copy of every
 * editor row on it (#323). That is what lets a docs chip such as
 * "Advanced / Keyboard modes / Browser / Custom symbol sets" open that row.
 */
class SettingsLinksDump {

    @Test
    fun `write the index for the docs`() {
        val out = System.getenv("WM_SETTINGS_LINKS_OUT")
        assumeTrue("set WM_SETTINGS_LINKS_OUT to write the dump", !out.isNullOrBlank())

        val strings = XmlSearchStrings.forApp()
        val home = strings.getString(com.wasimaster.wmkeyboard.common.R.string.common_settings)
        val rows = settingsSearchIndex(strings).map { entry ->
            // A tool page is a destination too, but its own path ends with its
            // own name, unlike a section whose path stops above it.
            val isScreen = entry.weight == EntryWeight.SECTION || entry.weight == EntryWeight.TOOL
            val screens = entry.screenPath.filterNot { it == home }
                .let { if (entry.weight == EntryWeight.TOOL && it.lastOrNull() == entry.title) it.dropLast(1) else it }
            val name = entry.key.substringAfter('#')
            val base = if (name.endsWith("_title")) name.removeSuffix("_title") else name
            val description = entry.subtitle.takeIf { it.isNotBlank() }
                ?: strings.getByName("${base}_subtitle")
                ?: strings.getByName("${base}_summary")
                ?: strings.getByName("${base}_desc")
                ?: strings.getByName("${name}_subtitle")
            val help = strings.getByName("${base}_info")
                ?: strings.getByName("${base}_help")
                ?: strings.getByName("${name}_info")
                ?: strings.getByName("${name}_help")
            Row(
                title = entry.title,
                name = name,
                route = entry.route,
                screens = screens,
                screen = isScreen,
                pattern = entry.screenPattern,
                description = description?.takeIf { it.isNotBlank() },
                help = help?.takeIf { it.isNotBlank() },
            )
        }
        // After the index's own rows, so a chip that names no mode still
        // matches the generic entry first.
        val modesTitle = strings.getString(R.string.home_modes_title)
        val editorRows = rows.filter { it.pattern == MODE_EDIT_PATTERN }
        val shippedModes = DefaultKeyboardModes.flatMap { mode ->
            val route = "mode_edit/${mode.id}"
            listOf(Row(mode.name, mode.id, route, listOf(modesTitle), screen = true, pattern = MODE_EDIT_PATTERN)) +
                editorRows.map { it.copy(route = route, screens = listOf(modesTitle, mode.name)) }
        }
        val entries = (rows + shippedModes).map { it.toJson() }
        val file = File(out!!)
        file.parentFile?.mkdirs()
        file.writeText(
            "[\n" + entries.joinToString(",\n") { "  $it" } + "\n]\n",
        )
        println("wrote ${entries.size} entries to ${file.absolutePath}")
    }

    /** One line of the dump. [pattern] only on a row drawn on an argument screen. */
    private data class Row(
        val title: String,
        val name: String,
        val route: String,
        val screens: List<String>,
        val screen: Boolean,
        val pattern: String? = null,
        val description: String? = null,
        val help: String? = null,
    ) {
        fun toJson(): String = buildString {
            append("{\"title\":").append(json(title))
            append(",\"name\":").append(json(name))
            append(",\"route\":").append(json(route))
            append(",\"screens\":[").append(screens.joinToString(",") { json(it) }).append(']')
            append(",\"screen\":").append(if (screen) "true" else "false")
            if (pattern != null) append(",\"pattern\":").append(json(pattern))
            if (description != null) append(",\"description\":").append(json(description))
            if (help != null) append(",\"help\":").append(json(help))
            append('}')
        }
    }

    private companion object {
        fun json(text: String): String = buildString {
            append('"')
            for (ch in text) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
                }
            }
            append('"')
        }
    }
}
