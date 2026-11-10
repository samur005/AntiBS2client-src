package net.antibs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

private val FilterChipShape = RoundedCornerShape(16.dp)
private val FilterMenuShape = RoundedCornerShape(14.dp)

/** 2.0.35: compact protocol/IP row + country chips. 2.0.38: accent-selected chips, no drag, compact menu. */
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
    serverCount: Int = 0,
    onPingChip: (tab: String, country: String) -> Unit = { _, _ -> },
    onDeleteChip: (tab: String, country: String) -> Unit = { _, _ -> },
) {
    if (tabs.isEmpty()) return
    var menuKey by remember { mutableStateOf(ShotHooks.filterMenu) }
    val view = LocalView.current
    fun openMenu(key: String) {
        AppHaptics.perform(view, AppHaptics.Kind.CLICK, haptics)
        menuKey = key
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProtocolChipRow(tabs, selectedTab, onTab, serverCount, menuKey, { openMenu(it) }, { menuKey = it }, onPingChip, onDeleteChip)
        CountryChipRow(countries, selectedCountry, allCount, onCountry, menuKey, { openMenu(it) }, { menuKey = it }, onPingChip, onDeleteChip)
    }
}

@Composable
private fun ProtocolChipRow(
    tabs: List<HomeFilters.Tab>,
    selected: String,
    onTab: (String) -> Unit,
    serverCount: Int,
    menuKey: String?,
    onOpen: (String) -> Unit,
    onMenuKey: (String?) -> Unit,
    onPingChip: (String, String) -> Unit,
    onDeleteChip: (String, String) -> Unit,
) {
    val scroll = rememberScrollState()
    val xPos = remember { mutableStateMapOf<String, Int>() }
    val wPos = remember { mutableStateMapOf<String, Int>() }
    val selKey = if (HomeFilters.isAllTab(selected)) HomeFilters.TAB_ALL else selected
    LaunchedEffect(selKey, xPos[selKey], wPos[selKey], scroll.maxValue) {
        val x = xPos[selKey] ?: return@LaunchedEffect
        val tw = wPos[selKey] ?: 0
        val view = scroll.viewportSize
        val start = scroll.value
        if (x < start + 24 || x + tw > start + view - 8) {
            scroll.scrollTo((x - 48).coerceIn(0, scroll.maxValue.coerceAtLeast(0)))
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val allKey = HomeFilters.chipMenuKey(tab = HomeFilters.TAB_ALL)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .onGloballyPositioned {
                    xPos[HomeFilters.TAB_ALL] = it.positionInParent().x.toInt()
                    wPos[HomeFilters.TAB_ALL] = it.size.width
                }
                .testTag(HomeFilters.tabTestTag(HomeFilters.TAB_ALL)),
        ) {
            MiniCountryChip(
                label = "Все",
                meta = serverCount.toString(),
                metaColor = null,
                selected = HomeFilters.isAllTab(selected),
                gestureKey = allKey,
                onClick = { onTab(HomeFilters.TAB_ALL) },
                onOpenMenu = { onOpen(allKey) },
            )
            FilterChipMenu(
                expanded = menuKey == allKey,
                onDismiss = { onMenuKey(null) },
                onPing = { onPingChip(HomeFilters.TAB_ALL, "") },
                onDelete = { onDeleteChip(HomeFilters.TAB_ALL, "") },
            )
        }
        tabs.forEach { tab ->
            val on = tab.id == selected
            val border = if (on) accent.copy(alpha = 0.85f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
            val bg = if (on) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
            val fg = if (on) accent else MaterialTheme.colorScheme.onSurface
            val countFg = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant
            val key = HomeFilters.chipMenuKey(tab = tab.id)
            key(tab.id) {
            Box(
                Modifier
                    .onGloballyPositioned {
                        xPos[tab.id] = it.positionInParent().x.toInt()
                        wPos[tab.id] = it.size.width
                    }
                    .testTag(HomeFilters.tabTestTag(tab.id))
                    .height(28.dp)
                    .clip(FilterChipShape)
                    .background(bg)
                    .border(1.dp, border, FilterChipShape)
                    .filterChipGestures(
                        key = tab.id,
                        onClick = { onTab(tab.id) },
                        onOpenMenu = { onOpen(key) },
                    )
                    .padding(start = 10.dp, end = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label = buildAnnotatedString {
                    append(tab.label)
                    withStyle(
                        SpanStyle(
                            fontSize = 9.sp,
                            baselineShift = BaselineShift(0.35f),
                            fontWeight = FontWeight.SemiBold,
                            color = countFg,
                            letterSpacing = 0.2.sp,
                        ),
                    ) {
                        append('\u2009')
                        append(tab.count.toString())
                    }
                }
                Text(
                    label,
                    color = fg,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
                FilterChipMenu(
                    expanded = menuKey == key,
                    onDismiss = { onMenuKey(null) },
                    onPing = { onPingChip(tab.id, "") },
                    onDelete = { onDeleteChip(tab.id, "") },
                )
            }
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
    onOpen: (String) -> Unit,
    onMenuKey: (String?) -> Unit,
    onPingChip: (String, String) -> Unit,
    onDeleteChip: (String, String) -> Unit,
) {
    val scroll = rememberScrollState()
    val xPos = remember { mutableStateMapOf<String, Int>() }
    val wPos = remember { mutableStateMapOf<String, Int>() }
    val selKey = selected.ifEmpty { HomeFilters.COUNTRY_ALL }
    LaunchedEffect(selKey, xPos[selKey], wPos[selKey], scroll.maxValue) {
        val x = xPos[selKey] ?: return@LaunchedEffect
        val tw = wPos[selKey] ?: 0
        val view = scroll.viewportSize
        val start = scroll.value
        if (x < start + 24 || x + tw > start + view - 8) {
            scroll.scrollTo((x - 48).coerceIn(0, scroll.maxValue.coerceAtLeast(0)))
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val allKey = HomeFilters.chipMenuKey(country = HomeFilters.COUNTRY_ALL)
        Box(Modifier.onGloballyPositioned {
            xPos[HomeFilters.COUNTRY_ALL] = it.positionInParent().x.toInt()
            wPos[HomeFilters.COUNTRY_ALL] = it.size.width
        }.testTag(HomeFilters.countryTestTag(HomeFilters.COUNTRY_ALL))) {
            MiniCountryChip(
                label = "Все страны",
                meta = allCount.toString(),
                metaColor = null,
                selected = selected.isEmpty(),
                gestureKey = allKey,
                onClick = { onCountry(HomeFilters.COUNTRY_ALL) },
                onOpenMenu = { onOpen(allKey) },
            )
            FilterChipMenu(
                expanded = menuKey == allKey,
                onDismiss = { onMenuKey(null) },
                onPing = { onPingChip("", HomeFilters.COUNTRY_ALL) },
                onDelete = { onDeleteChip("", HomeFilters.COUNTRY_ALL) },
            )
        }
        countries.forEach { c ->
            val ping = c.bestPing
            val meta = if (ping != null) "$ping мс" else c.count.toString()
            val metaColor = if (ping != null) pingColor(ping) else null
            val key = HomeFilters.chipMenuKey(country = c.code)
            key(c.code) {
            Box(Modifier.onGloballyPositioned {
                xPos[c.code] = it.positionInParent().x.toInt()
                wPos[c.code] = it.size.width
            }.testTag(HomeFilters.countryTestTag(c.code))) {
                MiniCountryChip(
                    label = "${c.flag} ${c.name}",
                    meta = meta,
                    metaColor = metaColor,
                    selected = selected == c.code,
                    gestureKey = key,
                    onClick = { onCountry(c.code) },
                    onOpenMenu = { onOpen(key) },
                )
                FilterChipMenu(
                    expanded = menuKey == key,
                    onDismiss = { onMenuKey(null) },
                    onPing = { onPingChip("", c.code) },
                    onDelete = { onDeleteChip("", c.code) },
                )
            }
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
    onClick: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val border = if (selected) accent.copy(alpha = 0.85f)
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val bg = if (selected) accent.copy(alpha = 0.12f)
    else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .height(28.dp)
            .clip(FilterChipShape)
            .background(bg)
            .border(1.dp, border, FilterChipShape)
            .filterChipGestures(key = gestureKey, onClick = onClick, onOpenMenu = onOpenMenu)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(
            meta,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            color = metaColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
internal fun FilterChipMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val gapPx = with(density) { 4.dp.roundToPx() }
    val marginPx = with(density) { 8.dp.roundToPx() }
    val provider = remember(gapPx, marginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
                val x = anchorBounds.left.coerceIn(marginPx, maxX)
                val below = anchorBounds.bottom + gapPx
                val above = anchorBounds.top - popupContentSize.height - gapPx
                val y = when {
                    below + popupContentSize.height <= windowSize.height - marginPx -> below
                    above >= marginPx -> above
                    else -> (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
                }
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        val stroke = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
        Surface(
            shape = FilterMenuShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .widthIn(min = 132.dp, max = 150.dp)
                .border(1.dp, stroke, FilterMenuShape),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                FilterMenuRow(
                    icon = { Icon(Icons.Filled.NetworkCheck, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary) },
                    label = "Пинг",
                    color = MaterialTheme.colorScheme.onSurface,
                    onClick = { onDismiss(); onPing() },
                )
                FilterMenuRow(
                    icon = { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(17.dp), tint = AntiBSColors.Err) },
                    label = "Удалить",
                    color = AntiBSColors.Err,
                    onClick = { onDismiss(); onDelete() },
                )
            }
        }
    }
}

@Composable
private fun FilterMenuRow(
    icon: @Composable () -> Unit,
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon()
        Text(label, color = color, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun pingColor(ms: Long): Color = when {
    ms < 80 -> AntiBSColors.Ok
    ms < 180 -> Color(0xFFF59E0B)
    else -> AntiBSColors.Err
}
