package app.dukaan.android.core.scan

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.dukaan.android.R
import app.dukaan.android.core.ui.ltr
import app.dukaan.android.core.ui.theme.MonoFamily
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Full-screen camera scanner (M3). Single mode returns the first code. [continuous] mode keeps
 * the camera open for a box of phones: each new code beeps and is collected; "Done" returns all.
 * Camera denied or missing → a clear way back to typing (PLAN.md § Error handling).
 */
@Composable
fun ScanScreen(continuous: Boolean, onResult: (List<String>) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    val codes = remember { mutableStateListOf<String>() }
    // Beep once per NEW code; the camera sees the same barcode in many frames.
    val beep = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }
    DisposableEffect(Unit) { onDispose { beep.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            granted -> CameraPreview { code ->
                if (code in codes) return@CameraPreview
                codes += code
                beep.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                if (!continuous) onResult(listOf(code))
            }
            denied -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.scan_denied), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onClose) { Text(stringResource(R.string.scan_type_instead)) }
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.padding(top = 32.dp, start = 8.dp)) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close), tint = Color.White)
        }
        if (granted) {
            // Aim box: a shopkeeper lines the barcode up here.
            Box(Modifier.align(Alignment.Center).size(width = 300.dp, height = 140.dp).border(3.dp, Color.White, RoundedCornerShape(12.dp)))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (continuous) pluralStringResource(R.plurals.scan_count, codes.size, codes.size) else stringResource(R.string.scan_hint),
                    color = Color.White, style = MaterialTheme.typography.titleMedium,
                )
                codes.lastOrNull()?.let {
                    Text(ltr(it), color = Color.White, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily))
                }
                if (continuous) {
                    Button(onClick = { onResult(codes.toList()) }, enabled = codes.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(stringResource(R.string.scan_done, codes.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    val main = remember { ContextCompat.getMainExecutor(context) }
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy, scanner) { code -> main.execute { onCode(code) } } }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, main)
            view
        },
    )
}

/** One camera frame → ML Kit. ImageProxy.image is the documented ML Kit path; CameraX marks it opt-in. */
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
private fun analyze(proxy: ImageProxy, scanner: BarcodeScanner, onCode: (String) -> Unit) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
        .addOnSuccessListener { barcodes -> barcodes.firstNotNullOfOrNull { it.rawValue }?.let { onCode(ScanCodes.clean(it)) } }
        .addOnCompleteListener { proxy.close() }
}
