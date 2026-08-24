package com.pulseloop.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The symbologies the scanner accepts — the four that Open Food Facts is keyed on
 * (iOS BarcodeScannerSheet.swift:56: [.ean13, .ean8, .upce, .code128]). Factored out so the
 * accepted set is unit-testable without the camera stack; [mlKitFormatFlags] is the one-line
 * device-side translation to ML Kit's [Barcode.FORMAT_*] flags.
 */
object BarcodeSymbologies {
    const val EAN13 = "EAN-13"
    const val EAN8 = "EAN-8"
    const val UPCE = "UPC-E"
    const val CODE128 = "Code-128"

    /** The complete accepted set — anything else (QR, PDF417, …) is ignored. */
    val accepted = setOf(EAN13, EAN8, UPCE, CODE128)

    fun isAccepted(symbology: String): Boolean = symbology in accepted

    /** ML Kit [Barcode.FORMAT_*] flags for [accepted] (iOS BarcodeScannerSheet.swift:56). */
    fun mlKitFormatFlags(): Int =
        Barcode.FORMAT_EAN_13 or
            Barcode.FORMAT_EAN_8 or
            Barcode.FORMAT_UPC_E or
            Barcode.FORMAT_CODE_128
}

/**
 * Full-screen barcode scanner sheet — the port of BarcodeScannerSheet (BarcodeScannerSheet.swift,
 * iOS PR #96). A CameraX preview + ImageAnalysis feed ML Kit's bundled-model BarcodeScanning
 * client restricted to [BarcodeSymbologies.accepted]; the FIRST non-empty recognized payload is
 * delivered exactly once, then scanning stops (iOS BarcodeScannerSheet.swift:77-87). Camera
 * permission is requested through the standard ActivityResult flow; denial or missing hardware
 * shows the iOS fallback copy verbatim (BarcodeScannerSheet.swift:26-39).
 */
@Composable
fun BarcodeScannerScreen(onScan: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // iOS scanningAvailable = DataScannerViewController.isSupported && isAvailable
    // (BarcodeScannerSheet.swift:13-15) — the Android halves are the camera feature and the
    // permission; a bind failure at runtime covers "isAvailable" false (camera disabled).
    val hasCameraHardware = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var availability by remember { mutableStateOf(ScanAvailability.Checking) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        availability = if (granted) ScanAvailability.Granted else ScanAvailability.Unavailable
    }
    LaunchedEffect(Unit) {
        when {
            !hasCameraHardware -> availability = ScanAvailability.Unavailable
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> availability = ScanAvailability.Granted
            else -> permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // The bound provider (if any) so dispose / cancel can always unbind exactly once.
    val cameraRef = remember { object { var provider: ProcessCameraProvider? = null } }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraRef.provider?.unbindAll() }
            cameraRef.provider = null
        }
    }
    LaunchedEffect(availability, previewView) {
        if (availability != ScanAvailability.Granted) return@LaunchedEffect
        try {
            val provider = awaitFuture(context, ProcessCameraProvider.getInstance(context))
            // One delivery, ever — iOS Coordinator.delivered (BarcodeScannerSheet.swift:71,78).
            val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(BarcodeSymbologies.mlKitFormatFlags())
                    .build(),
            )
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            // The proxy stays open while ML Kit processes the frame asynchronously and is
            // closed exactly once in each listener.
            val analyzer = ImageAnalysis.Analyzer { proxy ->
                when {
                    delivered.get() -> proxy.close()
                    else -> {
                        val media = proxy.image
                        if (media == null) {
                            proxy.close()
                        } else {
                            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                .addOnSuccessListener { barcodes ->
                                    // First non-empty payload wins (iOS :80 — payloadStringValue
                                    // non-empty; the symbology filter is already applied by the
                                    // scanner options above).
                                    val payload = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                                    proxy.close()
                                    if (payload != null && delivered.compareAndSet(false, true)) {
                                        runCatching { provider.unbindAll() }
                                        onScan(payload)
                                    }
                                }
                                .addOnFailureListener {
                                    proxy.close()
                                }
                        }
                    }
                }
            }
            analysis.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer)
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            cameraRef.provider = provider
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Camera present but unusable (disabled, occupied) — the iOS fallback path.
            availability = ScanAvailability.Unavailable
        } finally {
            runCatching { cameraRef.provider?.unbindAll() }
            cameraRef.provider = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(PulseColors.background)
            .systemBarsPadding(),
    ) {
        // iOS: navigationTitle "Scan barcode" (inline) + .cancellationAction Cancel (:41-45).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Scan barcode",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = PulseColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
        when (availability) {
            ScanAvailability.Granted ->
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )

            ScanAvailability.Unavailable -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // iOS fallback block, verbatim copy (BarcodeScannerSheet.swift:26-39).
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        // iOS uses SF Symbol "barcode.viewfinder" (BarcodeScannerSheet.swift:25);
                        // material-icons-extended 1.6.8 has no BarcodeScanner glyph — QrCodeScanner
                        // is its viewfinder-style stand-in.
                        Icons.Filled.QrCodeScanner,
                        contentDescription = null,
                        tint = PulseColors.textMuted,
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Scanning unavailable",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PulseColors.textPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Barcode scanning needs a device camera and camera access. Search by name instead.",
                        fontSize = 12.sp,
                        color = PulseColors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            ScanAvailability.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PulseColors.accent)
            }
        }
    }
}

/** The scanner as a full-screen sheet — the Android shape of the iOS
 *  .sheet(isPresented:) + .presentationDetents([.large]) presentation. */
@Composable
fun BarcodeScannerDialog(onScan: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BarcodeScannerScreen(onScan = onScan, onDismiss = onDismiss)
    }
}

private enum class ScanAvailability { Checking, Granted, Unavailable }

/** Bridges a Guava [ListenableFuture] (CameraX's provider future) to a suspend call without
 *  the kotlinx-coroutines-jdk8 artifact — the listener resumes on the main executor. */
private suspend fun <T> awaitFuture(context: Context, future: ListenableFuture<T>): T =
    suspendCancellableCoroutine { cont ->
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }
