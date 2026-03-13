package com.android.systemui.qs.customize;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NothingActiveTileGridView extends ViewGroup {

    public static final int COLS = 4;
    public static final int ROWS = 4;
    public static final int MAX_SLOTS = COLS * ROWS;

    static final String DRAG_LABEL = "qs_tile_drag";

    private static final float INNER_GRID_LINE_DP = 1f;
    private static final float OUTER_GRID_LINE_DP = 2f;
    private static final float GRID_CORNER_DP = 28f;

    private int mCellW;
    private int mCellH;
    private int mTileInsetX;
    private int mTileInsetY;
    private int mExpectedTileSize;
    
    private int mXOffset;
    private int mYOffset;

    private final Paint mInnerGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOuterBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHoverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTmp = new RectF();

    private int mHoverSlot = -1;
    private DragCallback mDragCallback;
    private int mPageIndex;

    private final Map<Integer, Animator> mRunningAnimators = new HashMap<>();
    private final Map<String, Rect> mOldPositions = new HashMap<>();

    private static class TileDragShadow extends DragShadowBuilder {
        private static final float SCALE = 0.88f;
        TileDragShadow(View v) { super(v); }
        @Override public void onProvideShadowMetrics(Point s, Point t) {
            int w = (int)(getView().getWidth() * SCALE);
            int h = (int)(getView().getHeight() * SCALE);
            s.set(w, h); t.set(w / 2, h / 2);
        }
        @Override public void onDrawShadow(Canvas c) {
            c.scale(SCALE, SCALE); getView().draw(c);
        }
    }

    public interface DragCallback {
        void onTileMoved(int fromPage, int fromIndex, int toPage, int toSlot);
    }

    public NothingActiveTileGridView(Context c) { super(c); init(c); }
    public NothingActiveTileGridView(Context c, AttributeSet a) { super(c, a); init(c); }
    public NothingActiveTileGridView(Context c, AttributeSet a, int s) { super(c, a, s); init(c); }

    private void init(Context ctx) {
        setWillNotDraw(false);
        float dp = ctx.getResources().getDisplayMetrics().density;
        mExpectedTileSize = ctx.getResources().getDimensionPixelSize(R.dimen.qs_tile_height);

        mInnerGridPaint.setStyle(Paint.Style.STROKE);
        mInnerGridPaint.setStrokeWidth(INNER_GRID_LINE_DP * dp);
        mInnerGridPaint.setColor(0x33FFFFFF);

        mOuterBorderPaint.setStyle(Paint.Style.STROKE);
        mOuterBorderPaint.setStrokeWidth(OUTER_GRID_LINE_DP * dp);
        mOuterBorderPaint.setColor(0x33FFFFFF);

        mHoverPaint.setStyle(Paint.Style.FILL);
        mHoverPaint.setColor(0x1AFFFFFF);

        setOnDragListener(mDragListener);
    }

    public void setPageIndex(int idx) { mPageIndex = idx; }
    public void setDragCallback(DragCallback cb) { mDragCallback = cb; }

    public int getTileWidth(int span) {
        return (mCellW * span) - (2 * mTileInsetX);
    }

    public void setTileChildren(List<View> views) {
        // FIX: Crash prevented! Copy values to a separate list before canceling to avoid ConcurrentModificationException
        List<Animator> anims = new ArrayList<>(mRunningAnimators.values());
        mRunningAnimators.clear();
        for (Animator a : anims) {
            a.cancel();
        }
        
        mOldPositions.clear();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            Object tag = c.getTag();
            if (tag instanceof String && ((String) tag).startsWith("tile_spec_")) {
                mOldPositions.put((String) tag, new Rect(c.getLeft(), c.getTop(), c.getRight(), c.getBottom()));
            }
        }

        removeAllViews();
        for (int i = 0; i < views.size(); i++) {
            installDragOnChild(views.get(i), i);
            addView(views.get(i));
        }
    }

    private void installDragOnChild(View child, int childIndex) {
        child.setOnLongClickListener(v -> {
            ClipData cd = new ClipData(
                    DRAG_LABEL,
                    new String[]{ ClipDescription.MIMETYPE_TEXT_PLAIN },
                    new ClipData.Item(mPageIndex + ":" + childIndex));
            child.setAlpha(0.28f);
            child.startDragAndDrop(cd, new TileDragShadow(child), child, 0);
            return true;
        });
    }

    private final OnDragListener mDragListener = (v, ev) -> {
        switch (ev.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return ev.getClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                    && DRAG_LABEL.equals(ev.getClipDescription().getLabel().toString());

            case DragEvent.ACTION_DRAG_LOCATION: {
                int s = cellAt((int) ev.getX(), (int) ev.getY());
                if (s != mHoverSlot) { mHoverSlot = s; invalidate(); }
                return true;
            }
            case DragEvent.ACTION_DRAG_EXITED:
                mHoverSlot = -1; invalidate(); return true;

            case DragEvent.ACTION_DRAG_ENDED:
                mHoverSlot = -1; invalidate();
                restoreAlpha(ev); return true;

            case DragEvent.ACTION_DROP:
                mHoverSlot = -1; invalidate();
                restoreAlpha(ev); deliverDrop(ev); return true;

            default: return false;
        }
    };

    private void restoreAlpha(DragEvent ev) {
        if (ev.getLocalState() instanceof View) ((View) ev.getLocalState()).setAlpha(1f);
    }

    private void deliverDrop(DragEvent ev) {
        if (mDragCallback == null) return;
        String[] p = ev.getClipData().getItemAt(0).getText().toString().split(":");
        if (p.length != 2) return;
        int toSlot = cellAt((int) ev.getX(), (int) ev.getY());
        if (toSlot < 0) return;
        mDragCallback.onTileMoved(
                Integer.parseInt(p[0]), Integer.parseInt(p[1]), mPageIndex, toSlot);
    }

    private int cellAt(int x, int y) {
        if (mCellW == 0 || mCellH == 0) return -1;
        int col = (x - mXOffset) / mCellW;
        int row = (y - mYOffset) / mCellH;
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return -1;
        return row * COLS + col;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int totalW = MeasureSpec.getSize(wSpec);
        int totalH = MeasureSpec.getSize(hSpec);

        // PERFECT SQUARES: Force Height to perfectly match Width
        mCellW = totalW / COLS;
        mCellH = mCellW; 
        
        mXOffset = (totalW - (COLS * mCellW)) / 2;
        mYOffset = (totalH - (ROWS * mCellH)) / 2;
        if (mXOffset < 0) mXOffset = 0;
        if (mYOffset < 0) mYOffset = 0;

        // FLAWLESS CENTERING: Calculate exact leftover space around the fixed tile size
        mTileInsetX = (mCellW - mExpectedTileSize) / 2;
        mTileInsetY = (mCellH - mExpectedTileSize) / 2;

        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            GridParams lp = (GridParams) ch.getLayoutParams();
            
            int tileW = getTileWidth(lp.colSpan);
            if (lp.animWidth != -1) tileW = lp.animWidth; 
            
            ch.measure(
                    MeasureSpec.makeMeasureSpec(tileW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(mExpectedTileSize, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(totalW, totalH);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean[] occ = new boolean[MAX_SLOTS];

        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            GridParams lp = (GridParams) ch.getLayoutParams();
            int slot = findFreeSlot(occ, lp.colSpan);
            if (slot < 0) { ch.layout(0, 0, 0, 0); continue; }
            markOccupied(occ, slot, lp.colSpan);

            int col = slot % COLS, row = slot / COLS;
            int tl = mXOffset + (col * mCellW) + mTileInsetX;
            int tt = mYOffset + (row * mCellH) + mTileInsetY;
            int tw = getTileWidth(lp.colSpan);
            if (lp.animWidth != -1) tw = lp.animWidth;

            Object tag = ch.getTag();
            Rect old = null;
            if (tag instanceof String && ((String) tag).startsWith("tile_spec_")) {
                old = mOldPositions.get(tag.toString());
            }

            ch.layout(tl, tt, tl + tw, tt + mExpectedTileSize);

            if (old != null && (old.left != tl || old.top != tt)) {
                smoothAnimate(ch, i, old.left - tl, old.top - tt);
            }
        }
        mOldPositions.clear();
    }

    private void smoothAnimate(View child, int idx, float dx, float dy) {
        Animator old = mRunningAnimators.get(idx);
        if (old != null) old.cancel();

        child.setTranslationX(dx);
        child.setTranslationY(dy);

        // FIX: Removed Overshoot (Bounce). Using clean, fast Decelerate.
        ObjectAnimator ax = ObjectAnimator.ofFloat(child, View.TRANSLATION_X, dx, 0f);
        ObjectAnimator ay = ObjectAnimator.ofFloat(child, View.TRANSLATION_Y, dy, 0f);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(ax, ay);
        set.setDuration(250); 
        set.setInterpolator(new DecelerateInterpolator(1.5f));
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                child.setTranslationX(0f); child.setTranslationY(0f);
                mRunningAnimators.remove(idx);
            }
        });
        mRunningAnimators.put(idx, set);
        set.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mCellW == 0 || mCellH == 0) return;

        float dp = getResources().getDisplayMetrics().density;
        float corner = GRID_CORNER_DP * dp;
        float outerHalf = mOuterBorderPaint.getStrokeWidth() / 2f;

        float gridTop = mYOffset + outerHalf;
        float gridBottom = mYOffset + (ROWS * mCellH) - outerHalf;
        float gridLeft = mXOffset + outerHalf;
        float gridRight = mXOffset + (COLS * mCellW) - outerHalf;

        boolean[] occ = new boolean[MAX_SLOTS];
        boolean[] pillLeft = new boolean[MAX_SLOTS];

        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            GridParams lp = (GridParams) ch.getLayoutParams();
            int slot = findFreeSlot(occ, lp.colSpan);
            if (slot < 0) continue;
            markOccupied(occ, slot, lp.colSpan);
            if (lp.colSpan == 2) pillLeft[slot] = true;
        }

        if (mHoverSlot >= 0) {
            int hCol = mHoverSlot % COLS, hRow = mHoverSlot / COLS;
            boolean isRight = (mHoverSlot > 0) && pillLeft[mHoverSlot - 1];
            int startCol = isRight ? hCol - 1 : hCol;
            boolean isPillHover = pillLeft[mHoverSlot] || isRight;
            int spanCols = isPillHover ? 2 : 1;

            float fx = mXOffset + (startCol * mCellW);
            float fy = mYOffset + (hRow * mCellH);
            float fr = fx + (mCellW * spanCols);
            float fb = fy + mCellH;
            mTmp.set(fx + outerHalf, fy + outerHalf, fr - outerHalf, fb - outerHalf);
            canvas.drawRoundRect(mTmp, corner / 2f, corner / 2f, mHoverPaint);
        }

        mTmp.set(gridLeft, gridTop, gridRight, gridBottom);
        canvas.drawRoundRect(mTmp, corner, corner, mOuterBorderPaint);

        for (int row = 1; row < ROWS; row++) {
            float y = mYOffset + (row * mCellH);
            canvas.drawLine(gridLeft, y, gridRight, y, mInnerGridPaint);
        }

        for (int col = 1; col < COLS; col++) {
            float x = mXOffset + (col * mCellW);
            canvas.drawLine(x, gridTop, x, gridBottom, mInnerGridPaint);
        }
    }

    static int findFreeSlot(boolean[] occ, int span) {
        for (int s = 0; s < MAX_SLOTS; s++) {
            int col = s % COLS;
            if (col + span > COLS) continue;
            boolean ok = true;
            for (int k = 0; k < span; k++) if (occ[s + k]) { ok = false; break; }
            if (ok) return s;
        }
        return -1;
    }

    private static void markOccupied(boolean[] occ, int start, int span) {
        for (int k = 0; k < span; k++) occ[start + k] = true;
    }

    public static class GridParams extends LayoutParams {
        public int colSpan; 
        public int animWidth = -1; 

        public GridParams(int span) {
            super(WRAP_CONTENT, WRAP_CONTENT);
            this.colSpan = span;
        }
    }

    @Override protected LayoutParams generateDefaultLayoutParams() { return new GridParams(1); }
    @Override protected boolean checkLayoutParams(LayoutParams p) { return p instanceof GridParams; }
    @Override protected LayoutParams generateLayoutParams(LayoutParams p) { return new GridParams(1); }
}
