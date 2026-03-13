package com.android.systemui.qs.customize;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.qs.QSEditEvent;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.TileLayout;
import com.android.systemui.qs.customize.NothingActiveTileGridView.GridParams;
import com.android.systemui.qs.customize.TileQueryHelper.TileInfo;
import com.android.systemui.qs.customize.TileQueryHelper.TileStateListener;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.dagger.QSThemedContext;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.tileimpl.QSIconViewImpl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

@QSScope
public class NothingQSCustomizerAdapter implements TileStateListener, NothingActiveTileGridView.DragCallback {

    public static final String SHAPE_PREFS_NAME = "qs_tile_config";
    public static final String PREF_KEY_CIRCLE_PREFIX = "tile_is_circle_";

    private static final int SLOTS_PER_PAGE = NothingActiveTileGridView.MAX_SLOTS;
    private static final int INACTIVE_COLS = 4;

    private static final Map<String, String> SPEC_CATEGORY = new LinkedHashMap<>();
    static {
        SPEC_CATEGORY.put("internet", "Connectivity");
        SPEC_CATEGORY.put("wifi", "Connectivity");
        SPEC_CATEGORY.put("cell", "Connectivity");
        SPEC_CATEGORY.put("bt", "Connectivity");
        SPEC_CATEGORY.put("bluetooth", "Connectivity");
        SPEC_CATEGORY.put("airplane", "Connectivity");
        SPEC_CATEGORY.put("hotspot", "Connectivity");
        SPEC_CATEGORY.put("nfc", "Connectivity");
        SPEC_CATEGORY.put("usb_tether", "Connectivity");
        SPEC_CATEGORY.put("cast", "Connectivity");
        SPEC_CATEGORY.put("share", "Connectivity");
        SPEC_CATEGORY.put("nearby", "Connectivity");

        SPEC_CATEGORY.put("dnd", "Controls");
        SPEC_CATEGORY.put("do_not_disturb", "Controls");
        SPEC_CATEGORY.put("zen", "Controls");
        SPEC_CATEGORY.put("battery", "Controls");
        SPEC_CATEGORY.put("flashlight", "Controls");
        SPEC_CATEGORY.put("rotation", "Controls");
        SPEC_CATEGORY.put("auto_rotate", "Controls");
        SPEC_CATEGORY.put("screen_record", "Controls");
        SPEC_CATEGORY.put("dream", "Controls");
        SPEC_CATEGORY.put("power_share", "Controls");
        SPEC_CATEGORY.put("sound", "Controls");
        SPEC_CATEGORY.put("volume", "Controls");
        SPEC_CATEGORY.put("alarm", "Controls");
        SPEC_CATEGORY.put("caffeine", "Controls");

        SPEC_CATEGORY.put("night", "Display");
        SPEC_CATEGORY.put("dark", "Display");
        SPEC_CATEGORY.put("ui_mode_night", "Display");
        SPEC_CATEGORY.put("color_correction", "Display");
        SPEC_CATEGORY.put("color_inversion", "Display");
        SPEC_CATEGORY.put("reduce_brightness", "Display");
        SPEC_CATEGORY.put("heads_up", "Display");
        SPEC_CATEGORY.put("font_size", "Display");
        SPEC_CATEGORY.put("display", "Display");
        SPEC_CATEGORY.put("screen", "Display");

        SPEC_CATEGORY.put("camera_toggle", "Privacy");
        SPEC_CATEGORY.put("mic_toggle", "Privacy");
        SPEC_CATEGORY.put("location", "Privacy");
        SPEC_CATEGORY.put("sensor_privacy", "Privacy");
        SPEC_CATEGORY.put("camera", "Privacy");
        SPEC_CATEGORY.put("mic", "Privacy");
        SPEC_CATEGORY.put("privacy", "Privacy");

        SPEC_CATEGORY.put("sync", "Utilities");
        SPEC_CATEGORY.put("work", "Utilities");
        SPEC_CATEGORY.put("data_saver", "Utilities");
        SPEC_CATEGORY.put("data_switch", "Utilities");
        SPEC_CATEGORY.put("qr_code_scanner", "Utilities");
        SPEC_CATEGORY.put("wallet", "Utilities");
        SPEC_CATEGORY.put("one_handed", "Utilities");
        SPEC_CATEGORY.put("user", "Utilities");
        SPEC_CATEGORY.put("hearing", "Utilities");
        SPEC_CATEGORY.put("nfc_payment", "Utilities");
        SPEC_CATEGORY.put("calculator", "Utilities");
        SPEC_CATEGORY.put("clock", "Utilities");
        SPEC_CATEGORY.put("timer", "Utilities");
        SPEC_CATEGORY.put("storage", "Utilities");
    }

    private static final String CAT_APPS = "Apps";
    private static final String CAT_OTHER = "Other";

    private static final List<String> CATEGORY_ORDER = Arrays.asList(
            "Connectivity", "Controls", "Display", "Privacy", "Utilities", CAT_APPS, CAT_OTHER);

    private final Context mContext;
    private final QSHost mHost;
    private final UiEventLogger mUiEventLogger;
    private final SharedPreferences mShapePrefs;

    private final List<TileEntry> mAllEntries = new ArrayList<>();

    @Nullable private List<String> mCurrentSpecs;
    @Nullable private List<TileInfo> mAllTiles;

    private ActivePagerAdapter mActivePagerAdapter;
    private InactiveAdapter mInactiveAdapter;

    @Nullable private ViewPager2 mActivePager;
    @Nullable private RecyclerView mInactiveRecycler;
    @Nullable private LinearLayout mDotContainer;

    @Inject
    public NothingQSCustomizerAdapter(
            @QSThemedContext Context context,
            QSHost qsHost,
            UiEventLogger uiEventLogger) {
        mContext = context;
        mHost = qsHost;
        mUiEventLogger = uiEventLogger;
        mShapePrefs = context.getSharedPreferences(SHAPE_PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void attachViews(ViewPager2 activePager, RecyclerView inactiveRecycler, LinearLayout dotContainer) {
        mActivePager = activePager;
        mInactiveRecycler = inactiveRecycler;
        mDotContainer = dotContainer;

        mActivePagerAdapter = new ActivePagerAdapter();
        mActivePager.setAdapter(mActivePagerAdapter);
        mActivePager.setOffscreenPageLimit(2);
        mActivePager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrolled(int pos, float offset, int offsetPx) {
                updateDotIndicatorScrolled(pos, offset);
            }
            @Override
            public void onPageSelected(int pos) {
                updateDotIndicator(pos, 0f);
            }
        });

        mInactiveAdapter = new InactiveAdapter();
        GridLayoutManager glm = new GridLayoutManager(mContext, INACTIVE_COLS);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return mInactiveAdapter.isHeader(position) ? INACTIVE_COLS : 1;
            }
        });
        mInactiveRecycler.setLayoutManager(glm);
        mInactiveRecycler.setAdapter(mInactiveAdapter);
        
        mActivePager.post(() -> rebuildDotIndicator());
    }

    @Override
    public void onTilesChanged(List<TileInfo> tiles) {
        mAllTiles = tiles;
        recalcEntries();
    }

    @Override
    public void onTileMoved(int fromPage, int fromIndex, int toPage, int toSlot) {
        List<List<TileEntry>> pages = buildPages();
        if (fromPage >= pages.size()) return;
        List<TileEntry> srcPage = pages.get(fromPage);
        if (fromIndex >= srcPage.size()) return;

        int globalFrom = globalActiveIndex(pages, fromPage, fromIndex);
        if (globalFrom < 0) return;

        int toIndexInPage = slotToChildIndex(pages, toPage, toSlot);
        int globalTo = globalActiveIndex(pages, toPage, toIndexInPage);
        if (globalTo < 0) globalTo = globalActiveIndexPageEnd(pages, toPage);
        if (globalFrom == globalTo) return;

        List<TileEntry> active = getActiveList();
        if (globalFrom >= active.size() || globalTo >= active.size()) return;

        TileEntry moved = active.remove(globalFrom);
        active.add(globalTo, moved);
        applyActiveOrder(active);

        mUiEventLogger.log(QSEditEvent.QS_EDIT_MOVE, 0, moved.info.spec);
        saveSpecs(mHost);
        notifyAdapters();
    }

    public void setTileSpecs(List<String> currentSpecs) {
        if (currentSpecs.equals(mCurrentSpecs)) return;
        mCurrentSpecs = new ArrayList<>(currentSpecs);
        recalcEntries();
    }

    public void saveSpecs(QSHost host) {
        List<String> specs = new ArrayList<>();
        for (TileEntry e : mAllEntries) if (e.isActive) specs.add(e.info.spec);
        host.changeTilesByUser(mCurrentSpecs, specs);
        mCurrentSpecs = new ArrayList<>(specs);
    }

    public void resetTileSpecs(List<String> specs) {
        mHost.changeTilesByUser(mCurrentSpecs, specs);
        setTileSpecs(specs);
    }

    public void activateTile(TileEntry entry) {
        if (entry.isActive) return;
        entry.isActive = true;
        entry.isCircle = mShapePrefs.getBoolean(PREF_KEY_CIRCLE_PREFIX + entry.info.spec, true);
        mUiEventLogger.log(QSEditEvent.QS_EDIT_ADD, 0, entry.info.spec);
        saveSpecs(mHost);
        notifyAdapters();
    }

    public void deactivateTile(TileEntry entry) {
        if (!entry.isActive) return;
        entry.isActive = false;
        if (!entry.isCircle) {
            entry.isCircle = true;
            persistShape(entry.info.spec, true);
            TileLayout.broadcastTileSizeChange();
        }
        mUiEventLogger.log(QSEditEvent.QS_EDIT_REMOVE, 0, entry.info.spec);
        saveSpecs(mHost);
        notifyAdapters();
    }

    public void toggleShape(TileEntry entry, CustomizeTileView tileView) {
        if (!entry.isActive) return;
        boolean toCircle = !entry.isCircle;
        entry.isCircle = toCircle;
        persistShape(entry.info.spec, toCircle);

        NothingActiveTileGridView parentGrid = (NothingActiveTileGridView) tileView.getParent();
        int startW = tileView.getWidth();
        int targetW = parentGrid.getTileWidth(toCircle ? 1 : 2);

        GridParams gp = (GridParams) tileView.getLayoutParams();
        gp.colSpan = toCircle ? 1 : 2;
        gp.animWidth = startW; 
        tileView.setLayoutParams(gp);

        ValueAnimator anim = ValueAnimator.ofInt(startW, targetW);
        anim.setDuration(250); 
        // FIX: Removed Overshoot for clean morph without bouncing
        anim.setInterpolator(new DecelerateInterpolator(1.5f));
        anim.addUpdateListener(a -> {
            gp.animWidth = (int) a.getAnimatedValue();
            tileView.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                gp.animWidth = -1;
                tileView.requestLayout();
                
                tileView.post(() -> {
                    TileLayout.broadcastTileSizeChange();
                    saveSpecs(mHost);
                    notifyAdapters();
                });
            }
        });
        anim.start();

        tileView.setTileMode(toCircle);
        View labels = tileView.getLabelContainerView();
        View divider = tileView.getDividerView();
        
        if (toCircle) {
            if (labels != null) {
                labels.setVisibility(View.VISIBLE);
                labels.setAlpha(1f);
                labels.animate().alpha(0f).setDuration(200).withEndAction(() -> labels.setVisibility(View.GONE)).start();
            }
            if (divider != null) {
                divider.setVisibility(View.VISIBLE);
                divider.setAlpha(1f);
                divider.animate().alpha(0f).setDuration(200).withEndAction(() -> divider.setVisibility(View.GONE)).start();
            }
        } else {
            if (labels != null) {
                labels.setVisibility(View.VISIBLE);
                labels.setAlpha(0f);
                labels.animate().alpha(1f).setDuration(200).start();
            }
            if (divider != null) {
                divider.setVisibility(View.VISIBLE);
                divider.setAlpha(0f);
                divider.animate().alpha(1f).setDuration(200).start();
            }
        }
    }

    private void rebuildDotIndicator() {
        if (mDotContainer == null || mActivePager == null) return;
        int count = buildPages().size();
        int current = mActivePager.getCurrentItem();
        if (current >= count) {
            mActivePager.setCurrentItem(Math.max(0, count - 1), false);
            current = Math.max(0, count - 1);
        }
        
        mDotContainer.removeAllViews();
        float dp = mContext.getResources().getDisplayMetrics().density;
        int dotH = Math.round(6 * dp);
        
        for (int i = 0; i < count; i++) {
            View dot = new View(mContext);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dotH, dotH);
            lp.setMargins(Math.round(3 * dp), 0, Math.round(3 * dp), 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            dot.setLayoutParams(lp);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius(dotH / 2f);
            gd.setColor(0x55FFFFFF);
            dot.setBackground(gd);
            mDotContainer.addView(dot);
        }
        updateDotIndicator(current, 0f);
    }

    private void updateDotIndicatorScrolled(int pos, float offset) {
        if (mDotContainer == null) return;
        int count = mDotContainer.getChildCount();
        if (count == 0) return;

        float dp = mContext.getResources().getDisplayMetrics().density;
        int dotH = Math.round(6 * dp);
        int selectedW = Math.round(18 * dp);

        for (int i = 0; i < count; i++) {
            View dot = mDotContainer.getChildAt(i);
            int targetW;
            float alpha;
            if (i == pos) {
                targetW = Math.round(selectedW + (dotH - selectedW) * offset);
                alpha = 1f - 0.67f * offset; 
            } else if (i == pos + 1) {
                targetW = Math.round(dotH + (selectedW - dotH) * offset);
                alpha = 0.33f + 0.67f * offset; 
            } else {
                targetW = dotH;
                alpha = 0.33f;
            }
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            if (lp.width != targetW) {
                lp.width = targetW;
                dot.setLayoutParams(lp);
            }
            dot.setAlpha(alpha);
        }
    }

    private void updateDotIndicator(int selected, float offset) {
        if (mDotContainer == null) return;
        int count = mDotContainer.getChildCount();
        if (count == 0) return;
        float dp = mContext.getResources().getDisplayMetrics().density;
        int dotH = Math.round(6 * dp);
        int selectedW = Math.round(18 * dp);

        for (int i = 0; i < count; i++) {
            View dot = mDotContainer.getChildAt(i);
            int w = (i == selected) ? selectedW : dotH;
            float alpha = (i == selected) ? 1f : 0.33f;
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            lp.width = w;
            dot.setLayoutParams(lp);
            dot.setAlpha(alpha);
        }
    }

    private void persistShape(String spec, boolean circle) {
        mShapePrefs.edit().putBoolean(PREF_KEY_CIRCLE_PREFIX + spec, circle).apply();
    }

    private void recalcEntries() {
        if (mCurrentSpecs == null || mAllTiles == null) return;
        List<TileInfo> remaining = new ArrayList<>(mAllTiles);
        mAllEntries.clear();
        for (String spec : mCurrentSpecs) {
            TileInfo info = removeBySpec(remaining, spec);
            if (info == null) continue;
            boolean circle = mShapePrefs.getBoolean(PREF_KEY_CIRCLE_PREFIX + spec, true);
            mAllEntries.add(new TileEntry(info, true, circle));
        }
        for (TileInfo info : remaining) {
            boolean circle = mShapePrefs.getBoolean(PREF_KEY_CIRCLE_PREFIX + info.spec, true);
            mAllEntries.add(new TileEntry(info, false, circle));
        }
        notifyAdapters();
    }

    @Nullable
    private static TileInfo removeBySpec(List<TileInfo> list, String spec) {
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).spec.equals(spec)) return list.remove(i);
        return null;
    }

    private void notifyAdapters() {
        if (mActivePagerAdapter != null) mActivePagerAdapter.rebuildPages();
        if (mInactiveAdapter != null) mInactiveAdapter.refreshList();
        rebuildDotIndicator(); 
    }

    public void reloadTileHeight() { notifyAdapters(); }

    private List<TileEntry> getActiveList() {
        List<TileEntry> out = new ArrayList<>();
        for (TileEntry e : mAllEntries) if (e.isActive) out.add(e);
        return out;
    }

    private List<List<TileEntry>> buildPages() {
        List<TileEntry> active = getActiveList();
        List<List<TileEntry>> pages = new ArrayList<>();
        List<TileEntry> cur = new ArrayList<>();
        int used = 0;
        for (TileEntry e : active) {
            int cost = e.isCircle ? 1 : 2;
            if (used + cost > SLOTS_PER_PAGE && !cur.isEmpty()) {
                pages.add(cur); cur = new ArrayList<>(); used = 0;
            }
            cur.add(e); used += cost;
        }
        if (!cur.isEmpty()) pages.add(cur);
        if (pages.isEmpty()) pages.add(new ArrayList<>());
        return pages;
    }

    private int slotToChildIndex(List<List<TileEntry>> pages, int page, int slot) {
        if (page >= pages.size()) return 0;
        List<TileEntry> pg = pages.get(page);
        int used = 0;
        for (int i = 0; i < pg.size(); i++) {
            int cost = pg.get(i).isCircle ? 1 : 2;
            if (used + cost > slot) return i;
            used += cost;
        }
        return Math.max(0, pg.size() - 1);
    }

    private int globalActiveIndex(List<List<TileEntry>> pages, int pageIdx, int childIdx) {
        int global = 0;
        for (int p = 0; p < pageIdx && p < pages.size(); p++) global += pages.get(p).size();
        if (pageIdx >= pages.size()) return -1;
        int clamped = Math.min(childIdx, pages.get(pageIdx).size() - 1);
        return clamped < 0 ? -1 : global + clamped;
    }

    private int globalActiveIndexPageEnd(List<List<TileEntry>> pages, int pageIdx) {
        int g = 0;
        for (int p = 0; p <= pageIdx && p < pages.size(); p++) g += pages.get(p).size();
        return g - 1;
    }

    private void applyActiveOrder(List<TileEntry> newActive) {
        int ai = 0;
        for (int i = 0; i < mAllEntries.size(); i++)
            if (mAllEntries.get(i).isActive && ai < newActive.size())
                mAllEntries.set(i, newActive.get(ai++));
    }

    private String categoryOf(TileInfo info) {
        if (!info.isSystem || info.spec.startsWith(CustomTile.PREFIX)) return CAT_APPS;
        if (SPEC_CATEGORY.containsKey(info.spec)) return SPEC_CATEGORY.get(info.spec);
        for (Map.Entry<String, String> e : SPEC_CATEGORY.entrySet())
            if (info.spec.contains(e.getKey())) return e.getValue();
        return CAT_OTHER;
    }

    private int dp(float v) {
        return Math.round(v * mContext.getResources().getDisplayMetrics().density);
    }

    private static String tileName(TileInfo info) {
        if (info.state != null && info.state.label != null && info.state.label.length() > 0) {
            return info.state.label.toString();
        }
        String s = info.spec.replace('_', ' ');
        if (s.length() == 0) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static class TileEntry {
        public final TileInfo info;
        public boolean isActive;
        public boolean isCircle;
        TileEntry(TileInfo info, boolean isActive, boolean isCircle) {
            this.info = info; this.isActive = isActive; this.isCircle = isCircle;
        }
    }

    class ActivePagerAdapter extends RecyclerView.Adapter<ActivePagerAdapter.PageHolder> {

        private List<List<TileEntry>> mPages = new ArrayList<>();

        ActivePagerAdapter() { setHasStableIds(true); }

        void rebuildPages() {
            mPages = buildPages();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            NothingActiveTileGridView grid = new NothingActiveTileGridView(mContext);
            grid.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            grid.setDragCallback(NothingQSCustomizerAdapter.this);
            return new PageHolder(grid);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            holder.grid.setPageIndex(position);
            List<TileEntry> pageTiles = position < mPages.size() ? mPages.get(position) : new ArrayList<>();

            List<View> views = new ArrayList<>();
            for (TileEntry entry : pageTiles) {
                CustomizeTileView tv = new CustomizeTileView(mContext, new QSIconViewImpl(mContext));
                tv.setTag("tile_spec_" + entry.info.spec);
                tv.changeState(entry.info.state);
                tv.setShowSideView(entry.info.isSystem);
                tv.setShowAppLabel(false);
                tv.setTileMode(entry.isCircle);
                tv.setEditMode(true);

                final TileEntry cap = entry;
                tv.setOnResizeClickListener(v -> toggleShape(cap, tv));
                tv.setOnClickListener(v -> deactivateTile(cap));

                tv.setLayoutParams(new GridParams(entry.isCircle ? 1 : 2));
                views.add(tv);
            }
            holder.grid.setTileChildren(views);
            holder.grid.invalidate();
        }

        @Override public int getItemCount() { return Math.max(1, mPages.size()); }
        @Override public long getItemId(int pos) { return pos; }

        class PageHolder extends RecyclerView.ViewHolder {
            final NothingActiveTileGridView grid;
            PageHolder(NothingActiveTileGridView g) { super(g); grid = g; }
        }
    }

    class InactiveAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int VT_HEADER = 0;
        private static final int VT_TILE = 1;

        private final List<Object> mItems = new ArrayList<>();

        void refreshList() {
            mItems.clear();

            Map<String, List<TileEntry>> grouped = new LinkedHashMap<>();
            for (String cat : CATEGORY_ORDER) grouped.put(cat, new ArrayList<>());

            for (TileEntry e : mAllEntries) {
                String cat = categoryOf(e.info);
                List<TileEntry> bucket = grouped.get(cat);
                if (bucket == null) { bucket = new ArrayList<>(); grouped.put(cat, bucket); }
                bucket.add(e);
            }

            for (Map.Entry<String, List<TileEntry>> entry : grouped.entrySet()) {
                List<TileEntry> tiles = entry.getValue();
                if (tiles.isEmpty()) continue;
                mItems.add(entry.getKey());
                mItems.addAll(tiles);
            }
            notifyDataSetChanged();
        }

        boolean isHeader(int position) {
            return position < mItems.size() && mItems.get(position) instanceof String;
        }

        @Override public int getItemViewType(int pos) {
            return isHeader(pos) ? VT_HEADER : VT_TILE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {

            if (viewType == VT_HEADER) {
                TextView tv = new TextView(mContext);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tv.setLayoutParams(lp);
                tv.setPadding(0, dp(18), 0, dp(8));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                tv.setTextColor(0x55FFFFFF);
                tv.setLetterSpacing(0.14f);
                tv.setAllCaps(true);
                tv.setGravity(Gravity.CENTER);
                return new HeaderHolder(tv);
            }

            LinearLayout cell = new LinearLayout(mContext);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            RecyclerView.LayoutParams cellLp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cell.setLayoutParams(cellLp);
            cell.setPadding(0, dp(6), 0, dp(10));

            CustomizeTileView tv = new CustomizeTileView(mContext, new QSIconViewImpl(mContext));
            tv.setTileMode(true);
            tv.setEditMode(false);
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tvLp.gravity = Gravity.CENTER_HORIZONTAL;
            cell.addView(tv, tvLp);

            TextView name = new TextView(mContext);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameLp.topMargin = dp(5);
            name.setLayoutParams(nameLp);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            name.setTextColor(0xFFFFFFFF);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(name, nameLp);

            return new TileHolder(cell, tv, name);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            if (vh instanceof HeaderHolder) {
                ((HeaderHolder) vh).label.setText((String) mItems.get(position));
                return;
            }
            TileEntry entry = (TileEntry) mItems.get(position);
            TileHolder holder = (TileHolder) vh;

            holder.tileView.changeState(entry.info.state);
            holder.tileView.setShowAppLabel(false);
            holder.tileView.setShowSideView(false);
            holder.tileView.setTileMode(true);
            holder.tileView.setEditMode(false);

            holder.nameView.setText(tileName(entry.info));

            if (entry.isActive) {
                holder.itemView.setAlpha(0.35f);
                holder.itemView.setClickable(false);
                holder.tileView.setOnClickListener(null);
            } else {
                holder.itemView.setAlpha(1f);
                holder.itemView.setClickable(true);
                final TileEntry cap = entry;
                holder.tileView.setOnClickListener(v -> activateTile(cap));
            }
        }

        @Override public int getItemCount() { return mItems.size(); }

        class HeaderHolder extends RecyclerView.ViewHolder {
            final TextView label;
            HeaderHolder(TextView tv) { super(tv); label = tv; }
        }

        class TileHolder extends RecyclerView.ViewHolder {
            final CustomizeTileView tileView;
            final TextView nameView;
            TileHolder(View root, CustomizeTileView tv, TextView name) {
                super(root); tileView = tv; nameView = name;
            }
        }
    }

    public List<TileEntry> getAllEntries() {
        return Collections.unmodifiableList(mAllEntries);
    }
}
