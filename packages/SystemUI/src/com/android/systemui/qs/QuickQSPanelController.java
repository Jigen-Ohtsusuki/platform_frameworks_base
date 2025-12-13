/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL;
import static com.android.systemui.qs.dagger.QSFragmentModule.QS_USING_COLLAPSED_LANDSCAPE_MEDIA;
import static com.android.systemui.qs.dagger.QSFragmentModule.QS_USING_MEDIA_PLAYER;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.controls.ui.MediaHierarchyManager;
import com.android.systemui.media.controls.ui.MediaHost;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.util.leak.RotationUtils;
import com.android.systemui.settings.brightness.BrightnessController;
import com.android.systemui.settings.brightness.BrightnessMirrorHandler;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.tuner.TunerService;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

@QSScope
public class QuickQSPanelController extends QSPanelControllerBase<QuickQSPanel> {

    private final Provider<Boolean> mUsingCollapsedLandscapeMediaProvider;
    private final BrightnessController mBrightnessController;
    private final TunerService mTunerService;
    private final BrightnessSliderController mBrightnessSliderController;
    private final BrightnessMirrorHandler mBrightnessMirrorHandler;
    private BrightnessMirrorController mBrightnessMirrorController;
    
    private static final String PREFS_FILE = "qs_tile_config";
    private static final String PREF_PREFIX_SHAPE = "tile_is_circle_";

    @Inject
    QuickQSPanelController(QuickQSPanel view, QSHost qsHost,
            QSCustomizerController qsCustomizerController,
            @Named(QS_USING_MEDIA_PLAYER) boolean usingMediaPlayer,
            @Named(QUICK_QS_PANEL) MediaHost mediaHost,
            @Named(QS_USING_COLLAPSED_LANDSCAPE_MEDIA)
                    Provider<Boolean> usingCollapsedLandscapeMediaProvider,
            MetricsLogger metricsLogger, UiEventLogger uiEventLogger, QSLogger qsLogger,
            DumpManager dumpManager,
            TunerService tunerService,
            BrightnessController.Factory brightnessControllerFactory,
            BrightnessSliderController.Factory brightnessSliderFactory
    ) {
        super(view, qsHost, qsCustomizerController, usingMediaPlayer, mediaHost, metricsLogger,
                uiEventLogger, qsLogger, dumpManager);
        mUsingCollapsedLandscapeMediaProvider = usingCollapsedLandscapeMediaProvider;
        mTunerService = tunerService;

        mBrightnessSliderController = brightnessSliderFactory.create(getContext(), mView);
        mView.setBrightnessView(mBrightnessSliderController.getRootView());

        mBrightnessController = brightnessControllerFactory.create(mBrightnessSliderController);
        mBrightnessMirrorHandler = new BrightnessMirrorHandler(mBrightnessController);
    }

    @Override
    protected void onInit() {
        super.onInit();
        updateMediaExpansion();
        mMediaHost.setShowsOnlyActiveMedia(true);
        mMediaHost.init(MediaHierarchyManager.LOCATION_QQS);
        mBrightnessSliderController.init();
        
        mView.setUsingHorizontalLayout(false, mMediaHost.getHostView(), false);
        mView.setShouldMoveMediaOnExpansion(false);
        
        // CRITICAL FIX: Subscribe to TileLayout resize events to refresh QQS immediately
        TileLayout.addResizeListener(this::setTiles);
    }

    private void updateMediaExpansion() {
        int rotation = getRotation();
        boolean isLandscape = rotation == RotationUtils.ROTATION_LANDSCAPE
                || rotation == RotationUtils.ROTATION_SEASCAPE;
        boolean usingCollapsedLandscapeMedia = mUsingCollapsedLandscapeMediaProvider.get();
        if (!usingCollapsedLandscapeMedia || !isLandscape) {
            mMediaHost.setExpansion(MediaHost.EXPANDED);
        } else {
            mMediaHost.setExpansion(MediaHost.COLLAPSED);
        }
    }

    @VisibleForTesting
    protected int getRotation() {
        return RotationUtils.getRotation(getContext());
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        mTunerService.addTunable(mView, QSPanel.QS_SHOW_AUTO_BRIGHTNESS);
        mTunerService.addTunable(mView, QSPanel.QS_SHOW_BRIGHTNESS_SLIDER);

        mView.setBrightnessRunnable(() -> {
            mView.updateResources();
            updateBrightnessMirror();
        });

        mBrightnessMirrorHandler.onQsPanelAttached();
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mTunerService.removeTunable(mView);
        mView.setBrightnessRunnable(null);
        mBrightnessMirrorHandler.onQsPanelDettached();
    }

    private void updateBrightnessMirror() {
        if (mBrightnessMirrorController != null) {
            mBrightnessSliderController.setMirrorControllerAndMirror(mBrightnessMirrorController);
        }
    }

    @Override
    void setListening(boolean listening) {
        super.setListening(listening);

        if (listening) {
            mBrightnessController.registerCallbacks();
        } else {
            mBrightnessController.unregisterCallbacks();
        }
    }

    public boolean isListening() {
        return mView.isListening();
    }

    private void setMaxTiles(int parseNumTiles) {
        mView.setMaxTiles(parseNumTiles);
        setTiles();
    }

    @Override
    public void refreshAllTiles() {
        mBrightnessController.checkRestrictionAndSetEnabled();
        super.refreshAllTiles();
    }

    @Override
    protected void onConfigurationChanged() {
        updateMediaExpansion();
        setTiles();
    }

    @Override
    public void setTiles() {
        List<QSTile> tiles = new ArrayList<>();
        
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
        
        int currentRow = 0;
        int currentColumn = 0;
        int maxColumns = 4;
        int maxRows = 2;
        
        for (QSTile tile : mHost.getTiles()) {
            boolean isCircle = prefs.getBoolean(PREF_PREFIX_SHAPE + tile.getTileSpec(), true);
            int span = isCircle ? 1 : 2;

            if (currentColumn + span > maxColumns) {
                currentRow++;
                currentColumn = 0;
            }

            if (currentRow >= maxRows) {
                break;
            }

            tiles.add(tile);
            
            currentColumn += span;
            
            if (currentColumn >= maxColumns) {
                currentRow++;
                currentColumn = 0;
            }
        }
        
        super.setTiles(tiles, true);
        
        // Force immediate layout update
        if (mView != null) {
            mView.post(() -> {
                mView.requestLayout();
                mView.invalidate();
            });
        }
    }

    public void setContentMargins(int marginStart, int marginEnd) {
        mView.setContentMargins(marginStart, marginEnd, mMediaHost.getHostView());
    }

    public int getNumQuickTiles() {
        return mView.getNumQuickTiles();
    }

    public void setBrightnessMirror(BrightnessMirrorController brightnessMirrorController) {
        mBrightnessMirrorController = brightnessMirrorController;
        mBrightnessMirrorHandler.setController(brightnessMirrorController);
    }
}
