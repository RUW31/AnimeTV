package com.example.animetv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Biblioteca del sitio (o un género) en cuadrícula, cargando más páginas al bajar. */
class LibraryActivity : ScraperHostActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, LibraryFragment())
                .commit()
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"

        fun intent(context: Context, title: String, url: String): Intent =
            Intent(context, LibraryActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_URL, url)
    }
}

class LibraryFragment : VerticalGridSupportFragment() {

    private val gridAdapter = ArrayObjectAdapter(CardPresenter())
    private val seen = HashSet<String>()
    private var nextUrl: String? = null
    private var loading = false
    private var exhausted = false
    private val scraperHost get() = requireActivity() as LibraryActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = requireActivity().intent.getStringExtra(LibraryActivity.EXTRA_TITLE) ?: getString(R.string.menu_library)
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply { numberOfColumns = 6 }
        adapter = gridAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            Nav.open(requireActivity(), item)
        }
        setOnItemViewSelectedListener(OnItemViewSelectedListener { _, item, _, _ ->
            (item as? CardItem)?.let { scraperHost.setBackdrop(it.image) }
            // Al llegar a las dos últimas filas, pide la siguiente página.
            if (item != null && gridAdapter.indexOf(item) >= gridAdapter.size() - 12) loadMore()
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (gridAdapter.size() == 0) loadFirst()
    }

    private fun loadFirst() {
        val host = scraperHost
        val url = host.intent.getStringExtra(LibraryActivity.EXTRA_URL) ?: return
        loading = true
        host.showStatus(getString(R.string.loading_library))
        viewLifecycleOwner.lifecycleScope.launch {
            val page = host.scraper.scrapeListPage(url)
            loading = false
            if (!append(page)) {
                exhausted = true
                host.showStatus(getString(R.string.library_empty), spinner = false)
            } else {
                host.hideStatus()
            }
        }
    }

    private fun loadMore() {
        if (loading || exhausted) return
        loading = true
        val host = scraperHost
        viewLifecycleOwner.lifecycleScope.launch {
            val next = nextUrl
            val page = if (next != null) host.scraper.scrapeListPage(next, timeoutMs = 12_000)
            else host.scraper.loadMoreInPlace()
            loading = false
            if (!append(page)) exhausted = true
        }
    }

    /** Agrega solo los animes nuevos. Devuelve false si no llegó nada nuevo. */
    private fun append(page: ListPage): Boolean {
        nextUrl = page.nextUrl
        val fresh = page.items.filter { seen.add(it.url) }
        if (fresh.isEmpty()) return false
        gridAdapter.addAll(gridAdapter.size(), fresh)
        Catalog.remember(fresh)
        return true
    }
}
