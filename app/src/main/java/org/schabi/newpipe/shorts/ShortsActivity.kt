package org.schabi.newpipe.shorts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.video.VideoSize
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.resolver.PlayerQualityResolver
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.external_communication.ShareUtils
import com.google.android.exoplayer2.Player as ExoPlayerApi

/**
 * YouTube-style Shorts: full-screen vertical videos, swipe up for the next one. A single
 * ExoPlayer is moved to whichever page is on screen; streams are resolved with the same
 * [PlayerDataSource] / [VideoPlaybackResolver] as the main player, so every service and
 * delivery method the app supports plays here too. Each Short loops until swiped away.
 */
class ShortsActivity : AppCompatActivity(), ShortsPagerAdapter.Callbacks {

    private lateinit var pager: ViewPager2
    private lateinit var adapter: ShortsPagerAdapter
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var resolver: VideoPlaybackResolver

    private val disposables = CompositeDisposable()
    private var loading: Disposable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val historyRecordManager by lazy { HistoryRecordManager(this) }

    private var currentPosition = RecyclerView.NO_POSITION
    private var currentView: StyledPlayerView? = null
    private var resumeOnStart = true

    private val progressUpdater = object : Runnable {
        override fun run() {
            val duration = exoPlayer.duration
            if (duration > 0) {
                currentHolder()?.setProgress(
                    (exoPlayer.currentPosition * PROGRESS_MAX / duration).toInt())
            }
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private val playerListener = object : ExoPlayerApi.Listener {
        override fun onRenderedFirstFrame() {
            currentHolder()?.showVideo()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            currentHolder()?.showPaused(!isPlaying && !exoPlayer.playWhenReady)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Could not play Short", error)
            currentHolder()?.showError()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // vertical videos fill the screen like on YouTube, others fit inside it
            currentView?.resizeMode = if (videoSize.width in 1 until videoSize.height) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // the Shorts player is always dark, whatever the app theme is
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        super.onCreate(savedInstanceState)

        val (items, startIndex) = ShortsQueue.take()
        if (items.isEmpty()) {
            // e.g. the process was restarted: there is nothing left to show
            finish()
            return
        }

        setContentView(R.layout.activity_shorts)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<android.view.View>(R.id.shorts_back).setOnClickListener { finish() }

        val dataSource = PlayerDataSource(this, DownloaderImpl.USER_AGENT,
            DefaultBandwidthMeter.Builder(this).build())
        resolver = VideoPlaybackResolver(this, dataSource, PlayerQualityResolver(this) { true })
        exoPlayer = ExoPlayer.Builder(this,
            DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer.repeatMode = ExoPlayerApi.REPEAT_MODE_ONE
        exoPlayer.addListener(playerListener)

        adapter = ShortsPagerAdapter(items, this)
        pager = findViewById(R.id.shorts_pager)
        pager.offscreenPageLimit = 1
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = play(position)
        })
        pager.setCurrentItem(startIndex, false)
        pager.post { play(pager.currentItem) }
    }

    override fun onStart() {
        super.onStart()
        if (!::exoPlayer.isInitialized) {
            return
        }
        if (resumeOnStart && currentPosition >= 0) {
            exoPlayer.play()
        }
        handler.post(progressUpdater)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(progressUpdater)
        if (::exoPlayer.isInitialized) {
            resumeOnStart = exoPlayer.playWhenReady
            exoPlayer.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        loading?.dispose()
        disposables.dispose()
        if (::exoPlayer.isInitialized) {
            exoPlayer.removeListener(playerListener)
            exoPlayer.release()
        }
    }

    // Playback

    private fun play(position: Int) {
        if (position == currentPosition || position !in adapter.items.indices) {
            return
        }
        currentPosition = position
        loading?.dispose()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        attachPlayer(position, attempts = 5)

        val item = adapter.items[position]
        loading = ExtractorHelper.getStreamInfo(item.serviceId, item.url, false)
            .subscribeOn(Schedulers.io())
            .map { info -> Resolved(info, resolver.resolve(info)) }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ resolved ->
                if (position != currentPosition) {
                    return@subscribe
                }
                adapter.setInfo(position, resolved.info, currentHolder())
                val source = resolved.source
                if (source == null) {
                    currentHolder()?.showError()
                } else {
                    exoPlayer.setMediaSource(source)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    recordView(resolved.info)
                }
                loadMoreIfNeeded(position, resolved.info)
            }, { error ->
                Log.e(TAG, "Could not load Short ${item.url}", error)
                if (position == currentPosition) {
                    currentHolder()?.showError()
                }
            })
    }

    /** Moves the player to the page at [position], once that page is laid out. */
    private fun attachPlayer(position: Int, attempts: Int) {
        val holder = holderAt(position)
        if (holder == null) {
            if (attempts > 0) {
                pager.post { if (position == currentPosition) attachPlayer(position, attempts - 1) }
            }
            return
        }
        holder.showPoster()
        StyledPlayerView.switchTargetView(exoPlayer, currentView, holder.playerView)
        currentView = holder.playerView
    }

    private fun recordView(info: StreamInfo) {
        disposables.add(historyRecordManager.onViewed(info)
            .subscribeOn(Schedulers.io())
            .onErrorComplete()
            .subscribe())
    }

    /** Near the end of the list, continues with Shorts related to the current one. */
    private fun loadMoreIfNeeded(position: Int, info: StreamInfo) {
        if (position < adapter.items.size - PRELOAD_DISTANCE) {
            return
        }
        val related = info.relatedItems.orEmpty()
            .filterIsInstance<StreamInfoItem>()
            .filter { it.isShortFormContent || it.duration in 1..MAX_SHORT_SECONDS }
        adapter.addAll(related)
    }

    private fun holderAt(position: Int): ShortsPagerAdapter.PageHolder? =
        (pager.getChildAt(0) as? RecyclerView)
            ?.findViewHolderForAdapterPosition(position) as? ShortsPagerAdapter.PageHolder

    private fun currentHolder(): ShortsPagerAdapter.PageHolder? = holderAt(currentPosition)

    private class Resolved(val info: StreamInfo, val source: MediaSource?)

    // Page actions

    override fun onPageTapped(position: Int) {
        if (position != currentPosition) {
            return
        }
        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
        currentHolder()?.showPaused(!exoPlayer.playWhenReady)
    }

    override fun onChannelClicked(position: Int) {
        val item = adapter.items[position]
        if (!item.uploaderUrl.isNullOrEmpty()) {
            NavigationHelper.openChannelFragmentUsingIntent(this, item.serviceId,
                item.uploaderUrl, item.uploaderName.orEmpty())
        }
    }

    override fun onCommentsClicked(position: Int) {
        // the comments live on the regular video page
        val item = adapter.items[position]
        exoPlayer.pause()
        NavigationHelper.openVideoDetail(this, item.serviceId, item.url, item.name, null, false)
    }

    override fun onShareClicked(position: Int) {
        val item = adapter.items[position]
        ShareUtils.shareText(this, item.name, item.url, item.thumbnailUrl)
    }

    companion object {
        private const val TAG = "ShortsActivity"
        private const val PROGRESS_MAX = 1000L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val PRELOAD_DISTANCE = 3
        private const val MAX_SHORT_SECONDS = 60L

        /** Opens the Shorts player on [items], starting at [startIndex]. */
        @JvmStatic
        fun open(context: Context, items: List<StreamInfoItem>, startIndex: Int) {
            ShortsQueue.set(items, startIndex)
            context.startActivity(Intent(context, ShortsActivity::class.java))
        }
    }
}
