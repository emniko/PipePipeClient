package org.schabi.newpipe.info_list.holder;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.PicassoHelper;

/**
 * YouTube-style Shorts tile: a tall 9:16 thumbnail with title and views on it. Two tiles share a
 * row (see {@link org.schabi.newpipe.info_list.InfoListAdapter#getCardSpanSizeLookup}).
 */
public class StreamShortsInfoItemHolder extends InfoItemHolder {
    private final ImageView thumbnailView;
    private final TextView titleView;
    private final TextView detailsView;
    private final View moreButton;

    public StreamShortsInfoItemHolder(final InfoItemBuilder infoItemBuilder,
                                      final ViewGroup parent) {
        super(infoItemBuilder, R.layout.list_stream_shorts_item, parent);
        thumbnailView = itemView.findViewById(R.id.itemThumbnailView);
        titleView = itemView.findViewById(R.id.itemVideoTitleView);
        detailsView = itemView.findViewById(R.id.itemAdditionalDetails);
        moreButton = itemView.findViewById(R.id.itemMoreButton);
    }

    @Override
    public void updateFromItem(final InfoItem infoItem,
                               final HistoryRecordManager historyRecordManager) {
        if (!(infoItem instanceof StreamInfoItem)) {
            return;
        }
        final StreamInfoItem item = (StreamInfoItem) infoItem;

        titleView.setText(item.getName());
        detailsView.setText(item.getViewCount() >= 0
                ? Localization.shortViewCount(itemBuilder.getContext(), item.getViewCount())
                : "");
        PicassoHelper.loadThumbnail(item.getThumbnailUrl()).into(thumbnailView);

        itemView.setOnClickListener(view -> {
            if (itemBuilder.getOnStreamSelectedListener() != null) {
                itemBuilder.getOnStreamSelectedListener().selected(item);
            }
        });
        final View.OnLongClickListener showMenu = view -> {
            if (itemBuilder.getOnStreamSelectedListener() != null) {
                itemBuilder.getOnStreamSelectedListener().held(item);
            }
            return true;
        };
        itemView.setOnLongClickListener(showMenu);
        moreButton.setOnClickListener(view -> showMenu.onLongClick(itemView));
    }
}
