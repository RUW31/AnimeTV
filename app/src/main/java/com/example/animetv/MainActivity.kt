package com.example.animetv

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Inicio: Continuar viendo, Favoritos, Explorar (Biblioteca/Buscar), secciones del sitio y Géneros. */
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
    private val continueAdapter = ArrayObjectAdapter(CardPresenter())
    private val favoritesAdapter = ArrayObjectAdapter(CardPresenter())
    private lateinit var continueRow: ListRow
    private lateinit var favoritesRow: ListRow
    private var loaded = false
    private val scraperHost get() = requireActivity() as MainActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = requireContext()
        continueRow = ListRow(HeaderItem(ROW_CONTINUE, getString(R.string.row_continue)), continueAdapter)
        favoritesRow = ListRow(HeaderItem(ROW_FAVORITES, getString(R.string.row_favorites)), favoritesAdapter)

        title = getString(R.string.app_name)
        headersState = BrowseSupportFragment.HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(ctx, R.color.header_bg)
        searchAffordanceColor = ContextCompat.getColor(ctx, R.color.brand)
        adapter = rowsAdapter

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            Nav.open(requireActivity(), item)
        }
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            (item as? CardItem)?.let { scraperHost.setBackdrop(it.image) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!loaded) loadHome()
    }

    override fun onResume() {
        super.onResume()
        // Al volver del reproductor o de una ficha, actualiza progreso y favoritos.
        if (loaded) refreshPersonalRows()
    }

    private fun loadHome() {
        val host = scraperHost
        host.showStatus(getString(R.string.loading_catalog))
        viewLifecycleOwner.lifecycleScope.launch {
            val home = host.scraper.scrapeHome()
            host.scraper.stop()
            Store.saveSiteMap(home.siteMap)
            Catalog.remember(home.catalog)
            buildSiteRows(home)
            loaded = true
            refreshPersonalRows()
            if (home.catalog.isEmpty()) {
                host.showStatus(getString(R.string.no_results), spinner = false)
            } else {
                host.hideStatus()
                home.catalog.firstOrNull()?.let { host.setBackdrop(it.image) }
            }
        }
    }

    private fun buildSiteRows(home: Home) {
        val explore = ArrayObjectAdapter(MenuPresenter()).apply {
            add(MenuCard(MenuCard.Kind.LIBRARY, getString(R.string.menu_library), R.drawable.ic_library, home.siteMap.libraryUrl))
            add(MenuCard(MenuCard.Kind.SEARCH, getString(R.string.menu_search), R.drawable.ic_search))
        }
        rowsAdapter.add(ListRow(HeaderItem(ROW_EXPLORE, getString(R.string.row_explore)), explore))

        var id = ROW_SITE_START
        home.catalog.groupBy { it.section }.forEach { (section, items) ->
            val rowAdapter = ArrayObjectAdapter(CardPresenter()).apply { addAll(0, items) }
            rowsAdapter.add(ListRow(HeaderItem(id++, section), rowAdapter))
        }

        if (home.siteMap.genres.isNotEmpty()) {
            val genres = ArrayObjectAdapter(MenuPresenter()).apply {
                addAll(0, home.siteMap.genres.map { MenuCard(MenuCard.Kind.GENRE, it.name, R.drawable.ic_genre, it.url) })
            }
            rowsAdapter.add(ListRow(HeaderItem(ROW_GENRES, getString(R.string.row_genres)), genres))
        }
    }

    private fun refreshPersonalRows() {
        syncRow(continueRow, continueAdapter, Store.continueWatching().map { ContinueItem(it) }, position = 0)
        val favPosition = if (rowsAdapter.indexOf(continueRow) >= 0) 1 else 0
        syncRow(favoritesRow, favoritesAdapter, Store.favorites(), position = favPosition)
    }

    /** Muestra, actualiza u oculta una fila personal sin reconstruir todo (no se pierde el foco). */
    private fun syncRow(row: ListRow, adapter: ArrayObjectAdapter, items: List<Any>, position: Int) {
        val index = rowsAdapter.indexOf(row)
        if (items.isEmpty()) {
            if (index >= 0) rowsAdapter.removeItems(index, 1)
            return
        }
        if (adapter.unmodifiableList<Any>() != items) {
            adapter.clear()
            adapter.addAll(0, items)
        }
        if (index < 0) rowsAdapter.add(position, row)
    }

    companion object {
        private const val ROW_CONTINUE = 0L
        private const val ROW_FAVORITES = 1L
        private const val ROW_EXPLORE = 2L
        private const val ROW_GENRES = 3L
        private const val ROW_SITE_START = 10L
    }
}
