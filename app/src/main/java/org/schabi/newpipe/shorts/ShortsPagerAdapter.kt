package org.schabi.newpipe.shorts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ui.StyledPlayerView
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.PicassoHelper

/** One full-screen page per Short. The player is moved between pages by [ShortsActivity]. */
internal class ShortsPagerAdapter(
    initialItems: List<StreamInfoItem>,
    private val callbacks: Callbacks,
) : RecyclerView.Adapter<ShortsPagerAdapter.PageHolder>() {

    interface Callbacks {
        fun onPageTapped(position: Int)
        fun onChannelClicked(position: Int)
        fun onCommentsClicked(position: Int)
        fun onShareClicked(position: Int)
    }

    val items: MutableList<StreamInfoItem> = ArrayList(initialItems)

    /** Details loaded for a page (likes, ...), by URL. */
    private val infos = HashMap<String, StreamInfo>()

    fun addAll(newItems: List<StreamInfoItem>) {
        val known = items.mapTo(HashSet()) { it.url }
        val fresh = newItems.filter { known.add(it.url) }
        if (fresh.isNotEmpty()) {
            val start = items.size
            items.addAll(fresh)
            notifyItemRangeInserted(start, fresh.size)
        }
    }

    fun setInfo(position: Int, info: StreamInfo, holder: PageHolder?) {
        infos[items[position].url] = info
        holder?.bindInfo(info)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
        PageHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_short_page, parent, false))

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.bind(items[position])
        infos[items[position].url]?.let(holder::bindInfo)
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.playerView.player = null
    }

    inner class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val playerView: StyledPlayerView = view.findViewById(R.id.short_player)
        private val poster: ImageView = view.findViewById(R.id.short_poster)
        private val loading: ProgressBar = view.findViewById(R.id.short_loading)
        private val playIcon: ImageView = view.findViewById(R.id.short_play_icon)
        private val error: TextView = view.findViewById(R.id.short_error)
        private val like: TextView = view.findViewById(R.id.short_like)
        private val dislike: TextView = view.findViewById(R.id.short_dislike)
        private val title: TextView = view.findViewById(R.id.short_title)
        private val channelName: TextView = view.findViewById(R.id.short_channel_name)
        private val avatar: ImageView = view.findViewById(R.id.short_avatar)
        private val progress: ProgressBar = view.findViewById(R.id.short_progress)

        init {
            view.setOnClickListener { pos()?.let(callbacks::onPageTapped) }
            view.findViewById<View>(R.id.short_channel).setOnClickListener {
                pos()?.let(callbacks::onChannelClicked)
            }
            view.findViewById<View>(R.id.short_comments).setOnClickListener {
                pos()?.let(callbacks::onCommentsClicked)
            }
            view.findViewById<View>(R.id.short_share).setOnClickListener {
                pos()?.let(callbacks::onShareClicked)
            }
        }

        private fun pos(): Int? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }

        fun bind(item: StreamInfoItem) {
            title.text = item.name
            channelName.text = item.uploaderName.orEmpty()
            like.setText(R.string.yt_like)
            dislike.setText(R.string.yt_dislike)
            avatar.setImageResource(R.drawable.buddy)
            if (!item.uploaderAvatarUrl.isNullOrEmpty()) {
                PicassoHelper.loadAvatar(item.uploaderAvatarUrl).into(avatar)
            }
            PicassoHelper.loadThumbnail(item.thumbnailUrl).into(poster)
            showPoster()
            setProgress(0)
        }

        fun bindInfo(info: StreamInfo) {
            val context = itemView.context
            if (info.likeCount >= 0) {
                like.text = Localization.shortCount(context, info.likeCount)
            }
            if (info.dislikeCount >= 0) {
                dislike.text = Localization.shortCount(context, info.dislikeCount)
            }
            if (channelName.text.isNullOrEmpty()) {
                channelName.text = info.uploaderName.orEmpty()
            }
            if (!info.uploaderAvatarUrl.isNullOrEmpty()) {
                PicassoHelper.loadAvatar(info.uploaderAvatarUrl).into(avatar)
            }
        }

        /** Thumbnail and spinner while the video is not rendering yet. */
        fun showPoster() {
            poster.visibility = View.VISIBLE
            loading.visibility = View.VISIBLE
            playIcon.visibility = View.GONE
            error.visibility = View.GONE
        }

        fun showVideo() {
            poster.visibility = View.GONE
            loading.visibility = View.GONE
        }

        fun showError() {
            loading.visibility = View.GONE
            playIcon.visibility = View.GONE
            error.visibility = View.VISIBLE
        }

        fun showPaused(paused: Boolean) {
            playIcon.visibility = if (paused) View.VISIBLE else View.GONE
        }

        fun setProgress(permille: Int) {
            progress.progress = permille
        }
    }
}
