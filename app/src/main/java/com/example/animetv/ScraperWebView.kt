package com.example.animetv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * WebView "oculto" que lee los listados del sitio: va debajo de la UI (tapado y
 * sin foco) para que Chromium ejecute el JavaScript de la página con normalidad.
 *
 *  1. Carga páginas y espera a que terminen.
 *  2. Inyecta JavaScript (ver [SiteConfig]) y devuelve el resultado como JSON:
 *     catálogo, biblioteca, búsqueda y fichas de anime.
 *
 * Los videos NO pasan por aquí: se ven en [WebPlayerActivity] con el reproductor de la propia página.
 */
@SuppressLint("SetJavaScriptEnabled")
class ScraperWebView(context: Context) {

    val view: WebView = WebView(context)
    val userAgent: String

    @Volatile private var currentPage = ""
    @Volatile private var currentHost = ""
    private var pageFinished = CompletableDeferred<Unit>()

    init {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) // chrome://inspect en debug
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true // aquí no se reproduce nada
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            blockNetworkImage = true // no necesitamos píxeles, solo URLs
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replace("; wv", "")
        }
        userAgent = view.settings.userAgentString
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.setBackgroundColor(0)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webChromeClient = WebChromeClient()
        view.webViewClient = Client()
    }

    // ------------------------------------------------------------------ API

    /** Portada: filas de animes + biblioteca, buscador y géneros detectados en el menú. */
    suspend fun scrapeHome(): Home {
        loadPage(SiteConfig.BASE_URL)
        val catalog = pollList(SiteConfig.JS_CATALOG, timeoutMs = 25_000) { parseAnimeList(it) }
        val map = parseSiteMap(evalObject(SiteConfig.JS_DISCOVER))
        Log.d(TAG, "Inicio: ${catalog.size} animes, biblioteca=${map.libraryUrl}, géneros=${map.genres.size}, búsqueda=${map.searchAction}")
        return Home(catalog, map)
    }

    /** Una página de un listado (biblioteca, género o búsqueda) + enlace a la siguiente. */
    suspend fun scrapeListPage(url: String, timeoutMs: Long = 20_000): ListPage {
        loadPage(url)
        val items = pollList(SiteConfig.JS_CATALOG, timeoutMs) { parseAnimeList(it) }
        return ListPage(items, nextPage(url))
    }

    /** Para listados sin paginación: pulsa "Cargar más" / hace scroll y vuelve a leer las tarjetas. */
    suspend fun loadMoreInPlace(): ListPage {
        val page = currentPage
        eval(SiteConfig.JS_LOAD_MORE)
        delay(2_500)
        val items = evalArray(SiteConfig.JS_CATALOG)?.let { parseAnimeList(it) }.orEmpty()
        return ListPage(items, nextPage(page))
    }

    /** Ficha del anime: sinopsis, póster, géneros y episodios ordenados. */
    suspend fun scrapeDetails(animeUrl: String): AnimeDetails {
        loadPage(animeUrl)
        var obj: JSONObject? = null
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            obj = evalObject(SiteConfig.JS_DETAILS)
            if ((obj?.optJSONArray("episodes")?.length() ?: 0) > 0) break
            delay(1_500)
        }
        val o = obj ?: JSONObject()
        val episodes = cleanEpisodes(animeUrl, o.optJSONArray("episodes")?.let { parseEpisodes(it) }.orEmpty())
        Log.d(TAG, "Ficha: ${episodes.size} episodios")
        return AnimeDetails(
            title = o.str("title"),
            synopsis = o.str("synopsis"),
            poster = o.str("poster"),
            backdrop = o.str("backdrop"),
            genres = o.optJSONArray("genres")?.strings().orEmpty(),
            episodes = episodes
        )
    }

    /** Detiene el sitio dejando una página en blanco. */
    fun stop() {
        view.stopLoading()
        view.loadUrl("about:blank")
    }

    fun destroy() {
        view.stopLoading()
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    // ------------------------------------------------------------ parsing

    private fun parseAnimeList(arr: JSONArray): List<Anime> = arr.objects().mapNotNull { o ->
        val title = o.str("title")
        val link = o.str("url")
        if (title.isBlank() || link.isBlank()) null
        else Anime(title, link, o.str("image"), o.str("section").ifBlank { "Recientes" })
    }

    private fun parseEpisodes(arr: JSONArray): List<Episode> = arr.objects().mapNotNull { o ->
        val link = o.str("url")
        if (link.isBlank()) return@mapNotNull null
        val n = episodeNumber(link)
        Episode(
            title = o.str("title").ifBlank { "Episodio ${n ?: "?"}" },
            url = link,
            image = o.str("image"),
            number = n
        )
    }

    /** Quita enlaces a episodios de OTROS animes (sidebar, "recientes"...) y ordena por número. */
    private fun cleanEpisodes(animeUrl: String, all: List<Episode>): List<Episode> {
        val withoutSelf = all.filter { it.url != animeUrl }
        val slug = animeUrl.trimEnd('/').substringAfterLast('/')
        val own = if (slug.length >= 3) withoutSelf.filter { it.url.contains(slug, ignoreCase = true) } else emptyList()
        val list = own.ifEmpty { withoutSelf }
        return if (list.all { it.number != null }) list.sortedBy { it.number } else list
    }

    private fun parseSiteMap(o: JSONObject?): SiteMap {
        val search = o?.optJSONObject("search")
        return SiteMap(
            libraryUrl = SiteConfig.LIBRARY_URL.ifBlank { o?.str("library").orEmpty() }.ifBlank { null },
            searchAction = search?.str("action")?.ifBlank { null },
            searchParam = search?.str("param")?.ifBlank { null },
            genres = o?.optJSONArray("genres")?.objects().orEmpty().mapNotNull { g ->
                val name = g.str("name")
                val url = g.str("url")
                if (name.isBlank() || url.isBlank()) null else Genre(name, url)
            }
        )
    }

    private suspend fun nextPage(current: String): String? =
        evalArray(SiteConfig.JS_NEXT_PAGE)?.strings()
            ?.firstOrNull { it.startsWith("http") && it.trimEnd('/') != current.trimEnd('/') }

    // ------------------------------------------------------------ internos

    private suspend fun loadPage(url: String, timeoutMs: Long = 20_000) {
        val done = withContext(Dispatchers.Main) {
            val d = CompletableDeferred<Unit>()
            pageFinished = d
            currentPage = url
            currentHost = hostOf(url)
            view.loadUrl(url)
            d
        }
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

    private suspend fun evalObject(script: String): JSONObject? {
        val raw = eval(script)
        return try {
            JSONObject(raw)
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

    private inner class Client : WebViewClient() {

        override fun onPageFinished(v: WebView, url: String?) {
            pageFinished.complete(Unit)
        }

        // Bloquea dominios de publicidad conocidos (carga más rápido).
        override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
            return if (SiteConfig.isAd(request.url.toString())) {
                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            } else null
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
        private val LAST_NUMBER_RE = Regex("""(\d+)(?!.*\d)""")

        private fun episodeNumber(url: String): Int? =
            LAST_NUMBER_RE.find(url.trimEnd('/'))?.value?.toIntOrNull()

        private fun hostOf(url: String): String =
            Uri.parse(url).host?.removePrefix("www.").orEmpty()
    }
}
