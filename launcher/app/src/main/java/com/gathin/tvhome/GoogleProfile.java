package com.gathin.tvhome;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.Log;

import java.util.Locale;

/**
 * Discovers connected Google Account and generates a circular material avatar.
 */
public final class GoogleProfile {
    private static final String TAG = "TvHome.Profile";
    private static final int BG_COLOR = 0xFF1A73E8; // Google Material Blue

    private GoogleProfile() {}

    /**
     * Resolves the primary Google account email on the device.
     */
    public static String getPrimaryGoogleAccount(Context context) {
        try {
            AccountManager am = AccountManager.get(context);
            Account[] accounts = am.getAccountsByType("com.google");
            if (accounts != null && accounts.length > 0) {
                return accounts[0].name;
            }
        } catch (Exception e) {
            Log.d(TAG, "Cannot access Google accounts: " + e.getMessage());
        }
        return "ahmedposhi@gmail.com";
    }

    /**
     * Creates a circular Material avatar with the first letter of the account name.
     */
    public static Drawable createAvatarDrawable(Context context, String accountName, float density) {
        int size = Math.round(22 * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(BG_COLOR);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint);

        String initial = "A";
        if (accountName != null && !accountName.trim().isEmpty()) {
            initial = accountName.trim().substring(0, 1).toUpperCase(Locale.ROOT);
        }

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(13 * density);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);

        Rect bounds = new Rect();
        textPaint.getTextBounds(initial, 0, initial.length(), bounds);
        float y = (size / 2f) + (bounds.height() / 2f) - bounds.bottom;
        canvas.drawText(initial, size / 2f, y, textPaint);

        BitmapDrawable bd = new BitmapDrawable(context.getResources(), bitmap);
        bd.setBounds(0, 0, size, size);
        return bd;
    }

    /**
     * Opens system accounts or sync settings.
     */
    public static void openAccountSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_SYNC_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {
            }
        }
    }
}
