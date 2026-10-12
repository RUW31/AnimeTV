package com.example.animetv

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity

/**
 * Base común: infla el layout, crea el [ScraperWebView] oculto dentro de
 * `scraper_container` y expone un overlay de carga/estado.
 */
abstract class ScraperHostActivity(@LayoutRes private val layoutRes: Int) : FragmentActivity() {

    lateinit var scraper: ScraperWebView
        private set

    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: View
    private lateinit var loadingText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layoutRes)

        scraper = ScraperWebView(this)
        findViewById<FrameLayout>(R.id.scraper_container).addView(
            scraper.view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        loadingOverlay = findViewById(R.id.loading_overlay)
        loadingSpinner = findViewById(R.id.loading_spinner)
        loadingText = findViewById(R.id.loading_text)
    }

    fun showStatus(text: CharSequence, spinner: Boolean = true) {
        loadingText.text = text
        loadingSpinner.isVisible = spinner
        loadingOverlay.isVisible = true
    }

    fun hideStatus() {
        loadingOverlay.isVisible = false
    }

    override fun onDestroy() {
        if (::scraper.isInitialized) scraper.destroy()
        super.onDestroy()
    }
}
