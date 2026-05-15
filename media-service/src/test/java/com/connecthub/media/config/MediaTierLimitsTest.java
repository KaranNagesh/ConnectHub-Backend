package com.connecthub.media.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaTierLimitsTest {

    @Test
    void normalizeTier_null_returnsFree() {
        assertThat(MediaTierLimits.normalizeTier(null)).isEqualTo("FREE");
    }

    @Test
    void normalizeTier_blank_returnsFree() {
        assertThat(MediaTierLimits.normalizeTier("   ")).isEqualTo("FREE");
    }

    @Test
    void normalizeTier_lowercasePro_returnsUppercase() {
        assertThat(MediaTierLimits.normalizeTier("pro")).isEqualTo("PRO");
    }

    @Test
    void normalizeTier_withWhitespace_trims() {
        assertThat(MediaTierLimits.normalizeTier(" PRO ")).isEqualTo("PRO");
    }

    @Test
    void storageCapKb_free_returns100MB() {
        assertThat(MediaTierLimits.storageCapKb("FREE")).isEqualTo(100L * 1024L);
    }

    @Test
    void storageCapKb_pro_returns10GB() {
        assertThat(MediaTierLimits.storageCapKb("PRO")).isEqualTo(10L * 1024L * 1024L);
    }

    @Test
    void storageCapKb_business_returns10GB() {
        assertThat(MediaTierLimits.storageCapKb("BUSINESS")).isEqualTo(10L * 1024L * 1024L);
    }

    @Test
    void storageCapKb_unknown_returnsFreeLimit() {
        assertThat(MediaTierLimits.storageCapKb("ENTERPRISE")).isEqualTo(100L * 1024L);
    }

    @Test
    void uploadsPerMinute_free_returns5() {
        assertThat(MediaTierLimits.uploadsPerMinute("FREE")).isEqualTo(5);
    }

    @Test
    void uploadsPerMinute_pro_returns30() {
        assertThat(MediaTierLimits.uploadsPerMinute("PRO")).isEqualTo(30);
    }

    @Test
    void uploadsPerMinute_business_returns30() {
        assertThat(MediaTierLimits.uploadsPerMinute("BUSINESS")).isEqualTo(30);
    }

    @Test
    void uploadsPerMinute_null_returnsFreeLimits() {
        assertThat(MediaTierLimits.uploadsPerMinute(null)).isEqualTo(5);
    }
}
