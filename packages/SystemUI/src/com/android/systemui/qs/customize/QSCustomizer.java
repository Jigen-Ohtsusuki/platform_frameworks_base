/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.qs.customize;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toolbar;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSContainerController;
import com.android.systemui.qs.QSDetailClipper;
import com.android.systemui.qs.QSUtils;
import com.android.systemui.statusbar.phone.LightBarController;

/**
 * Nothing-OS-style QS customizer panel.
 *
 * <p>This replaces the original AOSP single-RecyclerView design with a split architecture:
 * <ul>
 *   <li>Top half: {@link ViewPager2} of paginated 4×4 active-tile grids with dot indicators.</li>
 *   <li>Bottom half: {@link RecyclerView} pool of inactive tiles forced to circle shape.</li>
 * </ul>
 *
 * <p>The toolbar carries only a back-arrow — no title text, no reset menu item.
 */
public class QSCustomizer extends LinearLayout {

    // Kept for Controller/Bundle compatibility
    static final String EXTRA_QS_CUSTOMIZING = "qs_customizing";

    private final QSDetailClipper mClipper;
    private final View mTransparentView;

    private boolean isShown;
    private boolean mCustomizing;
    private boolean mOpening;
    private boolean mIsShowingNavBackdrop;

    @Nullable private QSContainerController mQsContainerController;
    @Nullable private QS mQs;

    private int mX;
    private int mY;

    // New split-view widgets
    private final ViewPager2 mActivePager;
    private final RecyclerView mInactiveRecycler;
    private final LinearLayout mDotContainer;

    public QSCustomizer(Context context, AttributeSet attrs) {
        super(context, attrs);

        LayoutInflater.from(getContext()).inflate(R.layout.qs_customize_panel_content, this);

        mClipper = new QSDetailClipper(findViewById(R.id.customize_container));

        // ── Toolbar: back-arrow only, NO title, NO reset menu ──
        Toolbar toolbar = findViewById(com.android.internal.R.id.action_bar);
        TypedValue value = new TypedValue();
        mContext.getTheme().resolveAttribute(android.R.attr.homeAsUpIndicator, value, true);
        toolbar.setNavigationIcon(
                getResources().getDrawable(value.resourceId, mContext.getTheme()));
        // Deliberately leave title empty and add NO menu items.

        // ── New split-architecture views ──
        mActivePager      = findViewById(R.id.active_tiles_pager);
        mInactiveRecycler = findViewById(R.id.inactive_tiles_recycler);
        mDotContainer     = findViewById(R.id.page_indicator_container);
        mTransparentView  = findViewById(R.id.customizer_transparent_view);

        updateTransparentViewHeight();
    }

    // -------------------------------------------------------------------------
    // Public accessors used by QSCustomizerController
    // -------------------------------------------------------------------------

    ViewPager2 getActivePager()       { return mActivePager; }
    RecyclerView getInactiveRecycler(){ return mInactiveRecycler; }
    LinearLayout getDotContainer()    { return mDotContainer; }

    // -------------------------------------------------------------------------
    // Resource / config updates
    // -------------------------------------------------------------------------

    void updateResources() {
        updateTransparentViewHeight();
    }

    void updateNavBackDrop(Configuration newConfig, LightBarController lightBarController) {
        View navBackdrop = findViewById(R.id.nav_bar_background);
        mIsShowingNavBackdrop = newConfig.smallestScreenWidthDp >= 600
                || newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE;
        if (navBackdrop != null) {
            navBackdrop.setVisibility(mIsShowingNavBackdrop ? View.VISIBLE : View.GONE);
        }
        updateNavColors(lightBarController);
    }

    void updateNavColors(LightBarController lightBarController) {
        lightBarController.setQsCustomizing(mIsShowingNavBackdrop && isShown);
    }

    // -------------------------------------------------------------------------
    // Show / hide
    // -------------------------------------------------------------------------

    /**
     * Animate the panel open from the edit button location.
     *
     * @param x,y Screen coordinates of the edit button (animation origin).
     * @param adapter The fully-configured {@link NothingQSCustomizerAdapter}.
     */
    void show(int x, int y, NothingQSCustomizerAdapter adapter) {
        if (!isShown) {
            int[] containerLocation =
                    findViewById(R.id.customize_container).getLocationOnScreen();
            mX = x - containerLocation[0];
            mY = y - containerLocation[1];
            isShown = true;
            mOpening = true;
            setVisibility(View.VISIBLE);
            long duration = mClipper.animateCircularClip(
                    mX, mY, true, new ExpandAnimatorListener(adapter));
            mQsContainerController.setCustomizerAnimating(true);
            mQsContainerController.setCustomizerShowing(true, duration);
        }
    }

    void showImmediately() {
        if (!isShown) {
            setVisibility(VISIBLE);
            mClipper.cancelAnimator();
            mClipper.showBackground();
            isShown = true;
            setCustomizing(true);
            mQsContainerController.setCustomizerAnimating(false);
            mQsContainerController.setCustomizerShowing(true);
        }
    }

    /** Hide the customizer. */
    public void hide(boolean animate) {
        if (isShown) {
            isShown = false;
            mClipper.cancelAnimator();
            mOpening = false;
            long duration = 0;
            if (animate) {
                duration = mClipper.animateCircularClip(
                        mX, mY, false, mCollapseAnimationListener);
            } else {
                setVisibility(View.GONE);
            }
            mQsContainerController.setCustomizerAnimating(animate);
            mQsContainerController.setCustomizerShowing(false, duration);
        }
    }

    public boolean isShown()         { return isShown; }
    public boolean isOpening()       { return mOpening; }
    public boolean isCustomizing()   { return mCustomizing || mOpening; }

    void setCustomizing(boolean customizing) {
        mCustomizing = customizing;
        if (mQs != null) mQs.notifyCustomizeChanged();
    }

    public void setContainerController(QSContainerController controller) {
        mQsContainerController = controller;
    }

    public void setQs(@Nullable QS qs) {
        mQs = qs;
    }

    public void setEditLocation(int x, int y) {
        int[] containerLocation =
                findViewById(R.id.customize_container).getLocationOnScreen();
        mX = x - containerLocation[0];
        mY = y - containerLocation[1];
    }

    // -------------------------------------------------------------------------
    // Animation listeners
    // -------------------------------------------------------------------------

    class ExpandAnimatorListener extends AnimatorListenerAdapter {
        private final NothingQSCustomizerAdapter mAdapter;

        ExpandAnimatorListener(NothingQSCustomizerAdapter adapter) {
            mAdapter = adapter;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (isShown) setCustomizing(true);
            mOpening = false;
            mQsContainerController.setCustomizerAnimating(false);
            // Adapters are already attached by the controller — nothing to do here.
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mOpening = false;
            if (mQs != null) mQs.notifyCustomizeChanged();
            mQsContainerController.setCustomizerAnimating(false);
        }
    }

    private final Animator.AnimatorListener mCollapseAnimationListener =
            new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (!isShown) setVisibility(View.GONE);
            mQsContainerController.setCustomizerAnimating(false);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (!isShown) setVisibility(View.GONE);
            mQsContainerController.setCustomizerAnimating(false);
        }
    };

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void updateTransparentViewHeight() {
        LayoutParams lp = (LayoutParams) mTransparentView.getLayoutParams();
        lp.height = QSUtils.getQsHeaderSystemIconsAreaHeight(mContext);
        mTransparentView.setLayoutParams(lp);
    }
}
