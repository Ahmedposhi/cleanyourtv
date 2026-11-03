package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Card strip of apps Home recently opened (like the App library). OK reopens; hold offers Close;
 * Clear all stops background processes for every entry. Used where the TV has no system Recents UI.
 */
final class RecentsDialog {
    private final Activity activity;
    private final Runnable dismissed;
    private final LinearLayout panel;
    private final boolean reducedMotion;
    private final boolean animations;
    private final boolean rtl;
    private final float density;
    private final int catalogWidth;
    final PopupWindow popup;

    private List<String> apps = new ArrayList<>();
    private TextView instructions;
    private TextView countView;
    /** Header Clear all; cards send Up here, and Down comes back to the focused card. */
    private TextView clearButton;
    private RecyclerView catalog;
    private FrameLayout shelf;
    private GlassView highlight;
    private SpringAnimation spring;
    private float scrollOffset;
    private boolean highlightShown;
    private boolean closed;
    private String selectedPackage;
    private int selectedPosition;
    private PopupWindow actionPopup;

    RecentsDialog(Activity activity, boolean animations, boolean reducedMotion, Runnable dismissed) {
        this.activity = activity;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.dismissed = dismissed;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_app_grid);
        density = activity.getResources().getDisplayMetrics().density;
        panel = new LinearLayout(activity);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panel.setBackgroundResource(R.drawable.glass_panel);
        View decor = activity.getWindow().getDecorView();
        catalogWidth = Math.min(dp(800), Math.max(dp(200), decor.getWidth() - dp(64)));
        popup = new PopupWindow(panel, catalogWidth, dp(280), true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            closed = true;
            dismissActions();
            stopHighlight();
            dismissed.run();
        });
    }

    void close() {
        dismissActions();
        if (popup.isShowing()) popup.dismiss();
        else if (!closed) {
            closed = true;
            dismissed.run();
        }
    }

    void show() {
        fill();
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        View container = panel.getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.55f;
            activity.getWindowManager().updateViewLayout(container, params);
        }
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(reducedMotion ? Motion.REDUCED_MS : 180).start();
    }

    private void fill() {
        apps = new ArrayList<>(LastOpened.recent(activity));
        showCatalog();
    }

    private void showCatalog() {
        stopHighlight();
        popup.setWidth(catalogWidth);
        popup.setHeight(dp(280));
        if (popup.isShowing()) popup.update(catalogWidth, dp(280));
        panel.removeAllViews();

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(8), dp(4));
        TextView title = text(activity.getString(R.string.recents), 22, Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        countView = text(activity.getResources().getQuantityString(
                R.plurals.library_app_count, apps.size(), apps.size()), 13, 0xA6FFFFFF);
        countView.setSingleLine(true);
        header.addView(countView, new LinearLayout.LayoutParams(-2, -2));
        clearButton = null;
        if (!apps.isEmpty()) {
            TextView clear = text(activity.getString(R.string.recents_clear_all), 13, 0xFFFFFFFF);
            clear.setId(View.generateViewId());
            clear.setPadding(dp(14), dp(8), dp(14), dp(8));
            clear.setBackground(clearBackground(false));
            clear.setFocusable(true);
            clear.setFocusableInTouchMode(true);
            clear.setOnClickListener(v -> clearAll());
            clear.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusSelectedCard();
                    return true;
                }
                // Stay on Clear all for Up / sideways — nothing else in the header.
                return key == KeyEvent.KEYCODE_DPAD_UP
                        || key == KeyEvent.KEYCODE_DPAD_LEFT
                        || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            });
            clear.setOnFocusChangeListener((v, focused) -> v.setBackground(clearBackground(focused)));
            LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-2, -2);
            clearParams.setMarginStart(dp(12));
            header.addView(clear, clearParams);
            clearButton = clear;
        }
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));

        instructions = text(activity.getString(R.string.recents_hint), 13, 0xBBFFFFFF);
        instructions.setSingleLine(true);
        instructions.setEllipsize(TextUtils.TruncateAt.END);
        instructions.setPadding(dp(12), 0, dp(12), dp(6));
        panel.addView(instructions, new LinearLayout.LayoutParams(-1, dp(26)));

        if (apps.isEmpty()) {
            instructions.setVisibility(View.GONE);
            TextView empty = text(activity.getString(R.string.recents_empty), 18, 0xFFFFFFFF);
            empty.setPadding(dp(12), dp(10), dp(12), dp(8));
            panel.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            panel.setFocusableInTouchMode(true);
            panel.requestFocus();
            return;
        }

        shelf = new FrameLayout(activity);
        shelf.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shelf.setClipChildren(false);
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        catalog = list;
        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false) {
            @Override protected boolean isLayoutRTL() { return rtl; }
        });
        list.setItemAnimator(null);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        list.setAdapter(new Catalog());
        shelf.addView(list, new FrameLayout.LayoutParams(-1, -1));
        highlight = new GlassView(activity, null);
        highlight.setRadius(dp(20));
        highlight.setAlpha(0f);
        highlight.setFocusable(false);
        highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shelf.addView(highlight, new FrameLayout.LayoutParams(dp(144), dp(180)));
        scrollOffset = 0;
        spring = new SpringAnimation(highlight, new FloatPropertyCompat<View>("recentsScrollX") {
            @Override public float getValue(View view) { return view.getTranslationX() + scrollOffset; }
            @Override public void setValue(View view, float value) { view.setTranslationX(value - scrollOffset); }
        });
        if (reducedMotion) Motion.reduce(spring);
        else spring.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scrollOffset += dx;
                highlight.setTranslationX(highlight.getTranslationX() - dx);
            }
        });
        panel.addView(shelf, new LinearLayout.LayoutParams(-1, 0, 1));

        int position = Math.min(Math.max(selectedPosition, 0), apps.size() - 1);
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).equals(selectedPackage)) { position = i; break; }
        }
        selectedPosition = position;
        selectedPackage = apps.get(position);
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, 0);
        final int target = position;
        list.post(() -> {
            if (catalog != list || !popup.isShowing()) return;
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void open(String pkg) {
        // Close Recents first and clear the Home-toggle flag so Home from that app returns to Home only.
        if (activity instanceof MainActivity) ((MainActivity) activity).leaveRecentsForApp();
        else close();
        if (!LastOpened.open(activity, pkg)) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void closeApp(String pkg) {
        dismissActions();
        int index = apps.indexOf(pkg);
        LastOpened.close(activity, pkg);
        apps = new ArrayList<>(LastOpened.recent(activity));
        if (apps.isEmpty()) {
            selectedPackage = null;
            selectedPosition = 0;
            showCatalog();
            return;
        }
        selectedPosition = Math.min(Math.max(index, 0), apps.size() - 1);
        selectedPackage = apps.get(selectedPosition);
        showCatalog();
    }

    private void clearAll() {
        dismissActions();
        LastOpened.clearAll(activity);
        apps = new ArrayList<>();
        selectedPackage = null;
        selectedPosition = 0;
        showCatalog();
    }

    private void showActions(String pkg, View anchor) {
        dismissActions();
        selectedPackage = pkg;
        if (spring != null) spring.cancel();
        View menuPanel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, panel, false);
        LinearLayout rows = menuPanel.findViewById(R.id.rows);
        PopupWindow menu = new PopupWindow(menuPanel, dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        actionPopup = menu;
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setElevation(0f);
        menu.setOnDismissListener(() -> {
            if (actionPopup == menu) actionPopup = null;
            restoreCardFocus();
        });
        addAction(rows, R.string.library_open, () -> open(pkg));
        addAction(rows, R.string.recents_close, () -> closeApp(pkg));
        menu.setWidth(dp(220));
        menuPanel.measure(View.MeasureSpec.makeMeasureSpec(dp(220), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        menuPanel.setAlpha(0f);
        menu.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.START,
                location[0], location[1] + dp(8));
        menuPanel.animate().alpha(1f).setDuration(reducedMotion ? Motion.REDUCED_MS : 180).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addAction(LinearLayout rows, int label, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(label);
        row.setFocusableInTouchMode(true);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> action.run());
        rows.addView(row);
    }

    private void dismissActions() {
        PopupWindow menu = actionPopup;
        actionPopup = null;
        if (menu != null) menu.dismiss();
    }

    private void restoreCardFocus() {
        if (catalog == null || !popup.isShowing()) return;
        catalog.post(() -> {
            if (catalog == null || !popup.isShowing() || actionPopup != null) return;
            focusSelectedCard();
        });
    }

    private void focusSelectedCard() {
        if (catalog == null) return;
        int position = Math.min(Math.max(selectedPosition, 0), Math.max(apps.size() - 1, 0));
        RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            holder.itemView.requestFocus();
            return;
        }
        catalog.scrollToPosition(position);
        catalog.post(() -> {
            if (catalog == null) return;
            RecyclerView.ViewHolder again = catalog.findViewHolderForAdapterPosition(position);
            if (again != null) again.itemView.requestFocus();
        });
    }

    private void stopHighlight() {
        if (spring != null) spring.cancel();
        if (catalog != null) catalog.stopScroll();
        catalog = null;
        highlightShown = false;
    }

    private void highlight(View card, boolean animate) {
        if (highlight == null || card.getParent() == null) return;
        Rect rect = new Rect(0, 0, card.getWidth(), card.getHeight());
        shelf.offsetDescendantRectToMyCoords(card, rect);
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        lp.width = rect.width() + dp(12);
        lp.height = rect.height() + dp(12);
        highlight.setLayoutParams(lp);
        highlight.setTranslationY(rect.top - dp(6));
        float x = rect.left - dp(6) + scrollOffset;
        if (!highlightShown || !animate || !animations) {
            spring.cancel();
            highlight.setTranslationX(x - scrollOffset);
        } else spring.animateToFinalPosition(x);
        highlightShown = true;
        highlight.setAlpha(1f);
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), 0x18FFFFFF);
        return bg;
    }

    /** Clear all: quiet at rest, bright fill + thick white border when focused. */
    private GradientDrawable clearBackground(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0x66FFFFFF : 0x28FFFFFF);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(focused ? 3 : 1), focused ? 0xFFFFFFFF : 0x40FFFFFF);
        return bg;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(UiFont.get(activity, R.string.font_medium));
        view.setLetterSpacing(0.01f);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }

    private CharSequence labelOf(String pkg) {
        try {
            ApplicationInfo info = activity.getPackageManager().getApplicationInfo(pkg, 0);
            return Cards.cleanLabel(String.valueOf(info.loadLabel(activity.getPackageManager())), pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pkg;
        }
    }

    private Drawable iconOf(String pkg) {
        try {
            return activity.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return activity.getDrawable(R.drawable.ic_apps);
        }
    }

    private final class Catalog extends RecyclerView.Adapter<Row> {
        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout row = new FrameLayout(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(132), dp(170));
            params.setMargins(dp(6), dp(2), dp(6), dp(2));
            row.setLayoutParams(params);
            row.setPadding(dp(10), dp(12), dp(10), dp(10));
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            row.addView(content, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(activity);
            icon.setId(R.id.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon, new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView name = text("", 15, Color.WHITE);
            name.setId(R.id.title);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(44));
            nameParams.topMargin = dp(10);
            content.addView(name, nameParams);
            TextView status = text(activity.getString(R.string.library_open), 11, 0xBBFFFFFF);
            status.setId(R.id.status);
            status.setGravity(Gravity.CENTER);
            content.addView(status, new LinearLayout.LayoutParams(-1, -2));
            return new Row(row);
        }

        @Override public void onBindViewHolder(@NonNull Row holder, int position) {
            String pkg = apps.get(position);
            ImageView icon = holder.itemView.findViewById(R.id.icon);
            icon.setImageDrawable(iconOf(pkg));
            TextView name = holder.itemView.findViewById(R.id.title);
            name.setText(labelOf(pkg));
            name.setEllipsize(TextUtils.TruncateAt.END);
            holder.itemView.setBackground(surface(0x24FFFFFF, 18));
            holder.itemView.setOnClickListener(v -> open(pkg));
            holder.itemView.setOnLongClickListener(v -> { showActions(pkg, v); return true; });
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    selectedPackage = pkg;
                    int index = holder.getBindingAdapterPosition();
                    if (index != RecyclerView.NO_POSITION) selectedPosition = index;
                    v.post(() -> { if (v.hasFocus() && catalog != null) highlight(v, true); });
                }
            });
            if (clearButton != null) holder.itemView.setNextFocusUpId(clearButton.getId());
            holder.itemView.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int index = holder.getBindingAdapterPosition();
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    if (clearButton != null) {
                        clearButton.requestFocus();
                        return true;
                    }
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
                if ((rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT) && index == 0)
                    return true;
                return (rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT)
                        && index == apps.size() - 1;
            });
        }

        @Override public int getItemCount() { return apps.size(); }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        Row(View view) { super(view); }
    }
}
