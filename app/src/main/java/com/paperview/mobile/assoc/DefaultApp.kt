package com.paperview.mobile.assoc

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Becoming the device's PDF handler.
 *
 * Android has no API to claim a MIME type outright — the manifest advertises
 * that PaperView opens `application/pdf`, and the user confirms it. This is the
 * counterpart to the desktop build's registry-based file association, which
 * could just write the association itself.
 */
object DefaultApp {

    private fun probeIntent(): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://com.android.providers.media.documents/document/1"), "application/pdf")
            addCategory(Intent.CATEGORY_DEFAULT)
        }

    /** True when PaperView is already the default for PDFs. */
    fun isDefault(context: Context): Boolean {
        val resolved = context.packageManager.resolveActivity(
            probeIntent(),
            PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: return false
        val pkg = resolved.activityInfo?.packageName ?: return false
        // A chooser is showing rather than a real default.
        if (pkg == "android" || resolved.activityInfo.name.contains("ResolverActivity")) return false
        return pkg == context.packageName
    }

    /** How many apps could handle a PDF — 1 means we're it by default. */
    fun handlerCount(context: Context): Int =
        context.packageManager
            .queryIntentActivities(probeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            .size

    /**
     * Opens the screen where the user makes it the default. On Android 12+
     * that's the app's own "Open by default" page; older versions only have
     * the app details screen, which contains the same setting.
     */
    fun openDefaultsSettings(context: Context): Boolean {
        val uri = Uri.parse("package:${context.packageName}")
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        }
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return true }
            }
        }
        return false
    }

    /** Plain-language instructions for the screen the user lands on. */
    fun instructions(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        "Tap \"Open by default\", then \"Add link\" or \"Supported links\" and allow PaperView " +
            "to open PDF files. If you don't see it, open any PDF and choose PaperView, then " +
            "tap \"Always\"."
    } else {
        "Tap \"Open by default\" and set PaperView to open PDFs. If you don't see it, open any " +
            "PDF and choose PaperView, then tap \"Always\"."
    }
}
