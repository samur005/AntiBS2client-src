package net.antibs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 2.0.35: compact segmented protocol/IP row + country chips, СЕТЬ-style. */
@Composable
fun HomeFiltersBar(
    tabs: List<HomeFilters.Tab>,
    selectedTab: String,
    onTab: (String) -> Unit,
    countries: List<HomeFilters.Country>,
    selectedCountry: String,
    allCount: Int,
    onCountry: (String) -> Unit,
    modifier: Modifier = Modifier,
    haptics: Boolean = true,
    onPingChip: (tab: String, country: String) -> Unit = { _, _ -> },
    onDeleteChip: (tab: String, country: String) -> Unit = { _, _ -> },
    tabOrder: List<String> = emptyList(),
    onTabOrder: (List<String>) -> Unit = {},
    countryOrder: List<String> = emptyList(),
    onCountryOrder: (List<String>) -> Unit = {},
) {
    if (tabs.isEmpty()) return
    var menuKey by remember { mutableStateOf(ShotHooks.filterMenu) }
    val view = LocalView.current
    fun arm() { AppHaptics.perform(view, AppHaptics.Kind.CLICK, haptics) }
    val shownTabs = remember(tabs, tabOrder) {
        val ids = HomeFilters.applyOrder(tabs.map { it.id }, tabOrder)
        ids.mapNotNull { id -> tabs.firstOrNull { it.id == id } }
    }
    val shownCountries = remember(countries, countryOrder) {
        val ids = HomeFilters.applyOrder(countries.map { it.code }, countryOrder)
        ids.mapNotNull { id -> countries.firstOrNull { it.code == id } }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SegmentedFilterTabs(
            shownTabs, selectedTab, onTab, menuKey, { menuKey = it },
            onPingChip, onDeleteChip, onArm = { arm() }, onTabOrder = onTabOrder,
        )
        CountryChipRow(
            shownCountries, selectedCountry, allCount, onCountry, menuKey, { menuKey = it },
            onPingChip, onDeleteChip, onArm = { arm() }, onCountryOrder = onCountryOrder,
        )
    }
}

@Composable
private fun SegmentedFilterTabs(
    tabs: List<HomeFilters.Tab>,
    selected: String,
    onTab: (String) -> Unit,
    menuKey: String?,
    onMenuKey: (String?) -> Unit,
    onPingChip: (String, String) -> Unit,
    onDeleteChip: (String, String) -> Unit,
    onArm: () -> Unit,
    onTabOrder: (List<String>) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val stroke = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val scroll = rememberScrollState()
    val xPos = remember { mutableStateMapOf<String, Int>() }
    val wPos = remember { mutableStateMapOf<String, Int>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var preview by remember { mutableStateOf(tabs.map { it.id }) }
    var startOrder by remember { mutableStateOf(tabs.map { it.id }) }
    var startScroll by remember { mutableFloatStateOf(0f) }
    var startX by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var startW by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var fingerViewportX by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(selected, xPos[selected], wPos[selected], scroll.maxValue, dragId) {
        if (dragId != null) return@LaunchedEffect
        val x = xPos[selected] ?: return@LaunchedEffect
        val tw = wPos[selected] ?: 0
        val view = scroll.viewportSize
        val start = scroll.value
        if (x < start + 24 || x + tw > start + view - 8) {
            scroll.scrollTo((x - 48).coerceIn(0, scroll.maxValue.coerceAtLeast(0)))
        }
    }

    AutoScrollWhileDragging(scroll, dragId != null, fingerViewportX)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shown = if (dragId != null) preview.mapNotNull { id -> tabs.firstOrNull { it.id == id } } else tabs
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(shape)
            .background(track)
            .border(1.dp, stroke, shape)
            .horizontalScroll(scroll)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { tab ->
            val on = tab.id == selected
            val seg = RoundedCornerShape(7.dp)
            val bg = when {
                !on -> Color.Transparent
                dark -> Color.White.copy(alpha = 0.16f)
                else -> Color.White
            }
            val fg = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            val key = HomeFilters.chipMenuKey(tab = tab.id)
            val lifting = dragId == tab.id
            Box(
                Modifier
                    .zIndex(if (lifting) 2f else 0f)
                    .graphicsLayer {
                        if (lifting) {
                            translationX = dragDx
                            scaleX = 1.06f
                            scaleY = 1.06f
                        }
                    }
                    .fillMaxHeight()
                    .onGloballyPositioned {
                        xPos[tab.id] = it.positionInParent().x.toInt()
                        wPos[tab.id] = it.size.width
                    }
                    .then(if (lifting) Modifier.shadow(6.dp, seg) else Modifier)
                    .clip(seg)
                    .background(bg)
                    .then(if (on) Modifier.border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), seg) else Modifier)
                    .filterChipGestures(
                        key = tab.id,
                        reorderable = true,
                        onClick = { onTab(tab.id) },
                        onArmed = onArm,
                        onOpenMenu = { onMenuKey(key) },
                        onDragStart = {
                            onMenuKey(null)
                            startOrder = tabs.map { it.id }
                            preview = startOrder
                            startX = xPos.toMap()
                            startW = wPos.toMap()
                            startScroll = scroll.value.toFloat()
                            dragId = tab.id
                            dragDx = 0f
                        },
                        onDrag = { dx, _ ->
                            val scrollDelta = scroll.value - startScroll
                            dragDx = dx + scrollDelta
                            fingerViewportX = (startX[tab.id] ?: 0) + (startW[tab.id] ?: 0) / 2f + dragDx - scroll.value
                            val center = (startX[tab.id] ?: 0) + (startW[tab.id] ?: 0) / 2f + dragDx
                            val next = targetIndex(center, startOrder, startX, startW)
                            preview = HomeFilters.moveId(startOrder, tab.id, next)
                        },
                        onDragEnd = {
                            val final = preview
                            dragId = null
                            dragDx = 0f
                            if (final.isNotEmpty()) onTabOrder(final)
                        },
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label = buildAnnotatedString {
                    append(tab.label)
                    withStyle(
                        SpanStyle(
                            fontSize = 9.sp,
                            baselineShift = BaselineShift(0.35f),
                            fontWeight = FontWeight.SemiBold,
                            color = fg.copy(alpha = 0.8f),
                        ),
                    ) { append(tab.count.toString()) }
                }
                Text(label, color = fg, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                FilterChipMenu(
                    expanded = menuKey == key && dragId == null,
                    onDismiss = { onMenuKey(null) },
                    onPing = { onPingChip(tab.id, "") },
                    onDelete = { onDeleteChip(tab.id, "") },
                )
            }
        }
    }
}

@Composable
private fun CountryChipRow(
    countries: List<HomeFilters.Country>,
    selected: String,
    allCount: Int,
    onCountry: (String) -> Unit,
    menuKey: String?,
    onMenuKey: (String?) -> Unit,
    onPingChip: (String, String) -> Unit,
    onDeleteChip: (String, String) -> Unit,
    onArm: () -> Unit,
    onCountryOrder: (List<String>) -> Unit,
) {
    val scroll = rememberScrollState()
    val xPos = remember { mutableStateMapOf<String, Int>() }
    val wPos = remember { mutableStateMapOf<String, Int>() }
    val selKey = selected.ifEmpty { HomeFilters.COUNTRY_ALL }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var preview by remember { mutableStateOf(countries.map { it.code }) }
    var startOrder by remember { mutableStateOf(countries.map { it.code }) }
    var startScroll by remember { mutableFloatStateOf(0f) }
    var startX by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var startW by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var fingerViewportX by remember { mutableFloatStateOf(0f) }
    val gapPx = with(LocalDensity.current) { 6.dp.toPx() }

    LaunchedEffect(selKey, xPos[selKey], wPos[selKey], scroll.maxValue, dragId) {
        if (dragId != null) return@LaunchedEffect
        val x = xPos[selKey] ?: return@LaunchedEffect
        val tw = wPos[selKey] ?: 0
        val view = scroll.viewportSize
        val start = scroll.value
        if (x < start + 24 || x + tw > start + view - 8) {
            scroll.scrollTo((x - 48).coerceIn(0, scroll.maxValue.coerceAtLeast(0)))
        }
    }
    AutoScrollWhileDragging(scroll, dragId != null, fingerViewportX)

    val shown = if (dragId != null) preview.mapNotNull { id -> countries.firstOrNull { it.code == id } } else countries
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val allKey = HomeFilters.chipMenuKey(country = HomeFilters.COUNTRY_ALL)
        Box(Modifier.onGloballyPositioned {
            xPos[HomeFilters.COUNTRY_ALL] = it.positionInParent().x.toInt()
            wPos[HomeFilters.COUNTRY_ALL] = it.size.width
        }) {
            MiniCountryChip(
                label = "Все страны",
                meta = allCount.toString(),
                metaColor = null,
                selected = selected.isEmpty(),
                gestureKey = allKey,
                reorderable = false,
                onClick = { onCountry(HomeFilters.COUNTRY_ALL) },
                onArmed = onArm,
                onOpenMenu = { onMenuKey(allKey) },
            )
            FilterChipMenu(
                expanded = menuKey == allKey,
                onDismiss = { onMenuKey(null) },
                onPing = { onPingChip("", HomeFilters.COUNTRY_ALL) },
                onDelete = { onDeleteChip("", HomeFilters.COUNTRY_ALL) },
            )
        }
        shown.forEach { c ->
            val ping = c.bestPing
            val meta = if (ping != null) "$ping мс" else c.count.toString()
            val metaColor = if (ping != null) pingColor(ping) else null
            val key = HomeFilters.chipMenuKey(country = c.code)
            val lifting = dragId == c.code
            Box(
                Modifier
                    .zIndex(if (lifting) 2f else 0f)
                    .graphicsLayer {
                        if (lifting) {
                            translationX = dragDx
                            scaleX = 1.06f
                            scaleY = 1.06f
                        }
                    }
                    .then(if (lifting) Modifier.shadow(6.dp, RoundedCornerShape(16.dp)) else Modifier)
                    .onGloballyPositioned {
                        xPos[c.code] = it.positionInParent().x.toInt()
                        wPos[c.code] = it.size.width
                    },
            ) {
                MiniCountryChip(
                    label = "${c.flag} ${c.name}",
                    meta = meta,
                    metaColor = metaColor,
                    selected = selected == c.code,
                    gestureKey = key,
                    reorderable = true,
                    onClick = { onCountry(if (selected == c.code) HomeFilters.COUNTRY_ALL else c.code) },
                    onArmed = onArm,
                    onOpenMenu = { onMenuKey(key) },
                    onDragStart = {
                        onMenuKey(null)
                        startOrder = countries.map { it.code }
                        preview = startOrder
                        startX = xPos.toMap()
                        startW = wPos.toMap()
                        startScroll = scroll.value.toFloat()
                        dragId = c.code
                        dragDx = 0f
                    },
                    onDrag = { dx, _ ->
                        val scrollDelta = scroll.value - startScroll
                        dragDx = dx + scrollDelta
                        fingerViewportX = (startX[c.code] ?: 0) + (startW[c.code] ?: 0) / 2f + dragDx - scroll.value
                        val center = (startX[c.code] ?: 0) + (startW[c.code] ?: 0) / 2f + dragDx
                        val next = targetIndex(center, startOrder, startX, startW, gapPx)
                        preview = HomeFilters.moveId(startOrder, c.code, next)
                    },
                    onDragEnd = {
                        val final = preview
                        dragId = null
                        dragDx = 0f
                        if (final.isNotEmpty()) onCountryOrder(final)
                    },
                )
                FilterChipMenu(
                    expanded = menuKey == key && dragId == null,
                    onDismiss = { onMenuKey(null) },
                    onPing = { onPingChip("", c.code) },
                    onDelete = { onDeleteChip("", c.code) },
                )
            }
        }
    }
}

@Composable
private fun MiniCountryChip(
    label: String,
    meta: String,
    metaColor: Color?,
    selected: Boolean,
    gestureKey: String,
    reorderable: Boolean,
    onClick: () -> Unit,
    onArmed: () -> Unit,
    onOpenMenu: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
) {
    val shape = RoundedCornerShape(16.dp)
    val border = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .height(28.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .filterChipGestures(
                key = gestureKey,
                reorderable = reorderable,
                onClick = onClick,
                onArmed = onArmed,
                onOpenMenu = onOpenMenu,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(meta, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
            color = metaColor ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
internal fun FilterChipMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Пинг") },
            leadingIcon = { Icon(Icons.Filled.NetworkCheck, contentDescription = null) },
            onClick = { onDismiss(); onPing() },
        )
        DropdownMenuItem(
            text = { Text("Удалить", color = AntiBSColors.Err) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = AntiBSColors.Err) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

@Composable
private fun AutoScrollWhileDragging(scroll: androidx.compose.foundation.ScrollState, dragging: Boolean, fingerViewportX: Float) {
    val edge = with(LocalDensity.current) { 40.dp.toPx() }
    val speed = with(LocalDensity.current) { 14.dp.toPx() }
    LaunchedEffect(dragging, fingerViewportX, scroll.viewportSize, edge) {
        if (!dragging) return@LaunchedEffect
        while (isActive) {
            val view = scroll.viewportSize.toFloat()
            val delta = when {
                fingerViewportX < edge -> -speed
                fingerViewportX > view - edge -> speed
                else -> 0f
            }
            if (delta != 0f) scroll.scrollBy(delta)
            delay(16)
        }
    }
}

/** Slot the dragged chip's center belongs to, using positions captured at drag start. */
/** Slot the dragged chip's center belongs to, using positions captured at drag start (original order). */
internal fun targetIndex(
    center: Float,
    startOrder: List<String>,
    startX: Map<String, Int>,
    startW: Map<String, Int>,
    gapPx: Float = 0f,
): Int {
    if (startOrder.isEmpty()) return 0
    val mids = startOrder.map { id -> (startX[id] ?: 0) + (startW[id] ?: 0) / 2f }
    val idx = mids.indexOfFirst { center < it }
    return if (idx < 0) startOrder.lastIndex else idx
}

@Composable
private fun pingColor(ms: Long): Color = when {
    ms < 80 -> AntiBSColors.Ok
    ms < 180 -> Color(0xFFF59E0B)
    else -> AntiBSColors.Err
}
