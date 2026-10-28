package com.gathin.tvhome;

import java.util.Calendar;
import java.util.Locale;

/**
 * 100% offline, zero-network mathematical solar calculation of prayer times for Cairo, Egypt.
 * Uses Egyptian General Authority of Survey standards:
 * - Fajr: 19.5 degrees
 * - Isha: 17.5 degrees
 * - Asr: Shafi'i shadow factor (1.0)
 * - Cairo coordinates: 30.0444 N, 31.2357 E
 * - Time zone: Egypt standard GMT+2, summer time GMT+3 (device Africa/Cairo
 *   tables on older Android TV builds often miss the 2023+ DST restoration)
 */
final class CairoAthan {

    static final double LATITUDE = 30.0444;
    static final double LONGITUDE = 31.2357;
    /** Egypt winter / standard offset (EET). */
    static final double OFFSET_STANDARD_HOURS = 2.0;
    /** Egypt summer time offset (EEST / GMT+3). */
    static final double OFFSET_SUMMER_HOURS = 3.0;

    static final class Prayer {
        final String name;
        final String nameArabic;
        final int hour;
        final int minute;
        final double timeDecimal;

        Prayer(String name, String nameArabic, int hour, int minute, double timeDecimal) {
            this.name = name;
            this.nameArabic = nameArabic;
            this.hour = hour;
            this.minute = minute;
            this.timeDecimal = timeDecimal;
        }

        String format12h() {
            int h = hour % 12;
            if (h == 0) h = 12;
            String ampm = hour >= 12 ? "PM" : "AM";
            return String.format(Locale.ROOT, "%d:%02d %s", h, minute, ampm);
        }

        String format24h() {
            return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
        }
    }

    static final class Schedule {
        final Prayer fajr;
        final Prayer sunrise;
        final Prayer dhuhr;
        final Prayer asr;
        final Prayer maghrib;
        final Prayer isha;
        final Prayer nextPrayer;

        Schedule(Prayer fajr, Prayer sunrise, Prayer dhuhr, Prayer asr, Prayer maghrib, Prayer isha, Prayer nextPrayer) {
            this.fajr = fajr;
            this.sunrise = sunrise;
            this.dhuhr = dhuhr;
            this.asr = asr;
            this.maghrib = maghrib;
            this.isha = isha;
            this.nextPrayer = nextPrayer;
        }

        Prayer[] all() {
            return new Prayer[]{fajr, sunrise, dhuhr, asr, maghrib, isha};
        }
    }

    private CairoAthan() {
    }

    /** Calculates Cairo prayer times for today using current device date/time. */
    static Schedule today() {
        Calendar cal = Calendar.getInstance();
        return forDate(cal);
    }

    static Schedule forDate(Calendar cal) {
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);

        // Egypt wall-clock offset: GMT+3 in summer, GMT+2 otherwise (do not trust
        // outdated Android tv Africa/Cairo zoneinfo on Xiaomi / Android 11 boxes).
        double tzOffsetHours = egyptOffsetHours(year, month, day);

        // Julian Day Number
        int a = (14 - month) / 12;
        int y = year + 4800 - a;
        int m = month + 12 * a - 3;
        long jd = day + (153 * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045;
        double d = jd - 2451545.0;

        double mDeg = (357.529 + 0.98560028 * d) % 360.0;
        double lDeg = (280.459 + 0.98564736 * d) % 360.0;
        double mRad = Math.toRadians(mDeg);

        double lamDeg = lDeg + 1.915 * Math.sin(mRad) + 0.020 * Math.sin(2 * mRad);
        double lamRad = Math.toRadians(lamDeg);
        double epsRad = Math.toRadians(23.439 - 0.00000036 * d);

        double sinDelta = Math.sin(epsRad) * Math.sin(lamRad);
        double deltaRad = Math.asin(sinDelta);

        double raRad = Math.atan2(Math.cos(epsRad) * Math.sin(lamRad), Math.cos(lamRad));
        double raDeg = (Math.toDegrees(raRad) + 360.0) % 360.0;

        double eqT = (lDeg - raDeg) * 4.0; // minutes
        if (eqT > 20.0) eqT -= 1440.0;
        if (eqT < -20.0) eqT += 1440.0;

        double noonUtc = 12.0 - (LONGITUDE / 15.0) - (eqT / 60.0);
        double noonLocal = noonUtc + tzOffsetHours;

        double latRad = Math.toRadians(LATITUDE);

        double hFajr = hourAngle(-19.5, latRad, deltaRad) / 15.0;
        double hSun = hourAngle(-0.8333, latRad, deltaRad) / 15.0;

        // Asr (Shafi'i shadow factor 1.0)
        double asrAltRad = Math.atan(1.0 / (1.0 + Math.tan(Math.abs(latRad - deltaRad))));
        double cosHAsr = (Math.sin(asrAltRad) - Math.sin(latRad) * Math.sin(deltaRad)) / (Math.cos(latRad) * Math.cos(deltaRad));
        cosHAsr = Math.max(-1.0, Math.min(1.0, cosHAsr));
        double hAsr = Math.toDegrees(Math.acos(cosHAsr)) / 15.0;

        double hIsha = hourAngle(-17.5, latRad, deltaRad) / 15.0;

        Prayer fajr = toPrayer("Fajr", "الفجر", noonLocal - hFajr);
        Prayer sunrise = toPrayer("Sunrise", "الشروق", noonLocal - hSun);
        Prayer dhuhr = toPrayer("Dhuhr", "الظهر", noonLocal + (1.0 / 60.0));
        Prayer asr = toPrayer("Asr", "العصر", noonLocal + hAsr);
        Prayer maghrib = toPrayer("Maghrib", "المغرب", noonLocal + hSun);
        Prayer isha = toPrayer("Isha", "العشاء", noonLocal + hIsha);

        double currentDecimal = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0;
        Prayer next = fajr;
        if (currentDecimal < fajr.timeDecimal) next = fajr;
        else if (currentDecimal < sunrise.timeDecimal) next = sunrise;
        else if (currentDecimal < dhuhr.timeDecimal) next = dhuhr;
        else if (currentDecimal < asr.timeDecimal) next = asr;
        else if (currentDecimal < maghrib.timeDecimal) next = maghrib;
        else if (currentDecimal < isha.timeDecimal) next = isha;
        else next = fajr; // After Isha, next is tomorrow's Fajr

        return new Schedule(fajr, sunrise, dhuhr, asr, maghrib, isha, next);
    }

    /**
     * Egypt DST (restored 2023): summer time GMT+3 from the last Friday of April
     * through the last Thursday of October; otherwise GMT+2.
     */
    static double egyptOffsetHours(int year, int month, int day) {
        if (year < 2023) {
            return OFFSET_STANDARD_HOURS;
        }
        int dstStart = lastWeekdayOfMonth(year, 4, Calendar.FRIDAY);
        int dstEnd = lastWeekdayOfMonth(year, 10, Calendar.THURSDAY);
        int ymd = year * 10000 + month * 100 + day;
        int start = year * 10000 + 4 * 100 + dstStart;
        int end = year * 10000 + 10 * 100 + dstEnd;
        if (ymd >= start && ymd <= end) {
            return OFFSET_SUMMER_HOURS;
        }
        return OFFSET_STANDARD_HOURS;
    }

    /** Day-of-month for the last {@code weekday} in {@code month} (1=Jan … 12=Dec). */
    private static int lastWeekdayOfMonth(int year, int month, int weekday) {
        Calendar c = Calendar.getInstance(Locale.ROOT);
        c.clear();
        c.set(Calendar.YEAR, year);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
        while (c.get(Calendar.DAY_OF_WEEK) != weekday) {
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        return c.get(Calendar.DAY_OF_MONTH);
    }

    private static double hourAngle(double alphaDeg, double latRad, double deltaRad) {
        double alphaRad = Math.toRadians(alphaDeg);
        double cosH = (Math.sin(alphaRad) - Math.sin(latRad) * Math.sin(deltaRad)) / (Math.cos(latRad) * Math.cos(deltaRad));
        if (cosH > 1.0) return 0.0;
        if (cosH < -1.0) return 180.0;
        return Math.toDegrees(Math.acos(cosH));
    }

    private static Prayer toPrayer(String name, String nameArabic, double timeDecimal) {
        double d = (timeDecimal + 24.0) % 24.0;
        int hour = (int) d;
        int minute = (int) Math.round((d - hour) * 60.0);
        if (minute >= 60) {
            hour = (hour + 1) % 24;
            minute = 0;
        }
        return new Prayer(name, nameArabic, hour, minute, d);
    }

    /** Formats a short banner string, e.g. "🕌 Asr 2:48 PM" or "🕌 Next: Maghrib 5:12 PM". */
    static String nextPrayerBanner(boolean is24Hour) {
        Schedule s = today();
        Prayer p = s.nextPrayer;
        String time = is24Hour ? p.format24h() : p.format12h();
        return "🕌 " + p.name + " " + time;
    }

    static String todayPrayerSummary() {
        return nextPrayerBanner(false);
    }
}
