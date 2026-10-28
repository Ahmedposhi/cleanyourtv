package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Discovers and formats TV sources (HDMI 1, HDMI 2, AV, consoles, set-top boxes)
 * for direct inclusion as tiles in the launcher grid and dock.
 */
public final class SourceInputs {
    private static final String TAG = "TvHome.SourceInputs";
    public static final String SOURCE_PKG = "com.gathin.tvhome.source";

    public static final class SourceItem {
        public final String id;
        public final String label;
        public final String cleanName;
        public final String portName;
        public final String emoji;
        public final ComponentName component;

        public SourceItem(String id, String label, String cleanName, String portName, String emoji) {
            this.id = id;
            this.label = label;
            this.cleanName = cleanName;
            this.portName = portName;
            this.emoji = emoji;
            this.component = new ComponentName(SOURCE_PKG, id.replace('/', '_').replace(' ', '_'));
        }
    }

    private SourceInputs() {}

    /**
     * Resolves all available TV sources: hardware inputs from TvInputManager plus
     * declarative sources from config.json (tv_sources).
     */
    public static List<SourceItem> load(Context context, List<String> configSources) {
        List<SourceItem> list = new ArrayList<>();

        // 1. Hardware inputs from TvInputManager if present
        TvInputManager tim = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (info == null || !info.isPassthroughInput() || info.isHidden(context)) continue;
                    CharSequence l = info.loadLabel(context);
                    String label = l == null ? info.getId() : l.toString();
                    boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                    if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;

                    CharSequence custom = InputNames.tidy(info.loadCustomLabel(context));
                    boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                    if (named) label = custom + " \u00B7 " + label;

                    list.add(parseSourceItem(info.getId(), label));
                }
            } catch (Exception e) {
                Log.d(TAG, "TvInputManager scan skipped: " + e.getMessage());
            }
        }

        // 2. Declarative sources from config (tv_sources)
        if (configSources != null && !configSources.isEmpty()) {
            for (String src : configSources) {
                if (src == null || src.trim().isEmpty()) continue;
                String trimmed = src.trim();
                boolean exists = false;
                for (SourceItem item : list) {
                    if (item.label.equalsIgnoreCase(trimmed) || item.portName.equalsIgnoreCase(trimmed)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    list.add(parseSourceItem("source_" + trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_"), trimmed));
                }
            }
        }

        return list;
    }

    public static SourceItem parseSourceItem(String id, String raw) {
        String s = raw.trim();
        String emoji = "📺";
        String upper = s.toUpperCase(Locale.ROOT);

        if (upper.contains("PLAYSTATION") || upper.contains("PS5") || upper.contains("PS4")
                || upper.contains("XBOX") || upper.contains("SWITCH") || upper.contains("GAME")) {
            emoji = "🎮";
        } else if (upper.contains("PC") || upper.contains("MAC") || upper.contains("COMPUTER") || upper.contains("LAPTOP")) {
            emoji = "💻";
        } else if (upper.contains("APPLE") || upper.contains("STREAMER") || upper.contains("BOX") || upper.contains("SHIELD")) {
            emoji = "🍎";
        } else if (upper.contains("SAT") || upper.contains("CABLE") || upper.contains("RECEIVER") || upper.contains("DVB")) {
            emoji = "📡";
        } else if (upper.contains("AV") || upper.contains("COMPOSITE")) {
            emoji = "📹";
        }

        String port = s;
        String clean = s;
        int dot = s.indexOf('\u00B7');
        if (dot > 0) {
            clean = s.substring(0, dot).trim();
            port = s.substring(dot + 1).trim();
        }

        return new SourceItem(id, s, clean, port, emoji);
    }

    /**
     * Creates a high-fidelity dark tile banner for the TV source.
     */
    public static Drawable createBanner(Context context, SourceItem item, float density) {
        int w = 320, h = 180;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Modern gradient dark tile background
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF131D26);
        canvas.drawRect(0, 0, w, h, bg);

        // Decorative subtle top border accent
        Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        accent.setColor(0x334B88FF);
        canvas.drawRect(0, 0, w, 4, accent);

        // Big centered emoji icon
        Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        iconPaint.setTextSize(54f);
        iconPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(item.emoji, w / 2f, h / 2f - 4, iconPaint);

        // Source title
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFE2E8F0);
        textPaint.setTextSize(19f);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(item.cleanName, w / 2f, h - 34, textPaint);

        // Subtitle (Port name if different)
        if (!item.portName.equalsIgnoreCase(item.cleanName)) {
            Paint portPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            portPaint.setColor(0x9994A3B8);
            portPaint.setTextSize(13f);
            portPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(item.portName, w / 2f, h - 16, portPaint);
        }

        return new BitmapDrawable(context.getResources(), bmp);
    }
}
