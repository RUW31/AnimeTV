package com.example.animetv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

/** Lista de episodios que está reproduciendo el reproductor (para "siguiente episodio"). */
object PlaybackQueue {
    var anime: Anime? = null
        private set
    var episodes: List<Episode> = emptyList()
        private set

    fun set(anime: Anime?, episodes: List<Episode>) {
        this.anime = anime
        this.episodes = episodes
    }
}

/** Todo lo que la app ya vio del sitio: sirve para buscar sin conexión al buscador del sitio. */
object Catalog {
    private val known = LinkedHashMap<String, Anime>()

    fun remember(items: List<Anime>) {
        items.forEach { if (!SiteConfig.isEpisodeUrl(it.url)) known[it.url] = it }
    }

    fun search(query: String): List<Anime> =
        known.values.filter { it.title.contains(query, ignoreCase = true) }.take(40)
}

/** A dónde lleva cada tarjeta al pulsar OK. */
object Nav {

    fun open(activity: Activity, item: Any?) {
        when (item) {
            is Anime -> {
                if (SiteConfig.isEpisodeUrl(item.url)) {
                    // Tarjetas de "últimos episodios" de la portada: se reproducen directo.
                    playSingle(activity, Episode(item.title, item.url, item.image, null), Anime(item.title, "", item.image), -1L)
                } else {
                    activity.startActivity(DetailsActivity.intent(activity, item))
                }
            }
            is ContinueItem -> {
                val p = item.progress
                if (p.animeUrl.isBlank()) {
                    playSingle(
                        activity,
                        Episode(p.episodeTitle, p.episodeUrl, p.animeImage, p.episodeNumber),
                        Anime(p.animeTitle, "", p.animeImage),
                        if (p.finished) 0L else p.positionMs
                    )
                } else {
                    activity.startActivity(DetailsActivity.intent(activity, Anime(p.animeTitle, p.animeUrl, p.animeImage)))
                }
            }
            is MenuCard -> when (item.kind) {
                MenuCard.Kind.SEARCH -> activity.startActivity(Intent(activity, SearchActivity::class.java))
                MenuCard.Kind.LIBRARY, MenuCard.Kind.GENRE -> {
                    val url = item.url
                    if (url.isNullOrBlank()) {
                        Toast.makeText(activity, R.string.library_missing, Toast.LENGTH_LONG).show()
                    } else {
                        activity.startActivity(LibraryActivity.intent(activity, item.label, url))
                    }
                }
            }
        }
    }

    private fun playSingle(activity: Activity, episode: Episode, anime: Anime, startMs: Long) {
        PlaybackQueue.set(anime, listOf(episode))
        activity.startActivity(Playback.intent(activity, 0, startMs))
    }
}

/** Elige el reproductor: nativo para enlaces directos de video, web para páginas. */
object Playback {
    private val DIRECT_MEDIA = Regex("""\.(m3u8|mp4|mkv|webm)(\?|#|$)""", RegexOption.IGNORE_CASE)

    fun isDirectMedia(url: String): Boolean = DIRECT_MEDIA.containsMatchIn(url)

    fun intent(context: Context, index: Int, startMs: Long = -1L): Intent {
        val url = PlaybackQueue.episodes.getOrNull(index)?.url.orEmpty()
        return if (isDirectMedia(url)) PlayerActivity.intent(context, index, startMs)
        else WebPlayerActivity.intent(context, index)
    }
}
