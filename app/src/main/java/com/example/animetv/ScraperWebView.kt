package com.example.animetv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/** Enlace de video detectado + cabeceras que hay que reenviar para que el servidor lo sirva. */
data class MediaHit(val url: String, val headers: Map<String, String>) {
    val isHls: Boolean get() = url.contains(".m3u8", ignoreCase = true)
}

/**
 * WebView "oculto": se coloca debajo de la UI (tapado y sin foco) para que Chromium
 * lo trate como visible y ejecute el JavaScript del sitio con normalidad.
 *
 * Hace tres cosas:
 *  1. Carga páginas y espera a que terminen.
 *  2. Inyecta JavaScript (ver [SiteConfig]) y devuelve el resultado como JSON.
 *  3. Espía TODAS las peticiones de red (también las de iframes de otros dominios)
 *     para capturar enlaces .m3u8 / .mp4 junto con su Referer.
 *
 * Todos los métodos públicos son `suspend` y se pueden llamar desde lifecycleScope.
 */
@SuppressLint("SetJavaScriptEnabled")
class ScraperWebView(context: Context) {

    val view: WebView = WebView(context)
    val userAgent: String

    @Volatile private var mediaChannel: Channel<MediaHit>? = null
    @Volatile private var currentPage = ""
    @Volatile private var currentHost = ""
    private var pageFinished = CompletableDeferred<Unit>()

    init {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) // chrome://inspect en debug
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            blockNetworkImage = true // no necesitamos píxeles, solo URLs
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replace("; wv", "")
        }
        userAgent = view.settings.userAgentString
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webChromeClient = WebChromeClient()
        view.webViewClient = Client()
    }

    // ------------------------------------------------------------------ API

    /** Extrae la lista de animes de la portada. */
    suspend fun scrapeCatalog(url: String = SiteConfig.BASE_URL): List<Anime> {
        loadPage(url)
        return pollList(SiteConfig.JS_CATALOG, timeoutMs = 25_000) { arr ->
            arr.objects().mapNotNull { o ->
                val title = o.optString("title")
                val link = o.optString("url")
                if (title.isBlank() || link.isBlank()) null
                else Anime(title, link, o.optString("image"), o.optString("section").ifBlank { "Recientes" })
            }
        }.also { Log.d(TAG, "Catálogo: ${it.size} elementos") }
    }

    /** Extrae los episodios de la ficha de un anime, ordenados por número si es posible. */
    suspend fun scrapeEpisodes(animeUrl: String): List<Episode> {
        loadPage(animeUrl)
        val all = pollList(SiteConfig.JS_EPISODES, timeoutMs = 20_000) { arr ->
            arr.objects().mapNotNull { o ->
                val link = o.optString("url")
                if (link.isBlank() || link == animeUrl) return@mapNotNull null
                val n = episodeNumber(link)
                Episode(
                    title = o.optString("title").ifBlank { "Episodio ${n ?: "?"}" },
                    url = link,
                    image = o.optString("image"),
                    number = n
                )
            }
        }
        // Descarta enlaces a episodios de OTROS animes (sidebar, "recientes"...).
        val slug = animeUrl.trimEnd('/').substringAfterLast('/')
        val own = if (slug.length >= 3) all.filter { it.url.contains(slug, ignoreCase = true) } else emptyList()
        val list = own.ifEmpty { all }
        Log.d(TAG, "Episodios: ${list.size}")
        return if (list.all { it.number != null }) list.sortedBy { it.number } else list
    }

    /**
     * Abre la página de un episodio y devuelve el primer .m3u8 (preferido) o .mp4 que aparezca.
     * Plan A: la propia página. Plan B: cada iframe / servidor embebido, con Referer correcto.
     */
    suspend fun sniffMedia(episodeUrl: String): MediaHit? {
        val embeds = mutableListOf<String>()
        sniffPage(episodeUrl, referer = null, timeoutMs = 15_000, collectEmbeds = embeds)?.let { return it }

        val candidates = embeds.filter { it.startsWith("http") && !SiteConfig.isAd(it) }.distinct().take(5)
        Log.d(TAG, "Plan B: ${candidates.size} reproductores embebidos")
        for (embed in candidates) {
            sniffPage(embed, referer = episodeUrl, timeoutMs = 12_000)?.let { return it }
        }
        return null
    }

    /** Detiene el sitio (scripts, audio del reproductor web) dejando una página en blanco. */
    fun stop() {
        mediaChannel = null
        view.stopLoading()
        view.loadUrl("about:blank")
    }

    fun destroy() {
        mediaChannel = null
        view.stopLoading()
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    // ------------------------------------------------------------ internos

    private suspend fun startLoad(url: String, referer: String?): CompletableDeferred<Unit> =
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            pageFinished = done
            currentPage = url
            currentHost = hostOf(url)
            if (referer != null) view.loadUrl(url, mapOf("Referer" to referer)) else view.loadUrl(url)
            done
        }

    private suspend fun loadPage(url: String, referer: String? = null, timeoutMs: Long = 20_000) {
        val done = startLoad(url, referer)
        withTimeoutOrNull(timeoutMs) { done.await() }
    }

    private suspend fun eval(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript(script) { result ->
                if (cont.isActive) cont.resume(result ?: "null")
            }
        }
    }

    private suspend fun evalArray(script: String): JSONArray? {
        val raw = eval(script)
        return try {
            JSONArray(raw)
        } catch (e: JSONException) {
            null
        }
    }

    /** Re-ejecuta el script cada 1.5 s hasta que devuelva algo (contenido cargado por JS / lazy-load). */
    private suspend fun <T> pollList(
        script: String,
        timeoutMs: Long,
        parse: (JSONArray) -> List<T>
    ): List<T> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val list = evalArray(script)?.let(parse).orEmpty()
            if (list.isNotEmpty()) return list
            delay(1_500)
        }
        return emptyList()
    }

    private suspend fun sniffPage(
        url: String,
        referer: String?,
        timeoutMs: Long,
        collectEmbeds: MutableList<String>? = null
    ): MediaHit? = coroutineScope {
        val channel = Channel<MediaHit>(Channel.UNLIMITED)
        mediaChannel = channel
        startLoad(url, referer)

        // "Empujón": revisa el DOM, intenta dar play y simula toques en el reproductor.
        val kicker = launch {
            delay(2_000)
            repeat(10) { round ->
                if (collectEmbeds != null && collectEmbeds.isEmpty()) {
                    collectEmbeds += evalArray(SiteConfig.JS_EMBEDS)?.strings().orEmpty()
                }
                evalArray(SiteConfig.JS_FIND_MEDIA)?.strings().orEmpty()
                    .filter { MEDIA_RE.containsMatchIn(it) && !SiteConfig.isAd(it) }
                    .forEach { channel.trySend(MediaHit(it, mapOf("Referer" to url))) }
                if (round < 3) tapCenter()
                delay(1_500)
            }
        }

        val hit = withTimeoutOrNull(timeoutMs) {
            var best = channel.receive()
            // Si lo primero fue un .mp4, espera un poco por si aparece un .m3u8 (mejor calidad / adaptativo).
            if (!best.isHls) {
                withTimeoutOrNull(3_000) { while (!best.isHls) best = channel.receive() }
            }
            best
        }
        kicker.cancel()
        mediaChannel = null
        if (hit != null) Log.d(TAG, "Stream elegido: ${hit.url}")
        hit
    }

    /** Toque sintético en el centro: muchos reproductores no piden el stream hasta que se pulsa play. */
    private suspend fun tapCenter() = withContext(Dispatchers.Main) {
        val w = view.width
        val h = view.height
        if (w == 0 || h == 0) return@withContext
        val t = SystemClock.uptimeMillis()
        val x = w / 2f
        val y = h / 2f
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private inner class Client : WebViewClient() {

        override fun onPageFinished(v: WebView, url: String?) {
            pageFinished.complete(Unit)
        }

        // Se ejecuta en un hilo de fondo para CADA petición (documentos, iframes, XHR, media...).
        override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (SiteConfig.isAd(url)) return emptyResponse()
            if (MEDIA_RE.containsMatchIn(url)) {
                val headers = HashMap(request.requestHeaders ?: emptyMap())
                if (headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                    headers["Referer"] = currentPage
                }
                Log.d(TAG, "Media detectado: $url")
                mediaChannel?.trySend(MediaHit(url, headers))
            }
            return null
        }

        // Bloquea redirecciones de anuncios a otros dominios en el frame principal.
        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && request.isRedirect) return false
            val host = request.url.host.orEmpty()
            val allowed = host.endsWith(SiteConfig.HOST) ||
                (currentHost.isNotEmpty() && host.endsWith(currentHost))
            if (!allowed) Log.d(TAG, "Navegación bloqueada: ${request.url}")
            return !allowed
        }
    }

    companion object {
        private const val TAG = "Scraper"
        private val MEDIA_RE = Regex("""\.(m3u8|mp4)(\?|#|$)""", RegexOption.IGNORE_CASE)
        private val LAST_NUMBER_RE = Regex("""(\d+)(?!.*\d)""")

        private fun episodeNumber(url: String): Int? =
            LAST_NUMBER_RE.find(url.trimEnd('/'))?.value?.toIntOrNull()

        private fun hostOf(url: String): String =
            Uri.parse(url).host?.removePrefix("www.").orEmpty()

        private fun emptyResponse() =
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        private fun JSONArray.objects(): List<JSONObject> =
            (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONArray.strings(): List<String> =
            (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }
}
