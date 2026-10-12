package com.example.animetv

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DetailsOverviewRow
import androidx.leanback.widget.FullWidthDetailsOverviewRowPresenter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.launch

/** Ficha del anime: póster, sinopsis, géneros, botones y episodios en filas de 50. */
class DetailsActivity : ScraperHostActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, AnimeDetailsFragment())
                .commit()
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_IMAGE = "extra_image"

        fun intent(context: Context, anime: Anime): Intent =
            Intent(context, DetailsActivity::class.java)
                .putExtra(EXTRA_TITLE, anime.title)
                .putExtra(EXTRA_URL, anime.url)
                .putExtra(EXTRA_IMAGE, anime.image)
    }
}

class AnimeDetailsFragment : DetailsSupportFragment() {

    private lateinit var anime: Anime
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val actionsAdapter = ArrayObjectAdapter()
    private val episodeAdapters = mutableListOf<ArrayObjectAdapter>()
    private var details: AnimeDetails? = null
    private val scraperHost get() = requireActivity() as DetailsActivity
    private val episodes: List<Episode> get() = details?.episodes.orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = requireActivity().intent
        anime = Anime(
            intent.getStringExtra(DetailsActivity.EXTRA_TITLE).orEmpty(),
            intent.getStringExtra(DetailsActivity.EXTRA_URL).orEmpty(),
            intent.getStringExtra(DetailsActivity.EXTRA_IMAGE).orEmpty()
        )
        val ctx = requireContext()
        val overview = FullWidthDetailsOverviewRowPresenter(DescriptionPresenter())
        overview.setBackgroundColor(ContextCompat.getColor(ctx, R.color.details_panel))
        overview.setActionsBackgroundColor(ContextCompat.getColor(ctx, R.color.details_actions))
        overview.setOnActionClickedListener { action -> onAction(action) }

        val selector = ClassPresenterSelector()
        selector.addClassPresenter(DetailsOverviewRow::class.java, overview)
        selector.addClassPresenter(ListRow::class.java, ListRowPresenter())
        rowsAdapter = ArrayObjectAdapter(selector)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val ep = item as? Episode ?: return@OnItemViewClickedListener
            play(episodes.indexOf(ep).coerceAtLeast(0), -1L)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (details == null) load()
    }

    override fun onResume() {
        super.onResume()
        // Al volver del reproductor: actualiza "Continuar" y las barras de progreso.
        if (details != null) {
            refreshActions()
            episodeAdapters.forEach { it.notifyArrayItemRangeChanged(0, it.size()) }
        }
    }

    private fun load() {
        val host = scraperHost
        host.setBackdrop(anime.image)
        host.showStatus(getString(R.string.loading_details))
        viewLifecycleOwner.lifecycleScope.launch {
            val scraped = host.scraper.scrapeDetails(anime.url)
            host.scraper.stop()
            val d = scraped.copy(
                title = anime.title.ifBlank { scraped.title },
                poster = scraped.poster.ifBlank { anime.image },
                backdrop = scraped.backdrop.ifBlank { anime.image }
            )
            if (anime.title.isBlank()) anime = anime.copy(title = d.title)
            if (anime.image.isBlank()) anime = anime.copy(image = d.poster)
            details = d
            host.hideStatus()
            host.setBackdrop(d.backdrop)
            showDetails(d)
        }
    }

    private fun showDetails(d: AnimeDetails) {
        val row = DetailsOverviewRow(d)
        row.isImageScaleUpAllowed = true
        row.actionsAdapter = actionsAdapter
        if (d.poster.isNotBlank()) {
            val w = resources.getDimensionPixelSize(R.dimen.poster_width)
            val h = resources.getDimensionPixelSize(R.dimen.poster_height)
            Glide.with(this)
                .load(imageModel(d.poster))
                .centerCrop()
                .into(object : CustomTarget<Drawable>(w, h) {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        row.imageDrawable = resource
                    }

                    override fun onLoadCleared(placeholder: Drawable?) = Unit
                })
        }

        rowsAdapter.clear()
        rowsAdapter.add(row)
        refreshActions()

        episodeAdapters.clear()
        episodes.chunked(EPISODES_PER_ROW).forEachIndexed { i, chunk ->
            val adapter = ArrayObjectAdapter(EpisodePresenter()).apply { addAll(0, chunk) }
            episodeAdapters += adapter
            val first = chunk.first().number ?: (i * EPISODES_PER_ROW + 1)
            val last = chunk.last().number ?: (i * EPISODES_PER_ROW + chunk.size)
            rowsAdapter.add(ListRow(HeaderItem(i + 1L, getString(R.string.row_episodes, first, last)), adapter))
        }
        if (episodes.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_episodes_details, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------- Botones

    private fun refreshActions() {
        if (details == null) return
        val (label1, label2) = playLabels()
        val list = ArrayList<Action>()
        list.add(Action(ACTION_PLAY, label1, label2))
        if (episodes.size > 1) {
            list.add(Action(ACTION_LATEST, getString(R.string.action_latest), episodes.last().label))
        }
        list.add(favoriteAction())
        actionsAdapter.clear()
        actionsAdapter.addAll(0, list)
    }

    private fun favoriteAction() = Action(
        ACTION_FAVORITE,
        getString(if (Store.isFavorite(anime.url)) R.string.action_fav_remove else R.string.action_fav_add)
    )

    /** Qué episodio toca: el que quedó a medias, el siguiente al último visto, o el primero. */
    private fun playTarget(): Pair<Int, Long> {
        val last = Store.lastForAnime(anime.url) ?: return 0 to -1L
        val i = episodes.indexOfFirst { it.url == last.episodeUrl }
        return when {
            i < 0 -> 0 to -1L
            !last.finished -> i to last.positionMs
            i + 1 < episodes.size -> (i + 1) to 0L
            else -> i to 0L
        }
    }

    private fun playLabels(): Pair<String, String> {
        if (episodes.isEmpty()) return getString(R.string.action_play) to ""
        val last = Store.lastForAnime(anime.url)
        val (i, start) = playTarget()
        val ep = episodes[i]
        return when {
            last == null || episodes.none { it.url == last.episodeUrl } -> getString(R.string.action_play) to ep.label
            ep.url == last.episodeUrl && start > 0 -> getString(R.string.action_continue) to "${ep.label} · ${formatTime(start)}"
            ep.url == last.episodeUrl -> getString(R.string.action_rewatch) to ep.label
            else -> getString(R.string.action_next) to ep.label
        }
    }

    private fun onAction(action: Action) {
        when (action.id) {
            ACTION_PLAY -> {
                val (i, start) = playTarget()
                play(i, start)
            }
            ACTION_LATEST -> play(episodes.lastIndex, 0L)
            ACTION_FAVORITE -> {
                val added = Store.toggleFavorite(anime)
                val index = actionsAdapter.indexOf(action)
                if (index >= 0) actionsAdapter.replace(index, favoriteAction())
                Toast.makeText(
                    requireContext(),
                    if (added) R.string.fav_added else R.string.fav_removed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun play(index: Int, startMs: Long) {
        // Sin lista de episodios (películas, etc.): se intenta reproducir la propia página.
        val list = episodes.ifEmpty { listOf(Episode(anime.title, anime.url, anime.image, null)) }
        PlaybackQueue.set(anime, list)
        startActivity(PlayerActivity.intent(requireContext(), index.coerceIn(0, list.lastIndex), startMs))
    }

    companion object {
        private const val ACTION_PLAY = 1L
        private const val ACTION_LATEST = 2L
        private const val ACTION_FAVORITE = 3L
        private const val EPISODES_PER_ROW = 50
    }
}

/** Título, géneros y sinopsis en la ficha. */
class DescriptionPresenter : AbstractDetailsDescriptionPresenter() {
    override fun onBindDescription(vh: AbstractDetailsDescriptionPresenter.ViewHolder, item: Any) {
        val d = item as? AnimeDetails ?: return
        vh.title.text = d.title
        val meta = buildList {
            if (d.genres.isNotEmpty()) add(d.genres.joinToString(" · "))
            if (d.episodes.isNotEmpty()) add("${d.episodes.size} episodios")
        }
        vh.subtitle.text = meta.joinToString("   |   ")
        vh.body.text = d.synopsis.ifBlank { "Sin sinopsis disponible." }
    }
}
