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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanelControllerBase.TileRecord;
import com.android.systemui.qs.tileimpl.HeightOverrideable;
import com.android.systemui.qs.tileimpl.QSTileViewImpl;

import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.Collections;
import java.util.Set;
import java.util.List;

public class TileLayout extends ViewGroup implements QSTileLayout {

    public static final int NO_MAX_COLUMNS = 100;
    private static final String TAG = "TileLayout";
    
    private static final String PREFS_FILE = "qs_tile_config";
    private static final String PREF_PREFIX_SHAPE = "tile_is_circle_";
    
    private static final long RESIZE_ANIMATION_DURATION = 300;

    protected int mColumns;
    protected int mCellWidth;
    protected int mResourceCellHeightResId = R.dimen.qs_tile_height;
    protected int mResourceCellHeight;
    protected int mEstimatedCellHeight;
    protected int mCellHeight;
    protected int mCellMarginHorizontal;
    protected int mCellMarginVertical;
    protected int mSidePadding;
    protected int mRows = 1;

    protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
    protected boolean mListening;
    protected int mMaxAllowedRows = 4;

    private final boolean mLessRows;
    protected int mMinRows = 1;
    private int mMaxColumns = NO_MAX_COLUMNS;
    protected int mResourceColumns;
    private float mSquishinessFraction = 1f;
    protected int mLastTileBottom;

    protected TextView mTempTextView;
    
    private static boolean sEditMode = false;
    private OnRequestLayoutListener mLayoutRequestListener;
    private Vibrator mVibrator;
    
    private AnimatorSet mCurrentResizeAnimation;

    private static final Set<TileLayout> sActiveLayouts = 
        Collections.newSetFromMap(new WeakHashMap<TileLayout, Boolean>());
        
    // New listener list for controllers (like QuickQSPanelController) to subscribe to
    private static final List<Runnable> sResizeListeners = new ArrayList<>();

    public interface OnRequestLayoutListener {
        void onRequestDistribution();
    }

    public void setOnRequestLayoutListener(OnRequestLayoutListener listener) {
        mLayoutRequestListener = listener;
    }

    public TileLayout(Context context) {
        this(context, null);
    }

    public TileLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mLessRows = ((Settings.System.getInt(context.getContentResolver(), "qs_less_rows", 0) != 0)
                || useQsMediaPlayer(context));
        mTempTextView = new TextView(context);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        setClipChildren(false);
        setClipToPadding(false);

        updateResources();
        
        sActiveLayouts.add(this);
        
        if (!this.getClass().getSimpleName().contains("QQS")) {
            setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    toggleEditMode();
                    return true;
                }
            });
        }
    }
    
    // Allow external controllers to subscribe to resize events
    public static void addResizeListener(Runnable listener) {
        if (!sResizeListeners.contains(listener)) {
            sResizeListeners.add(listener);
        }
    }
    
    public static void addLayout(TileLayout layout) {
        if (layout != null) {
            sActiveLayouts.add(layout);
        }
    }
    
    private boolean isTileCircle(String tileSpec) {
        if (tileSpec == null) return true;
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_PREFIX_SHAPE + tileSpec, true); 
    }

    private void saveTileShape(String tileSpec, boolean isCircle) {
        if (tileSpec == null) return;
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_PREFIX_SHAPE + tileSpec, isCircle).apply();
    }
    
    private static void notifyAllLayouts() {
        new Handler(Looper.getMainLooper()).post(() -> {
            // 1. Update all views
            for (TileLayout layout : sActiveLayouts) {
                if (layout != null) {
                    layout.updateAllTilesVisuals();
                    layout.requestLayout();
                    layout.invalidate();
                    if (layout.mLayoutRequestListener != null) {
                        layout.mLayoutRequestListener.onRequestDistribution();
                    }
                }
            }
            // 2. Notify controllers to refresh their tile lists (Specific Fix for QQS)
            for (Runnable listener : sResizeListeners) {
                listener.run();
            }
        });
    }
    
    private void toggleEditMode() {
        sEditMode = !sEditMode;
        if (mVibrator != null && mVibrator.hasVibrator()) {
            VibrationEffect effect = VibrationEffect.createPredefined(
                sEditMode ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK
            );
            mVibrator.vibrate(effect);
        }
        
        String msg = sEditMode ? "Edit Mode ON - Tap handles to resize" : "Edit Mode OFF";
        Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
        
        notifyAllLayouts();
    }
    
    private void updateAllTilesVisuals() {
        for (TileRecord record : mRecords) {
            if (record.tileView instanceof QSTileViewImpl) {
                ((QSTileViewImpl) record.tileView).setEditMode(sEditMode);
                if (record.tile != null) {
                    boolean isCircle = isTileCircle(record.tile.getTileSpec());
                    ((QSTileViewImpl) record.tileView).setTileMode(isCircle);
                }
            }
        }
    }
    
    public static void disableEditMode() {
        if (sEditMode) {
            sEditMode = false;
            notifyAllLayouts();
        }
    }
    
    private void onTileResizeClicked(TileRecord record) {
        if (record.tile == null || !(record.tileView instanceof QSTileViewImpl)) return;
        
        String spec = record.tile.getTileSpec();
        QSTileViewImpl view = (QSTileViewImpl) record.tileView;
        
        boolean currentIsCircle = isTileCircle(spec);
        boolean newShape = !currentIsCircle;
        
        if (mVibrator != null && mVibrator.hasVibrator()) {
            mVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        }
        
        saveTileShape(spec, newShape);

        animateTileResize(view, currentIsCircle, newShape, () -> {
            notifyAllLayouts();
        });
    }
    
    private void animateTileResize(QSTileViewImpl view, boolean wasCircle, boolean willBeCircle, Runnable onComplete) {
        if (mCurrentResizeAnimation != null && mCurrentResizeAnimation.isRunning()) {
            mCurrentResizeAnimation.cancel();
        }
        
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.9f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.9f, 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.7f, 1f);
        
        mCurrentResizeAnimation = new AnimatorSet();
        mCurrentResizeAnimation.playTogether(scaleX, scaleY, alpha);
        mCurrentResizeAnimation.setDuration(RESIZE_ANIMATION_DURATION);
        
        mCurrentResizeAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setTileMode(willBeCircle);
                view.setScaleX(1f);
                view.setScaleY(1f);
                view.setAlpha(1f);
                
                if (onComplete != null) {
                    onComplete.run();
                }
                mCurrentResizeAnimation = null;
            }
        });
        
        mCurrentResizeAnimation.start();
    }

    @Override
    public int getOffsetTop(TileRecord tile) {
        return getTop();
    }

    public void setListening(boolean listening) {
        setListening(listening, null);
    }

    @Override
    public void setListening(boolean listening, @Nullable UiEventLogger uiEventLogger) {
        if (mListening == listening) return;
        mListening = listening;
        for (TileRecord record : mRecords) {
            record.tile.setListening(this, mListening);
        }
    }

    @Override
    public boolean setMinRows(int minRows) {
        if (minRows < 2) minRows = 2;
        
        if (mMinRows != minRows) {
            mMinRows = minRows;
            updateResources();
            return true;
        }
        return false;
    }

    @Override
    public boolean setMaxColumns(int maxColumns) {
        mMaxColumns = maxColumns;
        return updateColumns();
    }

    public void addTile(TileRecord tile) {
        mRecords.add(tile);
        tile.tile.setListening(this, mListening);
        
        if (tile.tileView instanceof QSTileViewImpl) {
            QSTileViewImpl view = (QSTileViewImpl) tile.tileView;
            
            String spec = tile.tile.getTileSpec();
            boolean isCircle = isTileCircle(spec);
            
            view.setTileMode(isCircle);
            view.setEditMode(sEditMode);
            
            view.setOnResizeClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    onTileResizeClicked(tile);
                }
            });
        }
        
        addTileView(tile);
    }

    protected void addTileView(TileRecord tile) {
        addView(tile.tileView);
    }

    @Override
    public void removeTile(TileRecord tile) {
        mRecords.remove(tile);
        tile.tile.setListening(this, false);
        removeView(tile.tileView);
    }

    public void removeAllViews() {
        for (TileRecord record : mRecords) {
            record.tile.setListening(this, false);
        }
        mRecords.clear();
        super.removeAllViews();
    }

    public boolean updateResources() {
        Resources res = getResources();
        mResourceColumns = 4;
        mResourceCellHeight = res.getDimensionPixelSize(mResourceCellHeightResId);
        mCellMarginHorizontal = res.getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal);
        mSidePadding = useSidePadding() ? mCellMarginHorizontal / 2 : 0;
        mCellMarginVertical= res.getDimensionPixelSize(R.dimen.qs_tile_margin_vertical);
        
        int defaultMaxRows = Math.max(1, getResources().getInteger(R.integer.quick_settings_max_rows));
        mMaxAllowedRows = Math.max(mMinRows, defaultMaxRows);
        
        if (mLessRows) {
            mMaxAllowedRows = Math.max(mMinRows, mMaxAllowedRows - 1);
        }
        mTempTextView.dispatchConfigurationChanged(mContext.getResources().getConfiguration());
        if (updateColumns()) {
            requestLayout();
            return true;
        }
        return false;
    }

    protected boolean useSidePadding() {
        return false;
    }

    private boolean updateColumns() {
        int oldColumns = mColumns;
        mColumns = Math.min(mResourceColumns, mMaxColumns);
        return oldColumns != mColumns;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int numTiles = mRecords.size();
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int availableWidth = width - getPaddingStart() - getPaddingEnd();
        
        final int gaps = mColumns - 1;
        final int singleCellWidth = (availableWidth - (mCellMarginHorizontal * gaps) - mSidePadding * 2) / mColumns;

        int currentRow = 0;
        int currentColumn = 0;
        int tilesPlaced = 0;
        
        View previousView = this;
        int verticalMeasure = exactly(getCellHeight());

        for (int i = 0; i < numTiles; i++) {
            TileRecord record = mRecords.get(i);
            if (record.tileView.getVisibility() == GONE) continue;

            boolean isCircle = true;
            if (record.tile != null) {
                isCircle = isTileCircle(record.tile.getTileSpec());
            }
            int span = isCircle ? 1 : 2;

            if (record.tileView instanceof QSTileViewImpl) {
                ((QSTileViewImpl) record.tileView).setTileMode(isCircle);
                ((QSTileViewImpl) record.tileView).setEditMode(sEditMode);
            }

            if (currentColumn + span > mColumns) {
                currentRow++;
                currentColumn = 0;
            }
            
            if (currentRow >= mMaxAllowedRows) {
                record.tileView.measure(exactly(0), exactly(0));
                continue;
            }

            int tileWidth = (span * singleCellWidth) + ((span - 1) * mCellMarginHorizontal);

            record.tileView.measure(exactly(tileWidth), verticalMeasure);
            previousView = record.tileView.updateAccessibilityOrder(previousView);
            
            if (i == 0 || mCellHeight <= 0) {
                 mCellHeight = record.tileView.getMeasuredHeight();
            }

            currentColumn += span;
            tilesPlaced++;
            
            if (currentColumn >= mColumns) {
                currentRow++;
                currentColumn = 0;
            }
        }

        if (tilesPlaced == 0) {
            mRows = 0;
        } else if (currentColumn == 0 && currentRow > 0) {
            mRows = currentRow; 
        } else {
            mRows = currentRow + 1;
        }
        
        if (mRows < mMinRows) {
             mRows = mMinRows;
        }
        
        if (mRows > mMaxAllowedRows) {
            mRows = mMaxAllowedRows;
        }

        int height = (mCellHeight + mCellMarginVertical) * mRows;
        height -= mCellMarginVertical;
        if (height < 0) height = 0;

        setMeasuredDimension(width, height);
    }

    public boolean updateMaxRows(int allowedHeight, int tilesCount) {
        final int availableHeight =  allowedHeight + mCellMarginVertical;
        final int previousRows = mRows;
        
        int calculatedRows = availableHeight / (getCellHeight() + mCellMarginVertical);
        
        if (calculatedRows < mMinRows) {
            calculatedRows = mMinRows;
        }
        
        mRows = calculatedRows;
        
        if (mRows >= mMaxAllowedRows) {
            mRows = mMaxAllowedRows;
        }
        return previousRows != mRows;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    protected static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    protected void estimateCellHeight() {
        mEstimatedCellHeight = mResourceCellHeight;
    }

    protected int getCellHeight() {
        return mResourceCellHeight;
    }

    private void layoutTileRecords(int numRecords, boolean forLayout) {
        final boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        
        int row = 0;
        int column = 0; 
        mLastTileBottom = 0;

        int width = getMeasuredWidth();
        int availableWidth = width - getPaddingStart() - getPaddingEnd();
        int gaps = mColumns - 1;
        int singleCellWidth = (availableWidth - (mCellMarginHorizontal * gaps) - mSidePadding * 2) / mColumns;

        for (int i = 0; i < numRecords; i++) {
            final TileRecord record = mRecords.get(i);
            if (record.tileView.getVisibility() == GONE) continue;
            
            if (row >= mMaxAllowedRows) {
                record.tileView.layout(0,0,0,0);
                continue;
            }

            boolean isCircle = true;
            if (record.tile != null) {
                isCircle = isTileCircle(record.tile.getTileSpec());
            }
            int span = isCircle ? 1 : 2;

            if (column + span > mColumns) {
                row++;
                column = 0;
                if (row >= mMaxAllowedRows) {
                    record.tileView.layout(0,0,0,0);
                    continue;
                }
            }

            int slotWidth = (span * singleCellWidth) + ((span - 1) * mCellMarginHorizontal);
            int tileWidth = record.tileView.getMeasuredWidth();
            int centerOffset = (slotWidth - tileWidth) / 2;

            int gridLeft = getPaddingStart() + mSidePadding + (column * (singleCellWidth + mCellMarginHorizontal));
            int leftPos = gridLeft + centerOffset;
            
            final int top = getRowTop(row);
            final int right = leftPos + tileWidth;
            final int bottom = top + record.tileView.getMeasuredHeight();

            if (forLayout) {
                record.tileView.layout(leftPos, top, right, bottom);
            } else {
                record.tileView.setLeftTopRightBottom(leftPos, top, right, bottom);
            }
            record.tileView.setPosition(i);
            
            mLastTileBottom = top + record.tileView.getMeasuredHeight();

            column += span;
            if (column >= mColumns) {
                row++;
                column = 0;
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutTileRecords(mRecords.size(), true);
    }

    protected int getRowTop(int row) {
        return row * (getCellHeight() + mCellMarginVertical);
    }

    protected int getColumnStart(int column) {
        return getPaddingStart() + mSidePadding
                + column * (mCellWidth + mCellMarginHorizontal);
    }

    @Override
    public int getNumVisibleTiles() {
        return mRecords.size();
    }

    public boolean isFull() {
        return false;
    }

    public int maxTiles() {
        return Math.max(mColumns * mRows, 1);
    }

    @Override
    public int getTilesHeight() {
        return mLastTileBottom + getPaddingBottom();
    }

    @Override
    public void setSquishinessFraction(float squishinessFraction) {
    }

    @Override
    public void setExpansion(float expansion, float proposedTranslation) {
        if (expansion == 0f) {
            TileLayout.disableEditMode();
        }

        int row = 0;
        int column = 0;
        int columns = mColumns > 0 ? mColumns : 4; 

        for (TileRecord record : mRecords) {
             if (record.tileView.getVisibility() == GONE) continue;

             boolean isCircle = true;
             if (record.tile != null) {
                 isCircle = isTileCircle(record.tile.getTileSpec());
             }
             int span = isCircle ? 1 : 2;

             if (column + span > columns) {
                 row++;
                 column = 0;
             }

             if (row >= 2) {
                 record.tileView.setAlpha(expansion);
                 float scale = 0.8f + (0.2f * expansion);
                 record.tileView.setScaleX(scale);
                 record.tileView.setScaleY(scale);
                 record.tileView.setTranslationX(0);
                 record.tileView.setTranslationY(0);
             } else {
                 record.tileView.setAlpha(1f);
                 record.tileView.setScaleX(1f);
                 record.tileView.setScaleY(1f);
             }

             column += span;
             if (column >= columns) {
                 row++;
                 column = 0;
             }
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        info.setCollectionInfo(
                new AccessibilityNodeInfo.CollectionInfo(mRecords.size(), 1, false));
    }
}
