package com.example.animetv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Cuadrícula de episodios de un anime. */
class EpisodesActivity : ScraperHostActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, EpisodesFragment())
                .commit()
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_IMAGE = "extra_image"

        fun intent(context: Context, anime: Anime): Intent =
            Intent(context, EpisodesActivity::class.java)
                .putExtra(EXTRA_TITLE, anime.title)
                .putExtra(EXTRA_URL, anime.url)
                .putExtra(EXTRA_IMAGE, anime.image)
    }
}

class EpisodesFragment : VerticalGridSupportFragment() {

    private val gridAdapter = ArrayObjectAdapter(CardPresenter())
    private val scraperHost get() = requireActivity() as EpisodesActivity
    private val animeTitle get() = requireActivity().intent.getStringExtra(EpisodesActivity.EXTRA_TITLE).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = animeTitle
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM).apply {
            numberOfColumns = 5
        }
        adapter = gridAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val episode = item as? Episode ?: return@OnItemViewClickedListener
            startActivity(
                PlayerActivity.intent(requireContext(), episode.url, "$animeTitle · ${episode.title}")
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (gridAdapter.size() == 0) loadEpisodes()
    }

    private fun loadEpisodes() {
        val host = scraperHost
        val url = host.intent.getStringExtra(EpisodesActivity.EXTRA_URL) ?: return
        val poster = host.intent.getStringExtra(EpisodesActivity.EXTRA_IMAGE).orEmpty()

        host.showStatus(getString(R.string.loading_episodes))
        viewLifecycleOwner.lifecycleScope.launch {
            val episodes = host.scraper.scrapeEpisodes(url)
            host.scraper.stop()
            if (episodes.isEmpty()) {
                host.showStatus(getString(R.string.no_episodes), spinner = false)
                return@launch
            }
            host.hideStatus()
            // Si el episodio no trae miniatura, usa el póster del anime.
            gridAdapter.addAll(0, episodes.map { if (it.image.isBlank()) it.copy(image = poster) else it })
        }
    }
}
