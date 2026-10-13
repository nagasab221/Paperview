package com.paperview.mobile

import android.app.Application
import com.paperview.mobile.store.Prefs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Manual dependency container — the app has few enough moving parts that a DI
 * framework would cost more than it saves (same call as LocalityAndroid).
 */
class PaperViewApp : Application() {
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // PDFBox loads its font metrics and CMaps out of assets; without this
        // every text-drawing operation (watermark, page numbers) throws.
        PDFBoxResourceLoader.init(this)
    }
}
