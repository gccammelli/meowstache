package io.github.gccammelli.meowstache.ui

import androidx.compose.runtime.collectAsState
import io.github.gccammelli.meowstache.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.Typography
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import io.github.gccammelli.meowstache.res.Res
import io.github.gccammelli.meowstache.res.figtree
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.meowstache.Repo
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Colours; the neutrals follow the system theme ([dark] is set by [AppTheme]). Light values sampled from the official app's light theme. */
object W {
    var dark by mutableStateOf(true)
    fun pick(dark: Long, light: Long) = Color(if (this.dark) dark else light)
    val Bg get() = pick(0xFF303030, 0xFFF1F1F1)
    val Bar get() = pick(0xFF3F3F3F, 0xFFFFFFFF)
    val Card get() = pick(0xFF3B3B3B, 0xFFFCFCFC)
    val Inner get() = pick(0xFF353535, 0xFFEDEDED)
    val Line get() = pick(0xFF4C4C4C, 0xFFD3D3D3)
    val Text get() = pick(0xFFFFFFFF, 0xFF3A3A3A)
    val Text2 get() = pick(0xFFD6D6D6, 0xFF5F5F5F)
    val Muted get() = pick(0xFF9A9A9A, 0xFFA8A8A8)
    val Icon get() = pick(0xFFDDDDDD, 0xFF5F5F5F) // list icons, chart grid, idle control icons
    val Ink get() = pick(0xFF1E1E1E, 0xFFFFFFFF) // glyphs drawn on a W.Text disc
    val Tip get() = pick(0xFF1E1E1E, 0xFF7E7E7E) // chart tooltip
    val Nav get() = pick(0xFF424242, 0xFFFFFFFF) // bottom nav
    val Row get() = pick(0xFF363636, 0xFFFCFCFC) // event rows / breakdown inside cards
    val Well get() = pick(0xFF2E2E2E, 0xFFFFFFFF) // control circles, dark segmented track
    val Off get() = pick(0xFF555555, 0xFFC8C8C8) // disabled arrows, segmented border
    val Gauge get() = pick(0xFFE6E6E6, 0xFFD6D6D6)
    val Orange = Color(0xFFFBAA4A) // warnings only (drawer filling up, litter overfill)
    val Accent = Color(0xFF4A9BFB) // brand blue
    val Teal = Color(0xFF00838F)
    val Purple = Color(0xFFC94DF0)
    val Green = Color(0xFF7FCBA4)
    val Blue = Color(0xFF2E9BF0)
    val Red = Color(0xFFE5534B)
}

/** Figtree (OFL): the closest free match to the official app's Stabil Grotesk. One variable font file for every weight. */
val Figtree: FontFamily
    @Composable get() = FontFamily(listOf(400, 500, 600, 700, 800).map {
        Font(Res.font.figtree, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it)))
    })

// Line height relative to size: M3's fixed 24sp lineHeight makes every small label a tall row.
private fun TextStyle.app(family: FontFamily, weight: FontWeight? = fontWeight) =
    copy(fontFamily = family, letterSpacing = 0.sp, lineHeight = androidx.compose.ui.unit.TextUnit(1.3f, androidx.compose.ui.unit.TextUnitType.Em), fontWeight = weight)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = isSystemInDarkTheme().also { W.dark = it }.let { if (it) darkColorScheme() else lightColorScheme() }.copy(
        background = W.Bg, surface = W.Card, primary = W.Accent, onPrimary = Color.White,
        surfaceContainerHigh = W.Card, onSurface = W.Text,
    ),
    typography = Figtree.let { f ->
        Typography().run {
            Typography(displayLarge.app(f), displayMedium.app(f), displaySmall.app(f), headlineLarge.app(f), headlineMedium.app(f),
                headlineSmall.app(f), titleLarge.app(f), titleMedium.app(f), titleSmall.app(f), bodyLarge.app(f, FontWeight.Medium), bodyMedium.app(f, FontWeight.Medium),
                bodySmall.app(f, FontWeight.Medium), labelLarge.app(f), labelMedium.app(f), labelSmall.app(f))
        }
    },
    content = content,
)

/** The official app's soft dark drop shadow under every card. */
fun Modifier.cardShadow(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp)) =
    W.pick(0xFF000000, 0x55000000).let { shadow(10.dp, shape, clip = false, ambientColor = it, spotColor = it) }

@Composable
fun WCard(
    modifier: Modifier = Modifier,
    color: Color = W.Card,
    pad: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    modifier
        .cardShadow()
        .clip(RoundedCornerShape(16.dp))
        .background(color)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(pad),
    content = content,
)

/** The tiny grey hint shown on controls the public API can't drive. */
@Composable
fun NotAvailable(modifier: Modifier = Modifier) =
    Text("not available", modifier, color = W.Muted, fontSize = 10.sp, fontWeight = FontWeight.Normal)

/** Whether to show things only Whisker's own app can do (Account › Show unavailable features). */
@Composable
fun unavailableShown() = Repo.showUnavailable.collectAsState().value

/** Tapping one of them explains why ([feature] names it). */
fun askUnavailable(feature: String) { Repo.askUnavailable.value = feature }

/** The answer to [askUnavailable], hosted once at the app's root. */
@Composable
fun UnavailableDialog() {
    val feature = Repo.askUnavailable.collectAsState().value ?: return
    val close = { Repo.askUnavailable.value = null }
    AlertDialog(
        onDismissRequest = close,
        containerColor = W.Card,
        title = { Text("Only in Whisker's app", color = W.Text) },
        text = { Text("$feature is only available in Whisker's official app: Whisker doesn't let other apps do it.\n\n" +
            "You can hide everything like this; show it again any time in Account › Show unavailable features.", color = W.Text2) },
        confirmButton = { TextButton(onClick = close) { Text("OK", color = W.Accent) } },
        dismissButton = { TextButton(onClick = { Repo.setShowUnavailable(false); close() }) { Text("Hide unavailable features", color = W.Text2) } },
    )
}

@Composable
fun MainTopBar(unread: Boolean, onBell: () -> Unit, onAccount: () -> Unit, onLogo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(W.Bar).statusBarsPadding().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clickable(onClick = onBell), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Notifications, "Messages", tint = W.Text, modifier = Modifier.size(28.dp))
            if (unread) Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(11.dp).background(W.Red, CircleShape))
        }
        Spacer(Modifier.weight(1f))
        Logo(36.dp, Modifier.clickable(onClick = onLogo))
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(44.dp).clickable(onClick = onAccount), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.AccountCircle, "Account", tint = W.Text, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
fun BackTopBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(W.Bar).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = W.Text, modifier = Modifier.size(22.dp))
        }
        Text(title, Modifier.weight(1f), color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { action?.invoke() }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, dot: Color? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        dot?.let { Box(Modifier.padding(end = 6.dp).size(10.dp).background(it, CircleShape)) }
        Text(title, color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        HorizontalDivider(Modifier.weight(1f).padding(horizontal = 14.dp), color = W.pick(0xFFD6D6D6, 0xFFD3D3D3), thickness = 1.dp)
        trailing?.invoke()
    }
}

@Composable
fun WTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            Column(Modifier.weight(1f).clickable { onSelect(i) }.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l, color = if (i == selected) W.Text else W.pick(0xFFD6D6D6, 0xFFA8A8A8), fontSize = 15.sp,
                    fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(0.72f).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i == selected) W.Accent else Color.Transparent))
            }
        }
    }
}

/** The official app's layout toggle glyphs: four rounded squares (grid) or three rounded bars (list). */
@Composable
fun LayoutGlyph(grid: Boolean, onClick: () -> Unit) = Canvas(Modifier.size(22.dp).clickable(onClick = onClick)) {
    val c = W.pick(0xFFDDDDDD, 0xFFD2D2D2); val g = size.width * 0.12f; val r = CornerRadius(size.width * 0.1f)
    if (grid) {
        val s = (size.width - g) / 2
        for (i in 0..1) for (j in 0..1) drawRoundRect(c, Offset(i * (s + g), j * (s + g)), Size(s, s), r)
    } else {
        val h = (size.height - 2 * g) / 3
        for (j in 0..2) drawRoundRect(c, Offset(0f, j * (h + g)), Size(size.width, h), r)
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 16.dp) = Box(Modifier.size(size).background(color, CircleShape))

@Composable
fun Chevron(tint: Color = W.Text) =
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = tint, modifier = Modifier.size(24.dp))

@Composable
fun ListRow(
    title: String, subtitle: String? = null, onClick: (() -> Unit)?, titleColor: Color = W.Text, extra: String? = null,
    icon: ImageVector? = null, divider: Boolean = false,
) {
    if (onClick == null && !unavailableShown()) return // only in Whisker's app, and hidden
    if (divider) HorizontalDivider(color = W.Line, thickness = 1.dp)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick ?: { askUnavailable(title) }).padding(vertical = if (icon != null) 17.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = if (onClick == null) W.Muted else W.Icon, modifier = Modifier.padding(end = 16.dp).size(24.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = if (icon != null) 16.sp else 15.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, color = W.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
            extra?.let { Text(it, color = W.Text2, fontSize = 12.sp) }
            if (onClick == null) NotAvailable()
        }
        Chevron(if (onClick == null) W.Muted else W.Text)
    }
}

/** The official app's pill segmented control: white track with orange selection, or ([dark]) dark track with blue selection. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, dark: Boolean = false) =
    Row(modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(50))
        .background(if (dark) W.Well else if (enabled) Color.White else Color(0xFF8A8A8A))
        .let { if (dark) it.border(1.dp, W.Off, RoundedCornerShape(50)) else it }) {
        val sel = if (dark) W.Blue.copy(alpha = if (enabled) 1f else 0.4f) else W.Accent
        options.forEachIndexed { i, o ->
            Box(Modifier.weight(1f).fillMaxHeight().background(if (i == selected) sel else Color.Transparent)
                .let { if (enabled) it.clickable { onSelect(i) } else it }, contentAlignment = Alignment.Center) {
                Text(o, color = if (i == selected) Color.White.copy(alpha = if (enabled) 1f else 0.6f) else if (dark) W.Text.copy(alpha = if (enabled) 1f else 0.6f) else Color(0xFF1E1E1E), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

/** A titled list of options; [multi] keeps it open and toggles, otherwise a tap picks and closes. */
@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Set<String>, onDismiss: () -> Unit, note: String? = null, multi: Boolean = false, onPick: (Set<String>) -> Unit) {
    var sel by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = W.Card,
        title = { Text(title, color = W.Text) },
        text = {
            Column {
                options.forEach { o ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (multi) sel = if (o in sel) sel - o else sel + o else { onPick(setOf(o)); onDismiss() }
                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(o, color = W.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (o in sel) StatusDot(W.Accent, 14.dp)
                    }
                }
                note?.let { Text(it, color = W.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { if (multi) TextButton(onClick = { onPick(sel); onDismiss() }) { Text("Save", color = W.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = W.Text2) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, dismiss: String = "Cancel") = AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = W.Card,
    title = { Text(title, color = W.Text) },
    text = { Text(text, color = W.Text2) },
    confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = W.Accent) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss, color = W.Text2) } },
)

@Composable
fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = W.Card,
    title = { Text(title, color = W.Text) },
    text = { Text(text, color = W.Text2) },
    confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = W.Accent) } },
)

// ---------- images ----------

// shortcut: unbounded-ish cache trimmed to the last 24 images; fine for a handful of pet photos.
private val imageCache = LinkedHashMap<String, ImageBitmap>()

@Composable
fun NetImage(url: String?, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val bmp by produceState(url?.let { imageCache[it] }, url) {
        if (url != null && value == null) value = runCatching {
            // "res:" = bundled resource (the demo cats), otherwise a photo URL.
            val bytes = if (url.startsWith("res:")) Res.readBytes(url.removePrefix("res:")) else Repo.api.bytes(url)
            decodeImage(bytes, 1400)?.also { imageCache[url] = it; if (imageCache.size > 24) imageCache.remove(imageCache.keys.first()) }
        }.getOrNull()
    }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = contentScale)
    else Box(modifier.background(W.Inner), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Pets, null, tint = W.Muted)
    }
}

@Composable
fun Avatar(url: String?, size: Dp) = NetImage(url, Modifier.size(size).clip(CircleShape))

// ---------- art ----------

/** Blue rounded-square badge with a rounded "m" (an upside-down "ω") cut out of it, see-through. */
@Composable
fun Logo(size: Dp, modifier: Modifier = Modifier) = Canvas(
    // Offscreen layer so BlendMode.Clear punches through the badge only, not the screen behind it.
    modifier.size(size).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
) {
    val w = this.size.width; val h = this.size.height
    drawRoundRect(W.Accent, cornerRadius = CornerRadius(w * 0.42f))
    // The "ω" turned upside down reads as an "m"; nudged down a little to sit optically centred.
    translate(top = h * 0.04f) { rotate(180f) {
        val p = Path().apply {
            moveTo(w * 0.22f, h * 0.30f)
            cubicTo(w * 0.22f, h * 0.78f, w * 0.5f, h * 0.78f, w * 0.5f, h * 0.48f)
            cubicTo(w * 0.5f, h * 0.78f, w * 0.78f, h * 0.78f, w * 0.78f, h * 0.30f)
        }
        drawPath(p, Color.Black, blendMode = androidx.compose.ui.graphics.BlendMode.Clear, style = Stroke(w * 0.11f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    } }
}

/** A simple drawing of a Litter-Robot 4. */
@Composable
fun RobotArt(size: Dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) {
    val w = this.size.width; val h = this.size.height
    val body = Color(0xFFF2F2F2); val shade = Color(0xFFCFCFCF)
    drawRoundRect(shade, Offset(w * 0.18f, h * 0.45f), Size(w * 0.64f, h * 0.53f), CornerRadius(w * 0.1f))
    drawRoundRect(body, Offset(w * 0.16f, h * 0.43f), Size(w * 0.64f, h * 0.53f), CornerRadius(w * 0.1f))
    drawCircle(shade, w * 0.43f, Offset(w * 0.5f, h * 0.4f))
    drawCircle(body, w * 0.41f, Offset(w * 0.49f, h * 0.39f))
    drawCircle(Color(0xFF2B2B2B), w * 0.3f, Offset(w * 0.5f, h * 0.38f))
    drawCircle(Color(0xFF444444), w * 0.24f, Offset(w * 0.53f, h * 0.35f))
    // White lip filling the bottom of the opening, with a gently curved top edge.
    clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.5f, h * 0.38f), w * 0.3f)) }) {
        drawPath(Path().apply {
            moveTo(w * 0.15f, h * 0.48f); quadraticTo(w * 0.5f, h * 0.58f, w * 0.85f, h * 0.48f)
            lineTo(w * 0.85f, h * 0.75f); lineTo(w * 0.15f, h * 0.75f); close()
        }, body)
    }
    drawRoundRect(Color(0xFF3A3A3A), Offset(w * 0.3f, h * 0.83f), Size(w * 0.4f, h * 0.035f), CornerRadius(8f))
    drawRoundRect(shade, Offset(w * 0.26f, h * 0.77f), Size(w * 0.48f, h * 0.15f), CornerRadius(w * 0.04f), style = Stroke(3f))
}

@Composable
fun WasteIcon(percent: Int) = Canvas(Modifier.width(32.dp).height(26.dp)) {
    val r = CornerRadius(8f)
    drawRoundRect(W.Gauge, cornerRadius = r)
    val fill = size.height * (percent.coerceIn(0, 100) / 100f)
    val color = when { percent >= 90 -> W.Red; percent >= 70 -> W.Orange; else -> W.Green }
    drawRoundRect(color, Offset(0f, size.height - fill), Size(size.width, fill), r)
}

@Composable
fun LitterIcon(state: String?) = Canvas(Modifier.size(28.dp)) {
    drawCircle(W.Gauge)
    val level = when (state) { "OVERFILL" -> 0.75f; "OPTIMAL" -> 0.5f; "REFILL" -> 0.35f; "LOW" -> 0.2f; "EMPTY" -> 0f; else -> 0.5f }
    val color = when (state) { "OPTIMAL" -> W.Green; "OVERFILL" -> W.Orange; else -> W.Red }
    clipCircle { drawRect(color, Offset(0f, size.height * (1 - level)), Size(size.width, size.height * level)) }
    drawLine(Color.White, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
}

private fun DrawScope.clipCircle(block: DrawScope.() -> Unit) {
    val p = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }
    drawContext.canvas.save(); drawContext.canvas.clipPath(p); block(); drawContext.canvas.restore()
}

@Composable
fun CircleIcon(icon: ImageVector, bg: Color, tint: Color = Color.White, size: Dp = 44.dp) =
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.58f))
    }

// ---------- charts ----------

private fun niceStep(range: Double, ticks: Int): Double {
    val raw = range / ticks
    return listOf(0.1, 0.2, 0.25, 0.5, 1.0, 2.0, 2.5, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 500.0, 1000.0)
        .firstOrNull { it >= raw } ?: raw
}

private fun axis(values: List<Double>, fromZero: Boolean): Triple<Double, Double, Double> {
    if (values.isEmpty()) return Triple(0.0, 5.0, 1.0)
    var lo = if (fromZero) 0.0 else values.min(); var hi = values.max()
    if (hi - lo < 1e-9) { hi += if (fromZero) 1.0 else 0.5; if (!fromZero) lo -= 0.5 }
    val pad = if (fromZero) 0.0 else (hi - lo) * 0.6 + 0.6
    val step = niceStep((hi - lo) + 2 * pad, 4)
    lo = if (fromZero) 0.0 else floor((lo - pad) / step) * step
    hi = ceil((hi + pad) / step) * step
    if (fromZero && hi < 5) hi = 5.0
    return Triple(max(0.0, lo), hi, if (fromZero && hi <= 5) 1.0 else niceStep(hi - lo, 4))
}

enum class ChartKind { LINE, BARS, AREA, DOTS }

/**
 * One chart for all of the official app's styles: smooth teal line (weight), teal bars (visits),
 * green area (cycles) and orange dots joined by a dotted line (SmartScale / visit details).
 * Tap a point to show its tooltip; the last point is selected initially.
 */
@Composable
fun Chart(
    values: List<Double?>,
    labels: List<String>,
    kind: ChartKind,
    modifier: Modifier = Modifier,
    average: Double? = null,
    fromZero: Boolean = kind != ChartKind.LINE,
    tooltip: (Double) -> String = { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) },
    color: Color = when (kind) { ChartKind.AREA -> W.Green; ChartKind.DOTS -> W.Accent; else -> W.Teal },
    marks: List<Int> = emptyList(), // per point: 1 = pattern up, -1 = pattern down, 0 = none
) {
    val tm = rememberTextMeasurer()
    var sel by remember(values) { mutableIntStateOf(values.indexOfLast { it != null }) }
    val nonNull = values.filterNotNull()
    val (lo, hi, step) = remember(values) { axis(nonNull + listOfNotNull(average), fromZero) }
    val fig = Figtree
    val labelStyle = TextStyle(color = W.Text, fontSize = 12.sp, fontFamily = fig)
    Canvas(
        modifier.fillMaxWidth().height(180.dp).pointerInput(values) {
            detectTapGestures { o ->
                val left = 44.dp.toPx(); val right = size.width - 12.dp.toPx()
                val n = values.size
                if (n == 0) return@detectTapGestures
                val idx = ((o.x - left) / ((right - left) / max(1, if (kind == ChartKind.BARS) n else n - 1)))
                    .let { if (kind == ChartKind.BARS) it.toInt() else kotlin.math.floor(it + 0.5f).toInt() }.coerceIn(0, n - 1)
                if (values[idx] != null) sel = idx
            }
        }
    ) {
        val left = 44.dp.toPx(); val right = size.width - 12.dp.toPx()
        val top = 12.dp.toPx(); val bottom = size.height - 36.dp.toPx()
        val n = values.size
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        val slot = (right - left) / max(1, if (kind == ChartKind.BARS) n else n - 1)
        fun x(i: Int) = if (kind == ChartKind.BARS) left + slot * (i + 0.5f) else if (n == 1) (left + right) / 2 else left + slot * i

        // grid + y labels
        var t = lo
        while (t <= hi + 1e-9) {
            val yy = y(t)
            drawLine(if (kind == ChartKind.AREA) W.Line else W.Icon, Offset(left, yy), Offset(right, yy), 2f)
            val txt = if (step < 1) "%.1f".format(t).removeSuffix(".0") else t.toInt().toString()
            val m = tm.measure(txt, labelStyle)
            drawText(m, topLeft = Offset(left - m.size.width - 10.dp.toPx(), yy - m.size.height / 2))
            t += step
        }
        // x labels: as many as fit without overlapping, always including the newest
        val widest = labels.maxOfOrNull { tm.measure(it, labelStyle).size.width } ?: 0
        val every = max(1, ceil(n * (widest + 12.dp.toPx()) / (right - left + slot)).toInt())
        for (i in 0 until n) if ((n - 1 - i) % every == 0) {
            val m = tm.measure(labels[i], labelStyle)
            drawText(m, topLeft = Offset((x(i) - m.size.width / 2).coerceIn(0f, size.width - m.size.width), bottom + 14.dp.toPx()))
        }
        val pts = values.mapIndexedNotNull { i, v -> v?.let { Offset(x(i), y(it)) } }
        when (kind) {
            ChartKind.BARS -> values.forEachIndexed { i, v ->
                if (v == null || v <= 0) return@forEachIndexed
                val bw = minOf(slot * 0.45f, 26.dp.toPx())
                val tl = Offset(x(i) - bw / 2, y(v))
                drawRect(color, tl, Size(bw, bottom - tl.y))
                if (i == sel) drawRect(W.Text, tl, Size(bw, bottom - tl.y), style = Stroke(3.dp.toPx()))
            }
            ChartKind.DOTS -> {
                if (pts.size > 1) drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } },
                    W.Muted, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
                pts.forEach { drawCircle(color, 9.dp.toPx(), it) }
            }
            else -> if (pts.isNotEmpty()) {
                val p = smoothPath(pts)
                if (kind == ChartKind.AREA) {
                    val fill = Path().apply { addPath(p); lineTo(pts.last().x, bottom); lineTo(pts.first().x, bottom); close() }
                    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.05f)), top, bottom))
                }
                drawPath(p, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                if (pts.size == 1) drawCircle(color, 5.dp.toPx(), pts[0])
            }
        }
        average?.let {
            drawLine(W.Purple, Offset(left, y(it)), Offset(right, y(it)), 3.dp.toPx() / 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)))
        }
        // Premium-style pattern markers: white disc with an arrow above the point
        marks.forEachIndexed { i, m ->
            val v = values.getOrNull(i) ?: return@forEachIndexed
            if (m == 0) return@forEachIndexed
            val c = Offset(x(i), y(v) - 16.dp.toPx())
            val r = 9.dp.toPx(); val a = 4.dp.toPx()
            drawCircle(W.Text, r, c)
            drawLine(W.Ink, Offset(c.x, c.y + a * m), Offset(c.x, c.y - a * m), 2.dp.toPx(), StrokeCap.Round)
            drawLine(W.Ink, Offset(c.x, c.y - a * m), Offset(c.x - a * 0.8f, c.y - a * m + a * 0.8f * m), 2.dp.toPx(), StrokeCap.Round)
            drawLine(W.Ink, Offset(c.x, c.y - a * m), Offset(c.x + a * 0.8f, c.y - a * m + a * 0.8f * m), 2.dp.toPx(), StrokeCap.Round)
        }
        // tooltip
        val sv = values.getOrNull(sel)
        if (sv != null && kind != ChartKind.AREA) {
            val c = Offset(x(sel), y(sv))
            if (kind == ChartKind.LINE) {
                drawCircle(color, 6.dp.toPx(), c); drawCircle(Color.White, 6.dp.toPx(), c, style = Stroke(1.5f.dp.toPx()))
            }
            val m = tm.measure(tooltip(sv), TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = fig))
            val bw = m.size.width + 24.dp.toPx(); val bh = m.size.height + 14.dp.toPx()
            val bx = (c.x - bw / 2).coerceIn(left, size.width - bw)
            val by = (c.y - bh - 12.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(W.Tip, Offset(bx, by), Size(bw, bh), CornerRadius(12.dp.toPx()))
            drawText(m, topLeft = Offset(bx + 12.dp.toPx(), by + 7.dp.toPx()))
        }
    }
}

private fun smoothPath(pts: List<Offset>) = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) {
        val a = pts[i - 1]; val b = pts[i]; val mx = (a.x + b.x) / 2
        cubicTo(mx, a.y, mx, b.y, b.x, b.y)
    }
}

@Composable
fun Legend(vararg items: Pair<String, Color>, dashedLast: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, (label, c) ->
            if (i == items.lastIndex && dashedLast && items.size > 1) Box(Modifier.padding(start = 16.dp).width(14.dp).height(3.dp).background(c))
            else Box(Modifier.padding(start = if (i > 0) 16.dp else 0.dp).size(8.dp).background(c, CircleShape))
            Text(label, color = W.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true, icon: (@Composable () -> Unit)? = null) =
    Row(
        modifier.clip(RoundedCornerShape(50)).background(if (!enabled) Color(0xFFD6D6D6) else if (filled) W.Accent else Color.White)
            .border(BorderStroke(1.dp, if (!enabled) Color(0xFFD6D6D6) else if (filled) W.Accent else Color(0xFFDDDDDD)), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Text(text, color = if (filled || !enabled) Color.White else Color(0xFF222222), fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = if (icon != null) 10.dp else 0.dp))
    }

/**
 * Slot-machine style wheel: drag or fling, it snaps to the row in the middle band.
 * Rows tilt and fade with distance from the centre so it reads as a rotating drum.
 */
@Composable
fun Wheel(items: List<String>, initial: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier, rowHeight: Dp = 44.dp, visible: Int = 5) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initial)
    val fling = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state, androidx.compose.foundation.gestures.snapping.SnapPosition.Center)
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { rowHeight.toPx() }
    val centred by remember {
        androidx.compose.runtime.derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0 }
    }
    androidx.compose.runtime.LaunchedEffect(centred) { onSelected(centred.coerceIn(0, items.lastIndex)) }
    Box(modifier.height(rowHeight * visible), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(rowHeight).clip(RoundedCornerShape(10.dp)).background(W.Inner))
        androidx.compose.foundation.lazy.LazyColumn(
            state = state, flingBehavior = fling,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = rowHeight * (visible / 2)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(items.size) { i ->
                val dist = (i - state.firstVisibleItemIndex - state.firstVisibleItemScrollOffset / rowPx).coerceIn(-3f, 3f)
                Box(
                    Modifier.height(rowHeight).fillMaxWidth().graphicsLayer {
                        rotationX = -dist * 24f
                        alpha = 1f - kotlin.math.abs(dist) * 0.28f
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(items[i], color = if (i == centred) W.Text else W.Text2, fontSize = if (i == centred) 24.sp else 19.sp,
                        fontWeight = if (i == centred) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}


/**
 * Premium "Time of Visit": one column per day, time of day top (midnight) to bottom (midnight),
 * a bubble per visit sized by how long it lasted.
 */
@Composable
fun TimeOfVisitChart(columns: List<String>, visits: List<Triple<Int, Float, Double?>>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val fig = Figtree
    val labelStyle = TextStyle(color = W.Text, fontSize = 12.sp, fontFamily = fig)
    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        val left = 44.dp.toPx(); val right = size.width - 8.dp.toPx()
        val top = 10.dp.toPx(); val bottom = size.height - 30.dp.toPx()
        val n = max(1, columns.size)
        val colW = (right - left) / n
        fun y(hour: Float) = top + (24f - hour) / 24f * (bottom - top)
        // grid: 6-hour rows, one column per day
        listOf(24 to "12am", 18 to "6pm", 12 to "12pm", 6 to "6am", 0 to "12am").forEach { (h, l) ->
            val yy = y(h.toFloat())
            drawLine(W.Line, Offset(left, yy), Offset(right, yy), 2f)
            val m = tm.measure(l, labelStyle)
            drawText(m, topLeft = Offset(left - m.size.width - 8.dp.toPx(), yy - m.size.height / 2))
        }
        for (i in 0..n) drawLine(W.Line, Offset(left + colW * i, top), Offset(left + colW * i, bottom), 2f)
        val every = max(1, ceil(n / 7.0).toInt())
        columns.forEachIndexed { i, l ->
            if ((n - 1 - i) % every != 0) return@forEachIndexed
            val m = tm.measure(l, labelStyle)
            drawText(m, topLeft = Offset(left + colW * (i + 0.5f) - m.size.width / 2, bottom + 8.dp.toPx()))
        }
        val maxR = minOf(colW * 0.45f, 14.dp.toPx())
        visits.forEach { (col, hour, minutes) ->
            val r = (maxR * kotlin.math.sqrt(((minutes ?: 2.0) / 15.0).coerceIn(0.08, 1.0))).toFloat()
            drawCircle(W.Teal.copy(alpha = 0.85f), r, Offset(left + colW * (col + 0.5f), y(hour)))
        }
    }
}
