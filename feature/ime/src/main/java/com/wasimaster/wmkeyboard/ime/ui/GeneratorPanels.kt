package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.tools.PasswordGen
import com.wasimaster.wmkeyboard.core.tools.QrCodeGen
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardClipboard
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.PwSettingAction
import com.wasimaster.wmkeyboard.ime.R
import java.security.SecureRandom
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- password / passphrase generator ----

/**
 * Passphrase material, filtered once from the bundled English dictionary
 * (no separate wordlist ships with the app).
 */
private object PassphraseWords {
    @Volatile private var cached: List<String>? = null

    suspend fun load(context: Context): List<String> = cached ?: withContext(Dispatchers.IO) {
        val words = runCatching {
            context.assets.open("dictionaries/en.txt").use { stream ->
                PasswordGen.buildWordlist(
                    DictionaryLoader.loadEntries(stream).asSequence().map { it.first },
                )
            }
        }.getOrDefault(emptyList())
        cached = words
        words
    }
}

/**
 * Password / passphrase generator. Every option persists (the same
 * controls live in the tool's settings); entropy is a rough bits estimate.
 * Nothing generated here is stored anywhere.
 */
@Composable
internal fun PasswordPanel(
    state: KeyboardUiState,
    onSetting: (PwSettingAction) -> Unit,
    onInsert: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val settings = state.settings
    val context = LocalContext.current
    val random = remember { SecureRandom() }
    var regenerateKey by remember { mutableIntStateOf(0) }

    val wordlist by produceState(initialValue = emptyList(), Unit) {
        value = PassphraseWords.load(context)
    }

    val passphraseSpec = PasswordGen.PassphraseSpec(
        words = settings.passwordGenerator.ppWordCount,
        separator = settings.passwordGenerator.ppSeparator,
        capitalize = settings.passwordGenerator.ppCapitalize,
        includeDigit = settings.passwordGenerator.ppIncludeDigit,
    )
    val passwordSpec = PasswordGen.PasswordSpec(
        length = settings.passwordGenerator.pwLength,
        upper = settings.passwordGenerator.pwUppercase,
        digits = settings.passwordGenerator.pwDigits,
        symbols = settings.passwordGenerator.pwSymbols,
        excludeAmbiguous = settings.passwordGenerator.pwExcludeAmbiguous,
        symbolPool = settings.toolLimits.passwordSymbols,
    )
    val passphraseMode = settings.passwordGenerator.pwPassphraseMode
    val generated = remember(passphraseMode, passwordSpec, passphraseSpec, wordlist, regenerateKey) {
        if (passphraseMode) PasswordGen.passphrase(wordlist, passphraseSpec, random)
        else PasswordGen.password(passwordSpec, random)
    }
    val entropy = if (passphraseMode) {
        PasswordGen.passphraseEntropyBits(passphraseSpec, wordlist.size)
    } else {
        PasswordGen.passwordEntropyBits(passwordSpec)
    }

    // Read here rather than in the Copy chip's click handler: that lambda is
    // not composable, so it cannot resolve a string itself.
    val copiedMessage = stringResource(R.string.ime_password_copied_toast)

    fun copyGenerated() {
        if (generated.isEmpty()) return
        KeyboardClipboard.copy(context, generated, "Password")
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }

    // Ring regions: the result card's regenerate/copy/insert, then the
    // flattened option list (steppers count as two slots each). The header
    // tabs publish CHIPS from the KeyboardScreen header block.
    PanelFocusTarget(
        panel = PanelMode.PASSWORD_GEN,
        region = FocusRegion.ACTIONS,
        count = 3,
        columns = 3,
    ) { index ->
        when (index) {
            0 -> regenerateKey++
            1 -> copyGenerated()
            2 -> if (generated.isNotEmpty()) onInsert(generated)
        }
    }
    val pg = settings.passwordGenerator
    val optionEntries: List<() -> Unit> = if (!passphraseMode) {
        listOf(
            { onSetting(PwSettingAction.Length(pg.pwLength - 1)) },
            { onSetting(PwSettingAction.Length(pg.pwLength + 1)) },
            { onSetting(PwSettingAction.Upper(!pg.pwUppercase)) },
            { onSetting(PwSettingAction.Digits(!pg.pwDigits)) },
            { onSetting(PwSettingAction.Symbols(!pg.pwSymbols)) },
            { onSetting(PwSettingAction.ExcludeAmbiguous(!pg.pwExcludeAmbiguous)) },
        )
    } else {
        val separators = listOf("-", ".", "_", " ", "")
        val next = separators[(separators.indexOf(pg.ppSeparator) + 1).mod(separators.size)]
        listOf(
            { onSetting(PwSettingAction.Words(pg.ppWordCount - 1)) },
            { onSetting(PwSettingAction.Words(pg.ppWordCount + 1)) },
            { onSetting(PwSettingAction.Separator(next)) },
            { onSetting(PwSettingAction.Capitalize(!pg.ppCapitalize)) },
            { onSetting(PwSettingAction.IncludeDigit(!pg.ppIncludeDigit)) },
        )
    }
    PanelFocusTarget(
        panel = PanelMode.PASSWORD_GEN,
        region = FocusRegion.RESULTS,
        count = optionEntries.size,
        columns = optionEntries.size,
    ) { index -> optionEntries.getOrNull(index)?.invoke() }
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val focusedOption = state.focusedIndex(FocusRegion.RESULTS)

    // The Password/Passphrase tabs live in the FullBleedTool header next to
    // the back button; the body starts at the generated result.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        val cardShape = kb.cardShape()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(cardShape)
                .background(kb.chip)
                .chipBorder(kb, cardShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                generated.ifEmpty { "…" },
                color = kb.chipText,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { regenerateKey++ },
                modifier = Modifier
                    .size(30.dp)
                    .focusRing(focusedAction == 0, CircleShape),
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.ime_password_regenerate_desc),
                    tint = kb.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(
                stringResource(CommonR.string.common_copy),
                modifier = Modifier.focusRing(focusedAction == 1),
            ) { copyGenerated() }
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(
                stringResource(R.string.ime_password_insert_action),
                modifier = Modifier.focusRing(focusedAction == 2),
            ) {
                if (generated.isNotEmpty()) onInsert(generated)
            }
        }
        Text(
            stringResource(R.string.ime_password_entropy_info, entropy),
            color = kb.secondaryText,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.End)
                .padding(bottom = 2.dp),
        )
        // Easter egg: one generation in five hundred also cites xkcd 936. A
        // caption beside the result, never an input to it — the roll is keyed
        // on the generated value but the value itself is untouched.
        val showXkcd = remember(generated) { generated.isNotEmpty() && Random.nextInt(500) == 0 }
        if (showXkcd) {
            Text(
                stringResource(R.string.ime_password_egg_info),
                color = kb.secondaryText,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(bottom = 2.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Ring indices mirror [optionEntries]: the stepper's − and + are
            // slots 0 and 1, then the chips in drawing order.
            if (!passphraseMode) {
                StepperRow(
                    label = stringResource(R.string.ime_password_length_label),
                    value = settings.passwordGenerator.pwLength,
                    focusedMinus = focusedOption == 0,
                    focusedPlus = focusedOption == 1,
                    onChange = { onSetting(PwSettingAction.Length(it)) },
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ToolPanelChip(
                        "A–Z",
                        selected = settings.passwordGenerator.pwUppercase,
                        modifier = Modifier.focusRing(focusedOption == 2),
                    ) {
                        onSetting(PwSettingAction.Upper(!settings.passwordGenerator.pwUppercase))
                    }
                    ToolPanelChip(
                        "0–9",
                        selected = settings.passwordGenerator.pwDigits,
                        modifier = Modifier.focusRing(focusedOption == 3),
                    ) {
                        onSetting(PwSettingAction.Digits(!settings.passwordGenerator.pwDigits))
                    }
                    ToolPanelChip(
                        "#!&",
                        selected = settings.passwordGenerator.pwSymbols,
                        modifier = Modifier.focusRing(focusedOption == 4),
                    ) {
                        onSetting(PwSettingAction.Symbols(!settings.passwordGenerator.pwSymbols))
                    }
                    ToolPanelChip(
                        stringResource(R.string.ime_password_exclude_ambiguous_label),
                        selected = settings.passwordGenerator.pwExcludeAmbiguous,
                        modifier = Modifier.focusRing(focusedOption == 5),
                    ) {
                        onSetting(PwSettingAction.ExcludeAmbiguous(!settings.passwordGenerator.pwExcludeAmbiguous))
                    }
                }
            } else {
                StepperRow(
                    label = stringResource(R.string.ime_password_words_label),
                    value = settings.passwordGenerator.ppWordCount,
                    focusedMinus = focusedOption == 0,
                    focusedPlus = focusedOption == 1,
                    onChange = { onSetting(PwSettingAction.Words(it)) },
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val separators = listOf("-", ".", "_", " ", "")
                    val next = separators[
                        (separators.indexOf(settings.passwordGenerator.ppSeparator) + 1).mod(separators.size),
                    ]
                    val separator = settings.passwordGenerator.ppSeparator
                    val separatorName = if (separator.isEmpty()) {
                        stringResource(CommonR.string.common_none)
                    } else {
                        "“$separator”"
                    }
                    ToolPanelChip(
                        stringResource(R.string.ime_password_separator_label, separatorName),
                        modifier = Modifier.focusRing(focusedOption == 2),
                    ) { onSetting(PwSettingAction.Separator(next)) }
                    ToolPanelChip(
                        stringResource(R.string.ime_password_capitalize_label),
                        selected = settings.passwordGenerator.ppCapitalize,
                        modifier = Modifier.focusRing(focusedOption == 3),
                    ) {
                        onSetting(PwSettingAction.Capitalize(!settings.passwordGenerator.ppCapitalize))
                    }
                    ToolPanelChip(
                        stringResource(R.string.ime_password_add_digit_label),
                        selected = settings.passwordGenerator.ppIncludeDigit,
                        modifier = Modifier.focusRing(focusedOption == 4),
                    ) {
                        onSetting(PwSettingAction.IncludeDigit(!settings.passwordGenerator.ppIncludeDigit))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    focusedMinus: Boolean = false,
    focusedPlus: Boolean = false,
    onChange: (Int) -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = kb.secondaryText, fontSize = 12.sp, modifier = Modifier.width(52.dp))
        ToolPanelChip("−", modifier = Modifier.focusRing(focusedMinus)) { onChange(value - 1) }
        Text(
            "$value",
            color = kb.modifierKeyText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp),
        )
        ToolPanelChip("+", modifier = Modifier.focusRing(focusedPlus)) { onChange(value + 1) }
    }
}

// ---- QR code generator ----

/**
 * QR code of content the user types into the panel itself (keystrokes reroute
 * into the shared search buffer via [PanelMode.hasMediaSearch], so the key
 * rows show below). It opens seeded with the field text as a convenience, but
 * from there it is independent of the focused field. Send commits the code as
 * a PNG.
 */
@Composable
internal fun QrGeneratorPanel(
    state: KeyboardUiState,
    onSend: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val content = state.mediaQuery
    // Preview renders small and cheap; the inserted PNG uses the size setting.
    val qrMaxChars = state.settings.toolLimits.qrMaxChars
    val bitmap = remember(content, state.settings.scanner.qrEcc, qrMaxChars) {
        QrCodeGen.bitmap(
            content,
            sizePx = 384,
            ecc = state.settings.scanner.qrEcc.name,
            maxChars = qrMaxChars,
        )
    }
    // Typing already reaches the content buffer; the ring only needs Send.
    PanelFocusTarget(
        panel = PanelMode.QR_GEN,
        region = FocusRegion.ACTIONS,
        count = if (bitmap != null) 1 else 0,
        columns = 1,
    ) { if (bitmap != null) onSend() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        // Editable content line with a faked caret — keys reroute into the
        // buffer, so the platform draws no cursor of its own.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(kb.chip)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchQueryText(
                query = content.replace("\n", " "),
                placeholder = stringResource(R.string.ime_qr_content_hint),
                active = state.mediaSearchActive,
                textColor = kb.suggestionText,
                placeholderColor = kb.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bitmap == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (content.length > qrMaxChars) {
                            pluralStringResource(
                                R.plurals.ime_qr_too_long_error,
                                content.length,
                                content.length,
                            )
                        } else {
                            stringResource(R.string.ime_qr_empty)
                        },
                        color = kb.secondaryText,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.ime_qr_image_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(4.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ToolPanelChip(
                        stringResource(R.string.ime_qr_send_action),
                        modifier = Modifier.focusRing(
                            state.focusedIndex(FocusRegion.ACTIONS) == 0,
                        ),
                    ) { onSend() }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        pluralStringResource(
                            R.plurals.ime_qr_meta_info,
                            content.length,
                            content.length,
                            state.settings.scanner.qrEcc.name,
                        ),
                        color = kb.secondaryText,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}
