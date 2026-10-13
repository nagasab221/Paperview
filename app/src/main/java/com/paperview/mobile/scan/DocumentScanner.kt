package com.paperview.mobile.scan

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Camera scanning, delegated to ML Kit's document scanner.
 *
 * It handles capture, edge detection, perspective correction and multi-page in
 * one flow — reimplementing any of that would mean pulling in OpenCV for tens
 * of megabytes. What it does *not* do is clean the page up by default: its
 * filters sit a tap deep in its editor and reset for every capture, so scans
 * came out as photographs of paper, shadows and all. We take the individual
 * pages rather than its finished PDF and run [Enhance] over them instead.
 */
object DocumentScanner {

    /** ML Kit's scanner ships via Play Services; without it there's nothing to launch. */
    fun isAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    fun client(pageLimit: Int = 30): GmsDocumentScanner {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        return GmsDocumentScanning.getClient(options)
    }
}

/** Remembers a launcher that starts the scanner and reports the resulting PDF. */
@Composable
fun rememberDocumentScanLauncher(
    onScanned: (pages: List<Uri>, fallbackPdf: Uri?) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scanner = remember { DocumentScanner.client() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        // The individual pages are what we want: they can still be cleaned up.
        // The assembled PDF is the fallback for the unlikely case that a build
        // of the scanner returns one without the other.
        val pages = scan?.pages?.map { it.imageUri }.orEmpty()
        val pdf = scan?.pdf?.uri
        if (pages.isEmpty() && pdf == null) {
            onError("The scanner didn't return a document")
        } else {
            onScanned(pages, pdf)
        }
    }

    return {
        val activity = context as? Activity
        if (activity == null) {
            onError("Cannot start the scanner from here")
        } else if (!DocumentScanner.isAvailable(context)) {
            onError("Scanning needs Google Play Services, which isn't available on this device")
        } else {
            scanner.getStartScanIntent(activity)
                .addOnSuccessListener { sender ->
                    launcher.launch(IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener { e ->
                    onError(e.message ?: "Could not start the scanner")
                }
        }
    }
}
