package org.schabi.newpipe.views;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

/**
 * ViewPager for the main tabs that behaves like YouTube's bottom navigation:
 * pages are switched only through the tab bar (no horizontal swiping, so carousels inside
 * a page scroll freely) and switching jumps directly instead of sliding.
 */
public class YtTabsViewPager extends ViewPager {
    private boolean swipeEnabled = false;

    public YtTabsViewPager(@NonNull final Context context) {
        super(context);
    }

    public YtTabsViewPager(@NonNull final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
    }

    public void setSwipeEnabled(final boolean swipeEnabled) {
        this.swipeEnabled = swipeEnabled;
    }

    @Override
    public boolean onInterceptTouchEvent(final MotionEvent ev) {
        return swipeEnabled && super.onInterceptTouchEvent(ev);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(final MotionEvent ev) {
        return swipeEnabled && super.onTouchEvent(ev);
    }

    @Override
    public void setCurrentItem(final int item) {
        // TabLayout calls this when a tab is tapped; skip the slide animation unless swiping
        super.setCurrentItem(item, swipeEnabled);
    }
}
