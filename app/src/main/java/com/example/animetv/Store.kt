package com.example.animetv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Datos guardados en la TV: favoritos, progreso de cada episodio y lo que se
 * detectó del sitio (biblioteca, buscador, géneros).
 */
object Store {

    private const val KEY_FAVORITES = "favorites"
    private const val KEY_PROGRESS = "progress"
    private const val KEY_SITEMAP = "sitemap"
    private const val MAX_PROGRESS = 400

    private lateinit var prefs: SharedPreferences
    private var progressCache: List<WatchProgress>? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("animetv", Context.MODE_PRIVATE)
    }

    // ----------------------------------------------------------- Favoritos

    fun favorites(): List<Anime> =
        readArray(KEY_FAVORITES).objects().map { it.toAnime() }.filter { it.url.isNotBlank() }

    fun isFavorite(url: String): Boolean = favorites().any { it.url == url }

    /** Devuelve true si quedó en favoritos. */
    fun toggleFavorite(anime: Anime): Boolean {
        val list = favorites().toMutableList()
        val removed = list.removeAll { it.url == anime.url }
        if (!removed) list.add(0, anime)
        writeArray(KEY_FAVORITES, list.map { it.toJson() })
        return !removed
    }

    // ------------------------------------------------------------ Progreso

    private fun allProgress(): List<WatchProgress> =
        progressCache ?: readArray(KEY_PROGRESS).objects().mapNotNull { it.toProgress() }
            .also { progressCache = it }

    fun progress(episodeUrl: String): WatchProgress? =
        allProgress().firstOrNull { it.episodeUrl == episodeUrl }

    fun lastForAnime(animeUrl: String): WatchProgress? =
        if (animeUrl.isBlank()) null
        else allProgress().filter { it.animeUrl == animeUrl }.maxByOrNull { it.updatedAt }

    /** Último episodio tocado de cada anime, del más reciente al más antiguo. */
    fun continueWatching(limit: Int = 20): List<WatchProgress> =
        allProgress()
            .sortedByDescending { it.updatedAt }
            .distinctBy { it.animeUrl.ifBlank { it.episodeUrl } }
            .take(limit)

    fun saveProgress(p: WatchProgress) {
        val current = allProgress()
        val list = ArrayList<WatchProgress>(current.size + 1)
        list.add(p)
        current.filterTo(list) { it.episodeUrl != p.episodeUrl }
        val trimmed = list.take(MAX_PROGRESS)
        progressCache = trimmed
        writeArray(KEY_PROGRESS, trimmed.map { it.toJson() })
    }

    // ------------------------------------------------------------- SiteMap

    fun saveSiteMap(map: SiteMap) {
        prefs.edit().putString(KEY_SITEMAP, map.toJson().toString()).apply()
    }

    fun siteMap(): SiteMap? = prefs.getString(KEY_SITEMAP, null)?.let { raw ->
        try {
            JSONObject(raw).toSiteMap()
        } catch (e: JSONException) {
            null
        }
    }

    // ------------------------------------------------------------- Helpers

    private fun readArray(key: String): JSONArray = try {
        JSONArray(prefs.getString(key, "[]") ?: "[]")
    } catch (e: JSONException) {
        JSONArray()
    }

    private fun writeArray(key: String, items: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }
}

private fun Anime.toJson(): JSONObject =
    JSONObject().put("title", title).put("url", url).put("image", image).put("section", section)

private fun JSONObject.toAnime(): Anime = Anime(str("title"), str("url"), str("image"), str("section"))

private fun WatchProgress.toJson(): JSONObject = JSONObject()
    .put("ep", episodeUrl)
    .put("epTitle", episodeTitle)
    .put("num", episodeNumber ?: -1)
    .put("anime", animeUrl)
    .put("animeTitle", animeTitle)
    .put("img", animeImage)
    .put("pos", positionMs)
    .put("dur", durationMs)
    .put("at", updatedAt)

private fun JSONObject.toProgress(): WatchProgress? {
    val ep = str("ep")
    if (ep.isBlank()) return null
    return WatchProgress(
        episodeUrl = ep,
        episodeTitle = str("epTitle"),
        episodeNumber = optInt("num", -1).takeIf { it >= 0 },
        animeUrl = str("anime"),
        animeTitle = str("animeTitle"),
        animeImage = str("img"),
        positionMs = optLong("pos"),
        durationMs = optLong("dur"),
        updatedAt = optLong("at")
    )
}

private fun SiteMap.toJson(): JSONObject = JSONObject()
    .put("library", libraryUrl ?: JSONObject.NULL)
    .put("searchAction", searchAction ?: JSONObject.NULL)
    .put("searchParam", searchParam ?: JSONObject.NULL)
    .put("genres", JSONArray(genres.map { JSONObject().put("name", it.name).put("url", it.url) }))

private fun JSONObject.toSiteMap(): SiteMap = SiteMap(
    libraryUrl = str("library").ifBlank { null },
    searchAction = str("searchAction").ifBlank { null },
    searchParam = str("searchParam").ifBlank { null },
    genres = optJSONArray("genres")?.objects().orEmpty()
        .map { Genre(it.str("name"), it.str("url")) }
        .filter { it.name.isNotBlank() && it.url.isNotBlank() }
)
