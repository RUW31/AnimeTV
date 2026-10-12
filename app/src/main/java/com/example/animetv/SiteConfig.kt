package com.example.animetv

import java.net.URI
import java.net.URLEncoder

/**
 * Todo lo que depende del HTML del sitio vive aquí.
 * Casi todo se detecta solo; los valores vacíos son "automático".
 *
 * Consejo: abre el sitio en Chrome de escritorio, F12 > Elements, y prueba
 * cada script pegándolo en la consola antes de compilar.
 */
object SiteConfig {

    /** Página principal que se scrapea para armar las filas. */
    const val BASE_URL = "https://animev1.com/"

    val HOST: String = URI(BASE_URL).host.removePrefix("www.")

    /** URL de la biblioteca / directorio. Vacío = se detecta sola desde el menú del sitio. */
    const val LIBRARY_URL = ""

    /** Plantilla de búsqueda con {q}, p. ej. "https://sitio.com/buscar?q={q}". Vacío = se detecta el formulario. */
    const val SEARCH_URL = ""

    /** Selector CSS opcional de cada tarjeta (p. ej. "article.anime"). Vacío = heurística automática. */
    const val CARD_SELECTOR = ""

    /** Regex que identifica las URL de episodios (se evalúa sobre la ruta del enlace). */
    const val EPISODE_PATTERN = """episodio|episode|capitulo|/ep-?\d+|/ver/"""

    /** Regex que identifica enlaces de géneros / categorías. */
    const val GENRE_PATTERN = """/(g[eé]neros?|genres?|categor[ií]as?|category|categories|tags?)/"""

    val EPISODE_REGEX = Regex(EPISODE_PATTERN, RegexOption.IGNORE_CASE)

    fun isEpisodeUrl(url: String): Boolean =
        EPISODE_REGEX.containsMatchIn(runCatching { URI(url).path }.getOrNull() ?: url)

    /** Dominios de publicidad que el WebView bloquea (acelera la carga y evita popups). */
    private val AD_HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
        "google-analytics.com", "popads.net", "popcash.net", "adsterra",
        "propellerads", "exoclick", "juicyads", "onclicka", "hilltopads"
    )

    fun isAd(url: String): Boolean = AD_HOSTS.any { url.contains(it, ignoreCase = true) }

    /** Arma la URL de búsqueda con la plantilla manual o con el formulario detectado. */
    fun searchUrl(map: SiteMap?, query: String): String? {
        val q = URLEncoder.encode(query, "UTF-8")
        if (SEARCH_URL.isNotBlank()) return SEARCH_URL.replace("{q}", q)
        val action = map?.searchAction ?: return null
        val param = map.searchParam ?: return null
        val sep = if (action.contains('?')) "&" else "?"
        return "$action$sep$param=$q"
    }

    private fun jsRegex(pattern: String) = pattern.replace("/", "\\/")

    // ---------------------------------------------------------------------
    // JavaScript inyectado. evaluateJavascript entrega el valor devuelto ya
    // serializado como JSON.
    // ---------------------------------------------------------------------

    /** Tarjetas de anime: [{title, url, image, section}]. Sirve para portada, biblioteca y búsqueda. */
    val JS_CATALOG: String = """
        (function () {
          if (document.body) window.scrollTo(0, document.body.scrollHeight);
          function abs(u) { try { return u ? new URL(u, location.href).href : ''; } catch (e) { return ''; } }
          var sel = '__CARD_SELECTOR__';
          var nodes = document.querySelectorAll(sel || 'a[href]');
          var out = [], seen = {};
          for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            var a = n.tagName === 'A' ? n : n.querySelector('a[href]');
            if (!a) continue;
            var img = n.querySelector('img') || a.querySelector('img');
            if (!img) continue;
            var href = a.href;
            if (!href || seen[href] || a.host !== location.host || a.pathname === '/') continue;
            var src = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') ||
                      img.getAttribute('data-original') || img.getAttribute('src') || '';
            if (src.indexOf('data:') === 0) src = '';
            var title = (a.getAttribute('title') || img.getAttribute('alt') || a.textContent || '')
                          .replace(/\s+/g, ' ').trim();
            if (title.length < 2) continue;
            var box = a.closest('section') || a.closest('[class*="section"]') || a.closest('[class*="row"]');
            var h = box ? box.querySelector('h1, h2, h3') : null;
            seen[href] = 1;
            out.push({
              title: title.slice(0, 120),
              url: href,
              image: abs(src),
              section: h ? h.textContent.replace(/\s+/g, ' ').trim().slice(0, 60) : 'Recientes'
            });
          }
          return out;
        })();
    """.trimIndent().replace("__CARD_SELECTOR__", CARD_SELECTOR)

    /** Detecta en el menú del sitio: enlace a la biblioteca, formulario de búsqueda y géneros. */
    val JS_DISCOVER: String = """
        (function () {
          function abs(u) { try { return u ? new URL(u, location.href).href : ''; } catch (e) { return ''; } }
          function clean(t) { return (t || '').replace(/\s+/g, ' ').trim(); }
          var LIB_RE = /^(biblioteca|directorio|directorio anime|cat[aá]logo|animes?|lista de animes?|todos los animes|explorar|browse|library|anime list)$/i;
          var GENRE_RE = /__GENRE_RE__/i;
          var lib = null, libScore = 0, genres = [], seenG = {};
          document.querySelectorAll('a[href]').forEach(function (a) {
            if (a.host !== location.host) return;
            var t = clean(a.textContent);
            if (LIB_RE.test(t)) {
              var s = /biblioteca/i.test(t) ? 3 : (/directorio|cat[aá]logo/i.test(t) ? 2 : 1);
              if (s > libScore) { lib = a.href; libScore = s; }
            }
            if (GENRE_RE.test(a.pathname) && t && t.length < 25 && !seenG[t.toLowerCase()]) {
              seenG[t.toLowerCase()] = 1;
              genres.push({ name: t, url: a.href });
            }
          });
          if (!lib) {
            var p = document.querySelector('a[href*="biblioteca"], a[href*="directorio"], a[href*="catalogo"], a[href*="/animes"]');
            if (p && p.host === location.host) lib = p.href;
          }
          var search = null;
          var input = document.querySelector('form input[type="search"], form input[name="q"], form input[name="s"], form input[name="search"], form input[name="buscar"], form input[name="query"], form input[name="keyword"]');
          if (input && input.name && input.form) {
            search = { action: abs(input.form.getAttribute('action') || location.href), param: input.name };
          }
          return { library: lib, search: search, genres: genres.slice(0, 40) };
        })();
    """.trimIndent().replace("__GENRE_RE__", jsRegex(GENRE_PATTERN))

    /** Ficha de un anime: título, sinopsis, póster, fondo, géneros y episodios. */
    val JS_DETAILS: String = """
        (function () {
          function abs(u) { try { return u ? new URL(u, location.href).href : ''; } catch (e) { return ''; } }
          function meta(p) {
            var m = document.querySelector('meta[property="' + p + '"], meta[name="' + p + '"]');
            return m ? (m.getAttribute('content') || '') : '';
          }
          function clean(t) { return (t || '').replace(/\s+/g, ' ').trim(); }
          var h1 = document.querySelector('h1');
          var title = clean(h1 ? h1.textContent : '') || clean(meta('og:title')).split(' | ')[0];
          var synopsis = '';
          document.querySelectorAll('[class*="sinopsis" i], [class*="synopsis" i], [class*="descrip" i], [class*="description" i], [itemprop="description"], [class*="resumen" i], [class*="overview" i]').forEach(function (e) {
            var t = clean(e.textContent);
            if (t.length > synopsis.length && t.length < 2500) synopsis = t;
          });
          if (synopsis.length < 40) synopsis = clean(meta('og:description') || meta('description')) || synopsis;
          var pimg = document.querySelector('[class*="poster" i] img, [class*="cover" i] img, [class*="portada" i] img, [class*="thumb" i] img, article img');
          var poster = pimg ? abs(pimg.getAttribute('data-src') || pimg.getAttribute('data-lazy-src') || pimg.getAttribute('src')) : '';
          if (poster.indexOf('data:') === 0) poster = '';
          var backdrop = abs(meta('og:image')) || poster;
          if (!poster) poster = backdrop;
          var GENRE_RE = /__GENRE_RE__/i, genres = [];
          var scopes = ['[class*="genres" i]', '[class*="generos" i]', '[class*="genre" i]', '[class*="genero" i]', 'main', 'article'];
          for (var s = 0; s < scopes.length && genres.length === 0; s++) {
            var box = document.querySelector(scopes[s]);
            if (!box) continue;
            box.querySelectorAll('a[href]').forEach(function (a) {
              if (a.host !== location.host || !GENRE_RE.test(a.pathname)) return;
              var t = clean(a.textContent);
              if (t && t.length < 25 && genres.indexOf(t) < 0) genres.push(t);
            });
          }
          var EP_RE = /__EP_RE__/i, eps = [], seen = {};
          document.querySelectorAll('a[href]').forEach(function (a) {
            var href = a.href;
            if (!href || seen[href] || a.host !== location.host || !EP_RE.test(a.pathname)) return;
            var img = a.querySelector('img');
            var src = img ? (img.getAttribute('data-src') || img.getAttribute('src') || '') : '';
            if (src.indexOf('data:') === 0) src = '';
            seen[href] = 1;
            eps.push({
              title: clean(a.getAttribute('title') || a.textContent || (img && img.getAttribute('alt'))).slice(0, 80),
              url: href,
              image: abs(src)
            });
          });
          return { title: title.slice(0, 120), synopsis: synopsis, poster: poster, backdrop: backdrop,
                   genres: genres.slice(0, 6), episodes: eps };
        })();
    """.trimIndent()
        .replace("__GENRE_RE__", jsRegex(GENRE_PATTERN))
        .replace("__EP_RE__", jsRegex(EPISODE_PATTERN))

    /** Enlace a la página siguiente de un listado (biblioteca, géneros, búsqueda). */
    val JS_NEXT_PAGE: String = """
        (function () {
          var a = document.querySelector('link[rel="next"], a[rel="next"], .pagination .next a, .pagination a.next, a.next, li.next > a, a[aria-label*="siguiente" i], a[aria-label*="next" i]');
          if (!a) {
            var all = document.querySelectorAll('a[href]');
            for (var i = 0; i < all.length; i++) {
              var t = (all[i].textContent || '').replace(/\s+/g, ' ').trim();
              if (/^(siguiente|next|»|›|>|>>|siguiente »|siguiente ›)$/i.test(t)) { a = all[i]; break; }
            }
          }
          return a && a.href ? [a.href] : [];
        })();
    """.trimIndent()

    /** Para listados con scroll infinito o botón "Cargar más". */
    val JS_LOAD_MORE: String = """
        (function () {
          var btns = document.querySelectorAll('button, a[href="#"], a[role="button"]');
          for (var i = 0; i < btns.length; i++) {
            var t = (btns[i].textContent || '').replace(/\s+/g, ' ').trim();
            if (/^(cargar m[aá]s|ver m[aá]s|mostrar m[aá]s|load more|show more|m[aá]s resultados)$/i.test(t)) {
              try { btns[i].click(); } catch (e) {}
              break;
            }
          }
          if (document.body) window.scrollTo(0, document.body.scrollHeight);
          return [];
        })();
    """.trimIndent()

    /** Busca enlaces de video en el DOM y en el HTML (incluye URLs escapadas dentro de JSON). */
    val JS_FIND_MEDIA: String = """
        (function () {
          var urls = [];
          document.querySelectorAll('video, video source').forEach(function (v) {
            var s = v.currentSrc || v.src || v.getAttribute('src');
            if (s && s.indexOf('blob:') !== 0) urls.push(s);
          });
          var html = document.documentElement ? document.documentElement.innerHTML : '';
          var re = /https?:[^"'\s<>]+?\.(?:m3u8|mp4)[^"'\s<>]*/gi, m;
          while ((m = re.exec(html)) !== null) {
            urls.push(m[0].replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&'));
          }
          document.querySelectorAll('video').forEach(function (v) {
            try { v.muted = true; v.play(); } catch (e) {}
          });
          return urls;
        })();
    """.trimIndent()

    /** Reproductores embebidos (iframes y botones de servidor con data-*). */
    val JS_EMBEDS: String = """
        (function () {
          var out = [];
          function add(u) {
            if (!u) return;
            try { u = new URL(u, location.href).href; } catch (e) { return; }
            if (u.indexOf('http') === 0 && out.indexOf(u) < 0) out.push(u);
          }
          document.querySelectorAll('iframe').forEach(function (f) {
            add(f.getAttribute('src') || f.getAttribute('data-src'));
          });
          document.querySelectorAll('[data-video], [data-player], [data-embed]').forEach(function (e) {
            add(e.getAttribute('data-video') || e.getAttribute('data-player') || e.getAttribute('data-embed'));
          });
          return out;
        })();
    """.trimIndent()
}
