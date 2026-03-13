/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.systemui.qs.customize.QSCustomizer.EXTRA_QS_CUSTOMIZING;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.Toolbar;

import androidx.annotation.Nullable;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.qs.QSContainerController;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSEditEvent;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * {@link ViewController} for the Nothing-OS-style {@link QSCustomizer}.
 *
 * <p>Key differences from AOSP {@code QSCustomizerController}:
 * <ul>
 *   <li>Uses {@link NothingQSCustomizerAdapter} instead of {@link TileAdapter}.</li>
 *   <li>No reset menu item — the toolbar only exposes a back-arrow.</li>
 *   <li>Attaches the adapter's three view references (pager, recycler, dots) in
 *       {@link #onViewAttached}.</li>
 * </ul>
 */
@QSScope
public class QSCustomizerController extends ViewController<QSCustomizer> {

    private final TileQueryHelper mTileQueryHelper;
    private final QSHost mQsHost;
    private final NothingQSCustomizerAdapter mAdapter;
    private final ScreenLifecycle mScreenLifecycle;
    private final KeyguardStateController mKeyguardStateController;
    private final LightBarController mLightBarController;
    private final ConfigurationController mConfigurationController;
    private final UiEventLogger mUiEventLogger;

    private final Toolbar mToolbar;

    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------

    private final KeyguardStateController.Callback mKeyguardCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onKeyguardShowingChanged() {
            if (!mView.isAttachedToWindow()) return;
            if (mKeyguardStateController.isShowing() && !mView.isOpening()) {
                hide();
            }
        }
    };

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onConfigChanged(Configuration newConfig) {
            mView.updateNavBackDrop(newConfig, mLightBarController);
            mView.updateResources();
            // Tile height may have changed (font scaling); ask adapter to reload
            mAdapter.reloadTileHeight();
        }
    };

    // -------------------------------------------------------------------------
    // Constructor — injected
    // -------------------------------------------------------------------------

    @Inject
    protected QSCustomizerController(
            QSCustomizer view,
            TileQueryHelper tileQueryHelper,
            QSHost qsHost,
            NothingQSCustomizerAdapter adapter,
            ScreenLifecycle screenLifecycle,
            KeyguardStateController keyguardStateController,
            LightBarController lightBarController,
            ConfigurationController configurationController,
            UiEventLogger uiEventLogger) {
        super(view);
        mTileQueryHelper    = tileQueryHelper;
        mQsHost             = qsHost;
        mAdapter            = adapter;
        mScreenLifecycle    = screenLifecycle;
        mKeyguardStateController    = keyguardStateController;
        mLightBarController         = lightBarController;
        mConfigurationController    = configurationController;
        mUiEventLogger              = uiEventLogger;

        mToolbar = mView.findViewById(com.android.internal.R.id.action_bar);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onViewAttached() {
        mView.updateNavBackDrop(getResources().getConfiguration(), mLightBarController);
        mConfigurationController.addCallback(mConfigurationListener);

        // Wire the TileQueryHelper → adapter
        mTileQueryHelper.setListener(mAdapter);

        // Attach the adapter's view references
        mAdapter.attachViews(
                mView.getActivePager(),
                mView.getInactiveRecycler(),
                mView.getDotContainer());

        // Toolbar: back-arrow only
        mToolbar.setNavigationOnClickListener(v -> hide());
        // Explicitly clear any menu items (safety net)
        mToolbar.getMenu().clear();
        // No title
        mToolbar.setTitle(null);
    }

    @Override
    protected void onViewDetached() {
        mTileQueryHelper.setListener(null);
        mConfigurationController.removeCallback(mConfigurationListener);
        mToolbar.setNavigationOnClickListener(null);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean isCustomizing() {
        return mView.isCustomizing();
    }

    public boolean isShown() {
        return mView.isShown();
    }

    /**
     * Show the customizer, optionally immediately (no animation).
     *
     * @param x,y Origin of the circular-reveal animation (screen coords of edit button).
     * @param immediate Skip animation.
     */
    public void show(int x, int y, boolean immediate) {
        if (!mView.isShown()) {
            setTileSpecs();
            if (immediate) {
                mView.showImmediately();
            } else {
                mView.show(x, y, mAdapter);
                mUiEventLogger.log(QSEditEvent.QS_EDIT_OPEN);
            }
            mTileQueryHelper.queryTiles(mQsHost);
            mKeyguardStateController.addCallback(mKeyguardCallback);
            mView.updateNavColors(mLightBarController);
        }
    }

    /** Hide the customizer and save tile order. */
    public void hide() {
        final boolean animate =
                mScreenLifecycle.getScreenState() != ScreenLifecycle.SCREEN_OFF;
        if (mView.isShown()) {
            mUiEventLogger.log(QSEditEvent.QS_EDIT_CLOSED);
            mToolbar.dismissPopupMenus();
            mView.setCustomizing(false);
            save();
            mView.hide(animate);
            mView.updateNavColors(mLightBarController);
            mKeyguardStateController.removeCallback(mKeyguardCallback);
        }
    }

    /** */
    public void setQs(@Nullable QSFragment qsFragment) {
        mView.setQs(qsFragment);
    }

    /** */
    public void setEditLocation(int x, int y) {
        mView.setEditLocation(x, y);
    }

    /** */
    public void setContainerController(QSContainerController controller) {
        mView.setContainerController(controller);
    }

    /** Restore customizer visibility state after process restart. */
    public void restoreInstanceState(Bundle savedInstanceState) {
        boolean customizing = savedInstanceState.getBoolean(EXTRA_QS_CUSTOMIZING);
        if (customizing) {
            mView.setVisibility(View.VISIBLE);
            mView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    mView.removeOnLayoutChangeListener(this);
                    show(0, 0, true);
                }
            });
        }
    }

    /** Save customizer open/close state to bundle. */
    public void saveInstanceState(Bundle outState) {
        if (mView.isShown()) {
            mKeyguardStateController.removeCallback(mKeyguardCallback);
        }
        outState.putBoolean(EXTRA_QS_CUSTOMIZING, mView.isCustomizing());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void save() {
        if (mTileQueryHelper.isFinished()) {
            mAdapter.saveSpecs(mQsHost);
        }
    }

    private void setTileSpecs() {
        List<String> specs = new ArrayList<>();
        for (QSTile tile : mQsHost.getTiles()) {
            specs.add(tile.getTileSpec());
        }
        mAdapter.setTileSpecs(specs);
    }
}
