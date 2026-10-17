package net.antibs.client.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import java.util.concurrent.ConcurrentHashMap

/**
 * Windows has no colour flag emoji: Segoe UI Emoji draws 🇩🇪 as the letters «DE». So flags are pictures here —
 * the Noto Color Emoji flags (the same artwork Android shows), bundled as small PNGs per ISO code in /flags.
 * Every regional-indicator pair (and 🌐) inside a text becomes an inline image; anything without a picture
 * (an unknown pair, a lone indicator) stays as plain text, exactly as on Android.
 */
object FlagImages {
    private val cache = ConcurrentHashMap<String, Any>()   // key → ImageBitmap, or NONE when there is no picture
    private val NONE = Any()

    fun image(key: String): ImageBitmap? = cache.getOrPut(key) {
        runCatching {
            FlagImages::class.java.getResourceAsStream("/flags/$key.png")?.use { s ->
                org.jetbrains.skia.Image.makeFromEncoded(s.readBytes()).toComposeImageBitmap()
            }
        }.getOrNull() ?: NONE
    } as? ImageBitmap

    fun has(key: String): Boolean = FlagImages::class.java.getResource("/flags/$key.png") != null

    private fun isRi(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** «🇩🇪» → "de"; null when the text is not exactly one flag. */
    fun isoOf(flag: String): String? {
        val cps = flag.codePoints().toArray().filter { it != 0xFE0F }
        if (cps.size != 2 || !isRi(cps[0]) || !isRi(cps[1])) return null
        return "${'a' + (cps[0] - 0x1F1E6)}${'a' + (cps[1] - 0x1F1E6)}"
    }

    /** One run of the text: either plain text, or a picture key («de», «globe») with the original characters. */
    data class Piece(val text: String, val image: String? = null)

    fun split(text: String): List<Piece> {
        if (text.none { Character.isSurrogate(it) }) return listOf(Piece(text))
        val out = mutableListOf<Piece>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out += Piece(plain.toString()); plain.clear() } }
        val cps = text.codePoints().toArray()
        var i = 0
        while (i < cps.size) {
            val c = cps[i]
            if (isRi(c) && i + 1 < cps.size && isRi(cps[i + 1])) {
                val key = "${'a' + (c - 0x1F1E6)}${'a' + (cps[i + 1] - 0x1F1E6)}"
                val raw = String(cps, i, 2)
                if (has(key)) { flush(); out += Piece(raw, key) } else plain.append(raw)
                i += 2
                if (i < cps.size && cps[i] == 0xFE0F) i++
                continue
            }
            // the cascade arrow is drawn as an icon too: not every Windows/Wine UI font has U+2192
            if (c == 0x2192) {
                if (plain.isNotEmpty() && plain.last() == '\u2009') plain.setLength(plain.length - 1)
                flush(); out += Piece("→", "arrow"); i++
                if (i < cps.size && cps[i] == 0x2009) i++
                continue
            }
            if (c == 0x1F310) { flush(); out += Piece(String(Character.toChars(c)), "globe"); i++; if (i < cps.size && cps[i] == 0xFE0F) i++; continue }
            plain.appendCodePoint(c); i++
        }
        flush()
        return out
    }

    fun hasPictures(text: String) = split(text).any { it.image != null && it.image != "arrow" }

    internal fun inline(pieces: List<Piece>): Pair<AnnotatedString, Map<String, InlineTextContent>> {
        val keys = linkedSetOf<String>()
        val s = buildAnnotatedString {
            for (p in pieces) if (p.image == null) append(p.text) else { keys += p.image; appendInlineContent("flag:${p.image}", p.text) }
        }
        val map = keys.associate { k ->
            val w = when (k) { "globe" -> 1.0; "arrow" -> 1.05; else -> 1.34 }
            "flag:$k" to InlineTextContent(Placeholder(w.em, 1.0.em, PlaceholderVerticalAlign.TextCenter)) {
                if (k == "arrow") androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Filled.ArrowForward, null,
                    Modifier.fillMaxSize().padding(horizontal = 1.5.dp), tint = LocalContentColor.current.copy(alpha = 0.75f),
                )
                else FlagImages.image(k)?.let { Image(it, null, Modifier.fillMaxSize()) }
            }
        }
        return s to map
    }
}

/** Drop-in for material3 Text (imported as `Text` in the UI files) that draws flag emoji as pictures. */
@Composable
fun FlagText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val pieces = remember(text) { FlagImages.split(text) }
    if (pieces.none { it.image != null }) {
        androidx.compose.material3.Text(text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing,
            textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style)
        return
    }
    val (s, inline) = remember(text) { FlagImages.inline(pieces) }
    androidx.compose.material3.Text(s, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing,
        textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, inline, onTextLayout ?: {}, style)
}

@Composable
fun FlagText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing,
    textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, inlineContent, onTextLayout, style)
