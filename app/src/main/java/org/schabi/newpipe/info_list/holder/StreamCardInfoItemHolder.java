package org.schabi.newpipe.info_list.holder;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.info_list.YtCardUi;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PicassoHelper;

/**
 * YouTube-style card layout for streams: full-width thumbnail, channel avatar,
 * title, "Channel · views · time" line and an overflow button.
 */
public class StreamCardInfoItemHolder extends StreamInfoItemHolder {
    private final ImageView itemUploaderAvatarView;
    private final View itemMoreButton;

    public StreamCardInfoItemHolder(final InfoItemBuilder infoItemBuilder, final ViewGroup parent) {
        super(infoItemBuilder, R.layout.list_stream_card_item, parent);
        itemUploaderAvatarView = itemView.findViewById(R.id.itemUploaderAvatarView);
        itemMoreButton = itemView.findViewById(R.id.itemMoreButton);
    }

    @Override
    public void updateFromItem(final InfoItem infoItem,
                               final HistoryRecordManager historyRecordManager) {
        super.updateFromItem(infoItem, historyRecordManager);
        if (!(infoItem instanceof StreamInfoItem)) {
            return;
        }
        final StreamInfoItem item = (StreamInfoItem) infoItem;
        YtCardUi.applyCardStyle(itemView);

        // Channel avatar: only shown when the service provides one in list results
        final String avatarUrl = item.getUploaderAvatarUrl();
        if (TextUtils.isEmpty(avatarUrl)) {
            itemUploaderAvatarView.setVisibility(View.GONE);
            itemUploaderAvatarView.setOnClickListener(null);
        } else {
            itemUploaderAvatarView.setVisibility(View.VISIBLE);
            PicassoHelper.loadAvatar(avatarUrl).into(itemUploaderAvatarView);
            if (TextUtils.isEmpty(item.getUploaderUrl())) {
                itemUploaderAvatarView.setOnClickListener(null);
                itemUploaderAvatarView.setClickable(false);
            } else {
                itemUploaderAvatarView.setOnClickListener(v ->
                        NavigationHelper.openChannelFragmentUsingIntent(itemBuilder.getContext(),
                                item.getServiceId(), item.getUploaderUrl(),
                                item.getUploaderName() == null ? "" : item.getUploaderName()));
            }
        }

        // Overflow button opens the same menu as a long press
        itemMoreButton.setVisibility(itemView.isLongClickable() ? View.VISIBLE : View.GONE);
        itemMoreButton.setOnClickListener(v -> itemView.performLongClick());
    }
}
