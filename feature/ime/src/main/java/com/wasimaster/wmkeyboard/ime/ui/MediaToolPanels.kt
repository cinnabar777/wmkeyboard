package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.GifSourceMode
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.tools.GifItem
import com.wasimaster.wmkeyboard.core.tools.GifSource
import com.wasimaster.wmkeyboard.core.tools.GifSources
import com.wasimaster.wmkeyboard.core.tools.MediaCategory
import com.wasimaster.wmkeyboard.core.tools.ToolApiKeys
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ImageResult
import com.wasimaster.wmkeyboard.core.tools.WebResult
import com.wasimaster.wmkeyboard.ime.ImageSearchUi
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.MediaUi
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.WebSearchUi
import okhttp3.OkHttpClient

// ---- shared bits ----

/** Panel height while its search box is active and the key rows are shown below. */
private val MediaSearchHeight = 132.dp

/**
 * Shortest panel that still gets a category row: the search bar, the source
 * chips, the row itself and one whole row of results. Below this the row would
 * be taking the last of the grid, and a browsing aid that hides what it browses
 * is worse than no aid. A three-row layout at the default key height falls
 * under it; a four-row layout clears it, as does a three-row layout with the
 * number row (which ships on) adding its height.
 */
private val MediaCategoryMinPanelHeight = 190.dp

/**
 * Whether the GIF/sticker panel shows its category row.
 *
 * Pure and separate from the composable so every rule here is a unit test
 * rather than a device check.
 *
 * The row is a default-view thing: it starts a search, it does not refine one,
 * so a typed query replaces it. It survives a category tap, though — otherwise
 * you could enter a category and never pick another. Local packs have pack
 * chips instead, and a field that takes no rich media has a notice to show in
 * the same space.
 */
internal fun showMediaCategories(
    state: KeyboardUiState,
    localGrid: Boolean,
    fullBleed: Boolean,
    /** Passed in rather than read off [state]: the state's own getter needs a
     *  framework call, and this rule is worth having under a plain JVM test. */
    acceptsRichMedia: Boolean,
): Boolean {
    if (state.mediaCategories.isEmpty()) return false
    if (state.mediaSearchActive || localGrid || !acceptsRichMedia) return false
    if (state.mediaQuery.isNotBlank() && state.mediaCategory == null) return false
    return fullBleed || keyRowsHeight(state) >= MediaCategoryMinPanelHeight
}

/**
 * Query text with a caret in it, for the in-panel search fields.
 *
 * None of those are real text fields — keys are rerouted into panel state
 * instead of the focused editor — so the platform draws no cursor for them and
 * typing looked dead. This draws one, and since #161 it draws it *where the
 * caret actually is*: these fields have a movable caret now, a tap in the
 * middle of the text puts it there, and typing, backspace and a glide all land
 * at it.
 *
 * The text scrolls to keep the caret in view rather than ellipsizing, because
 * an ellipsis at the end of a query is an ellipsis over the part being typed.
 */
@Composable
internal fun SearchQueryText(
    query: String,
    placeholder: String,
    active: Boolean,
    textColor: Color,
    placeholderColor: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val handle = LocalCaptureCaret.current
    val caret = if (active) handle.at.coerceIn(0, query.length) else -1
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Empty field: the caret sits in front of the placeholder, where the
        // first character will land. Nothing to scroll and nowhere to tap.
        if (caret >= 0 && query.isEmpty()) {
            SearchCaret(textColor, fontSize, query)
            Spacer(Modifier.width(4.dp))
        }
        if (query.isEmpty()) {
            Text(
                text = placeholder,
                color = placeholderColor,
                fontSize = fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            CaretQueryText(
                query = query,
                caret = caret,
                textColor = textColor,
                fontSize = fontSize,
                handle = handle,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * The query itself, with the caret drawn inside it and a tap that moves it.
 *
 * The caret's x comes from the text's own layout, so it lands between the same
 * two glyphs the index names in any script and at any font scale, and the tap
 * is the inverse of that — `getOffsetForPosition`, which is what a real text
 * field uses. Both work in the *content's* coordinates: the scroll modifier is
 * applied outside them, so a query scrolled halfway along still maps a touch
 * to the character under the finger.
 */
@Composable
private fun CaretQueryText(
    query: String,
    caret: Int,
    textColor: Color,
    fontSize: TextUnit,
    handle: CaptureCaretHandle,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    // A selection in this field (#352): drawn here, its handles and bar by the
    // body's overlay, which needs to know where this text is as it scrolls.
    val overlay = LocalSelectionOverlay.current
    val owner = remember { SelectionAnchor() }
    val active = caret >= 0
    val selecting = active && handle.hasSelection
    val latestHandle by androidx.compose.runtime.rememberUpdatedState(handle)
    Box(
        modifier = modifier
            .horizontalScroll(scroll)
            .pointerInput(query, active) {
                if (!active) return@pointerInput
                detectTapGestures(
                    onLongPress = { position -> fieldLongPress(query, layout, position, latestHandle) },
                ) { position ->
                    layout?.takeIf { it.layoutInput.text.text == query }?.let {
                        latestHandle.onCaretTap(it.getOffsetForPosition(position))
                    }
                }
            },
    ) {
        Text(
            text = query,
            color = textColor,
            fontSize = fontSize,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = {
                layout = it
                overlay?.moved(owner)
            },
            modifier = Modifier
                .onGloballyPositioned {
                    owner.coordinates = it
                    overlay?.moved(owner)
                }
                .selectionHighlight(
                    query,
                    if (selecting) handle.selectionStart else 0,
                    if (selecting) handle.selectionEnd else 0,
                    LocalKbTheme.current.accent.copy(alpha = HighlightAlpha),
                ) { layout },
        )
        PublishFieldSelection(
            owner = owner,
            text = query,
            active = active,
            handle = handle,
            coordinates = { owner.coordinates },
            layout = { layout?.takeIf { it.layoutInput.text.text == query } },
        )
        // Only a layout of *this* text can say where the caret goes. `Text`
        // reports its layout during the layout phase, which runs after the
        // composition that changed the text, so the first composition to see a
        // new query still holds the layout of the old one — and asking that one
        // for an offset past its end threw `offset(2) is out of bounds [0, 1]`
        // on the second keystroke in every one of these boxes (#252). The
        // caret waits the one frame instead: writing `layout` in `onTextLayout`
        // schedules the recomposition that draws it.
        val result = layout?.takeIf { it.layoutInput.text.text == query }
        // No caret while a span is selected: the highlight marks where typing lands.
        if (caret >= 0 && result != null && !selecting) {
            // Clamped against the layout's own text and not against [query]:
            // they are the same string here, and it is the layout that defines
            // the legal range.
            val offset = caret.coerceIn(0, result.layoutInput.text.length)
            val x = result.getHorizontalPosition(offset, usePrimaryDirection = true)
            Box(Modifier.offset { IntOffset(x.roundToInt(), 0) }) {
                SearchCaret(textColor, fontSize, caret to query)
            }
            // Keep the caret on screen: typing at the end of a long query used
            // to run off the edge behind an ellipsis.
            val caretX = with(density) { x.toDp() }
            LaunchedEffect(caret, query, scroll.maxValue, scroll.viewportSize) {
                val viewport = scroll.viewportSize
                if (viewport <= 0) return@LaunchedEffect
                val target = with(density) { caretX.toPx() }.roundToInt()
                val margin = with(density) { CaretScrollMarginDp.dp.toPx() }.roundToInt()
                val want = when {
                    target - margin < scroll.value -> target - margin
                    target + margin > scroll.value + viewport -> target + margin - viewport
                    else -> return@LaunchedEffect
                }
                scroll.scrollTo(want.coerceIn(0, scroll.maxValue))
            }
        }
    }
}

/** How much text is kept visible either side of the caret when it scrolls. */
private const val CaretScrollMarginDp = 12

/** The blinking bar itself. [restartKey] resets the phase on each keystroke — and on
 *  each caret move, so a tap into the middle of the text shows the caret solid. */
@Composable
private fun SearchCaret(color: Color, fontSize: TextUnit, restartKey: Any?) {
    // Reduce motion holds it solid. The caret still has to be drawn — it is
    // the only thing marking where typing lands — so this is a branch on the
    // loop rather than on an animation spec: with the blink gone there is no
    // spec left to slow down, and a caret that snapped between visible and
    // hidden would be the same flicker at a harder edge.
    val reduceMotion = LocalKbTheme.current.reduceMotion
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(restartKey, reduceMotion) {
        visible = true
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            delay(500)
            visible = !visible
        }
    }
    val height = with(LocalDensity.current) { fontSize.toDp() * 1.25f }
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .height(height)
            .background(if (visible) color else Color.Transparent),
    )
}

@Volatile
private var sharedMediaLoader: ImageLoader? = null
private val mediaLoaderLock = Any()

/**
 * The one image loader for the whole process, with animated GIF/WebP support.
 *
 * A singleton for two reasons. A disk cache directory can have exactly one
 * owner — Coil refuses two live loaders pointing at the same one — so building
 * a loader per call site is what had been keeping the disk cache from existing
 * at all, and every thumbnail was re-fetched on every scroll back. And a dozen
 * loaders each holding their own memory cache is a lot of heap for an input
 * method, which the system already kills early.
 *
 * The keyboard service declares no `android:process`, so the settings app and
 * the keyboard really do share one process and one loader.
 */
fun mediaImageLoader(context: Context): ImageLoader =
    sharedMediaLoader ?: synchronized(mediaLoaderLock) {
        sharedMediaLoader ?: ImageLoader.Builder(context.applicationContext)
            .components {
                // Every thumbnail and animation goes in the network activity
                // log. Image hosts are arbitrary third-party CDNs, so the rows
                // show the host and no path.
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .addInterceptor(InternetGate)
                                // OkHttp's own `okhttp/x.y` agent is refused
                                // outright by Wikimedia's image hosts, which
                                // left every Commons preview blank. Same agent
                                // the downloads send, so the preview and the
                                // file it stands for are fetched alike.
                                .addInterceptor { chain ->
                                    chain.proceed(
                                        chain.request().newBuilder()
                                            .header("User-Agent", ToolHttp.USER_AGENT)
                                            .build(),
                                    )
                                }
                                .addNetworkInterceptor(NetLogInterceptor(NetSource.MEDIA_IMAGES))
                                .build()
                        },
                    ),
                )
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            // Below Coil's 20% default: a bloated keyboard is a visible jank
            // source, and three screens of thumbnails is all the scrollback
            // anyone flicks through.
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.12).build() }
            // Under cacheDir, so the system can reclaim it under storage
            // pressure — correct for content that is trivially fetched again.
            .diskCache {
                DiskCache.Builder()
                    .directory(context.applicationContext.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(MEDIA_DISK_CACHE_BYTES)
                    .build()
            }
            .build()
            .also { sharedMediaLoader = it }
    }

/** The process-wide media loader; see [mediaImageLoader]. */
@Composable
fun rememberMediaImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { mediaImageLoader(context) }
}

private const val MEDIA_DISK_CACHE_BYTES = 64L * 1024 * 1024

/**
 * Header-row variant of the media search field, for full-bleed panels: it
 * sits next to the back button in the [FullBleedTool] header and takes the
 * row's free width. Same key-rerouting trick as emoji search — typing goes
 * into [KeyboardUiState.mediaQuery], never the focused field.
 */
@Composable
internal fun RowScope.MediaHeaderSearchBar(
    state: KeyboardUiState,
    placeholder: String,
    onQueryTap: () -> Unit,
    attribution: String? = null,
    // Prompt shown once the field is typed into. Null falls back to the search
    // wording; tools that aren't searching (e.g. translate) pass their own.
    // A default argument cannot hold a resource, hence the null.
    activePlaceholder: String? = null,
    focused: Boolean = false,
    // Shown while there is a query: starts a new search. Null hides it.
    onClear: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    val activeHint = activePlaceholder ?: stringResource(R.string.ime_media_search_active_hint)
    val shape = kb.cardShape()
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(start = 6.dp, end = 4.dp)
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .clickable { onQueryTap() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = kb.toolbarIcon,
        )
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = state.mediaQuery,
            placeholder = if (state.mediaSearchActive) activeHint else placeholder,
            active = state.mediaSearchActive,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (attribution != null) {
            Text(
                attribution,
                color = kb.secondaryText,
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        if (onClear != null && state.mediaQuery.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_clipboard_search_clear_desc),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(16.dp)
                    .clickable { onClear() },
                tint = kb.toolbarIcon,
            )
        }
    }
}

/**
 * The tappable search field at the top of the media panels — same
 * key-rerouting trick as emoji search: tapping it keeps the key rows on
 * screen and typing goes into [KeyboardUiState.mediaQuery].
 */
@Composable
private fun MediaSearchBar(
    state: KeyboardUiState,
    placeholder: String,
    onQueryTap: () -> Unit,
    attribution: String? = null,
    focused: Boolean = false,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = kb.cardShape()
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .focusRing(focused, shape)
                .clickable { onQueryTap() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = kb.toolbarIcon,
            )
            Spacer(Modifier.width(8.dp))
            SearchQueryText(
                query = state.mediaQuery,
                placeholder = if (state.mediaSearchActive) {
                    stringResource(R.string.ime_media_search_active_hint)
                } else {
                    placeholder
                },
                active = state.mediaSearchActive,
                textColor = kb.suggestionText,
                placeholderColor = kb.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
        }
        if (attribution != null) {
            Text(
                attribution,
                color = kb.secondaryText,
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Centered message with an optional action chip, in panel theme colors. */
@Composable
private fun PanelNotice(
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            color = kb.toolbarIcon,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                actionLabel,
                color = kb.chipActiveText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(kb.chipShape())
                    .background(kb.chipActive)
                    .chipBorder(kb, kb.chipShape())
                    .clickable { onAction() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The panel is empty because data saving is holding the fetch.
 *
 * Two shapes, from the two answers the user can have given: "ask each time"
 * offers the fetch here, and a tap on that chip allows this feature for the
 * rest of the session; "turn off" says so and offers nothing, because they
 * have already answered. Both name the connection rather than blaming the
 * network, so an empty grid never reads as a bug.
 */
@Composable
private fun MeteredNotice(canAllow: Boolean, onAllow: () -> Unit) {
    PanelNotice(
        message = stringResource(
            if (canAllow) R.string.ime_metered_ask_body else R.string.ime_metered_off_body,
        ),
        actionLabel = stringResource(R.string.ime_metered_allow_action).takeIf { canAllow },
        onAction = onAllow,
    )
}

/**
 * Standing bar across the top of a media grid: this field takes no rich
 * content, so nothing here can be sent into it. Deliberately not a toast —
 * the limitation belongs to the field, not to the tap, so it stays visible
 * for as long as that field is focused.
 */
@Composable
private fun MediaUnsupportedNotice(stickers: Boolean) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(kb.cardShape())
            .background(kb.chip)
            .chipBorder(kb, kb.cardShape())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = kb.toolbarIcon,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (stickers) {
                stringResource(R.string.ime_sticker_unsupported_field_notice)
            } else {
                stringResource(R.string.ime_gif_unsupported_field_notice)
            },
            color = kb.toolbarIcon,
            fontSize = 11.sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun PanelSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 3.dp,
            color = LocalKbTheme.current.accent,
        )
    }
}

// ---- GIF & sticker panels ----

// GIF/sticker picker: trending on open, live search from the search bar,
// animated previews, tap to download & commit into the editor. With
// several providers configured, either a chip per source or one
// evenly-mixed grid, per the tool's settings.

/** The hint in the search box: GIFs and stickers each have their own wording. */
@Composable
private fun gifSearchHint(stickers: Boolean): String = stringResource(
    if (stickers) R.string.ime_sticker_search_hint else R.string.ime_gif_search_hint,
)

/** "from Klipy · GIPHY" — whichever providers this grid is actually pulling from. */
@Composable
private fun gifAttribution(state: KeyboardUiState, stickers: Boolean = false): String? {
    val sources = gifSourcesFor(state, stickers)
    val tabs = state.settings.gif.sourceMode == GifSourceMode.TABS
    val targets = GifSources.targets(sources, state.mediaSource, tabs)
    // Nothing to credit for packs on the device.
    if (targets.isEmpty() || targets.all { it.onDevice }) return null
    val names = StringBuilder()
    for (target in targets) {
        if (names.isNotEmpty()) names.append(" · ")
        names.append(stringResource(GifSources.displayNameRes(target)))
    }
    return stringResource(R.string.ime_media_attribution, names.toString())
}

private fun gifSourcesFor(state: KeyboardUiState, stickers: Boolean): List<GifSource> =
    if (stickers) ToolApiKeys.stickerSources(state.settings) else ToolApiKeys.gifSources(state.settings)

/** The GIF/sticker search box sized for a [FullBleedTool] header row. */
@Composable
internal fun RowScope.GifHeaderSearchBar(
    state: KeyboardUiState,
    stickers: Boolean,
    onQueryTap: () -> Unit,
) {
    MediaHeaderSearchBar(
        state = state,
        placeholder = gifSearchHint(stickers),
        onQueryTap = onQueryTap,
        attribution = gifAttribution(state, stickers),
    )
}

/**
 * @param state the live keyboard UI state, read for the gif/sticker tab.
 * @param stickers true for the sticker tab, false for gif.
 * @param onQueryTap the search box was tapped.
 * @param onRetry the failed load should be retried.
 * @param onSelect a result was picked for sending.
 * @param onSourceSelect the gif/sticker source was switched.
 * @param onOpenToolSettings the panel's own settings row was tapped.
 * @param fullBleed the panel is inside a [FullBleedTool], which owns the
 *   height and hosts the search box in its header — so the body draws
 *   neither.
 * @param onCategorySelect a category chip was tapped.
 * @param onLongPress a result was long-pressed.
 * @param onPackFilter the sticker-pack filter changed, or was cleared.
 * @param onSaveToPack a result should be saved into a local pack.
 * @param onCopy a result should be copied to the clipboard.
 * @param onReport a result should be reported.
 * @param onDismissAction the open long-press action sheet should close.
 * @param onOpenRoute a settings route should open.
 */
@Composable
internal fun GifPanel(
    state: KeyboardUiState,
    stickers: Boolean,
    onQueryTap: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (GifItem) -> Unit,
    onSourceSelect: (GifSource) -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
    fullBleed: Boolean = false,
    onCategorySelect: (String) -> Unit = {},
    onLongPress: (GifItem) -> Unit = {},
    onPackFilter: (String?) -> Unit = {},
    onSaveToPack: (GifItem, String?) -> Unit = { _, _ -> },
    onCopy: (GifItem) -> Unit = {},
    onReport: (GifItem) -> Unit = {},
    onDismissAction: () -> Unit = {},
    onOpenRoute: (String) -> Unit = {},
) {
    val ui = if (stickers) state.sticker else state.gif
    val tool = if (stickers) ToolbarTool.STICKER else ToolbarTool.GIF
    val sources = gifSourcesFor(state, stickers)
    val tabsMode = state.settings.gif.sourceMode == GifSourceMode.TABS
    val chips = GifSources.chips(sources, tabsMode)
    val localGrid = GifSources.targets(sources, state.mediaSource, tabsMode) == listOf(GifSource.LOCAL)
    val sizing = if (fullBleed) {
        Modifier.fillMaxSize()
    } else {
        val height = if (state.mediaSearchActive) MediaSearchHeight else keyRowsHeight(state)
        Modifier
            .fillMaxWidth()
            .height(height)
    }
    // "Add to which pack?", up when the add chip is pressed under All with
    // more than one pack to choose from. Panel-local: nothing else reads it.
    var choosingAddPack by remember { mutableStateOf(false) }
    Box(modifier = sizing) {
        Column(modifier = Modifier.fillMaxSize()) {
            PanelFocusTarget(
                panel = state.panel,
                region = FocusRegion.SEARCH,
                count = 1,
                columns = 1,
                onActivate = { onQueryTap() },
            )
            if (!fullBleed) {
                MediaSearchBar(
                    state = state,
                    placeholder = gifSearchHint(stickers),
                    onQueryTap = onQueryTap,
                    attribution = gifAttribution(state, stickers),
                    focused = state.focusedIndex(FocusRegion.SEARCH) == 0,
                )
            }
            if (chips.isNotEmpty() && !state.mediaSearchActive) {
                // Tab reaches the source chips: results are useless if the
                // keyboard can browse them but not switch where they come from.
                PanelFocusTarget(
                    panel = state.panel,
                    region = FocusRegion.CHIPS,
                    count = chips.size,
                    columns = chips.size,
                    onActivate = { index -> chips.getOrNull(index)?.let { onSourceSelect(it.source) } },
                )
                GifSourceChips(
                    chips = chips,
                    selectedIndex = GifSources.selectedChip(chips, state.mediaSource),
                    onSelect = onSourceSelect,
                    focused = state.focusedIndex(FocusRegion.CHIPS),
                )
            }
            // With no packs at all the empty grid has its own way in, so the
            // row waits for the first one.
            if (localGrid && state.stickerPacks.isNotEmpty() && !state.mediaSearchActive) {
                StickerPackChips(
                    packs = state.stickerPacks,
                    selected = state.stickerPackId,
                    onSelect = onPackFilter,
                    onAdd = {
                        val target = stickerAddTarget(state.stickerPacks, state.stickerPackId)
                        if (target == null) {
                            choosingAddPack = true
                        } else {
                            onOpenRoute(stickerPackAddRoute(target))
                        }
                    },
                )
            }
            if (showMediaCategories(state, localGrid, fullBleed, state.acceptsRichMedia)) {
                // Trending first, so there is always a way back out of a
                // category — and somewhere for the focus ring to sit when no
                // category is on.
                val entries = listOf(
                    MediaCategory(term = "", labelRes = R.string.ime_media_category_trending_label),
                ) + state.mediaCategories
                PanelFocusTarget(
                    panel = state.panel,
                    region = FocusRegion.CATEGORIES,
                    count = entries.size,
                    columns = entries.size,
                    onActivate = { index ->
                        entries.getOrNull(index)?.let { onCategorySelect(it.term) }
                    },
                )
                GifCategoryChips(
                    categories = entries,
                    selected = state.mediaCategory,
                    onSelect = onCategorySelect,
                    focused = state.focusedIndex(FocusRegion.CATEGORIES),
                )
            }
            // The field publishes the MIME types it accepts; when none of them
            // is an image, nothing this panel can send will ever arrive. Say so
            // once, up front, instead of letting every tap end in a download
            // and a "copied, paste it instead" toast.
            val unsupported = !state.acceptsRichMedia
            // Not while the search box is up — the panel is squeezed to a couple
            // of rows there, and the notice is waiting when the results land.
            if (unsupported && !state.mediaSearchActive) MediaUnsupportedNotice(stickers)
            when (ui) {
                MediaUi.NeedKey -> PanelNotice(
                    if (stickers) {
                        stringResource(R.string.ime_sticker_need_key_body)
                    } else {
                        stringResource(R.string.ime_gif_need_key_body)
                    },
                    actionLabel = stringResource(R.string.ime_open_settings_action),
                    onAction = { onOpenToolSettings(tool) },
                )
                MediaUi.Loading -> PanelSpinner()
                is MediaUi.Error -> PanelNotice(
                    ui.message,
                    actionLabel = stringResource(CommonR.string.common_retry),
                    onAction = onRetry,
                )
                is MediaUi.Metered -> MeteredNotice(ui.canAllow, onRetry)
                is MediaUi.Ready -> {
                    if (ui.items.isEmpty()) {
                        LocalStickerEmptyNotice(
                            localGrid = localGrid,
                            state = state,
                            query = ui.query,
                            stickers = stickers,
                            onOpenRoute = onOpenRoute,
                        )
                    } else {
                        // Dimmed rather than removed when the field can't take
                        // them: long-press (save, copy, report) still works, and
                        // the user can see what they'd get in a field that
                        // accepts it.
                        Box(modifier = Modifier.alpha(if (unsupported) 0.45f else 1f)) {
                            GifGrid(
                                items = ui.items,
                                downloadingId = state.mediaDownloadingId,
                                progress = state.mediaDownloadProgress,
                                onSelect = onSelect,
                                onLongPress = onLongPress,
                                panel = state.panel,
                                focused = state.focusedIndex(),
                            )
                        }
                    }
                }
            }
        }
        if (choosingAddPack) {
            StickerAddPackSheet(
                packs = state.stickerPacks,
                onPick = { packId ->
                    choosingAddPack = false
                    onOpenRoute(stickerPackAddRoute(packId))
                },
                onDismiss = { choosingAddPack = false },
            )
        }
        val action = state.mediaAction
        if (action != null) {
            MediaActionSheet(
                item = action,
                stickers = stickers,
                packs = state.stickerPacks,
                onSaveToPack = onSaveToPack,
                onCopy = onCopy,
                onReport = onReport,
                onOpenRoute = onOpenRoute,
                onDismiss = onDismissAction,
            )
        }
    }
}

/** The empty grid: local packs have their own way of being empty. */
@Composable
private fun LocalStickerEmptyNotice(
    localGrid: Boolean,
    state: KeyboardUiState,
    query: String,
    stickers: Boolean,
    onOpenRoute: (String) -> Unit,
) {
    when {
        !localGrid -> PanelNotice(
            when {
                query.isBlank() -> stringResource(R.string.ime_media_trending_empty)
                stickers -> stringResource(R.string.ime_sticker_search_empty, query)
                else -> stringResource(R.string.ime_gif_search_empty, query)
            },
        )
        query.isNotBlank() ->
            PanelNotice(stringResource(R.string.ime_sticker_local_search_empty, query))
        state.stickerPacks.isEmpty() -> PanelNotice(
            stringResource(R.string.ime_sticker_no_packs_empty),
            actionLabel = stringResource(R.string.ime_sticker_manage_packs_action),
            onAction = { onOpenRoute(STICKER_PACKS_ROUTE) },
        )
        else -> PanelNotice(
            stringResource(R.string.ime_sticker_pack_empty),
            actionLabel = stringResource(R.string.ime_sticker_manage_packs_action),
            onAction = { onOpenRoute(STICKER_PACKS_ROUTE) },
        )
    }
}

/** Settings route hosting the sticker pack manager. */
internal const val STICKER_PACKS_ROUTE = "sticker_packs"

/**
 * Settings route that opens one pack with the photo picker already up. The
 * app's nav graph declares it as `sticker_pack/{packId}/add`.
 */
internal fun stickerPackAddRoute(packId: String): String =
    "sticker_pack/${android.net.Uri.encode(packId)}/add"

/**
 * Which pack the add chip adds to, or null when the user has to say.
 *
 * The pack the grid is filtered to, if one is; otherwise the only pack there
 * is. Under All with several packs any pick would be a guess, and a sticker
 * filed in the wrong pack is a second trip to move it.
 */
internal fun stickerAddTarget(
    packs: List<com.wasimaster.wmkeyboard.core.stickers.StickerPack>,
    selected: String?,
): String? =
    selected?.takeIf { id -> packs.any { it.id == id } } ?: packs.singleOrNull()?.id

/** Source chips: Klipy / GIPHY / My stickers, or Online / My stickers when mixed. */
@Composable
private fun GifSourceChips(
    chips: List<com.wasimaster.wmkeyboard.core.tools.SourceChip>,
    selectedIndex: Int,
    onSelect: (GifSource) -> Unit,
    focused: Int? = null,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val shape = kb.chipShape()
        chips.forEachIndexed { index, chip ->
            val active = index == selectedIndex
            Text(
                stringResource(chip.labelRes),
                color = if (active) kb.chipActiveText else kb.chipText,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (active) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .focusRing(index == focused, shape)
                    .clickable { onSelect(chip.source) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Category chips for the default view. Pressing one runs it as a search.
 *
 * A `LazyRow` rather than the plain `horizontalScroll` the pack chips use: a
 * ScrollState has no index-to-offset map, so a focus ring landing on a chip
 * past the right edge could never be scrolled into view.
 */
@Composable
private fun GifCategoryChips(
    categories: List<MediaCategory>,
    selected: String?,
    onSelect: (String) -> Unit,
    focused: Int? = null,
) {
    val kb = LocalKbTheme.current
    val listState = rememberLazyListState()
    ScrollFocusIntoView(focused) { listState.animateScrollToItem(it) }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(categories, key = { _, category -> category.term }) { index, category ->
            // Trending carries the blank term, so it is the active chip
            // exactly when no category is.
            val active = category.term == selected.orEmpty()
            val shape = kb.chipShape()
            Text(
                categoryLabel(category),
                color = if (active) kb.chipActiveText else kb.chipText,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .focusRing(index == focused, shape)
                    .clickable { onSelect(category.term) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** A bundled category names itself from resources; a provider's names itself. */
@Composable
private fun categoryLabel(category: MediaCategory): String =
    if (category.labelRes != 0) stringResource(category.labelRes) else category.label

/**
 * Pack chips under the "My stickers" tab: the add chip, then All and one per
 * pack. A single pack has nothing to filter, so it gets the add chip alone.
 *
 * Add comes first so a long row of packs cannot scroll it out of reach.
 */
@Composable
private fun StickerPackChips(
    packs: List<com.wasimaster.wmkeyboard.core.stickers.StickerPack>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onAdd: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = kb.chipShape()
        Box(
            modifier = Modifier
                .clip(shape)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .clickable { onAdd() }
                .padding(horizontal = 10.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = stringResource(R.string.ime_sticker_add_desc),
                modifier = Modifier.size(16.dp),
                tint = kb.chipText,
            )
        }
        if (packs.size < 2) return@Row
        val allLabel = stringResource(R.string.ime_sticker_pack_all_label)
        val entries = listOf<Pair<String?, String>>(null to allLabel) + packs.map { it.id to it.name }
        for ((id, label) in entries) {
            val active = id == selected
            Text(
                label,
                color = if (active) kb.chipActiveText else kb.chipText,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Long-press menu over the GIF or sticker grid.
 *
 * An IME can't put up a dialog — it has no activity window — so this is a
 * scrim plus a panel-local surface, dismissed by tapping outside.
 *
 * Which rows show depends on where the item came from. Saving to a pack is
 * sticker-only (a GIF can't become one), and neither reporting nor saving
 * makes sense for the user's own stickers — they own the file already, and
 * there's nobody to report it to.
 */
@Composable
private fun MediaActionSheet(
    item: GifItem,
    stickers: Boolean,
    packs: List<com.wasimaster.wmkeyboard.core.stickers.StickerPack>,
    onSaveToPack: (GifItem, String?) -> Unit,
    onCopy: (GifItem) -> Unit,
    onReport: (GifItem) -> Unit,
    onOpenRoute: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(kb.popup)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            // The item's name first — long-press is also the only way to
            // find out what a result is called. One of the user's own
            // stickers says everything it is found by instead: its title,
            // its pack, and the keywords and emoji that offer it while typing.
            val own = if (item.source == GifSource.LOCAL) ownSticker(item, packs) else null
            if (own != null) {
                OwnStickerInfo(own.first, own.second)
            } else if (item.title.isNotBlank()) {
                Text(
                    item.title,
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
            val local = item.source == GifSource.LOCAL
            if (stickers) {
                if (local) {
                    MediaActionRow(stringResource(R.string.ime_sticker_manage_packs_action)) {
                        onOpenRoute(STICKER_PACKS_ROUTE)
                    }
                } else if (packs.isEmpty()) {
                    MediaActionRow(stringResource(R.string.ime_sticker_save_new_pack_action)) {
                        onSaveToPack(item, null)
                    }
                } else {
                    for (pack in packs) {
                        val label =
                            stringResource(R.string.ime_sticker_save_to_pack_action, pack.name)
                        MediaActionRow(label) { onSaveToPack(item, pack.id) }
                    }
                }
            }
            MediaActionRow(stringResource(CommonR.string.common_copy)) { onCopy(item) }
            // Reporting goes to the provider; an imported pack has none.
            if (!item.source.onDevice) {
                MediaActionRow(stringResource(R.string.ime_media_report_action)) { onReport(item) }
            }
        }
    }
}

/** The pack and sticker behind a `local_…` grid item, from the packs the panel already holds. */
private fun ownSticker(
    item: GifItem,
    packs: List<com.wasimaster.wmkeyboard.core.stickers.StickerPack>,
): Pair<com.wasimaster.wmkeyboard.core.stickers.StickerPack, com.wasimaster.wmkeyboard.core.stickers.CustomSticker>? =
    packs.firstNotNullOfOrNull { pack ->
        val prefix = com.wasimaster.wmkeyboard.core.stickers.StickerPackStore.ITEM_PREFIX + pack.id + "_"
        if (!item.id.startsWith(prefix)) return@firstNotNullOfOrNull null
        pack.stickers.firstOrNull { it.id == item.id.removePrefix(prefix) }?.let { pack to it }
    }

/**
 * The head of the long-press sheet for one of the user's own stickers: the
 * title (or that it has none), the pack, then the word keywords and the emoji
 * on lines of their own, since those are two different ways of calling it up.
 */
@Composable
private fun OwnStickerInfo(
    pack: com.wasimaster.wmkeyboard.core.stickers.StickerPack,
    sticker: com.wasimaster.wmkeyboard.core.stickers.CustomSticker,
) {
    val kb = LocalKbTheme.current
    val (emoji, words) = sticker.keywords.partition {
        com.wasimaster.wmkeyboard.core.stickers.StickerKeywords.isEmoji(it)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            sticker.name.ifBlank { stringResource(R.string.ime_sticker_info_untitled) },
            color = kb.popupText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        OwnStickerInfoLine(stringResource(R.string.ime_sticker_info_pack, pack.name))
        if (words.isNotEmpty()) {
            OwnStickerInfoLine(stringResource(R.string.ime_sticker_info_keywords, words.joinToString(", ")))
        }
        if (emoji.isNotEmpty()) {
            OwnStickerInfoLine(stringResource(R.string.ime_sticker_info_emoji, emoji.joinToString(" ")))
        }
    }
}

@Composable
private fun OwnStickerInfoLine(text: String) {
    Text(
        text,
        color = LocalKbTheme.current.secondaryText,
        fontSize = 12.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * "Add to which pack?" for the add chip under All. The same scrim and surface
 * as [MediaActionSheet], for the same reason: an IME has no dialog.
 */
@Composable
private fun StickerAddPackSheet(
    packs: List<com.wasimaster.wmkeyboard.core.stickers.StickerPack>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(kb.popup)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            for (pack in packs) {
                MediaActionRow(stringResource(R.string.ime_sticker_add_to_pack_action, pack.name)) {
                    onPick(pack.id)
                }
            }
        }
    }
}

@Composable
private fun MediaActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = LocalKbTheme.current.popupText,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

@Composable
private fun GifGrid(
    items: List<GifItem>,
    downloadingId: String?,
    progress: Float?,
    onSelect: (GifItem) -> Unit,
    onLongPress: ((GifItem) -> Unit)? = null,
    panel: PanelMode = PanelMode.GIF,
    focused: Int? = null,
) {
    val loader = rememberMediaImageLoader()
    val listState = rememberLazyListState()
    // Justified rows instead of a fixed grid: each row shares one height and
    // every preview keeps its own aspect ratio, so nothing is cropped. Wide
    // GIFs pair up into rows of two; squarish ones sit three across.
    val rows = remember(items) { GifSources.rows(items) }
    // First flat index of each row, for mapping the focus ring to a row.
    val rowStarts = remember(rows) {
        var start = 0
        rows.map { row -> start.also { start += row.size } }
    }
    PanelFocusTarget(
        panel = panel,
        count = items.size,
        columns = 3,
        onActivate = { index -> items.getOrNull(index)?.let(onSelect) },
    )
    ScrollFocusIntoView(focused) { index ->
        val rowIndex = rowStarts.indexOfLast { it <= index }.coerceAtLeast(0)
        listState.animateScrollToItem(rowIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.first().id }) { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEachIndexed { indexInRow, item ->
                    val ratio = GifSources.cellRatio(item)
                    GifCell(
                        item = item,
                        loader = loader,
                        downloadingId = downloadingId,
                        progress = progress,
                        focused = rowStarts[rowIndex] + indexInRow == focused,
                        onSelect = onSelect,
                        onLongPress = onLongPress,
                        modifier = Modifier
                            .weight(ratio)
                            .aspectRatio(ratio),
                    )
                }
                // Weights hand a sparse row the full width, which would blow
                // its height up; pad thin rows out to the target instead.
                val sum = row.fold(0f) { acc, item -> acc + GifSources.cellRatio(item) }
                if (sum < GifSources.TARGET_ROW_RATIO) {
                    Spacer(Modifier.weight(GifSources.TARGET_ROW_RATIO - sum))
                }
            }
        }
    }
}

@Composable
private fun GifCell(
    item: GifItem,
    loader: ImageLoader,
    downloadingId: String?,
    progress: Float?,
    focused: Boolean,
    onSelect: (GifItem) -> Unit,
    onLongPress: ((GifItem) -> Unit)?,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(LocalKbTheme.current.chip)
            .focusRing(focused, RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = downloadingId == null,
                onClick = { onSelect(item) },
                onLongClick = onLongPress?.let { { it(item) } },
            ),
    ) {
        AsyncImage(
            model = item.previewUrl,
            contentDescription = item.title.ifBlank { stringResource(R.string.ime_gif_item_desc) },
            imageLoader = loader,
            modifier = Modifier.matchParentSize(),
            // Fit, not Crop: the cell already has the preview's shape, so
            // this only letterboxes the few whose ratio the cell clamped.
            contentScale = ContentScale.Fit,
        )
        if (downloadingId == item.id) MediaDownloadOverlay(progress)
    }
}

/**
 * What a media cell wears while its file comes down: the preview dimmed, and
 * over it how far the transfer has got.
 *
 * [progress] is null until the first bytes arrive, and stays null for a server
 * that declared no size — those keep the indeterminate spinner, because a ring
 * frozen at zero says less than one that turns. The fill is animated between
 * the whole percents the service publishes so it sweeps rather than steps.
 */
@Composable
internal fun BoxScope.MediaDownloadOverlay(progress: Float?) {
    val fraction by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "mediaDownloadProgress",
    )
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        if (progress == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = Color.White,
            )
        } else {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
            )
        }
    }
}

// ---- web search panel ----

/**
 * Web search results (full-bleed body; the search bar lives in the
 * [FullBleedTool] header): tap a result to insert its link.
 */
@Composable
internal fun WebSearchPanel(
    state: KeyboardUiState,
    onRetry: () -> Unit,
    onResult: (WebResult) -> Unit,
    onOpen: (WebResult) -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
) {
    val kb = LocalKbTheme.current
    Column(modifier = Modifier.fillMaxSize()) {
        when (val ui = state.webSearch) {
            WebSearchUi.NeedKey -> PanelNotice(
                stringResource(R.string.ime_web_search_need_key_body),
                actionLabel = stringResource(R.string.ime_open_settings_action),
                onAction = { onOpenToolSettings(ToolbarTool.WEB_SEARCH) },
            )
            WebSearchUi.Idle -> PanelNotice(stringResource(R.string.ime_web_search_idle))
            WebSearchUi.Loading -> PanelSpinner()
            is WebSearchUi.Error -> PanelNotice(
                ui.message,
                actionLabel = stringResource(CommonR.string.common_retry),
                onAction = onRetry,
            )
            is WebSearchUi.Metered -> MeteredNotice(ui.canAllow, onRetry)
            is WebSearchUi.Ready -> {
                if (ui.results.isEmpty()) {
                    PanelNotice(stringResource(R.string.ime_web_search_empty, ui.query))
                } else {
                    val focused = state.focusedIndex()
                    val listState = rememberLazyListState()
                    PanelFocusTarget(
                        panel = PanelMode.WEB_SEARCH,
                        count = ui.results.size,
                        columns = 1,
                        // The open-in-browser icon stays touch-only; Enter does
                        // what a tap on the row does, which is insert.
                        onActivate = { index -> ui.results.getOrNull(index)?.let(onResult) },
                    )
                    ScrollFocusIntoView(focused) { listState.animateScrollToItem(it) }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(ui.results) { index, result ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onResult(result) }
                                    .focusRing(index == focused, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        result.title,
                                        color = kb.accent,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        result.displayUrl,
                                        color = kb.secondaryText,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (result.snippet.isNotBlank()) {
                                        Text(
                                            result.snippet,
                                            color = kb.suggestionText,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(onClick = { onOpen(result) }) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.OpenInNew,
                                        contentDescription =
                                            stringResource(R.string.ime_web_search_open_desc),
                                        modifier = Modifier.size(18.dp),
                                        tint = kb.toolbarIcon,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- image search panel ----

/**
 * Camera button beside the image search box (#349): opens the camera tool
 * with Search in place of Send, to search by a photo instead of by words.
 */
@Composable
internal fun SearchByPhotoButton(onClick: () -> Unit) {
    ToolCircle(
        slot = IconSlots.forTool(ToolbarTool.CAMERA),
        description = stringResource(R.string.ime_image_search_by_photo_desc),
        active = false,
        onClick = onClick,
    )
}

/**
 * Image search: grid of thumbnails; tap inserts the image itself (via
 * commitContent, like clipboard images), long-press inserts the image URL.
 */
@Composable
internal fun ImageSearchPanel(
    state: KeyboardUiState,
    onRetry: () -> Unit,
    onResult: (ImageResult) -> Unit,
    onResultLink: (ImageResult) -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        when (val ui = state.imageSearch) {
            ImageSearchUi.NeedKey -> PanelNotice(
                stringResource(R.string.ime_image_search_need_key_body),
                actionLabel = stringResource(R.string.ime_open_settings_action),
                onAction = { onOpenToolSettings(ToolbarTool.IMAGE_SEARCH) },
            )
            ImageSearchUi.Idle -> PanelNotice(stringResource(R.string.ime_image_search_idle))
            ImageSearchUi.Loading -> PanelSpinner()
            is ImageSearchUi.Error -> PanelNotice(
                ui.message,
                actionLabel = stringResource(CommonR.string.common_retry),
                onAction = onRetry,
            )
            is ImageSearchUi.Metered -> MeteredNotice(ui.canAllow, onRetry)
            is ImageSearchUi.Ready -> {
                if (ui.results.isEmpty()) {
                    PanelNotice(stringResource(R.string.ime_image_search_empty, ui.query))
                } else {
                    ImageGrid(
                        results = ui.results,
                        downloadingId = state.mediaDownloadingId,
                        progress = state.mediaDownloadProgress,
                        columnCount = state.settings.emoji.mediaGridColumns,
                        onResult = onResult,
                        onResultLink = onResultLink,
                        focused = state.focusedIndex(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageGrid(
    results: List<ImageResult>,
    downloadingId: String?,
    progress: Float?,
    /** Columns, from the user's setting. Two gives a bigger look before sending. */
    columnCount: Int,
    onResult: (ImageResult) -> Unit,
    onResultLink: (ImageResult) -> Unit,
    focused: Int? = null,
) {
    val loader = rememberMediaImageLoader()
    // imageUrl is the LazyGrid key, which must be unique — Brave can return the
    // same full-image URL for two results (a widely-reposted image), and a
    // duplicate key throws during composition and crashes the IME. Dedup first.
    val uniqueResults = remember(results) { results.distinctBy { it.imageUrl } }
    val gridState = rememberLazyGridState()
    // Indexed against the deduped list, which is what the grid actually draws.
    PanelFocusTarget(
        panel = PanelMode.IMAGE_SEARCH,
        count = uniqueResults.size,
        columns = columnCount,
        onActivate = { index ->
            if (downloadingId == null) uniqueResults.getOrNull(index)?.let(onResult)
        },
    )
    ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columnCount),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(uniqueResults, key = { _, result -> result.imageUrl }) { index, result ->
            Box(
                modifier = Modifier
                    .height(86.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LocalKbTheme.current.chip)
                    .focusRing(index == focused, RoundedCornerShape(8.dp))
                    .pointerInput(result.imageUrl, downloadingId == null) {
                        detectTapGestures(
                            onTap = { if (downloadingId == null) onResult(result) },
                            onLongPress = { onResultLink(result) },
                        )
                    },
            ) {
                AsyncImage(
                    model = result.thumbUrl,
                    contentDescription = result.title,
                    imageLoader = loader,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                if (downloadingId == result.imageUrl) MediaDownloadOverlay(progress)
            }
        }
    }
}
