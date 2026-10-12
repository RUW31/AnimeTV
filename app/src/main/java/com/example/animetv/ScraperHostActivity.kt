package com.example.animetv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

/**
 * Base común: infla el layout, crea el [ScraperWebView] oculto dentro de
 * `scraper_container`, maneja el fondo difuminado y el overlay de carga/estado.
 */
abstract class ScraperHostActivity(@LayoutRes private val layoutRes: Int) : FragmentActivity() {

    lateinit var scraper: ScraperWebView
        private set

    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: View
    private lateinit var loadingText: TextView
    private var backdrop: ImageView? = null
    private var backdropUrl: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val backdropRunnable = Runnable { applyBackdrop() }

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
        backdrop = findViewById(R.id.backdrop)
    }

    fun showStatus(text: CharSequence, spinner: Boolean = true) {
        loadingText.text = text
        loadingSpinner.isVisible = spinner
        loadingOverlay.isVisible = true
    }

    fun hideStatus() {
        loadingOverlay.isVisible = false
    }

    /** Cambia el fondo a la imagen (difuminada) del elemento enfocado, con un pequeño retraso. */
    fun setBackdrop(url: String?) {
        if (backdrop == null || url.isNullOrBlank() || url == backdropUrl) return
        backdropUrl = url
        handler.removeCallbacks(backdropRunnable)
        handler.postDelayed(backdropRunnable, 350)
    }

    private fun applyBackdrop() {
        val iv = backdrop ?: return
        val url = backdropUrl ?: return
        if (isDestroyed || isFinishing) return
        Glide.with(this)
            .load(imageModel(url))
            .placeholder(iv.drawable)
            .transform(CenterCrop(), SoftBlur())
            .transition(DrawableTransitionOptions.withCrossFade(500))
            .into(iv)
    }

    override fun onDestroy() {
        handler.removeCallbacks(backdropRunnable)
        if (::scraper.isInitialized) scraper.destroy()
        super.onDestroy()
    }
}
