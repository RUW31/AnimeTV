package com.example.animetv

import java.net.URI

/**
 * Todo lo que depende del HTML del sitio vive aquí.
 * Si el sitio cambia su estructura, solo tienes que tocar este archivo.
 *
 * Consejo: abre el sitio en Chrome de escritorio, F12 > Elements, inspecciona
 * una tarjeta de anime y copia aquí su selector CSS. Puedes probar cada script
 * pegándolo en la consola de DevTools antes de compilar.
 */
object SiteConfig {

    /** Página principal que se scrapea para armar las filas. */
    const val BASE_URL = "https://animev1.com/"

    val HOST: String = URI(BASE_URL).host.removePrefix("www.")

    /**
     * Selector CSS opcional de cada tarjeta del catálogo (p. ej. "article.anime" o ".card a").
     * Vacío = heurística automática: cualquier <a> del mismo dominio que contenga un <img>.
     * Usa comillas dobles dentro del selector si las necesitas, nunca comillas simples.
     */
    const val CARD_SELECTOR = ""

    /**
     * Regex que identifica las URL de episodios (se evalúa sobre la ruta del enlace).
     * Ajústala a cómo el sitio nombra sus episodios, p. ej. "/ver/" o "-episodio-".
     */
    const val EPISODE_PATTERN = """episodio|episode|capitulo|/ep-?\d+|/ver/"""

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

    // ---------------------------------------------------------------------
    // JavaScript inyectado. Cada script devuelve un array, que evaluateJavascript
    // entrega a Kotlin ya serializado como JSON.
    // ---------------------------------------------------------------------

    /** Catálogo: [{title, url, image, section}] agrupado por el encabezado de su sección. */
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

    /** Episodios de la ficha de un anime: [{title, url, image}]. */
    val JS_EPISODES: String = """
        (function () {
          function abs(u) { try { return u ? new URL(u, location.href).href : ''; } catch (e) { return ''; } }
          var re = /__EP_RE__/i;
          var anchors = document.querySelectorAll('a[href]');
          var out = [], seen = {};
          for (var i = 0; i < anchors.length; i++) {
            var a = anchors[i], href = a.href;
            if (!href || seen[href] || a.host !== location.host || !re.test(a.pathname)) continue;
            var img = a.querySelector('img');
            var src = img ? (img.getAttribute('data-src') || img.getAttribute('src') || '') : '';
            if (src.indexOf('data:') === 0) src = '';
            var title = (a.getAttribute('title') || a.textContent || (img && img.getAttribute('alt')) || '')
                          .replace(/\s+/g, ' ').trim();
            seen[href] = 1;
            out.push({ title: title.slice(0, 80), url: href, image: abs(src) });
          }
          return out;
        })();
    """.trimIndent().replace("__EP_RE__", EPISODE_PATTERN.replace("/", "\\/"))

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
