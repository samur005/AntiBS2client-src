package net.antibs.client.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * 1.0.11 QR scanner: full-screen camera preview with a dimmed overlay around a centered rounded frame
 * (blue border, animated scan line), round ✕ and torch buttons on top, «Наведите камеру на QR-код» and a
 * «QR из галереи» pill at the bottom. Launched through zxing-embedded's ScanContract
 * (ScanOptions.setCaptureActivity), and it returns the text the same way CaptureActivity does
 * ([Intents.Scan.RESULT]), so the add-servers sheet's import logic is unchanged.
 */
class QrScanActivity : ComponentActivity() {
    private lateinit var barcodeView: BarcodeView
    private var hasCamera by mutableStateOf(false)
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        // Frame side used for both the overlay and the decoder crop (centered in the preview).
        val dm = resources.displayMetrics
        val framePx = (minOf(dm.widthPixels, dm.heightPixels) * FRAME_FRACTION).toInt()
        barcodeView = BarcodeView(this).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            framingRectSize = com.journeyapps.barcodescanner.Size(framePx, framePx)
        }
        hasCamera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasTorch = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        setContent {
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                hasCamera = ok
                if (ok) startCamera() else toast("Нет доступа к камере — можно выбрать QR из галереи")
            }
            LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }
            val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                val decoded = decodeQrFromUri(this, uri)
                if (decoded.isNullOrBlank()) toast("QR не найден") else deliver(decoded)
            }
            ScannerScreen(
                preview = { AndroidView(factory = { barcodeView }, modifier = Modifier.fillMaxSize()) },
                showPreview = hasCamera,
                torchAvailable = hasTorch,
                onTorch = { on -> runCatching { barcodeView.setTorch(on) } },
                onClose = { setResult(Activity.RESULT_CANCELED); finish() },
                onGallery = { gallery.launch("image/*") },
            )
        }
    }

    private fun startCamera() {
        if (finished || !hasCamera) return
        barcodeView.decodeSingle(BarcodeCallback { r -> r?.text?.takeIf { it.isNotBlank() }?.let { deliver(it) } })
        barcodeView.resume()
    }

    override fun onResume() {
        super.onResume()
        startCamera()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    private fun deliver(text: String) {
        if (finished) return
        finished = true
        barcodeView.pause()
        setResult(Activity.RESULT_OK, Intent(Intents.Scan.ACTION).apply {
            putExtra(Intents.Scan.RESULT, text)
            putExtra(Intents.Scan.RESULT_FORMAT, BarcodeFormat.QR_CODE.toString())
        })
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** Frame side as a fraction of the screen's short side. */
        const val FRAME_FRACTION = 0.72f
    }
}

private val ScanBlue = Color(0xFF3D7BFF)
private val ButtonDark = Color(0xCC1E2026)

@Composable
private fun ScannerScreen(
    preview: @Composable () -> Unit,
    showPreview: Boolean,
    torchAvailable: Boolean,
    onTorch: (Boolean) -> Unit,
    onClose: () -> Unit,
    onGallery: () -> Unit,
) {
    var torch by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (showPreview) preview()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val side = minOf(maxWidth, maxHeight) * QrScanActivity.FRAME_FRACTION
            ScanOverlay(sidePx = with(density) { side.toPx() })
        }
        // Top bar: ✕ left, torch right.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть", tint = Color.White, modifier = Modifier.size(26.dp)) }
            Spacer(Modifier.weight(1f))
            if (torchAvailable) {
                RoundButton(onClick = { torch = !torch; onTorch(torch) }) {
                    Icon(
                        if (torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                        if (torch) "Выключить фонарик" else "Включить фонарик",
                        tint = if (torch) ScanBlue else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        // Bottom: hint + «QR из галереи».
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Наведите камеру на QR-код", color = Color.White.copy(alpha = 0.92f), fontSize = 16.sp)
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(ButtonDark)
                    .clickable(role = Role.Button, onClick = onGallery)
                    .padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PhotoLibrary, null, tint = ScanBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("QR из галереи", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(ButtonDark).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Dim outside the frame, blue rounded border, and a scan line gliding up and down inside it. */
@Composable
private fun ScanOverlay(sidePx: Float) {
    val t = rememberInfiniteTransition(label = "scan")
    val pos = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "line")
    Canvas(Modifier.fillMaxSize()) {
        val left = (size.width - sidePx) / 2f
        val top = (size.height - sidePx) / 2f
        val r = 24.dp.toPx()
        val frame = RoundRect(left, top, left + sidePx, top + sidePx, CornerRadius(r, r))
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            addRoundRect(frame)
        }
        drawPath(dim, Color.Black.copy(alpha = 0.62f))
        val bw = 2.dp.toPx()
        drawRoundRect(
            ScanBlue,
            topLeft = Offset(left + bw / 2, top + bw / 2),
            size = Size(sidePx - bw, sidePx - bw),
            cornerRadius = CornerRadius(r - bw / 2, r - bw / 2),
            style = Stroke(width = bw),
        )
        // Scan line: inset from the rounded corners, with a soft glow trailing it.
        val inset = 20.dp.toPx()
        val margin = 18.dp.toPx()
        val y = top + margin + (sidePx - 2 * margin) * pos.value
        val glow = 14.dp.toPx()
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, ScanBlue.copy(alpha = 0.18f), Color.Transparent), startY = y - glow, endY = y + glow),
            topLeft = Offset(left + inset, y - glow),
            size = Size(sidePx - 2 * inset, glow * 2),
        )
        drawLine(ScanBlue, Offset(left + inset, y), Offset(left + sidePx - inset, y), strokeWidth = 2.dp.toPx())
    }
}
