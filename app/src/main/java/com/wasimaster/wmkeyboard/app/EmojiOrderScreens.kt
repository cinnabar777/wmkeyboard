package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.emoji.EmojiCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiEntry
import com.wasimaster.wmkeyboard.core.emoji.EmojiOrder
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The screens behind "Emoji categories" (issue #183): the order of the panel's
 * category tabs, which of them are drawn at all, and the order of the emoji
 * inside one category.
 *
 * Both edit a preference laid over the bundled catalog rather than the catalog
 * itself, and both resolve it through `EmojiOrder` — the same object the panel
 * resolves with, so a slot dragged here is the slot the panel draws.
 */

/** Route of the per-category emoji grid; the argument is a catalog category id. */
internal const val EMOJI_ORDER_ROUTE = "emojiorder"

internal fun emojiOrderRoute(category: String): String = "$EMOJI_ORDER_ROUTE/$category"

/**
 * The bundled catalog, read off the asset once per screen.
 *
 * The keyword packs the keyboard merges in are deliberately not read: `merge`
 * maps over the catalog and never adds an entry, so the bundled file is the
 * whole set of emoji and the whole set of categories, and loading the packs
 * would cost a folder walk to arrive at the same list.
 */
@Composable
private fun rememberEmojiCatalog(): List<EmojiEntry> {
    val context = LocalContext.current
    return produceState(initialValue = emptyList<EmojiEntry>(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("emoji/catalog.tsv").use { EmojiCatalog.load(it) }
            }.getOrDefault(emptyList())
        }
    }.value
}

/** A category id as a heading: the catalog names them lowercase. */
internal fun emojiCategoryLabel(category: String): String =
    category.replaceFirstChar { it.uppercase() }

@Composable
internal fun EmojiCategorySettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val catalog = rememberEmojiCatalog()
    // Counted off the catalog, not off the stored order, so the subtitle says
    // how many emoji the category has rather than how many were arranged.
    val counts = remember(catalog) {
        catalog.asSequence()
            .filter { it.parent == null }
            .groupingBy { it.category }
            .eachCount()
    }
    SettingsGroup(
        stringResource(R.string.langemoji_emoji_categories_order_title),
        info = stringResource(R.string.langemoji_emoji_categories_info),
    ) {
        item {
            val categoryOrder = settings.watch { it.emoji.categoryOrder }
            val categoryEmojiOrder = settings.watch { it.emoji.categoryEmojiOrder }
            val hiddenCategories = settings.watch { it.emoji.hiddenCategories }
            // Every category, including the hidden ones: this is the screen that
            // un-hides them, so filtering them out here would strand them.
            val categories = remember(catalog, categoryOrder) {
                EmojiOrder.merge(categoryOrder, EmojiOrder.catalogCategories(catalog))
            }
            val samples = remember(catalog, categoryEmojiOrder) {
                categories.associateWith { category ->
                    EmojiOrder.emoji(catalog, category, categoryEmojiOrder[category].orEmpty())
                        .take(3)
                        .joinToString("")
                }
            }
            ReorderableColumn(
                items = categories,
                label = { emojiCategoryLabel(it) },
                rowHeight = EmojiCategoryRowHeight,
                onReorder = { order -> scope.launch { repository.setEmojiCategoryOrder(order) } },
            ) { category ->
                EmojiCategoryRowBody(
                    category = category,
                    sample = samples[category].orEmpty(),
                    count = counts[category] ?: 0,
                    hidden = category in hiddenCategories,
                    // The last visible tab cannot be hidden: the panel pages by
                    // category, so a panel with none is one with nothing to
                    // page and no way back to the keys.
                    canHide = categories.count { it !in hiddenCategories } > 1,
                    onOpen = { onNavigate(emojiOrderRoute(category)) },
                    onVisibility = { visible ->
                        scope.launch { repository.setEmojiCategoryVisible(category, visible) }
                    },
                )
            }
        }
        item {
            ActionRow(
                title = R.string.langemoji_emoji_categories_reset_title,
                subtitle = stringResource(R.string.langemoji_emoji_categories_reset_subtitle),
                action = stringResource(CommonR.string.common_reset),
                confirm = stringResource(R.string.langemoji_emoji_categories_reset_confirm),
                enabled = settings.watch { s ->
                    s.emoji.categoryOrder.isNotEmpty() ||
                        s.emoji.hiddenCategories.isNotEmpty() ||
                        s.emoji.categoryEmojiOrder.isNotEmpty()
                },
            ) { scope.launch { repository.resetEmojiOrder() } }
        }
    }
}

/** Taller than a plain reorder row: the body carries two lines and a sample. */
private val EmojiCategoryRowHeight = 60.dp

@Composable
private fun RowScope.EmojiCategoryRowBody(
    category: String,
    sample: String,
    count: Int,
    hidden: Boolean,
    canHide: Boolean,
    onOpen: () -> Unit,
    onVisibility: (Boolean) -> Unit,
) {
    val name = emojiCategoryLabel(category)
    Row(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            sample,
            fontSize = 20.sp,
            maxLines = 1,
            modifier = Modifier.width(72.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (hidden) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                if (hidden) {
                    stringResource(R.string.langemoji_emoji_categories_hidden_label)
                } else {
                    pluralStringResource(R.plurals.langemoji_emoji_category_count, count, count)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    IconButton(
        onClick = { onVisibility(hidden) },
        enabled = hidden || canHide,
    ) {
        Icon(
            if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = stringResource(
                if (hidden) {
                    R.string.langemoji_emoji_category_show_desc
                } else {
                    R.string.langemoji_emoji_category_hide_desc
                },
                name,
            ),
            tint = if (hidden) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

/**
 * One category's emoji, dragged into the order the panel's grid will draw them
 * in.
 *
 * A lazy grid, so it gets [WmLazyScreen] rather than the scrolling settings
 * frame: the biggest category is 270 emoji, which is too many to lay out at
 * once and — the reason the drag is a long press — far too many to page
 * through if an ordinary drag scrolled nothing.
 */
@Composable
internal fun EmojiOrderScreen(
    anim: AnimatedVisibilityScope,
    repository: SettingsRepository,
    settings: LiveSettings,
    category: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val catalog = rememberEmojiCatalog()
    val stored = settings.watch { it.emoji.categoryEmojiOrder[category].orEmpty() }
    val emoji = remember(catalog, category, stored) {
        EmojiOrder.emoji(catalog, category, stored)
    }
    WmLazyScreen(
        anim = anim,
        title = emojiCategoryLabel(category),
        onBack = onBack,
        route = EMOJI_ORDER_ROUTE,
        subtitle = stringResource(
            if (stored.isEmpty()) {
                R.string.langemoji_emoji_order_catalog_label
            } else {
                R.string.langemoji_emoji_order_custom_label
            },
        ),
        subtitleInBar = true,
        // Under the bar rather than as the grid's first item: a spanning header
        // inside the grid takes an index, and the drag resolves what is under
        // the finger by the grid's own indices.
        pinned = {
            Text(
                stringResource(R.string.langemoji_emoji_order_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        },
        actions = {
            if (stored.isNotEmpty()) {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.setEmojiCategoryEmojiOrder(category, emptyList())
                        }
                    },
                ) { Text(stringResource(R.string.langemoji_emoji_order_reset_action)) }
            }
        },
    ) { padding ->
        EmojiReorderGrid(
            emoji = emoji,
            padding = padding,
            onReorder = { order ->
                scope.launch { repository.setEmojiCategoryEmojiOrder(category, order) }
            },
        )
    }
}

/** How close to an edge a drag has to come before the grid scrolls itself. */
private val AutoScrollMargin = 72.dp

/** Pixels per frame the grid scrolls while a drag is held at its edge. */
private const val AutoScrollStep = 12f

@Composable
private fun EmojiReorderGrid(
    emoji: List<String>,
    padding: PaddingValues,
    onReorder: (List<String>) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // The order on screen is this grid's own copy for the length of a drag,
    // moved synchronously under the finger — the store echoes a frame or more
    // later, and routing every swap through it is what makes a drag stutter.
    // The same trade as [ReorderableColumn], for the same reason.
    var working by remember(emoji) { mutableStateOf(emoji) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    val currentOnReorder by rememberUpdatedState(onReorder)
    val marginPx = with(LocalDensity.current) { AutoScrollMargin.toPx() }

    // Held at an edge, the grid walks itself along. Without this a category of
    // 270 emoji can only be reordered within the screenful the drag started on.
    //
    // Keyed on whether a drag is running, not on which slot it is in: the index
    // changes on every swap, and keying on it would tear this loop down and
    // rebuild it several times a second for the length of the drag.
    val dragging = dragIndex >= 0
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val height = gridState.layoutInfo.viewportSize.height
            if (height <= 0) continue
            val step = when {
                dragAt.y < marginPx -> -AutoScrollStep
                dragAt.y > height - marginPx -> AutoScrollStep
                else -> 0f
            }
            if (step != 0f) {
                gridState.scrollBy(step)
                // The finger has not moved, but the emoji under it has.
                gridState.itemIndexAt(dragAt)?.let { target ->
                    if (target != dragIndex && target in working.indices) {
                        working = working.toMutableList().apply { add(target, removeAt(dragIndex)) }
                        dragIndex = target
                    }
                }
            }
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = EmojiOrderCell),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = padding.calculateTopPadding(),
            bottom = 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(emoji) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { at ->
                        dragAt = at
                        dragIndex = gridState.itemIndexAt(at)
                            ?.takeIf { it in working.indices }
                            ?: -1
                    },
                    onDragEnd = {
                        if (dragIndex >= 0) currentOnReorder(working)
                        dragIndex = -1
                    },
                    onDragCancel = {
                        // Nothing was committed, so the screen goes back to
                        // what is stored rather than keeping a half-drag.
                        working = emoji
                        dragIndex = -1
                    },
                ) { change, delta ->
                    change.consume()
                    if (dragIndex < 0) return@detectDragGesturesAfterLongPress
                    dragAt += delta
                    val target = gridState.itemIndexAt(dragAt)
                    if (target != null && target != dragIndex && target in working.indices) {
                        working = working.toMutableList().apply { add(target, removeAt(dragIndex)) }
                        dragIndex = target
                    }
                }
            },
    ) {
        // Keyed by emoji, not by slot: a cell keeps its node as the list moves
        // under it, which is what lets the moved cell stay under the finger.
        itemsIndexed(working, key = { _, e -> e }) { index, e ->
            val lifted = index == dragIndex
            Box(
                modifier = Modifier
                    .size(EmojiOrderCell)
                    .zIndex(if (lifted) 1f else 0f)
                    .background(
                        if (lifted) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            Color.Transparent
                        },
                        MaterialTheme.shapes.small,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(e, fontSize = 26.sp)
            }
        }
    }
}

/** The reorder grid's cell; a touch target with the glyph at panel size in it. */
private val EmojiOrderCell = 48.dp

/**
 * The index of the item drawn at [offset] in the grid's own coordinates, or
 * null for a gap between items — which a drag treats as "no move", so a finger
 * crossing the spacing between two cells does not shuffle the list twice.
 */
private fun LazyGridState.itemIndexAt(offset: Offset): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { item ->
        offset.x >= item.offset.x &&
            offset.x < item.offset.x + item.size.width &&
            offset.y >= item.offset.y &&
            offset.y < item.offset.y + item.size.height
    }?.index
