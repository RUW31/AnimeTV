package com.example.animetv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.CookieManager
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
import kotlinx.coroutines.launch

/**
 * 1. Carga la página del episodio en el WebView oculto y captura el .m3u8/.mp4.
 * 2. Apaga el WebView (para que el reproductor web no suene por detrás).
 * 3. Reproduce con ExoPlayer reenviando Referer, User-Agent y cookies.
 *
 * Control remoto:
 *  - Centro/OK: pausa / reanuda      - Izquierda/Derecha: -10 s / +10 s
 *  - Atrás con controles visibles: los oculta; si no, sale
 *  - Teclas multimedia (play/pause, FF, RW): funcionan siempre
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ScraperHostActivity(R.layout.activity_player) {

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = findViewById(R.id.player_view)

        val pageUrl = intent.getStringExtra(EXTRA_URL)
        if (pageUrl == null) {
            finish()
            return
        }

        showStatus(getString(R.string.searching_video))
        lifecycleScope.launch {
            val hit = scraper.sniffMedia(pageUrl)
            scraper.stop()
            if (hit == null) {
                showStatus(getString(R.string.video_not_found), spinner = false)
            } else {
                startPlayback(hit)
            }
        }
    }

    private fun startPlayback(hit: MediaHit) {
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
            override fun onPlayerError(error: PlaybackException) {
                showStatus(getString(R.string.playback_error, error.errorCodeName), spinner = false)
            }
        })

        val mediaItem = MediaItem.Builder()
            .setUri(hit.url)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(intent.getStringExtra(EXTRA_TITLE)).build()
            )
            .apply { if (hit.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()

        exo.setMediaItem(mediaItem)
        exo.prepare()
        exo.playWhenReady = true

        player = exo
        playerView.player = exo
        playerView.isVisible = true
        hideStatus()
        playerView.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"

        fun intent(context: Context, pageUrl: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, pageUrl)
                .putExtra(EXTRA_TITLE, title)
    }
}
