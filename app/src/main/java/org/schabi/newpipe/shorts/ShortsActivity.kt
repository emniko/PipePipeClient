package org.schabi.newpipe.shorts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
import kotlin.math.abs
import com.google.android.exoplayer2.Player as ExoPlayerApi

/**
 * YouTube-style Shorts: full-screen vertical videos, swipe up for the next one.
 *
 * Swiping is instant because the neighbours are preloaded: the Short on screen and the ones
 * right before and after it each have their own player, prepared and showing their first frame
 * while paused; swiping only switches which of them plays. The details of the Short after the
 * next one are fetched ahead too. Players further away are released.
 *
 * Streams are resolved with the same [PlayerDataSource] / [VideoPlaybackResolver] as the main
 * player, so every service and delivery method the app supports plays here too. Each Short
 * loops until swiped away.
 */
class ShortsActivity : AppCompatActivity(), ShortsPagerAdapter.Callbacks {

    private lateinit var pager: ViewPager2
    private lateinit var adapter: ShortsPagerAdapter
    private lateinit var dataSource: PlayerDataSource

    /** Prepared players by page position: the current page and its direct neighbours. */
    private val slots = HashMap<Int, Slot>()

    private val disposables = CompositeDisposable()
    private val handler = Handler(Looper.getMainLooper())
    private val historyRecordManager by lazy { HistoryRecordManager(this) }

    private var currentPosition = RecyclerView.NO_POSITION
    private var resumeOnStart = true

    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = slots[currentPosition]?.player
            if (player != null && player.duration > 0) {
                holderAt(currentPosition)?.setProgress(
                    (player.currentPosition * PROGRESS_MAX / player.duration).toInt())
            }
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
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
        findViewById<View>(R.id.shorts_back).setOnClickListener { finish() }

        dataSource = PlayerDataSource(this, DownloaderImpl.USER_AGENT,
            DefaultBandwidthMeter.Builder(this).build())

        adapter = ShortsPagerAdapter(items, this)
        pager = findViewById(R.id.shorts_pager)
        pager.offscreenPageLimit = 1
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = select(position)
        })
        pager.setCurrentItem(startIndex, false)
        pager.post { select(pager.currentItem) }
    }

    override fun onStart() {
        super.onStart()
        if (!::adapter.isInitialized) {
            return
        }
        if (resumeOnStart) {
            slots[currentPosition]?.player?.playWhenReady = true
        }
        handler.post(progressUpdater)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(progressUpdater)
        slots[currentPosition]?.player?.let {
            resumeOnStart = it.playWhenReady
            it.playWhenReady = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        disposables.dispose()
        slots.values.forEach(Slot::release)
        slots.clear()
    }

    // Playback

    /** Plays the page at [position] and prepares its neighbours. */
    private fun select(position: Int) {
        if (position == currentPosition || position !in adapter.items.indices) {
            return
        }
        currentPosition = position
        slots.values.forEach { it.player.playWhenReady = false }

        val slot = slots.getOrPut(position) { createSlot(position) }
        slot.player.playWhenReady = true
        holderAt(position)?.let { slot.showOn(it) }
        slot.info?.let { onCurrentLoaded(slot, it) }

        preloadAround(position)
    }

    private fun preloadAround(position: Int) {
        // neighbours get a prepared player, the one after the next only its details
        for (neighbour in intArrayOf(position + 1, position - 1)) {
            if (neighbour in adapter.items.indices && neighbour !in slots) {
                slots[neighbour] = createSlot(neighbour)
            }
        }
        (position + 2).takeIf { it in adapter.items.indices }?.let { ahead ->
            val item = adapter.items[ahead]
            disposables.add(ExtractorHelper.getStreamInfo(item.serviceId, item.url, false)
                .subscribeOn(Schedulers.io())
                .subscribe({ }, { }))
        }
        // release everything that is not on screen or next to it
        slots.keys.filter { abs(it - position) > 1 }.forEach { far ->
            holderAt(far)?.playerView?.player = null
            slots.remove(far)?.release()
        }
    }

    private fun createSlot(position: Int): Slot = Slot(position).also { slot ->
        slot.load()
        holderAt(position)?.let { slot.showOn(it) }
    }

    /** The current Short's details are known: count the view, extend the list if needed. */
    private fun onCurrentLoaded(slot: Slot, info: StreamInfo) {
        if (!slot.viewRecorded) {
            slot.viewRecorded = true
            disposables.add(historyRecordManager.onViewed(info)
                .subscribeOn(Schedulers.io())
                .onErrorComplete()
                .subscribe())
        }
        loadMoreIfNeeded(slot.position, info)
    }

    /** Near the end of the list, continues with Shorts related to the current one. */
    private fun loadMoreIfNeeded(position: Int, info: StreamInfo) {
        if (position < adapter.items.size - PRELOAD_DISTANCE) {
            return
        }
        val related = info.relatedItems.orEmpty()
            .filterIsInstance<StreamInfoItem>()
            .filter { it.isShortFormContent || it.duration in 1..MAX_SHORT_SECONDS }
        val sizeBefore = adapter.items.size
        adapter.addAll(related)
        if (adapter.items.size > sizeBefore) {
            preloadAround(currentPosition)
        }
    }

    private fun holderAt(position: Int): ShortsPagerAdapter.PageHolder? =
        (pager.getChildAt(0) as? RecyclerView)
            ?.findViewHolderForAdapterPosition(position) as? ShortsPagerAdapter.PageHolder

    private class Resolved(val info: StreamInfo, val source: MediaSource?)

    /** One prepared Short: its own player, buffering in the background until it is shown. */
    private inner class Slot(val position: Int) : ExoPlayerApi.Listener {
        val player: ExoPlayer = ExoPlayer.Builder(this@ShortsActivity,
            DefaultRenderersFactory(this@ShortsActivity).setEnableDecoderFallback(true))
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.repeatMode = ExoPlayerApi.REPEAT_MODE_ONE
                it.playWhenReady = false
                it.addListener(this)
            }

        var info: StreamInfo? = null
        var viewRecorded = false
        private var loading: Disposable? = null
        private var rendered = false
        private var failed = false
        private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

        fun load() {
            val item = adapter.items[position]
            // a resolver per Short, so preloading several at once never shares state
            val resolver = VideoPlaybackResolver(this@ShortsActivity, dataSource,
                PlayerQualityResolver(this@ShortsActivity) { true })
            loading = ExtractorHelper.getStreamInfo(item.serviceId, item.url, false)
                .subscribeOn(Schedulers.io())
                .map { info -> Resolved(info, resolver.resolve(info)) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ resolved ->
                    info = resolved.info
                    adapter.setInfo(position, resolved.info, holderAt(position))
                    val source = resolved.source
                    if (source == null) {
                        fail()
                    } else {
                        player.setMediaSource(source)
                        player.prepare()
                    }
                    if (position == currentPosition) {
                        onCurrentLoaded(this, resolved.info)
                    }
                }, { error ->
                    Log.e(TAG, "Could not load Short ${item.url}", error)
                    fail()
                })
        }

        /** Shows this Short's state on its page and lets the page render the video. */
        fun showOn(holder: ShortsPagerAdapter.PageHolder) {
            holder.playerView.resizeMode = resizeMode
            if (holder.playerView.player !== player) {
                holder.playerView.player = player
            }
            when {
                failed -> holder.showError()
                rendered -> holder.showVideo()
                else -> holder.showPoster()
            }
            holder.showPaused(position == currentPosition && !player.playWhenReady)
        }

        private fun fail() {
            failed = true
            holderAt(position)?.showError()
        }

        override fun onRenderedFirstFrame() {
            // also happens while paused, so preloaded Shorts show their first frame right away
            rendered = true
            holderAt(position)?.showVideo()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (position == currentPosition) {
                holderAt(position)?.showPaused(!isPlaying && !player.playWhenReady)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Could not play Short", error)
            fail()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // vertical videos fill the screen like on YouTube, others fit inside it
            resizeMode = if (videoSize.width in 1 until videoSize.height) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            holderAt(position)?.playerView?.resizeMode = resizeMode
        }

        fun release() {
            loading?.dispose()
            player.removeListener(this)
            player.release()
        }
    }

    // Page callbacks

    override fun onPageBound(holder: ShortsPagerAdapter.PageHolder, position: Int) {
        val slot = slots[position]
        if (slot == null) {
            holder.playerView.player = null
        } else {
            slot.showOn(holder)
        }
    }

    override fun onPageTapped(position: Int) {
        val player = slots[position]?.player ?: return
        if (position != currentPosition) {
            return
        }
        player.playWhenReady = !player.playWhenReady
        holderAt(position)?.showPaused(!player.playWhenReady)
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
        slots[currentPosition]?.player?.playWhenReady = false
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
