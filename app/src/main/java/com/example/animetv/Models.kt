package com.example.animetv

/** Lo mínimo que necesita una tarjeta de Leanback. */
interface CardItem {
    val title: String
    val image: String
    val subtitle: String? get() = null
}

data class Anime(
    override val title: String,
    val url: String,
    override val image: String,
    val section: String
) : CardItem

data class Episode(
    override val title: String,
    val url: String,
    override val image: String,
    val number: Int?
) : CardItem {
    override val subtitle: String? get() = number?.let { "Episodio $it" }
}
