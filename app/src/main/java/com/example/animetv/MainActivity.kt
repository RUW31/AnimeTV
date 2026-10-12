package com.example.animetv

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Pantalla principal: filas de animes agrupadas por sección de la portada del sitio. */
class MainActivity : ScraperHostActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MainFragment())
                .commit()
        }
    }
}

class MainFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val scraperHost get() = requireActivity() as MainActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        headersState = BrowseSupportFragment.HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val anime = item as? Anime ?: return@OnItemViewClickedListener
            val ctx = requireContext()
            // Las portadas suelen mezclar fichas de anime con "últimos episodios".
            if (SiteConfig.isEpisodeUrl(anime.url)) {
                startActivity(PlayerActivity.intent(ctx, anime.url, anime.title))
            } else {
                startActivity(EpisodesActivity.intent(ctx, anime))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (rowsAdapter.size() == 0) loadCatalog()
    }

    private fun loadCatalog() {
        val host = scraperHost
        host.showStatus(getString(R.string.loading_catalog))
        viewLifecycleOwner.lifecycleScope.launch {
            val items = host.scraper.scrapeCatalog()
            host.scraper.stop()
            if (items.isEmpty()) {
                host.showStatus(getString(R.string.no_results), spinner = false)
                return@launch
            }
            host.hideStatus()
            items.groupBy { it.section }.entries.forEachIndexed { index, (section, list) ->
                val rowAdapter = ArrayObjectAdapter(CardPresenter()).apply { addAll(0, list) }
                rowsAdapter.add(ListRow(HeaderItem(index.toLong(), section), rowAdapter))
            }
        }
    }
}
