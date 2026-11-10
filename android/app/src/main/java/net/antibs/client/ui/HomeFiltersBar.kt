package net.antibs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
) {
    if (tabs.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SegmentedFilterTabs(tabs, selectedTab, onTab)
        CountryChipRow(countries, selectedCountry, allCount, onCountry)
    }
}

@Composable
private fun SegmentedFilterTabs(tabs: List<HomeFilters.Tab>, selected: String, onTab: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val stroke = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val scroll = rememberScrollState()
    val xPos = remember { mutableStateMapOf<String, Int>() }
    val wPos = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(selected, xPos[selected], wPos[selected], scroll.maxValue) {
        val x = xPos[selected] ?: return@LaunchedEffect
        val tw = wPos[selected] ?: 0
        val view = scroll.viewportSize
        val start = scroll.value
        if (x < start + 24 || x + tw > start + view - 8) {
            scroll.scrollTo((x - 48).coerceIn(0, scroll.maxValue.coerceAtLeast(0)))
        }
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
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
        tabs.forEach { tab ->
            val on = tab.id == selected
            val seg = RoundedCornerShape(7.dp)
            val bg = when {
                !on -> Color.Transparent
                dark -> Color.White.copy(alpha = 0.16f)
                else -> Color.White
            }
            val fg = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                Modifier
                    .fillMaxHeight()
                    .onGloballyPositioned {
                        xPos[tab.id] = it.positionInParent().x.toInt()
                        wPos[tab.id] = it.size.width
                    }
                    .clip(seg)
                    .background(bg)
                    .then(if (on) Modifier.border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), seg) else Modifier)
                    .clickable { onTab(tab.id) }
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
) {
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniCountryChip(
            label = "Все страны",
            meta = allCount.toString(),
            metaColor = null,
            selected = selected.isEmpty(),
            onClick = { onCountry(HomeFilters.COUNTRY_ALL) },
        )
        countries.forEach { c ->
            val ping = c.bestPing
            val meta = if (ping != null) "$ping мс" else c.count.toString()
            val metaColor = if (ping != null) pingColor(ping) else null
            MiniCountryChip(
                label = "${c.flag} ${c.name}",
                meta = meta,
                metaColor = metaColor,
                selected = selected == c.code,
                onClick = { onCountry(if (selected == c.code) HomeFilters.COUNTRY_ALL else c.code) },
            )
        }
    }
}

@Composable
private fun MiniCountryChip(label: String, meta: String, metaColor: Color?, selected: Boolean, onClick: () -> Unit) {
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
            .clickable(onClick = onClick)
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
private fun pingColor(ms: Long): Color = when {
    ms < 80 -> AntiBSColors.Ok
    ms < 180 -> Color(0xFFF59E0B)
    else -> AntiBSColors.Err
}
