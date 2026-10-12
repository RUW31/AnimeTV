package com.example.animetv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Búsqueda con teclado en pantalla o con el micrófono del control. */
class SearchActivity : ScraperHostActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SearchFragment())
                .commit()
        }
    }
}

@Suppress("DEPRECATION") // Reconocimiento de voz del sistema (no requiere permiso de micrófono).
class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private var searchJob: Job? = null
    private var lastQuery = ""
    private val scraperHost get() = requireActivity() as SearchActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            Nav.open(requireActivity(), item)
        })
        setSpeechRecognitionCallback {
            try {
                startActivityForResult(recognizerIntent, REQUEST_SPEECH)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_SPEECH && resultCode == Activity.RESULT_OK && data != null) {
            setSearchQuery(data, true)
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String): Boolean {
        schedule(newQuery, 1_200)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        schedule(query, 0)
        return true
    }

    private fun schedule(raw: String, delayMs: Long) {
        val q = raw.trim()
        if (q == lastQuery) return
        searchJob?.cancel()
        if (q.length < 2) {
            lastQuery = q
            rowsAdapter.clear()
            return
        }
        searchJob = lifecycleScope.launch {
            delay(delayMs)
            lastQuery = q
            runSearch(q)
        }
    }

    private suspend fun runSearch(q: String) {
        rowsAdapter.clear()
        val local = Catalog.search(q)
        if (local.isNotEmpty()) addRow(ROW_LOCAL, getString(R.string.search_local), local)

        val url = SiteConfig.searchUrl(Store.siteMap(), q)
        if (url == null) {
            if (local.isEmpty()) Toast.makeText(requireContext(), R.string.search_missing, Toast.LENGTH_LONG).show()
            return
        }
        val host = scraperHost
        val results = host.scraper.scrapeListPage(url, timeoutMs = 12_000).items
        host.scraper.stop()
        Catalog.remember(results)
        if (results.isNotEmpty()) {
            addRow(ROW_SITE, getString(R.string.search_site), results, position = 0)
        } else if (local.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.search_none, q), Toast.LENGTH_SHORT).show()
        }
    }

    private fun addRow(id: Long, name: String, items: List<Anime>, position: Int = rowsAdapter.size()) {
        val adapter = ArrayObjectAdapter(CardPresenter()).apply { addAll(0, items) }
        rowsAdapter.add(position, ListRow(HeaderItem(id, name), adapter))
    }

    companion object {
        private const val REQUEST_SPEECH = 41
        private const val ROW_SITE = 0L
        private const val ROW_LOCAL = 1L
    }
}
