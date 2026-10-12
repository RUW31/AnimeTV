package com.example.animetv

import androidx.annotation.DrawableRes

/** Lo mínimo que necesita una tarjeta con imagen. */
interface CardItem {
    val title: String
    val image: String
    val subtitle: String? get() = null
}

data class Anime(
    override val title: String,
    val url: String,
    override val image: String,
    val section: String = ""
) : CardItem

data class Episode(
    override val title: String,
    val url: String,
    override val image: String,
    val number: Int?
) : CardItem {
    override val subtitle: String? get() = number?.let { "Episodio $it" }
    val label: String get() = number?.let { "EP $it" } ?: title
}

data class AnimeDetails(
    val title: String,
    val synopsis: String,
    val poster: String,
    val backdrop: String,
    val genres: List<String>,
    val episodes: List<Episode>
)

data class Genre(val name: String, val url: String)

/** Lo que se detecta automáticamente del menú del sitio. */
data class SiteMap(
    val libraryUrl: String?,
    val searchAction: String?,
    val searchParam: String?,
    val genres: List<Genre>
)

data class Home(val catalog: List<Anime>, val siteMap: SiteMap)

data class ListPage(val items: List<Anime>, val nextUrl: String?)

/** Tarjetas de menú: Biblioteca, Buscar y géneros. */
data class MenuCard(val kind: Kind, val label: String, @DrawableRes val icon: Int, val url: String? = null) {
    enum class Kind { LIBRARY, SEARCH, GENRE }
}

/** Tarjeta de la fila "Continuar viendo". */
data class ContinueItem(val progress: WatchProgress) : CardItem {
    override val title: String get() = progress.animeTitle
    override val image: String get() = progress.animeImage
    override val subtitle: String
        get() {
            val ep = progress.episodeNumber?.let { "Ep $it" } ?: progress.episodeTitle
            return if (progress.finished) "$ep · Visto" else "$ep · ${formatTime(progress.positionMs)}"
        }
}

data class WatchProgress(
    val episodeUrl: String,
    val episodeTitle: String,
    val episodeNumber: Int?,
    val animeUrl: String,
    val animeTitle: String,
    val animeImage: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean
        get() = durationMs > 0 && positionMs >= durationMs * 0.92
}
