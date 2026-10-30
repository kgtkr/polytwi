package net.kgtkr.polytwi.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import kotlinx.coroutines.launch
import net.kgtkr.polytwi.crypto.TransferCryptoManager
import net.kgtkr.polytwi.transfer.SessionTransferManager

@Composable
fun SessionTransferDialog(
    transferManager: SessionTransferManager,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Receive (Destination), 1 = Send (Source)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Session Transfer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Receive (Destination)") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Send (Source)") }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    if (selectedTab == 0) {
                        ReceiveSessionTab(
                            transferManager = transferManager,
                            onImportSuccess = onDismiss
                        )
                    } else {
                        SendSessionTab(
                            transferManager = transferManager
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiveSessionTab(
    transferManager: SessionTransferManager,
    onImportSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keyPair by remember { mutableStateOf(transferManager.getOrCreateKeyPair()) }
    var replaceExisting by remember { mutableStateOf(true) }
    var isImporting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val qrPayload = remember(keyPair.publicKeyBase64Url) {
        TransferCryptoManager.buildQrPayload(keyPair.publicKeyBase64Url)
    }
    val keyFingerprint = remember(keyPair.publicKeyBase64Url) {
        TransferCryptoManager.computeKeyFingerprint(keyPair.publicKeyBase64Url)
    }
    val qrBitmap = remember(qrPayload) {
        generateQrCodeBitmap(qrPayload, 640)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isImporting = true
            statusMessage = null
            isError = false
            scope.launch {
                try {
                    val count = transferManager.importEncryptedBackupUri(
                        uri = uri,
                        replaceExisting = replaceExisting
                    )
                    isImporting = false
                    Toast.makeText(
                        context,
                        "Imported $count account(s) successfully!",
                        Toast.LENGTH_LONG
                    ).show()
                    onImportSuccess()
                } catch (e: Exception) {
                    isImporting = false
                    isError = true
                    statusMessage = e.message ?: "Failed to decrypt or import backup file"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "1. Scan this QR code on your old (source) device to encrypt your sessions for this device.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (qrBitmap != null) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Transfer Public Key QR Code",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Key ID: $keyFingerprint",
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("PolyTwi Public Key", qrPayload))
                    Toast.makeText(context, "Public key copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Copy Key")
            }

            OutlinedButton(
                onClick = {
                    keyPair = transferManager.regenerateKeyPair()
                    statusMessage = null
                    Toast.makeText(context, "Generated new key pair", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("New Key")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "2. After receiving the encrypted .ptbackup file via Quick Share, select it below to restore:",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { replaceExisting = !replaceExisting }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = replaceExisting,
                onCheckedChange = { replaceExisting = it }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Replace existing accounts on this device",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
            enabled = !isImporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Decrypting & Importing...")
            } else {
                Text("Select Encrypted Backup File (.ptbackup)")
            }
        }

        statusMessage?.let { msg ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SendSessionTab(
    transferManager: SessionTransferManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var isScanning by remember { mutableStateOf(false) }
    var keyInputText by remember { mutableStateOf("") }
    var isExporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val parsedPublicKey = remember(keyInputText) {
        TransferCryptoManager.parsePublicKeyFromInput(keyInputText)
    }
    val parsedFingerprint = remember(parsedPublicKey) {
        parsedPublicKey?.let { TransferCryptoManager.computeKeyFingerprint(it) }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            isScanning = true
        } else {
            Toast.makeText(context, "Camera permission is required to scan QR codes", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "1. Scan the QR code displayed on the destination device (Receive tab):",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isScanning && hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                QrCameraScannerView(
                    onQrCodeDetected = { rawValue ->
                        val validKey = TransferCryptoManager.parsePublicKeyFromInput(rawValue)
                        if (validKey != null) {
                            keyInputText = rawValue
                            isScanning = false
                            errorMessage = null
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { isScanning = false }) {
                Text("Stop Camera")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (hasCameraPermission) {
                            isScanning = true
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Scan QR with Camera")
                }

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                        if (!clip.isNullOrBlank()) {
                            keyInputText = clip
                            errorMessage = null
                        } else {
                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Paste Key")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = keyInputText,
            onValueChange = {
                keyInputText = it
                errorMessage = null
            },
            label = { Text("Destination Public Key / QR URI") },
            placeholder = { Text("polytwi://transfer?pk=...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (parsedPublicKey != null && parsedFingerprint != null) {
            Text(
                text = "Verified Destination Key ID: $parsedFingerprint",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (keyInputText.isNotBlank()) {
            Text(
                text = "Invalid transfer public key format",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "2. Encrypt all accounts, cookies, push subscriptions, and CPFT settings, then share via Quick Share:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                val targetPubKey = parsedPublicKey ?: return@Button
                isExporting = true
                errorMessage = null
                scope.launch {
                    try {
                        val backupUri = transferManager.createEncryptedBackupFile(targetPubKey)
                        isExporting = false
                        val shareIntent = transferManager.buildShareIntent(backupUri)
                        context.startActivity(shareIntent)
                    } catch (e: Exception) {
                        isExporting = false
                        errorMessage = e.message ?: "Failed to create encrypted backup"
                    }
                }
            },
            enabled = parsedPublicKey != null && !isExporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isExporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Encrypting Sessions...")
            } else {
                Text("Encrypt & Send (Quick Share)")
            }
        }

        errorMessage?.let { msg ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun QrCameraScannerView(
    onQrCodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = try {
                    cameraProviderFuture.get()
                } catch (_: Exception) {
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val reader = MultiFormatReader().apply {
                    setHints(
                        mapOf(
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                            DecodeHintType.TRY_HARDER to true,
                            DecodeHintType.CHARACTER_SET to "UTF-8"
                        )
                    )
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            decodeQrFromImageProxy(reader, imageProxy)?.let { qrText ->
                                previewView.post {
                                    onQrCodeDetected(qrText)
                                }
                            }
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}

private fun decodeQrFromImageProxy(reader: MultiFormatReader, imageProxy: ImageProxy): String? {
    try {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride

        val yBytes = ByteArray(width * height)
        buffer.rewind()
        if (rowStride == width) {
            buffer.get(yBytes, 0, width * height)
        } else {
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(yBytes, row * width, width)
            }
        }

        val source = PlanarYUVLuminanceSource(
            yBytes,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )

        try {
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            return result.text
        } catch (_: Exception) {
            reader.reset()
        }

        try {
            val inverted = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert())))
            return inverted.text
        } catch (_: Exception) {
            reader.reset()
        }
        return null
    } catch (_: Exception) {
        return null
    } finally {
        imageProxy.close()
    }
}

private fun generateQrCodeBitmap(content: String, sizePx: Int): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    } catch (_: Exception) {
        null
    }
}
