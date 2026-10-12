package com.example.animetv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import java.io.ByteArrayInputStream

/**
 * Abre la página del episodio tal cual, como un navegador, y deja que su propio
 * reproductor haga el trabajo. Para usarla con el control:
 *
 *  - Flechas: mueven el cursor (al llegar al borde, desplazan la página)
 *  - OK: clic donde está el cursor
 *  - Play/Pausa del control: clic en el centro de la pantalla
 *  - CH+ / CH− (o Siguiente/Anterior): cambia de episodio
 *  - Menú / Info: activa o desactiva el modo cine
 *  - Atrás: sale de pantalla completa, vuelve a la página anterior o cierra
 */
@SuppressLint("SetJavaScriptEnabled")
class WebPlayerActivity : FragmentActivity() {

    private lateinit var web: WebView
    private lateinit var fullscreen: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var hintBar: View
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var pageProgress: ProgressBar

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var index = 0
    private var theater = true
    private var clearHistoryOnLoad = false
    private var currentHost = ""
    private var openedAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val theaterRunnable = Runnable { applyTheater() }
    private val hideHintRunnable = Runnable {
        hintBar.animate().alpha(0f).setDuration(400).withEndAction { hintBar.isVisible = false }.start()
    }

    private val episodes: List<Episode> get() = PlaybackQueue.episodes
    private val hasNext: Boolean get() = index + 1 < episodes.size

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = findViewById(R.id.web_view)
        fullscreen = findViewById(R.id.fullscreen_container)
        cursor = findViewById(R.id.cursor)
        hintBar = findViewById(R.id.hint_bar)
        titleText = findViewById(R.id.web_title)
        subtitleText = findViewById(R.id.web_subtitle)
        pageProgress = findViewById(R.id.page_progress)
        setupWebView()

        if (episodes.isEmpty()) {
            finish()
            return
        }
        openEpisode(intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, episodes.lastIndex))
    }

    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false // sin ventanas emergentes
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.isFocusable = false
        web.isFocusableInTouchMode = false
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            // Bloquea dominios de publicidad conocidos, como haría cualquier bloqueador de anuncios.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return if (SiteConfig.isAd(request.url.toString())) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else null
            }

            // Evita que la página principal salte a sitios de anuncios.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && request.isRedirect) return false
                val host = request.url.host.orEmpty()
                return !(host.endsWith(SiteConfig.HOST) || (currentHost.isNotEmpty() && host.endsWith(currentHost)))
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (clearHistoryOnLoad) {
                    view.clearHistory()
                    clearHistoryOnLoad = false
                }
                scheduleTheater()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                pageProgress.progress = newProgress
                pageProgress.isVisible = newProgress < 100
            }

            // Botón de pantalla completa del reproductor de la página.
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null) return
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                fullscreen.addView(
                    view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                fullscreen.isVisible = true
                web.visibility = View.INVISIBLE
            }

            override fun onHideCustomView() {
                exitFullscreen(notifyPage = false)
            }

            // Sin esto, Android muestra un ícono gris de "play" antes de que cargue el video.
            override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    // ------------------------------------------------------------ Episodios

    private fun openEpisode(target: Int) {
        index = target
        val ep = episodes[target]
        val anime = PlaybackQueue.anime
        titleText.text = anime?.title?.takeIf { it.isNotBlank() } ?: ep.title
        subtitleText.text = if (ep.number != null) "${ep.label} · ${ep.title}" else ep.title
        showHint()

        saveProgress(ep, watched = false) // para "Continuar viendo"
        openedAt = SystemClock.elapsedRealtime()

        exitFullscreen(notifyPage = true)
        clearHistoryOnLoad = true
        currentHost = Uri.parse(ep.url).host.orEmpty().removePrefix("www.")
        web.loadUrl(ep.url)
        cursor.center()
    }

    private fun goToEpisode(target: Int) {
        if (target !in episodes.indices) return
        // Si avanzas, el episodio que dejas cuenta como visto.
        if (target > index) episodes.getOrNull(index)?.let { saveProgress(it, watched = true) }
        openEpisode(target)
    }

    /**
     * El video vive dentro de la página, así que no sabemos el minuto exacto:
     * se guarda qué episodio abriste y, si lo dejas atrás o pasas un buen rato en él, se marca como visto.
     */
    private fun saveProgress(ep: Episode, watched: Boolean) {
        val anime = PlaybackQueue.anime
        val mark = if (watched) 1L else 0L
        Store.saveProgress(
            WatchProgress(
                episodeUrl = ep.url,
                episodeTitle = ep.title,
                episodeNumber = ep.number,
                animeUrl = anime?.url.orEmpty(),
                animeTitle = anime?.title?.takeIf { it.isNotBlank() } ?: ep.title,
                animeImage = anime?.image?.takeIf { it.isNotBlank() } ?: ep.image,
                positionMs = mark,
                durationMs = mark,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // ------------------------------------------------------------ Modo cine

    /** Hace que el reproductor (el iframe o video más grande) ocupe toda la pantalla. */
    private fun scheduleTheater() {
        handler.removeCallbacks(theaterRunnable)
        if (!theater) return
        // Los reproductores suelen aparecer tarde, así que se reintenta un par de veces.
        handler.postDelayed(theaterRunnable, 800)
        handler.postDelayed(theaterRunnable, 2_500)
        handler.postDelayed(theaterRunnable, 5_000)
    }

    private fun applyTheater() {
        if (customView != null) return
        web.evaluateJavascript(if (theater) SiteConfig.JS_THEATER_ON else SiteConfig.JS_THEATER_OFF, null)
    }

    private fun toggleTheater() {
        theater = !theater
        handler.removeCallbacks(theaterRunnable)
        applyTheater()
        Toast.makeText(
            this,
            if (theater) R.string.theater_on else R.string.theater_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    // ------------------------------------------------------------ Cursor

    private fun moveCursor(keyCode: Int, repeatCount: Int) {
        val step = resources.displayMetrics.density * 12f * (1f + repeatCount / 3f).coerceAtMost(5f)
        val (dx, dy) = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -step to 0f
            KeyEvent.KEYCODE_DPAD_RIGHT -> step to 0f
            KeyEvent.KEYCODE_DPAD_UP -> 0f to -step
            else -> 0f to step
        }
        val (overX, overY) = cursor.moveBy(dx, dy)
        if (customView == null && (overX != 0f || overY != 0f)) {
            web.scrollBy(if (overX != 0f) dx.toInt() else 0, if (overY != 0f) dy.toInt() else 0)
        }
    }

    /** Envía un toque real a la página (o al video en pantalla completa) en esa posición. */
    private fun tapAt(x: Float, y: Float) {
        val target: View = customView ?: web
        val targetLoc = IntArray(2)
        val cursorLoc = IntArray(2)
        target.getLocationOnScreen(targetLoc)
        cursor.getLocationOnScreen(cursorLoc)
        val tx = x + cursorLoc[0] - targetLoc[0]
        val ty = y + cursorLoc[1] - targetLoc[1]
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, tx, ty, 0)
        val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, tx, ty, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun showHint() {
        handler.removeCallbacks(hideHintRunnable)
        hintBar.animate().cancel()
        hintBar.alpha = 1f
        hintBar.isVisible = true
        handler.postDelayed(hideHintRunnable, 6_000)
    }

    // ------------------------------------------------------------ Teclas

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) moveCursor(event.keyCode, event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (down && event.repeatCount == 0) {
                    cursor.pulse()
                    tapAt(cursor.cx, cursor.cy)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && event.repeatCount == 0) tapAt(cursor.width / 2f, cursor.height / 2f)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (down && event.repeatCount == 0) {
                    if (hasNext) goToEpisode(index + 1)
                    else Toast.makeText(this, R.string.no_next_episode, Toast.LENGTH_SHORT).show()
                }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (down && event.repeatCount == 0 && index > 0) goToEpisode(index - 1)
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_PROG_RED -> {
                if (down && event.repeatCount == 0) toggleTheater()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (!down && !event.isCanceled) handleBack()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack() {
        when {
            customView != null -> exitFullscreen(notifyPage = true)
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    private fun exitFullscreen(notifyPage: Boolean) {
        val v = customView ?: return
        customView = null
        fullscreen.removeView(v)
        fullscreen.isVisible = false
        web.visibility = View.VISIBLE
        val callback = customCallback
        customCallback = null
        if (notifyPage) callback?.onCustomViewHidden()
    }

    // ------------------------------------------------------------ Ciclo de vida

    override fun onPause() {
        super.onPause()
        web.onPause()
        // Si estuviste un buen rato en el episodio, se marca como visto.
        if (openedAt > 0 && SystemClock.elapsedRealtime() - openedAt >= WATCHED_AFTER_MS) {
            episodes.getOrNull(index)?.let { saveProgress(it, watched = true) }
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        exitFullscreen(notifyPage = true)
        web.stopLoading()
        web.loadUrl("about:blank")
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INDEX = "extra_index"
        private const val WATCHED_AFTER_MS = 18 * 60 * 1000L

        fun intent(context: Context, index: Int): Intent =
            Intent(context, WebPlayerActivity::class.java).putExtra(EXTRA_INDEX, index)
    }
}
