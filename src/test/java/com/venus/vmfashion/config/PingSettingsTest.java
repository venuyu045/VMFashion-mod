package com.venus.vmfashion.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PingSettings} 单测：默认值、越界收敛、后端地址推导。
 *
 * <p>纯逻辑，不加载任何 Minecraft / Fabric 类（{@code PingSettings} 本身也不依赖它们）。
 */
class PingSettingsTest {

    private static final String OFFICIAL_FASHION = "https://www.venusyu045.cn/api/fashion";
    private static final String OFFICIAL_PING = "https://www.venusyu045.cn/api/ping";

    // ==================== 默认值 ====================

    @Test
    void defaultsAreSane() {
        PingSettings settings = new PingSettings();
        assertTrue(settings.isEnabled());
        assertTrue(settings.isShowOwnPings());
        assertTrue(settings.isShowOthersPings());
        assertTrue(settings.isShowDistance());
        assertFalse(settings.isShowPlayerNames());
        assertTrue(settings.isPlaySound());
        assertEquals(0.7F, settings.getSoundVolume(), 1.0e-6F);
        assertEquals(96, settings.getMaxIconDistance());
        assertEquals(20, settings.getPingTtlSeconds());
        assertEquals(20, settings.getPollTimeoutSeconds());
        assertTrue(settings.isShowEdgeIndicator());
        assertTrue(settings.isShowStatus());
        assertTrue(settings.isDegradeToLocal());
        assertFalse(settings.isDebugLogging());
        assertEquals("", settings.getApiBaseUrl(), "默认留空 = 跟随时装后端");
    }

    // ==================== 越界收敛 ====================

    @Test
    void sanitizeClampsOutOfRangeValues() {
        PingSettings settings = new PingSettings();
        settings.setSoundVolume(2.5F);
        settings.setMaxIconDistance(1);
        settings.setPingTtlSeconds(999);
        settings.setPollTimeoutSeconds(1);
        settings.setApiBaseUrl("  https://example.com/api/ping///  ");
        settings.sanitize();

        assertEquals(1.0F, settings.getSoundVolume(), 1.0e-6F);
        assertEquals(8, settings.getMaxIconDistance());
        assertEquals(20, settings.getPingTtlSeconds());
        assertEquals(5, settings.getPollTimeoutSeconds());
        assertEquals("https://example.com/api/ping", settings.getApiBaseUrl());
    }

    @Test
    void sanitizeFallsBackForNonFiniteVolume() {
        PingSettings settings = new PingSettings();
        settings.setSoundVolume(Float.NaN);
        settings.sanitize();
        assertEquals(0.7F, settings.getSoundVolume(), 1.0e-6F);

        // 非有限值一律回退默认音量（setter 与 sanitize 同一口径），而不是夹到 0
        settings.setSoundVolume(Float.NEGATIVE_INFINITY);
        settings.sanitize();
        assertEquals(0.7F, settings.getSoundVolume(), 1.0e-6F);
    }

    // ==================== 后端地址推导 ====================

    @Test
    void derivesPingBaseFromOfficialFashionBase() {
        assertEquals(OFFICIAL_PING, PingSettings.deriveApiBaseUrl(OFFICIAL_FASHION));
    }

    @Test
    void derivesPingBaseFromTrailingSlashFashionBase() {
        assertEquals(OFFICIAL_PING, PingSettings.deriveApiBaseUrl(OFFICIAL_FASHION + "/"));
    }

    @Test
    void appendsPingWhenBaseDoesNotEndWithFashion() {
        assertEquals("https://example.com/api/ping", PingSettings.deriveApiBaseUrl("https://example.com/api"));
        assertEquals("https://example.com/ping", PingSettings.deriveApiBaseUrl("https://example.com"));
    }

    @Test
    void derivesEmptyWhenFashionBaseEmpty() {
        assertEquals("", PingSettings.deriveApiBaseUrl(""));
        assertEquals("", PingSettings.deriveApiBaseUrl(null));
        assertEquals("", PingSettings.deriveApiBaseUrl("   "));
    }

    @Test
    void explicitOverrideWinsOverDerivation() {
        PingSettings settings = new PingSettings();
        settings.setApiBaseUrl("https://self-hosted.example/ping/");
        assertEquals("https://self-hosted.example/ping",
                settings.resolveApiBaseUrl(OFFICIAL_FASHION));
    }

    @Test
    void blankOverrideFallsBackToDerivation() {
        PingSettings settings = new PingSettings();
        assertEquals(OFFICIAL_PING, settings.resolveApiBaseUrl(OFFICIAL_FASHION));
        assertEquals("", settings.resolveApiBaseUrl(""));
    }

    @Test
    void relayEnabledRequiresSwitchAndBackend() {
        PingSettings settings = new PingSettings();
        assertTrue(settings.relayEnabled(OFFICIAL_FASHION));
        assertFalse(settings.relayEnabled(""), "没配后端时只能本地标点");

        settings.setEnabled(false);
        assertFalse(settings.relayEnabled(OFFICIAL_FASHION));

        settings.setEnabled(true);
        settings.setApiBaseUrl("https://self-hosted.example/ping");
        assertTrue(settings.relayEnabled(""), "显式覆盖地址时不再依赖时装地址");
    }
}
