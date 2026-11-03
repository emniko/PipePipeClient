package org.schabi.newpipe.fragments.detail

import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.local.subscription.SubscriptionManager

/**
 * The YouTube-style Subscribe pill next to the channel on the watch page.
 *
 * It subscribes to the channel shown in the channel row (the sub-channel when there is one, as
 * on PeerTube) using only what the stream info provides; the channel's videos are fetched by the
 * next feed refresh like for any other subscription.
 */
class DetailSubscribeButton(private val button: TextView) {
    private val context get() = button.context
    private val subscriptionManager by lazy { SubscriptionManager(context) }
    private val disposables = CompositeDisposable()
    private var subscribed = false

    fun hide() {
        disposables.clear()
        button.visibility = View.GONE
        button.setOnClickListener(null)
    }

    fun bind(info: StreamInfo) {
        hide()
        val useSubChannel = !info.subChannelUrl.isNullOrEmpty()
        val url = if (useSubChannel) info.subChannelUrl else info.uploaderUrl
        if (url.isNullOrEmpty()) {
            return
        }
        val name = (if (useSubChannel) info.subChannelName else info.uploaderName).orEmpty()
        val avatarUrl = if (useSubChannel) info.subChannelAvatarUrl else info.uploaderAvatarUrl
        val subscriberCount = if (useSubChannel) -1L else info.uploaderSubscriberCount
        val serviceId = info.serviceId

        disposables.add(subscriptionManager.subscriptionTable()
            .getSubscriptionFlowable(serviceId, url)
            .map { it.isNotEmpty() }
            .distinctUntilChanged()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ isSubscribed ->
                subscribed = isSubscribed
                render()
                button.visibility = View.VISIBLE
            }, { Log.e(TAG, "Could not get subscription state of $url", it) }))

        button.setOnClickListener {
            val action = if (subscribed) {
                subscriptionManager.deleteSubscription(serviceId, url)
            } else {
                Completable.fromAction {
                    val entity = SubscriptionEntity()
                    entity.serviceId = serviceId
                    entity.url = url
                    entity.setData(name, avatarUrl, null,
                        if (subscriberCount >= 0) subscriberCount else null)
                    subscriptionManager.subscriptionTable().insert(entity)
                }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            }
            disposables.add(action.subscribe({}, {
                Log.e(TAG, "Could not change subscription to $url", it)
            }))
        }
    }

    fun release() {
        disposables.clear()
    }

    /** Dark pill "Subscribe" when not subscribed, grey pill "Subscribed" otherwise. */
    private fun render() {
        if (subscribed) {
            button.setText(R.string.subscribed_button_title)
            button.setBackgroundResource(R.drawable.yt_pill_background)
            button.setTextColor(ContextCompat.getColor(context, R.color.yt_on_surface))
        } else {
            button.setText(R.string.subscribe_button_title)
            button.setBackgroundResource(R.drawable.yt_subscribe_pill_background)
            button.setTextColor(ContextCompat.getColor(context, R.color.subscribe_text_color))
        }
    }

    companion object {
        private const val TAG = "DetailSubscribeButton"
    }
}
