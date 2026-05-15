package com.connecthub.message.config;

/**
 * Plan limits aligned with billing — enforced for REST and Kafka inbound paths.
 */
public final class SubscriptionTierLimits {

    public static final int FREE_MESSAGES_PER_MINUTE = 5;
    public static final int PRO_MESSAGES_PER_MINUTE = 30;

    private SubscriptionTierLimits() {}

    public static String normalizeTier(String headerOrTokenTier) {
        if (headerOrTokenTier == null || headerOrTokenTier.isBlank()) return "FREE";
        return headerOrTokenTier.trim().toUpperCase();
    }

    public static int messagesPerMinute(String tier) {
        String t = normalizeTier(tier);
        if ("PRO".equals(t) || "BUSINESS".equals(t)) return PRO_MESSAGES_PER_MINUTE;
        return FREE_MESSAGES_PER_MINUTE;
    }
}
