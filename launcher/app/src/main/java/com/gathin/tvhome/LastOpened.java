package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.text.format.DateUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Apps Home opened: timestamps for the cinematic shelf, and an MRU list for the Recents top-bar dialog
 * (cards like the App library). No system Recents permission — only what this launcher launched.
 */
final class LastOpened {
    private static final String PREFS = "last_opened";
    /** Package Home most recently launched. */
    private static final String LATEST = "_latest";
    /** Newline-separated MRU packages (newest first). */
    private static final String ORDER = "_order";
    static final int MAX_RECENT = 16;

    private LastOpened() {
    }

    /** Notes that Home just opened this app; moves it to the front of the Recents list. */
    static void record(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(ctx.getPackageName())) return;
        SharedPreferences prefs = prefs(ctx);
        List<String> order = decode(prefs.getString(ORDER, ""));
        if (order.isEmpty()) order = orderFromLegacy(prefs.getAll());
        order.remove(pkg);
        order.add(0, pkg);
        SharedPreferences.Editor edit = prefs.edit()
                .putLong(pkg, System.currentTimeMillis())
                .putString(LATEST, pkg);
        while (order.size() > MAX_RECENT) {
            String drop = order.remove(order.size() - 1);
            if (!drop.equals(pkg)) edit.remove(drop);
        }
        edit.putString(ORDER, encode(order)).apply();
    }

    /** Packages Home opened recently (newest first); skips uninstalled ones. */
    static List<String> recent(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        List<String> order = decode(prefs.getString(ORDER, ""));
        if (order.isEmpty()) order = orderFromLegacy(prefs.getAll());
        PackageManager pm = ctx.getPackageManager();
        List<String> out = new ArrayList<>();
        SharedPreferences.Editor prune = null;
        for (String pkg : order) {
            if (pkg.equals(ctx.getPackageName())) continue;
            if (!launchable(pm, pkg)) {
                if (prune == null) prune = prefs.edit();
                prune.remove(pkg);
                continue;
            }
            out.add(pkg);
        }
        if (prune != null) {
            prune.putString(ORDER, encode(out));
            if (!out.isEmpty()) prune.putString(LATEST, out.get(0));
            else prune.remove(LATEST);
            prune.apply();
        } else if (prefs.getString(ORDER, "").isEmpty() && !out.isEmpty()) {
            prefs.edit().putString(ORDER, encode(out)).putString(LATEST, out.get(0)).apply();
        }
        return out;
    }

    /** Package Home most recently launched, or null. */
    static String latest(Context ctx) {
        List<String> list = recent(ctx);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Milliseconds since the epoch, or 0 when Home never opened the app. */
    static long get(Context ctx, String pkg) {
        return pkg == null ? 0L : prefs(ctx).getLong(pkg, 0L);
    }

    /** Removes one app from the Recents list and kills its background processes. */
    static void close(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        remove(ctx, pkg);
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        if (am != null) am.killBackgroundProcesses(pkg);
    }

    /** Clears the Recents list and kills each app's background processes. */
    static void clearAll(Context ctx) {
        List<String> list = recent(ctx);
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        SharedPreferences.Editor edit = prefs(ctx).edit().remove(ORDER).remove(LATEST);
        for (String pkg : list) {
            edit.remove(pkg);
            if (am != null) am.killBackgroundProcesses(pkg);
        }
        edit.apply();
    }

    /** Drops one package from the list without killing it. */
    static void remove(Context ctx, String pkg) {
        SharedPreferences prefs = prefs(ctx);
        List<String> order = decode(prefs.getString(ORDER, ""));
        if (order.isEmpty()) order = orderFromLegacy(prefs.getAll());
        order.remove(pkg);
        SharedPreferences.Editor edit = prefs.edit().remove(pkg).putString(ORDER, encode(order));
        if (order.isEmpty()) edit.remove(LATEST);
        else edit.putString(LATEST, order.get(0));
        edit.apply();
    }

    /**
     * Brings back the last app Home opened. False when none was recorded or the app is gone.
     */
    static boolean openLatest(Context ctx) {
        String pkg = latest(ctx);
        return pkg != null && open(ctx, pkg);
    }

    /** Opens a package with its leanback/launcher entry; records it as latest. */
    static boolean open(Context ctx, String pkg) {
        if (pkg == null) return false;
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return false;
        try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            record(ctx, pkg);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /**
     * "5 minutes ago" within the hour, then the day and the time: "today, 11:40 am", "yesterday, 9:40 pm",
     * "3 days ago, 9:40 pm", "Oct 12, 9:40 pm". The parts are Android's own wording in the TV's language.
     */
    static String describe(Context ctx, long when) {
        long now = System.currentTimeMillis();
        // Under a minute Android says "0 minutes ago"; one minute reads better.
        when = Math.min(when, now - DateUtils.MINUTE_IN_MILLIS);
        if (now - when < DateUtils.HOUR_IN_MILLIS) {
            return DateUtils.getRelativeTimeSpanString(when, now, DateUtils.MINUTE_IN_MILLIS).toString();
        }
        String day = DateUtils.getRelativeTimeSpanString(when, now, DateUtils.DAY_IN_MILLIS).toString();
        // It follows "Last opened" mid-sentence: "yesterday", not "Yesterday". Month names keep their capital.
        if (Locale.getDefault().getLanguage().equals("en") && (day.startsWith("Yesterday") || day.startsWith("Today"))) {
            day = Character.toLowerCase(day.charAt(0)) + day.substring(1);
        }
        return day + ", " + DateUtils.formatDateTime(ctx, when, DateUtils.FORMAT_SHOW_TIME);
    }

    /** Newest package among per-app timestamps; skips meta keys. Package-visible for tests. */
    static String newestPackage(Map<String, ?> all) {
        List<String> order = orderFromLegacy(all);
        return order.isEmpty() ? null : order.get(0);
    }

    /** Builds MRU order from legacy timestamp-only prefs (newest first). Package-visible for tests. */
    static List<String> orderFromLegacy(Map<String, ?> all) {
        List<String> pkgs = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (LATEST.equals(key) || ORDER.equals(key)) continue;
            Object value = entry.getValue();
            if (!(value instanceof Long)) continue;
            long at = (Long) value;
            int i = 0;
            while (i < times.size() && times.get(i) >= at) i++;
            times.add(i, at);
            pkgs.add(i, key);
        }
        if (pkgs.size() > MAX_RECENT) return new ArrayList<>(pkgs.subList(0, MAX_RECENT));
        return pkgs;
    }

    static List<String> decode(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String pkg = line.trim();
            if (!pkg.isEmpty() && !out.contains(pkg)) out.add(pkg);
        }
        return out;
    }

    static String encode(List<String> order) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : order) {
            if (pkg == null || pkg.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(pkg);
        }
        return sb.toString();
    }

    private static boolean launchable(PackageManager pm, String pkg) {
        return pm.getLeanbackLaunchIntentForPackage(pkg) != null
                || pm.getLaunchIntentForPackage(pkg) != null;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
