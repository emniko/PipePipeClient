package org.schabi.newpipe.info_list;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.schabi.newpipe.R;

/**
 * Small helpers shared by the YouTube-style card layouts ({@code list_stream_card_item}).
 */
public final class YtCardUi {
    private static final String SEPARATOR = " · ";

    private YtCardUi() {
    }

    /**
     * Shows uploader and details as one "Channel · 1.2M views · 3 days ago" line in
     * {@code uploaderView}, like YouTube does under each video title.
     *
     * @param itemView the card's root view
     */
    public static void mergeMetaLine(final View itemView) {
        final TextView uploaderView = itemView.findViewById(R.id.itemUploaderView);
        final TextView detailsView = itemView.findViewById(R.id.itemAdditionalDetails);
        if (uploaderView == null || detailsView == null) {
            return;
        }
        // some holders hide the uploader view and put the uploader into the details instead
        final CharSequence uploader = uploaderView.getVisibility() == View.VISIBLE
                ? uploaderView.getText() : null;
        final CharSequence details = detailsView.getText();
        detailsView.setVisibility(View.GONE);
        if (TextUtils.isEmpty(details)) {
            return;
        }
        uploaderView.setVisibility(View.VISIBLE);
        if (TextUtils.isEmpty(uploader)) {
            uploaderView.setText(details);
        } else {
            uploaderView.setText(TextUtils.concat(uploader, SEPARATOR, details));
        }
    }

    /**
     * The holders color the duration badge with {@code setBackgroundColor}; this keeps the
     * color but gives the badge YouTube's rounded corners.
     *
     * @param itemView the card's root view
     */
    public static void roundDurationBadge(final View itemView) {
        final TextView durationView = itemView.findViewById(R.id.itemDurationView);
        if (durationView == null) {
            return;
        }
        final Drawable background = durationView.getBackground();
        if (background instanceof ColorDrawable) {
            final GradientDrawable rounded = new GradientDrawable();
            rounded.setColor(((ColorDrawable) background).getColor());
            rounded.setCornerRadius(4 * itemView.getResources().getDisplayMetrics().density);
            durationView.setBackground(rounded);
        }
    }

    /**
     * Applies both card tweaks after a holder has bound its item.
     *
     * @param itemView the card's root view
     */
    public static void applyCardStyle(@Nullable final View itemView) {
        if (itemView == null) {
            return;
        }
        mergeMetaLine(itemView);
        roundDurationBadge(itemView);
    }
}
