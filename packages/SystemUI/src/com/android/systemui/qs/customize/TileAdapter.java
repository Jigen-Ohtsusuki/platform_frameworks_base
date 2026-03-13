/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.customize;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import androidx.recyclerview.widget.RecyclerView.State;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.qs.QSEditEvent;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.TileLayout;
import com.android.systemui.qs.customize.TileAdapter.Holder;
import com.android.systemui.qs.customize.TileQueryHelper.TileInfo;
import com.android.systemui.qs.customize.TileQueryHelper.TileStateListener;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.dagger.QSThemedContext;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.tileimpl.QSIconViewImpl;
import com.android.systemui.qs.tileimpl.QSTileViewImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

@QSScope
public class TileAdapter extends RecyclerView.Adapter<Holder> implements TileStateListener {
    private static final long DRAG_LENGTH = 100;
    public static final long MOVE_DURATION = 150;

    private static final int TYPE_TILE = 0;
    private static final int TYPE_EDIT = 1;
    private static final int TYPE_ACCESSIBLE_DROP = 2;
    private static final int TYPE_HEADER = 3;
    private static final int TYPE_DIVIDER = 4;

    private static final int ACTION_NONE = 0;
    private static final int ACTION_ADD = 1;
    private static final int ACTION_MOVE = 2;

    private static final int NUM_COLUMNS_ID = R.integer.quick_settings_num_columns;

    private static final String PREFS_FILE = "qs_tile_config";
    private static final String PREF_PREFIX_SHAPE = "tile_is_circle_";

    private final Context mContext;

    private final Handler mHandler = new Handler();
    private final List<TileInfo> mTiles = new ArrayList<>();
    private final ItemTouchHelper mItemTouchHelper;
    private final SlotGridDecoration mSlotGridDecoration;
    private final MarginTileDecoration mMarginDecoration;
    private final int mMinNumTiles;
    private final QSHost mHost;
    private int mEditIndex;
    private int mTileDividerIndex;
    private int mFocusIndex;

    private boolean mNeedsFocus;
    @Nullable
    private List<String> mCurrentSpecs;
    @Nullable
    private List<TileInfo> mOtherTiles;
    @Nullable
    private List<TileInfo> mAllTiles;

    @Nullable
    private Holder mCurrentDrag;
    private int mAccessibilityAction = ACTION_NONE;
    private int mAccessibilityFromIndex;
    private final UiEventLogger mUiEventLogger;
    @Nullable
    private RecyclerView mRecyclerView;
    private int mNumColumns;

    private TextView mTempTextView;
    private int mMinTileViewHeight;

    @Inject
    public TileAdapter(
            @QSThemedContext Context context,
            QSHost qsHost,
            UiEventLogger uiEventLogger) {
        mContext = context;
        mHost = qsHost;
        mUiEventLogger = uiEventLogger;
        mItemTouchHelper = new ItemTouchHelper(mCallbacks);
        mSlotGridDecoration = new SlotGridDecoration(context);
        mMarginDecoration = new MarginTileDecoration(context);
        mMinNumTiles = context.getResources().getInteger(R.integer.quick_settings_min_num_tiles);
        mNumColumns = 4;
        mSizeLookup.setSpanIndexCacheEnabled(true);
        mTempTextView = new TextView(context);
        mMinTileViewHeight = context.getResources().getDimensionPixelSize(R.dimen.qs_tile_height);
    }

    private boolean isTileCircle(String tileSpec) {
        if (tileSpec == null) return true;
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_PREFIX_SHAPE + tileSpec, true);
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        mRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        mRecyclerView = null;
    }

    public boolean updateNumColumns() {
        if (mNumColumns != 4) {
            mNumColumns = 4;
            return true;
        }
        return false;
    }

    public int getNumColumns() {
        return mNumColumns;
    }

    public ItemTouchHelper getItemTouchHelper() {
        return mItemTouchHelper;
    }

    public ItemDecoration getSlotGridDecoration() {
        return mSlotGridDecoration;
    }

    public ItemDecoration getMarginItemDecoration() {
        return mMarginDecoration;
    }

    public void saveSpecs(QSHost host) {
        List<String> newSpecs = new ArrayList<>();
        clearAccessibilityState();
        for (int i = 1; i < mTiles.size() && mTiles.get(i) != null; i++) {
            newSpecs.add(mTiles.get(i).spec);
        }
        host.changeTilesByUser(mCurrentSpecs, newSpecs);
        mCurrentSpecs = newSpecs;
    }

    private void clearAccessibilityState() {
        mNeedsFocus = false;
        if (mAccessibilityAction == ACTION_ADD) {
            mTiles.remove(--mEditIndex);
            notifyDataSetChanged();
        }
        mAccessibilityAction = ACTION_NONE;
    }

    public void resetTileSpecs(List<String> specs) {
        mHost.changeTilesByUser(mCurrentSpecs, specs);
        setTileSpecs(specs);
    }

    public void setTileSpecs(List<String> currentSpecs) {
        if (currentSpecs.equals(mCurrentSpecs)) {
            return;
        }
        mCurrentSpecs = currentSpecs;
        recalcSpecs();
    }

    @Override
    public void onTilesChanged(List<TileInfo> tiles) {
        mAllTiles = tiles;
        recalcSpecs();
    }

    private void recalcSpecs() {
        if (mCurrentSpecs == null || mAllTiles == null) {
            return;
        }
        mOtherTiles = new ArrayList<TileInfo>(mAllTiles);
        mTiles.clear();
        mTiles.add(null);
        for (int i = 0; i < mCurrentSpecs.size(); i++) {
            final TileInfo tile = getAndRemoveOther(mCurrentSpecs.get(i));
            if (tile != null) {
                mTiles.add(tile);
            }
        }
        mTiles.add(null);
        for (int i = 0; i < mOtherTiles.size(); i++) {
            final TileInfo tile = mOtherTiles.get(i);
            if (tile.isSystem) {
                mOtherTiles.remove(i--);
                mTiles.add(tile);
            }
        }
        mTileDividerIndex = mTiles.size();
        mTiles.add(null);
        mTiles.addAll(mOtherTiles);
        updateDividerLocations();
        notifyDataSetChanged();
    }

    @Nullable
    private TileInfo getAndRemoveOther(String s) {
        for (int i = 0; i < mOtherTiles.size(); i++) {
            if (mOtherTiles.get(i).spec.equals(s)) {
                return mOtherTiles.remove(i);
            }
        }
        return null;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) {
            return TYPE_HEADER;
        }
        if (mAccessibilityAction == ACTION_ADD && position == mEditIndex - 1) {
            return TYPE_ACCESSIBLE_DROP;
        }
        if (position == mTileDividerIndex) {
            return TYPE_DIVIDER;
        }
        if (mTiles.get(position) == null) {
            return TYPE_EDIT;
        }
        return TYPE_TILE;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        final Context context = parent.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        if (viewType == TYPE_HEADER) {
            View v = inflater.inflate(R.layout.qs_customize_header, parent, false);
            v.setMinimumHeight(calculateHeaderMinHeight(context));
            return new Holder(v);
        }
        if (viewType == TYPE_DIVIDER) {
            return new Holder(inflater.inflate(R.layout.qs_customize_tile_divider, parent, false));
        }
        if (viewType == TYPE_EDIT) {
            return new Holder(inflater.inflate(R.layout.qs_customize_divider, parent, false));
        }
        
        FrameLayout frame = (FrameLayout) inflater.inflate(R.layout.qs_customize_tile_frame, parent, false);
        
        // OVERRIDE XML MARGINS: We force them to 0 so the ItemDecoration handles perfect gaps!
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) frame.getLayoutParams();
        mlp.topMargin = 0;
        mlp.bottomMargin = 0;
        mlp.leftMargin = 0;
        mlp.rightMargin = 0;
        frame.setLayoutParams(mlp);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        
        CustomizeTileView view = new CustomizeTileView(context, new QSIconViewImpl(context));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER; 
        frame.addView(view, lp);
        return new Holder(frame);
    }

    @Override
    public int getItemCount() {
        return mTiles.size();
    }

    @Override
    public boolean onFailedToRecycleView(Holder holder) {
        holder.stopDrag();
        holder.clearDrag();
        return true;
    }

    private void setSelectableForHeaders(View view) {
        final boolean selectable = mAccessibilityAction == ACTION_NONE;
        view.setFocusable(selectable);
        view.setImportantForAccessibility(selectable
                ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.setFocusableInTouchMode(selectable);
    }

    @Override
    public void onBindViewHolder(final Holder holder, int position) {
        if (holder.mTileView != null) {
            // FIX: Force FrameLayout to match exact qs_tile_height vertically
            holder.itemView.setMinimumHeight(mMinTileViewHeight);
            ViewGroup.LayoutParams containerLp = holder.itemView.getLayoutParams();
            containerLp.height = mMinTileViewHeight;
            holder.itemView.setLayoutParams(containerLp);
        }

        if (holder.getItemViewType() == TYPE_HEADER) {
            setSelectableForHeaders(holder.itemView);
            return;
        }
        if (holder.getItemViewType() == TYPE_DIVIDER) {
            holder.itemView.setVisibility(mTileDividerIndex < mTiles.size() - 1 ? View.VISIBLE
                    : View.INVISIBLE);
            return;
        }
        if (holder.getItemViewType() == TYPE_EDIT) {
            final String titleText;
            Resources res = mContext.getResources();
            if (mCurrentDrag == null) {
                titleText = res.getString(R.string.drag_to_add_tiles);
            } else if (!canRemoveTiles() && mCurrentDrag.getAdapterPosition() < mEditIndex) {
                titleText = res.getString(R.string.drag_to_remove_disabled, mMinNumTiles);
            } else {
                titleText = res.getString(R.string.drag_to_remove_tiles);
            }

            ((TextView) holder.itemView.findViewById(android.R.id.title)).setText(titleText);
            setSelectableForHeaders(holder.itemView);
            return;
        }
        if (holder.getItemViewType() == TYPE_ACCESSIBLE_DROP) {
            holder.mTileView.setClickable(true);
            holder.mTileView.setFocusable(true);
            holder.mTileView.setFocusableInTouchMode(true);
            holder.mTileView.setVisibility(View.VISIBLE);
            holder.mTileView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            holder.mTileView.setContentDescription(mContext.getString(
                    R.string.accessibility_qs_edit_tile_add_to_position, position));
            holder.mTileView.setOnClickListener(v -> selectPosition(holder.getLayoutPosition()));
            focusOnHolder(holder);
            return;
        }

        TileInfo info = mTiles.get(position);

        CustomizeTileView tileView =
                Objects.requireNonNull(
                        holder.getTileAsCustomizeView(), "The holder must have a tileView");
        
        boolean isAdded = position < mEditIndex;
        
        // INSTANT CIRCLE FIX: If tile is moved to inactive pool, FORCE it to be a circle right now
        boolean isCircle = !isAdded || isTileCircle(info.spec);

        if (mRecyclerView != null) {
            int recyclerWidth = mRecyclerView.getWidth();
            if (recyclerWidth <= 0) {
                recyclerWidth = mContext.getResources().getDisplayMetrics().widthPixels;
            }
            int sidePadding = mContext.getResources().getDimensionPixelSize(R.dimen.qs_horizontal_margin);
            int availableWidth = recyclerWidth - (sidePadding * 2);
            int horizontalMargin = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal);
            int singleCellWidth = (availableWidth - (horizontalMargin * 3)) / 4;
            
            int width = isCircle ? singleCellWidth : (singleCellWidth * 2) + horizontalMargin;
            
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tileView.getLayoutParams();
            if (lp == null) lp = new FrameLayout.LayoutParams(width, mMinTileViewHeight);
            lp.width = width;
            lp.height = mMinTileViewHeight;
            lp.gravity = Gravity.CENTER;
            tileView.setLayoutParams(lp);
        }

        tileView.changeState(info.state);
        tileView.setShowAppLabel(position > mEditIndex && !info.isSystem);
        tileView.setShowSideView(position < mEditIndex || info.isSystem);
        
        tileView.setTileMode(isCircle);
        
        // INSTANT HIDE RESIZE HANDLE: Turn off handle instantly if not added
        tileView.setEditMode(isAdded);

        if (isAdded) {
            tileView.setOnResizeClickListener(v -> {
                boolean currentCircle = isTileCircle(info.spec);
                SharedPreferences prefs = mContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
                prefs.edit().putBoolean(PREF_PREFIX_SHAPE + info.spec, !currentCircle).apply();
                notifyItemChanged(holder.getAdapterPosition());
                TileLayout.broadcastTileSizeChange();
            });
        } else {
            tileView.setOnResizeClickListener(null);
        }

        holder.mTileView.setSelected(true);

        holder.mTileView.setOnClickListener(v -> {
            int pos = holder.getLayoutPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            
            if (pos < mEditIndex) {
                if (canRemoveTiles()) move(pos, mEditIndex);
            } else {
                move(pos, mEditIndex);
            }
        });
    }

    private void focusOnHolder(Holder holder) {
        if (mNeedsFocus) {
            holder.mTileView.requestLayout();
            holder.mTileView.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    holder.mTileView.removeOnLayoutChangeListener(this);
                    holder.mTileView.requestAccessibilityFocus();
                }
            });
            mNeedsFocus = false;
            mFocusIndex = RecyclerView.NO_POSITION;
        }
    }

    private boolean canRemoveTiles() {
        return mCurrentSpecs.size() > mMinNumTiles;
    }

    private void selectPosition(int position) {
        if (mAccessibilityAction == ACTION_ADD) {
            mTiles.remove(mEditIndex--);
        }
        mAccessibilityAction = ACTION_NONE;
        move(mAccessibilityFromIndex, position, false);
        mFocusIndex = position;
        mNeedsFocus = true;
        notifyDataSetChanged();
    }

    private void startAccessibleAdd(int position) {
        mAccessibilityFromIndex = position;
        mAccessibilityAction = ACTION_ADD;
        mTiles.add(mEditIndex++, null);
        mTileDividerIndex++;
        mFocusIndex = mEditIndex - 1;
        mNeedsFocus = true;
        if (mRecyclerView != null) {
            mRecyclerView.post(() -> mRecyclerView.smoothScrollToPosition(mFocusIndex));
        }
        notifyDataSetChanged();
    }

    private void startAccessibleMove(int position) {
        mAccessibilityFromIndex = position;
        mAccessibilityAction = ACTION_MOVE;
        mFocusIndex = position;
        mNeedsFocus = true;
        notifyDataSetChanged();
    }

    private boolean canRemoveFromPosition(int position) {
        return canRemoveTiles() && isCurrentTile(position);
    }

    // THIS METHOD WAS MISSING, NOW RESTORED
    private boolean isCurrentTile(int position) {
        return position < mEditIndex;
    }

    private boolean canAddFromPosition(int position) {
        return position > mEditIndex;
    }

    private boolean addFromPosition(int position) {
        if (!canAddFromPosition(position)) return false;
        move(position, mEditIndex);
        return true;
    }

    private boolean removeFromPosition(int position) {
        if (!canRemoveFromPosition(position)) return false;
        TileInfo info = mTiles.get(position);
        move(position, info.isSystem ? mEditIndex : mTileDividerIndex);
        return true;
    }

    public SpanSizeLookup getSizeLookup() {
        return mSizeLookup;
    }

    private boolean move(int from, int to) {
        return move(from, to, true);
    }

    private boolean move(int from, int to, boolean notify) {
        if (to == from) return true;
        
        move(from, to, mTiles, notify);
        updateDividerLocations(); // Recalculates mEditIndex
        
        if (to >= mEditIndex) {
            mUiEventLogger.log(QSEditEvent.QS_EDIT_REMOVE, 0, strip(mTiles.get(to)));
        } else if (from >= mEditIndex) {
            mUiEventLogger.log(QSEditEvent.QS_EDIT_ADD, 0, strip(mTiles.get(to)));
        } else {
            mUiEventLogger.log(QSEditEvent.QS_EDIT_MOVE, 0, strip(mTiles.get(to)));
        }
        
        // SMART REVERT: Tile crosses into inactive pool - wipe memory instantly
        if (to >= mEditIndex && to < mTiles.size()) {
            TileInfo info = mTiles.get(to);
            if (info != null && info.spec != null && !isTileCircle(info.spec)) {
                SharedPreferences prefs = mContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
                prefs.edit().putBoolean(PREF_PREFIX_SHAPE + info.spec, true).apply();
            }
        }
        
        saveSpecs(mHost);
        
        // INSTANT REFRESH ON CLICK: Make sure the UI redraws handles and shapes right now
        mHandler.post(() -> {
            notifyItemChanged(to);
            TileLayout.broadcastTileSizeChange();
        });
        
        return true;
    }

    private void updateDividerLocations() {
        mEditIndex = -1;
        mTileDividerIndex = mTiles.size();
        for (int i = 1; i < mTiles.size(); i++) {
            if (mTiles.get(i) == null) {
                if (mEditIndex == -1) {
                    mEditIndex = i;
                } else {
                    mTileDividerIndex = i;
                }
            }
        }
        if (mTiles.size() - 1 == mTileDividerIndex) {
            notifyItemChanged(mTileDividerIndex);
        }
    }

    private static String strip(TileInfo tileInfo) {
        String spec = tileInfo.spec;
        if (spec.startsWith(CustomTile.PREFIX)) {
            ComponentName component = CustomTile.getComponentFromSpec(spec);
            return component.getPackageName();
        }
        return spec;
    }

    private <T> void move(int from, int to, List<T> list, boolean notify) {
        list.add(to, list.remove(from));
        if (notify) notifyItemMoved(from, to);
    }

    public class Holder extends ViewHolder {
        @Nullable private QSTileViewImpl mTileView;

        public Holder(View itemView) {
            super(itemView);
            if (itemView instanceof FrameLayout) {
                mTileView = (QSTileViewImpl) ((FrameLayout) itemView).getChildAt(0);
                mTileView.getIcon().disableAnimation();
                mTileView.setTag(this);
            }
        }

        @Nullable
        public CustomizeTileView getTileAsCustomizeView() {
            return (CustomizeTileView) mTileView;
        }

        public void clearDrag() {
            itemView.clearAnimation();
            itemView.setScaleX(1);
            itemView.setScaleY(1);
            itemView.setAlpha(1.0f);
        }

        public void startDrag() {
            // BUG FIXED: Replaced 1.2x scale glitch with simple clean opacity change
            itemView.animate()
                    .setDuration(DRAG_LENGTH)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(0.7f);
        }

        public void stopDrag() {
            itemView.animate()
                    .setDuration(DRAG_LENGTH)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(1.0f);
        }

        boolean canRemove() { return canRemoveFromPosition(getLayoutPosition()); }
        boolean canAdd() { return canAddFromPosition(getLayoutPosition()); }
        void toggleState() { if (canAdd()) add(); else remove(); }
        private void add() { if (addFromPosition(getLayoutPosition())) announce("added"); }
        private void remove() { if (removeFromPosition(getLayoutPosition())) announce("removed"); }
        private void announce(String act) { itemView.announceForAccessibility(act); }
        boolean isCurrentTile() { return TileAdapter.this.isCurrentTile(getLayoutPosition()); }
        void startAccessibleAdd() { TileAdapter.this.startAccessibleAdd(getLayoutPosition()); }
        void startAccessibleMove() { TileAdapter.this.startAccessibleMove(getLayoutPosition()); }
        boolean canTakeAccessibleAction() { return mAccessibilityAction == ACTION_NONE; }
    }

    private final SpanSizeLookup mSizeLookup = new SpanSizeLookup() {
        @Override
        public int getSpanSize(int position) {
            final int type = getItemViewType(position);
            if (type == TYPE_EDIT || type == TYPE_DIVIDER || type == TYPE_HEADER) {
                return mNumColumns;
            } else {
                if (position >= 0 && position < mTiles.size()) {
                    TileInfo info = mTiles.get(position);
                    if (info != null && info.spec != null) {
                        boolean isAdded = position < mEditIndex;
                        boolean isCircle = !isAdded || isTileCircle(info.spec);
                        return isCircle ? 1 : 2;
                    }
                }
                return 1;
            }
        }
    };

    // STRUCTURAL GRID UI: 4x4 Empty Physical Slots with Pagination Rules
    private class SlotGridDecoration extends ItemDecoration {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mRadius;
        private final int mHorizontalMargin;
        private final int mVerticalMargin;

        private SlotGridDecoration(Context context) {
            mPaint.setColor(Color.parseColor("#12FFFFFF")); // Dark empty slot color
            mPaint.setStyle(Paint.Style.FILL);
            mRadius = context.getResources().getDimensionPixelSize(R.dimen.qs_corner_radius);
            mHorizontalMargin = context.getResources().getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal);
            mVerticalMargin = context.getResources().getDimensionPixelSize(R.dimen.qs_tile_margin_vertical);
        }

        @Override
        public void onDraw(Canvas c, RecyclerView parent, State state) {
            super.onDraw(c, parent, state);

            int activeSpans = 0;
            for (int i = 1; i < mEditIndex; i++) {
                activeSpans += mSizeLookup.getSpanSize(i);
            }
            if (activeSpans == 0) return;

            // Strict Pagination: Always multiple of 16 slots (4x4)
            int pages = (int) Math.ceil(activeSpans / 16.0);
            if (pages == 0) pages = 1;

            // Find actual canvas Y coordinate of the first active row
            int firstActiveY = -1;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                int pos = parent.getChildAdapterPosition(child);
                
                if (pos == 0) { // Header
                    firstActiveY = child.getBottom() + (mVerticalMargin / 2);
                    break;
                } else if (pos > 0 && pos < mEditIndex) {
                    GridLayoutManager lm = (GridLayoutManager) parent.getLayoutManager();
                    if (lm != null) {
                        // FIX: Proper usage of getSpanGroupIndex through SpanSizeLookup
                        int spanRow = lm.getSpanSizeLookup().getSpanGroupIndex(pos, lm.getSpanCount());
                        firstActiveY = child.getTop() - (mVerticalMargin / 2) - (spanRow * (mMinTileViewHeight + mVerticalMargin));
                        break;
                    }
                }
            }

            if (firstActiveY == -1) return;

            int sidePadding = parent.getPaddingLeft();
            int availableWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            int cellWidth = (availableWidth - (mHorizontalMargin * 3)) / 4;
            int cellHeight = mMinTileViewHeight;

            // Draw the empty physical slots behind the active tiles
            for (int p = 0; p < pages; p++) {
                for (int r = 0; r < 4; r++) {
                    for (int col = 0; col < 4; col++) {
                        int x = sidePadding + col * (cellWidth + mHorizontalMargin);
                        int y = firstActiveY + (p * 4 + r) * (cellHeight + mVerticalMargin);
                        c.drawRoundRect(x, y, x + cellWidth, y + cellHeight, mRadius, mRadius, mPaint);
                    }
                }
            }
        }
    }

    private static class MarginTileDecoration extends ItemDecoration {
        private int mHorizontalMargin;
        private int mVerticalMargin;

        public MarginTileDecoration(Context context) {
            mHorizontalMargin = context.getResources().getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal);
            mVerticalMargin = context.getResources().getDimensionPixelSize(R.dimen.qs_tile_margin_vertical);
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                @NonNull RecyclerView parent, @NonNull State state) {
            if (parent.getLayoutManager() == null) return;

            int position = parent.getChildAdapterPosition(view);
            if (position == RecyclerView.NO_POSITION) return;
            
            GridLayoutManager lm = ((GridLayoutManager) parent.getLayoutManager());
            GridLayoutManager.LayoutParams lp = (GridLayoutManager.LayoutParams) view.getLayoutParams();
            int column = lp.getSpanIndex();
            int span = lp.getSpanSize();
            int count = lm.getSpanCount();

            if (view instanceof TextView || span == count) {
                super.getItemOffsets(outRect, view, parent, state);
            } else {
                // FIXED VERTICAL GAP: Half margin top and bottom perfectly clones the main QS Panel layout
                outRect.top = mVerticalMargin / 2;
                outRect.bottom = mVerticalMargin / 2;

                // FIXED HORIZONTAL GAP
                if (parent.isLayoutRtl()) {
                    outRect.right = column * mHorizontalMargin / count;
                    outRect.left = mHorizontalMargin - ((column + span) * mHorizontalMargin / count);
                } else {
                    outRect.left = column * mHorizontalMargin / count;
                    outRect.right = mHorizontalMargin - ((column + span) * mHorizontalMargin / count);
                }
            }
        }
    }

    private final ItemTouchHelper.Callback mCallbacks = new ItemTouchHelper.Callback() {

        @Override
        public boolean isLongPressDragEnabled() { return true; }

        @Override
        public boolean isItemViewSwipeEnabled() { return false; }

        @Override
        public void onSelectedChanged(ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder = null;
            }
            if (viewHolder == mCurrentDrag) return;
            
            final int dropPosition = mCurrentDrag != null ? mCurrentDrag.getAdapterPosition() : RecyclerView.NO_POSITION;
            
            if (mCurrentDrag != null) {
                int position = mCurrentDrag.getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    TileInfo info = mTiles.get(position);
                    ((CustomizeTileView) mCurrentDrag.mTileView).setShowAppLabel(position > mEditIndex && !info.isSystem);
                }
                mCurrentDrag.stopDrag();
                mCurrentDrag = null;
            }
            if (viewHolder != null) {
                mCurrentDrag = (Holder) viewHolder;
                mCurrentDrag.startDrag();
            }
            mHandler.post(() -> {
                notifyItemChanged(mEditIndex);
                if (dropPosition != RecyclerView.NO_POSITION) {
                    notifyItemChanged(dropPosition);
                }
            });
        }

        @Override
        public boolean canDropOver(RecyclerView recyclerView, ViewHolder current, ViewHolder target) {
            final int position = target.getAdapterPosition();
            if (position == 0 || position == RecyclerView.NO_POSITION) return false;
            if (!canRemoveTiles() && current.getAdapterPosition() < mEditIndex) return position < mEditIndex;
            return position <= mEditIndex + 1;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, ViewHolder viewHolder) {
            int type = viewHolder.getItemViewType();
            if (type == TYPE_EDIT || type == TYPE_DIVIDER || type == TYPE_HEADER) return makeMovementFlags(0, 0);
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.RIGHT | ItemTouchHelper.LEFT, 0);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, ViewHolder viewHolder, ViewHolder target) {
            int from = viewHolder.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from == 0 || from == RecyclerView.NO_POSITION || to == 0 || to == RecyclerView.NO_POSITION) return false;
            return move(from, to);
        }

        @Override
        public void onSwiped(ViewHolder viewHolder, int direction) { }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull ViewHolder viewHolder) {
            ((Holder) viewHolder).stopDrag();
            super.clearView(recyclerView, viewHolder);
            
            // INSTANT REFRESH ON DRAG DROP: So the resize handle immediately appears if dropped in active
            int pos = viewHolder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) mHandler.post(() -> notifyItemChanged(pos));
        }
    };

    private static int calculateHeaderMinHeight(Context context) {
        Resources res = context.getResources();
        TypedArray toolbarStyle = context.obtainStyledAttributes(
                R.style.QSCustomizeToolbar, com.android.internal.R.styleable.Toolbar);
        int buttonStyle = toolbarStyle.getResourceId(
                com.android.internal.R.styleable.Toolbar_navigationButtonStyle, 0);
        toolbarStyle.recycle();
        int buttonMinWidth = 0;
        if (buttonStyle != 0) {
            TypedArray t = context.obtainStyledAttributes(buttonStyle, android.R.styleable.View);
            buttonMinWidth = t.getDimensionPixelSize(android.R.styleable.View_minWidth, 0);
            t.recycle();
        }
        return res.getDimensionPixelSize(R.dimen.qs_panel_padding_top)
                + res.getDimensionPixelSize(R.dimen.brightness_mirror_height)
                + res.getDimensionPixelSize(R.dimen.qs_brightness_margin_top)
                + res.getDimensionPixelSize(R.dimen.qs_brightness_margin_bottom)
                - buttonMinWidth
                - res.getDimensionPixelSize(R.dimen.qs_tile_margin_top_bottom);
    }

    public void reloadTileHeight() {
        mMinTileViewHeight = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_height);
    }
}