package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Remembers the last two TV inputs Home switched to, so a long-press on Inputs (or a "back to" row)
 * can jump straight to the other HDMI port without opening the full list.
 */
final class LastInput {
    private static final String PREFS = "last_input";
    private static final String KEY_CURRENT = "current";
    private static final String KEY_PREVIOUS = "previous";

    private LastInput() {
    }

    /** Notes that Home just switched to this input. No-op when the id is empty or already current. */
    static void record(Context ctx, String inputId) {
        if (inputId == null || inputId.isEmpty()) return;
        SharedPreferences prefs = prefs(ctx);
        String current = prefs.getString(KEY_CURRENT, "");
        if (inputId.equals(current)) return;
        prefs.edit()
                .putString(KEY_PREVIOUS, current)
                .putString(KEY_CURRENT, inputId)
                .apply();
    }

    static String current(Context ctx) {
        return prefs(ctx).getString(KEY_CURRENT, "");
    }

    static String previous(Context ctx) {
        return prefs(ctx).getString(KEY_PREVIOUS, "");
    }

    /**
     * The input to open for "other port": the remembered previous when it differs from current,
     * else another connected HDMI when current is known. Null when there is nothing to jump to.
     */
    static String other(String current, String previous, List<String> connectedHdmiIds) {
        if (previous != null && !previous.isEmpty() && !previous.equals(current)) {
            return previous;
        }
        if (current == null || current.isEmpty() || connectedHdmiIds == null) return null;
        for (String id : connectedHdmiIds) {
            if (id != null && !id.isEmpty() && !id.equals(current)) return id;
        }
        return null;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
