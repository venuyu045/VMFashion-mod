package com.venus.vmfashion.ping.util;

import com.venus.vmfashion.config.PingSettings;
import com.venus.vmfashion.ping.PingService;
import com.venus.vmfashion.ping.core.PingKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * 提示音播放。
 *
 * <p>先用原版音效（{@code NOTE_BLOCK_PLING} / {@code NOTE_BLOCK_BASS}）区分"自己标点"和
 * "收到别人的标点"：自己标点音调高一点、别人的低一点，避免连续收到多个标点时糊成一片。
 * 自定义音效在后续打磨阶段替换，接口不变。
 *
 * <p>为什么不在渲染线程直接播：声音播放本身是线程安全的（SoundManager 内部队列），
 * 但为了不阻塞渲染，仍然走 {@link Minecraft#execute}。
 *
 * <p>合订版适配：配置从 {@link PingSettings} 读（{@code playSound} / {@code soundVolume} 改成 getter），
 * 包名迁入 {@code com.venus.vmfashion.ping.util}；播放逻辑不变。
 */
public final class SoundPlayer {

    private SoundPlayer() {
    }

    /** 自己标点：清脆的高音。 */
    public static void playOwnPing(PingKind kind) {
        play(kind, 1.0f, 1.6f);
    }

    /** 收到别人的标点：略低的提示音，音量随配置。 */
    public static void playRemotePing(PingKind kind) {
        play(kind, 0.8f, 1.0f);
    }

    /** 打开轮盘：轻响一声。 */
    public static void playWheelOpen() {
        play(PingKind.DEFAULT, 0.35f, 2.0f);
    }

    private static void play(PingKind kind, float volumeScale, float pitch) {
        PingSettings config = PingService.getInstance().settings();
        if (!config.isPlaySound() || config.getSoundVolume() <= 0f) {
            return;
        }
        float volume = Math.max(0f, Math.min(1f, config.getSoundVolume() * volumeScale));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> {
            try {
                // 危险标点用低音提醒，其它类型用 pling
                // NOTE_BLOCK_* 在 26.3 是 Holder.Reference，取 value() 拿到 SoundEvent 才能带 pitch
                var sound = (kind == PingKind.DANGER
                        ? SoundEvents.NOTE_BLOCK_BASS
                        : SoundEvents.NOTE_BLOCK_PLING).value();
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, volume, pitch));
            } catch (Throwable t) {
                // 声音播不出来不影响功能
            }
        });
    }
}
