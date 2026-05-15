package com.connecthub.media.config;

public final class MediaTierLimits {

    /** Total media storage per user (KB). */
    public static final long FREE_STORAGE_KB = 100L * 1024L;
    public static final long PRO_STORAGE_KB = 10L * 1024L * 1024L;

    public static final int FREE_UPLOADS_PER_MINUTE = 5;
    public static final int PRO_UPLOADS_PER_MINUTE = 30;

    private MediaTierLimits() {}

    public static String normalizeTier(String tier) {
        if (tier == null || tier.isBlank()) return "FREE";
        return tier.trim().toUpperCase();
    }

    public static long storageCapKb(String tier) {
        String t = normalizeTier(tier);
        if ("PRO".equals(t) || "BUSINESS".equals(t)) return PRO_STORAGE_KB;
        return FREE_STORAGE_KB;
    }

    public static int uploadsPerMinute(String tier) {
        String t = normalizeTier(tier);
        if ("PRO".equals(t) || "BUSINESS".equals(t)) return PRO_UPLOADS_PER_MINUTE;
        return FREE_UPLOADS_PER_MINUTE;
    }
}
