package com.example.animetv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reproductor nativo para enlaces DIRECTOS de video (.m3u8 / .mp4), por ejemplo de
 * tu propio servidor. Reproduce la cola de [PlaybackQueue]:
 *  1. Reproduce el enlace con ExoPlayer.
 *  2. Guarda el progreso cada 5 s y al final pasa solo al siguiente episodio.
 *
 * Control remoto:
 *  - OK: pausa / reanuda                - ◀ / ▶: -10 s / +10 s
 *  - Canal +/- o Siguiente/Anterior: cambia de episodio
 *  - Atrás con controles visibles: los oculta; si no, sale
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ScraperHostActivity(R.layout.activity_player) {

    private lateinit var playerView: PlayerView
    private lateinit var titleBar: View
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private var player: ExoPlayer? = null
    private var index = 0
    private var loadJob: Job? = null
    private var progressJob: Job? = null
    private var countdownJob: Job? = null

    private val episodes: List<Episode> get() = PlaybackQueue.episodes
    private val hasNext: Boolean get() = index + 1 < episodes.size

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = findViewById(R.id.player_view)
        titleBar = findViewById(R.id.title_bar)
        titleText = findViewById(R.id.player_title)
        subtitleText = findViewById(R.id.player_subtitle)

        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            titleBar.visibility = visibility
        })

        if (episodes.isEmpty()) {
            finish()
            return
        }
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, episodes.lastIndex)
        playEpisode(start, intent.getLongExtra(EXTRA_START_MS, -1L))
    }

    private fun playEpisode(target: Int, startMs: Long) {
        countdownJob?.cancel()
        loadJob?.cancel()
        saveProgress()
        releasePlayer()

        index = target
        val ep = episodes[target]
        val anime = PlaybackQueue.anime
        titleText.text = anime?.title?.takeIf { it.isNotBlank() } ?: ep.title
        subtitleText.text = if (ep.number != null) "${ep.label} · ${ep.title}" else ep.title
        showStatus(getString(R.string.searching_video_ep, ep.label))

        loadJob = lifecycleScope.launch {
            // Este reproductor es solo para enlaces directos (.m3u8 / .mp4), p. ej. de tu propio servidor.
            // Las páginas web se abren en WebPlayerActivity con su propio reproductor.
            if (!Playback.isDirectMedia(ep.url)) {
                showStatus(
                    getString(if (hasNext) R.string.video_not_found_next else R.string.video_not_found),
                    spinner = false
                )
                return@launch
            }
            val resume = if (startMs >= 0) startMs
            else Store.progress(ep.url)?.takeIf { !it.finished }?.positionMs ?: 0L
            startPlayback(MediaHit(ep.url, emptyMap()), resume)
        }
    }

    private fun startPlayback(hit: MediaHit, resumeMs: Long) {
        // Reenviamos solo las cabeceras que importan para los CDN de video.
        val headers = HashMap<String, String>()
        hit.headers.forEach { (key, value) ->
            if (key.equals("Referer", ignoreCase = true) || key.equals("Origin", ignoreCase = true)) {
                headers[key] = value
            }
        }
        CookieManager.getInstance().getCookie(hit.url)?.let { headers["Cookie"] = it }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(scraper.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEpisodeEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                showStatus(getString(R.string.playback_error, error.errorCodeName), spinner = false)
            }
        })

        val ep = episodes[index]
        val mediaItem = MediaItem.Builder()
            .setUri(hit.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(PlaybackQueue.anime?.title ?: ep.title)
                    .setSubtitle(ep.label)
                    .build()
            )
            .apply { if (hit.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()

        if (resumeMs > 0) exo.setMediaItem(mediaItem, resumeMs) else exo.setMediaItem(mediaItem)
        exo.prepare()
        exo.playWhenReady = true

        player = exo
        playerView.player = exo
        playerView.isVisible = true
        hideStatus()
        playerView.requestFocus()
        if (resumeMs > 5_000) {
            Toast.makeText(this, getString(R.string.resume_from, formatTime(resumeMs)), Toast.LENGTH_SHORT).show()
        }

        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (true) {
                delay(5_000)
                saveProgress()
            }
        }
    }

    /** Fin del episodio: cuenta regresiva de 5 s y pasa al siguiente. */
    private fun onEpisodeEnded() {
        saveProgress()
        if (!hasNext) {
            showStatus(getString(R.string.series_finished), spinner = false)
            return
        }
        val next = episodes[index + 1]
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            for (s in 5 downTo 1) {
                showStatus(getString(R.string.next_episode_countdown, next.label, s), spinner = false)
                delay(1_000)
            }
            playEpisode(index + 1, 0L)
        }
    }

    private fun saveProgress() {
        val p = player ?: return
        val ep = episodes.getOrNull(index) ?: return
        val duration = p.duration
        if (duration <= 0) return
        val anime = PlaybackQueue.anime
        Store.saveProgress(
            WatchProgress(
                episodeUrl = ep.url,
                episodeTitle = ep.title,
                episodeNumber = ep.number,
                animeUrl = anime?.url.orEmpty(),
                animeTitle = anime?.title?.takeIf { it.isNotBlank() } ?: ep.title,
                animeImage = anime?.image?.takeIf { it.isNotBlank() } ?: ep.image,
                positionMs = p.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun releasePlayer() {
        progressJob?.cancel()
        player?.release()
        player = null
        playerView.player = null
        playerView.isVisible = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val waitingNext = countdownJob?.isActive == true
            val failed = player == null && loadJob?.isActive == false
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> if (hasNext) {
                    playEpisode(index + 1, 0L)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (index > 0) {
                    playEpisode(index - 1, 0L)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if ((waitingNext || failed) && hasNext) {
                    playEpisode(index + 1, 0L)
                    return true
                }
            }
        }

        val p = player ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controlsVisible = playerView.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> if (controlsVisible) {
                    playerView.hideController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!controlsVisible) {
                    p.seekBack()
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controlsVisible) {
                    p.seekForward()
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controlsVisible) {
                    if (p.isPlaying) p.pause() else p.play()
                    playerView.showController()
                    return true
                }
            }
        }
        // El resto (navegar por los controles, teclas multimedia) lo maneja PlayerView.
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        saveProgress()
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_START_MS = "extra_start_ms"

        fun intent(context: Context, index: Int, startMs: Long = -1L): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_START_MS, startMs)
    }
}
