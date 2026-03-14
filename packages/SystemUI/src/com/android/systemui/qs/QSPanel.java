/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs;

import static com.android.systemui.util.Utils.useQsMediaPlayer;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.RemeasuringLinearLayout;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.List;

public class QSPanel extends LinearLayout implements Tunable {

    public static final String QS_SHOW_AUTO_BRIGHTNESS =
            Settings.Secure.QS_SHOW_AUTO_BRIGHTNESS;
    public static final String QS_SHOW_BRIGHTNESS_SLIDER =
            Settings.Secure.QS_SHOW_BRIGHTNESS_SLIDER;

    private static final String TAG = "QSPanel";

    protected final Context mContext;
    private final int mMediaTopMargin;
    private final int mMediaTotalBottomMargin;

    private Runnable mCollapseExpandAction;

    private int mMovableContentStartIndex;

    @Nullable
    protected View mBrightnessView;
    protected View mAutoBrightnessView;

    @Nullable
    protected BrightnessSliderController mToggleSliderController;

    protected Runnable mBrightnessRunnable;

    protected boolean mUsingMediaPlayer;

    protected boolean mExpanded;
    protected boolean mListening;
    protected boolean mIsAutomaticBrightnessAvailable = false;

    private final List<OnConfigurationChangedListener> mOnConfigurationChangedListeners =
            new ArrayList<>();

    @Nullable
    protected View mFooter;

    @Nullable
    private PageIndicator mFooterPageIndicator;
    private int mContentMarginStart;
    private int mContentMarginEnd;
    private boolean mUsingHorizontalLayout;

    @Nullable
    private LinearLayout mHorizontalLinearLayout;
    @Nullable
    protected LinearLayout mHorizontalContentContainer;

    @Nullable
    protected QSTileLayout mTileLayout;
    private float mSquishinessFraction = 1f;
    private final ArrayMap<View, Integer> mChildrenLayoutTop = new ArrayMap<>();
    private final Rect mClippingRect = new Rect();
    private ViewGroup mMediaHostView;
    private boolean mShouldMoveMediaOnExpansion = true;
    private QSLogger mQsLogger;
    private boolean mCanCollapse = true;

    public QSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mUsingMediaPlayer = useQsMediaPlayer(context);
        mMediaTotalBottomMargin = getResources().getDimensionPixelSize(
                R.dimen.quick_settings_bottom_margin_media);
        mMediaTopMargin = getResources().getDimensionPixelSize(
                R.dimen.qs_tile_margin_vertical);
        mContext = context;

        setOrientation(VERTICAL);

        mMovableContentStartIndex = getChildCount();

        mIsAutomaticBrightnessAvailable = getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);
    }

    void initialize(QSLogger qsLogger) {
        mQsLogger = qsLogger;
        mTileLayout = getOrCreateTileLayout();

        if (mUsingMediaPlayer) {
            mHorizontalLinearLayout = new RemeasuringLinearLayout(mContext);
            mHorizontalLinearLayout.setOrientation(LinearLayout.HORIZONTAL);
            mHorizontalLinearLayout.setVisibility(
                    mUsingHorizontalLayout ? View.VISIBLE : View.GONE);
            mHorizontalLinearLayout.setClipChildren(false);
            mHorizontalLinearLayout.setClipToPadding(false);

            mHorizontalContentContainer = new RemeasuringLinearLayout(mContext);
            mHorizontalContentContainer.setOrientation(LinearLayout.VERTICAL);
            setHorizontalContentContainerClipping();

            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            int marginSize = (int) mContext.getResources().getDimension(R.dimen.qs_media_padding);
            lp.setMarginStart(0);
            lp.setMarginEnd(marginSize);
            lp.gravity = Gravity.CENTER_VERTICAL;
            mHorizontalLinearLayout.addView(mHorizontalContentContainer, lp);

            lp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1);
            addView(mHorizontalLinearLayout, lp);
        }
    }

    protected void setHorizontalContentContainerClipping() {
        mHorizontalContentContainer.setClipChildren(true);
        mHorizontalContentContainer.setClipToPadding(false);
        mHorizontalContentContainer.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if ((right - left) != (oldRight - oldLeft)
                            || ((bottom - top) != (oldBottom - oldTop))) {
                        mClippingRect.right = right - left;
                        mClippingRect.bottom = bottom - top;
                        mHorizontalContentContainer.setClipBounds(mClippingRect);
                    }
                });
        mClippingRect.left = 0;
        mClippingRect.top = -1000;
        mHorizontalContentContainer.setClipBounds(mClippingRect);
    }

    public void setBrightnessView(@NonNull View view) {
        if (mBrightnessView != null) {
            removeView(mBrightnessView);
            mMovableContentStartIndex--;
        }
        mBrightnessView = view;
        mAutoBrightnessView = view.findViewById(R.id.brightness_icon);
        setBrightnessViewMargin();
        if (mBrightnessView != null) {
            addView(mBrightnessView);
            mMovableContentStartIndex++;
            
            ViewGroup parent = mUsingHorizontalLayout ? mHorizontalContentContainer : this;
            switchAllContentToParent(parent, mTileLayout);
        }
    }

    private void setBrightnessViewMargin() {
        if (mBrightnessView != null) {
            MarginLayoutParams lp = (MarginLayoutParams) mBrightnessView.getLayoutParams();
            
            lp.height = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_height);
            
            lp.topMargin = mContext.getResources()
                    .getDimensionPixelSize(R.dimen.qs_bottom_brightness_margin_top);
            lp.bottomMargin = 0;
            
            mBrightnessView.setLayoutParams(lp);
        }
    }

    public QSTileLayout getOrCreateTileLayout() {
        if (mTileLayout == null) {
            mTileLayout = (QSTileLayout) LayoutInflater.from(mContext)
                    .inflate(R.layout.qs_paged_tile_layout, this, false);
            mTileLayout.setLogger(mQsLogger);
            mTileLayout.setSquishinessFraction(mSquishinessFraction);
        }
        return mTileLayout;
    }

    public void setSquishinessFraction(float squishinessFraction) {
        if (Float.compare(squishinessFraction, mSquishinessFraction) == 0) {
            return;
        }
        mSquishinessFraction = squishinessFraction;
        if (mTileLayout == null) {
            return;
        }
        mTileLayout.setSquishinessFraction(squishinessFraction);
        if (getMeasuredWidth() == 0) {
            return;
        }
        updateViewPositions();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mTileLayout instanceof PagedTileLayout) {
            if (mFooterPageIndicator != null) {
                mFooterPageIndicator.setNumPages(((PagedTileLayout) mTileLayout).getNumPages());
            }

            if (((View) mTileLayout).getParent() == this) {
                int newHeight = 10000;
                int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
                int excessHeight = newHeight - availableHeight;
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.EXACTLY);
                ((PagedTileLayout) mTileLayout).setExcessHeight(excessHeight);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int height = getPaddingBottom() + getPaddingTop();
        int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                height += child.getMeasuredHeight();
                MarginLayoutParams layoutParams = (MarginLayoutParams) child.getLayoutParams();
                height += layoutParams.topMargin + layoutParams.bottomMargin;
            }
        }
        setMeasuredDimension(getMeasuredWidth(), height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            mChildrenLayoutTop.put(child, child.getTop());
        }
        updateViewPositions();
    }

    private void updateViewPositions() {
        int tileHeightOffset = mTileLayout.getTilesHeight() - mTileLayout.getHeight();

        boolean move = false;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (move) {
                int topOffset;
                if (child == mMediaHostView && !mShouldMoveMediaOnExpansion) {
                    topOffset = 0;
                } else {
                    topOffset = tileHeightOffset;
                }
                Integer childLayoutTop = mChildrenLayoutTop.get(child);
                if (childLayoutTop == null) {
                    continue;
                }
                int top = childLayoutTop;
                child.setLeftTopRightBottom(child.getLeft(), top + topOffset,
                        child.getRight(), top + topOffset + child.getHeight());
            }
            if (child == mTileLayout) {
                move = true;
            }
        }
    }

    protected String getDumpableTag() {
        return TAG;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case QS_SHOW_BRIGHTNESS_SLIDER:
                boolean value =
                       TunerService.parseInteger(newValue, 2) >= 1;
                if (mBrightnessView != null) {
                    mBrightnessView.setVisibility(value ? VISIBLE : GONE);
                }
                break;
            case QS_SHOW_AUTO_BRIGHTNESS:
                if (mAutoBrightnessView != null) {
                    mAutoBrightnessView.setVisibility(mIsAutomaticBrightnessAvailable &&
                            TunerService.parseIntegerSwitch(newValue, true) ? View.VISIBLE : View.GONE);
                }
                break;
            default:
                break;
         }
    }

    public void setBrightnessRunnable(Runnable runnable) {
        mBrightnessRunnable = runnable;
    }


    @Nullable
    View getBrightnessView() {
        return mBrightnessView;
    }

    public void setFooterPageIndicator(PageIndicator pageIndicator) {
        if (mTileLayout instanceof PagedTileLayout) {
            mFooterPageIndicator = pageIndicator;
            updatePageIndicator();
        }
    }

    private void updatePageIndicator() {
        if (mTileLayout instanceof PagedTileLayout) {
            if (mFooterPageIndicator != null) {
                // Ensure it isn't forced GONE here
                ((PagedTileLayout) mTileLayout).setPageIndicator(mFooterPageIndicator);
            }
        }
    }

    public void updateResources() {
        updatePadding();
        updatePageIndicator();
        setBrightnessViewMargin();

        if (mTileLayout != null) {
            mTileLayout.updateResources();
        }
    }

    protected void updatePadding() {
        final Resources res = mContext.getResources();
        int paddingTop = res.getDimensionPixelSize(R.dimen.qs_panel_padding_top);
        int paddingBottom = res.getDimensionPixelSize(R.dimen.qs_panel_padding_bottom);
        setPaddingRelative(getPaddingStart(),
                paddingTop,
                getPaddingEnd(),
                paddingBottom);
    }

    void addOnConfigurationChangedListener(OnConfigurationChangedListener listener) {
        mOnConfigurationChangedListeners.add(listener);
    }

    void removeOnConfigurationChangedListener(OnConfigurationChangedListener listener) {
        mOnConfigurationChangedListeners.remove(listener);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mOnConfigurationChangedListeners.forEach(
                listener -> listener.onConfigurationChange(newConfig));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mFooter = findViewById(R.id.qs_footer);
    }

    private void updateHorizontalLinearLayoutMargins() {
        if (mHorizontalLinearLayout != null && !displayMediaMarginsOnMedia()) {
            LayoutParams lp = (LayoutParams) mHorizontalLinearLayout.getLayoutParams();
            lp.bottomMargin = Math.max(mMediaTotalBottomMargin - getPaddingBottom(), 0);
            mHorizontalLinearLayout.setLayoutParams(lp);
        }
    }

    protected boolean displayMediaMarginsOnMedia() {
        return true;
    }

    protected boolean mediaNeedsTopMargin() {
        return false;
    }

    private boolean needsDynamicRowsAndColumns() {
        return true;
    }

    private void switchAllContentToParent(ViewGroup parent, QSTileLayout newLayout) {
        int index = parent == this ? mMovableContentStartIndex : 0;

        // 1. Tiles go FIRST
        if (newLayout != null) {
            switchToParent((View) newLayout, parent, index);
            index++;
        }

        // 2. Footer (Dots + Edit Button) goes SECOND
        if (mFooter != null) {
            switchToParent(mFooter, parent, index);
            index++;
        }

        // 3. Brightness Slider goes THIRD (Absolute Bottom)
        if (mBrightnessView != null) {
            switchToParent(mBrightnessView, parent, index);
            index++;
        }
    }

    private void switchToParent(View child, ViewGroup parent, int index) {
        switchToParent(child, parent, index, getDumpableTag());
    }

    private void reAttachMediaHost(ViewGroup hostView, boolean horizontal) {
        if (!mUsingMediaPlayer) {
            return;
        }
        mMediaHostView = hostView;
        ViewGroup newParent = horizontal ? mHorizontalLinearLayout : this;
        ViewGroup currentParent = (ViewGroup) hostView.getParent();
        Log.d(getDumpableTag(), "Reattaching media host: " + horizontal
                + ", current " + currentParent + ", new " + newParent);
        if (currentParent != newParent) {
            if (currentParent != null) {
                currentParent.removeView(hostView);
            }
            newParent.addView(hostView);
            LinearLayout.LayoutParams layoutParams = (LayoutParams) hostView.getLayoutParams();
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            layoutParams.width = horizontal ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.weight = horizontal ? 1f : 0;
            layoutParams.bottomMargin = !horizontal || displayMediaMarginsOnMedia()
                    ? Math.max(mMediaTotalBottomMargin - getPaddingBottom(), 0) : 0;
            layoutParams.topMargin = mediaNeedsTopMargin() && !horizontal
                    ? mMediaTopMargin : 0;
            hostView.setLayoutParams(layoutParams);
        }
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        
        if (!expanded) {
            TileLayout.disableEditMode();
        }
        
        if (!mExpanded && mTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mTileLayout).setCurrentItem(0, false);
        }
    }

    public void setPageListener(final PagedTileLayout.PageListener pageListener) {
        if (mTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mTileLayout).setPageListener(pageListener);
        }
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    public void setListening(boolean listening) {
        mListening = listening;
    }

    protected void drawTile(QSPanelControllerBase.TileRecord r, QSTile.State state) {
        r.tileView.onStateChanged(state);
    }

    protected QSEvent openPanelEvent() {
        return QSEvent.QS_PANEL_EXPANDED;
    }

    protected QSEvent closePanelEvent() {
        return QSEvent.QS_PANEL_COLLAPSED;
    }

    protected QSEvent tileVisibleEvent() {
        return QSEvent.QS_TILE_VISIBLE;
    }

    protected boolean shouldShowDetail() {
        return mExpanded;
    }

    final void addTile(QSPanelControllerBase.TileRecord tileRecord) {
        final QSTile.Callback callback = new QSTile.Callback() {
            @Override
            public void onStateChanged(QSTile.State state) {
                drawTile(tileRecord, state);
            }
        };

        tileRecord.tile.addCallback(callback);
        tileRecord.callback = callback;
        tileRecord.tileView.init(tileRecord.tile);
        tileRecord.tile.refreshState();

        if (mTileLayout != null) {
            mTileLayout.addTile(tileRecord);
        }
    }

    void removeTile(QSPanelControllerBase.TileRecord tileRecord) {
        mTileLayout.removeTile(tileRecord);
    }

    public int getGridHeight() {
        return getMeasuredHeight();
    }

    @Nullable
    QSTileLayout getTileLayout() {
        return mTileLayout;
    }

    public void setContentMargins(int startMargin, int endMargin, ViewGroup mediaHostView) {
        mContentMarginStart = startMargin;
        mContentMarginEnd = endMargin;
        updateMediaHostContentMargins(mediaHostView);
    }

    protected void updateMediaHostContentMargins(ViewGroup mediaHostView) {
        if (mUsingMediaPlayer) {
            int marginStart = 0;
            int marginEnd = 0;
            if (mUsingHorizontalLayout) {
                marginEnd = mContentMarginEnd;
            }
            updateMargins(mediaHostView, marginStart, marginEnd);
        }
    }

    protected void updateMargins(View view, int start, int end) {
        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        if (lp != null) {
            lp.setMarginStart(start);
            lp.setMarginEnd(end);
            view.setLayoutParams(lp);
        }
    }

    public boolean isListening() {
        return mListening;
    }

    protected void setPageMargin(int pageMargin) {
        if (mTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mTileLayout).setPageMargin(pageMargin);
        }
    }

    void setUsingHorizontalLayout(boolean horizontal, ViewGroup mediaHostView, boolean force) {
        if (horizontal != mUsingHorizontalLayout || force) {
            Log.d(getDumpableTag(), "setUsingHorizontalLayout: " + horizontal + ", " + force);
            mUsingHorizontalLayout = horizontal;
            ViewGroup newParent = horizontal ? mHorizontalContentContainer : this;
            switchAllContentToParent(newParent, mTileLayout);
            if (mBrightnessRunnable != null) {
                updateResources();
                mBrightnessRunnable.run();
            }
            reAttachMediaHost(mediaHostView, horizontal);
            if (needsDynamicRowsAndColumns()) {
                mTileLayout.setMinRows(horizontal ? 2 : 1);
                mTileLayout.setMaxColumns(horizontal ? 2 : 4);
            }
            updateMargins(mediaHostView);
            if (mHorizontalLinearLayout == null) return;
            mHorizontalLinearLayout.setVisibility(horizontal ? View.VISIBLE : View.GONE);
        }
    }

    private void updateMargins(ViewGroup mediaHostView) {
        updateMediaHostContentMargins(mediaHostView);
        updateHorizontalLinearLayoutMargins();
        updatePadding();
    }

    public void setShouldMoveMediaOnExpansion(boolean shouldMoveMediaOnExpansion) {
        mShouldMoveMediaOnExpansion = shouldMoveMediaOnExpansion;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mCanCollapse) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_EXPAND
                || action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
            if (mCollapseExpandAction != null) {
                mCollapseExpandAction.run();
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    public void setCollapseExpandAction(Runnable action) {
        mCollapseExpandAction = action;
    }

    public void setCanCollapse(boolean canCollapse) {
        mCanCollapse = canCollapse;
    }

    public interface QSTileLayout {
        default void saveInstanceState(Bundle outState) {}
        default void restoreInstanceState(Bundle savedInstanceState) {}
        void addTile(QSPanelControllerBase.TileRecord tile);
        void removeTile(QSPanelControllerBase.TileRecord tile);
        int getOffsetTop(QSPanelControllerBase.TileRecord tile);
        boolean updateResources();
        void setListening(boolean listening, UiEventLogger uiEventLogger);
        int getHeight();
        int getTilesHeight();
        void setSquishinessFraction(float squishinessFraction);
        default boolean setMinRows(int minRows) {
            return false;
        }
        default boolean setMaxColumns(int maxColumns) {
            return false;
        }
        default void setExpansion(float expansion, float proposedTranslation) {
            if (expansion == 0f) {
                TileLayout.disableEditMode();
            }
        }
        int getNumVisibleTiles();
        default void setLogger(QSLogger qsLogger) { }
    }

    interface OnConfigurationChangedListener {
        void onConfigurationChange(Configuration newConfig);
    }

    @VisibleForTesting
    static void switchToParent(View child, ViewGroup parent, int index, String tag) {
        if (parent == null) {
            Log.w(tag, "Trying to move view to null parent",
                    new IllegalStateException());
            return;
        }
        ViewGroup currentParent = (ViewGroup) child.getParent();
        if (currentParent != parent) {
            if (currentParent != null) {
                currentParent.removeView(child);
            }
            index = Math.min(index, parent.getChildCount());
            parent.addView(child, index);
            return;
        }
        int currentIndex = parent.indexOfChild(child);
        if (currentIndex == index) {
            return;
        }
        parent.removeView(child);
        index = Math.min(index, parent.getChildCount());
        parent.addView(child, index);
    }
}