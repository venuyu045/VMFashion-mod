package com.venus.vmfashion.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackUpdatePolicy} 单测：M01「服务端包新版本永远拉不下来」的判定规则。
 *
 * <p>这是纯逻辑（不加载 Minecraft 类），覆盖 sha 优先 / 版本回退 / 信息不足保守不动。
 */
class PackUpdatePolicyTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Test
    void sameShaIsUpToDate() {
        assertEquals(PackUpdatePolicy.Decision.UP_TO_DATE,
                PackUpdatePolicy.decide("1.0.0", SHA_A, "1.0.0", SHA_A));
    }

    @Test
    void shaIsCaseInsensitive() {
        // 后端/标记里的大小写不一定一致（虽然约定小写），不能因此误判成"要更新"
        assertEquals(PackUpdatePolicy.Decision.UP_TO_DATE,
                PackUpdatePolicy.decide("1.0.0", SHA_A.toUpperCase(), "1.0.0", SHA_A));
    }

    @Test
    void differentShaNeedsUpdateEvenIfVersionLooksSame() {
        // 同版本号但内容变了（后端重打了包）：sha 优先，必须更新
        assertEquals(PackUpdatePolicy.Decision.UPDATE,
                PackUpdatePolicy.decide("1.0.0", SHA_A, "1.0.0", SHA_B));
    }

    @Test
    void differentVersionNeedsUpdate() {
        assertEquals(PackUpdatePolicy.Decision.UPDATE,
                PackUpdatePolicy.decide("1.0.0", "", "1.1.0", ""));
    }

    @Test
    void sameVersionWithoutShaIsUpToDate() {
        assertEquals(PackUpdatePolicy.Decision.UP_TO_DATE,
                PackUpdatePolicy.decide("1.1.0", "", "1.1.0", ""));
    }

    @Test
    void shaWinsOverVersion() {
        // 本地 sha 与清单一致、版本字符串不同（例如后端改了版本号但内容没变）：不必重下
        assertEquals(PackUpdatePolicy.Decision.UP_TO_DATE,
                PackUpdatePolicy.decide("1.0.0", SHA_A, "1.0.1", SHA_A));
    }

    @Test
    void missingLocalInfoIsConservative() {
        // 老包 / 手放目录没有 .sha256 标记：信息不足时保守认为不用更新，避免每次进服重下
        assertEquals(PackUpdatePolicy.Decision.UP_TO_DATE,
                PackUpdatePolicy.decide("", "", "1.0.0", SHA_A));
    }

    @Test
    void missingCatalogInfoIsConservative() {
        assertEquals(PackUpdatePolicy.Decision.UP_TO_DATE,
                PackUpdatePolicy.decide("1.0.0", SHA_A, "", ""));
    }

    @Test
    void nullSafe() {
        assertEquals(PackUpdatePolicy.Decision.UP_TO_DATE,
                PackUpdatePolicy.decide(null, null, null, null));
        assertFalse(PackUpdatePolicy.needsUpdate(null, null, null, null));
        // 本地没有任何元数据（无标记的老包）：保守不动，避免每次进服重下
        assertFalse(PackUpdatePolicy.needsUpdate(null, null, "1.0.0", SHA_A));
        // 本地有版本、清单有不同 sha：正常判定为要更新
        assertTrue(PackUpdatePolicy.needsUpdate("1.0.0", SHA_A, "1.0.0", SHA_B));
    }
}
