package com.venus.vmfashion.mixin.ping;

import com.venus.vmfashion.ping.render.CameraCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 抓相机矩阵 —— 世界标点渲染的<b>唯一</b>数据来源。
 *
 * <p>为什么非要 mixin：26.3 的 {@code fabric-rendering-v1} 已经删掉了
 * {@code WorldRenderEvents}，HUD 绘制时拿不到世界渲染的矩阵；而
 * {@code GameRenderState}（含 {@code CameraRenderState}）是 {@code GameRenderer} 的
 * {@code private final} 字段，没有公开 getter。上游 Ping Wheel 26.3 分支的做法就是在
 * {@code GameRenderer#extract} 里抓一份，我们也照抄 —— 这样世界坐标 → 屏幕坐标只需要一次
 * 4×4 乘法，稳定、跨版本、不依赖 26.3 那套还很不友好的 submit 管线。
 *
 * <p>注入点选在 {@code Gui#extractRenderState} 调用<b>之前</b>：
 * <ol>
 *   <li>此时 {@code extractCamera} / {@code LevelExtractor.extract} 已经跑完，
 *       {@code cameraRenderState} 是本帧的最新值；</li>
 *   <li>而 HUD（我们的 {@code PingRenderer}）正是在 {@code Gui#extractRenderState}
 *       里面被调用的，所以拿到的一定是"本帧"的矩阵，不会晚一帧。</li>
 * </ol>
 *
 * <p><b>为什么要自己算投影矩阵</b>：{@code CameraRenderState#projectionMatrix} 是"干净"的
 * 投影矩阵，<b>不含</b>视角摆动（bobView）、受伤抖动（bobHurt）和反胃/传送门扭曲。
 * 直接用它在玩家走路、受伤、进传送门时会看到标点和世界错位（"飘"）。
 * 这里按原版 {@code GameRenderer#extractRenderState} 的顺序把这几项乘进去
 * （公式照抄上游 {@code GameRendererMixin#PingWheel_calculateProjectionMatrix}）。
 *
 * <p>性能：每帧只有 2 次 {@code Matrix4f#get}（16 个 float 的拷贝）+ 少量矩阵乘法，
 * 并且矩阵 / PoseStack 都是复用的静态对象，<b>稳态零分配</b>。
 *
 * <p>合订版适配：包名改为 {@code com.venus.vmfashion.mixin.ping}（mixin json 里登记为
 * {@code ping.GameRendererMixin}），快照目标改为 {@link CameraCapture}，
 * {@code @Unique} 成员前缀统一成 {@code vmfashion$} / {@code PING$}；注入逻辑不变。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    /** 受伤时的视角抖动（原版私有方法，这里用 shadow 调用）。 */
    @Shadow
    protected abstract void bobHurt(CameraRenderState cameraState, PoseStack poseStack);

    /** 走路时的视角摆动（原版私有方法）。 */
    @Shadow
    protected abstract void bobView(CameraRenderState cameraState, PoseStack poseStack);

    /** 复用的投影矩阵（渲染线程独占）。 */
    @Unique
    private static final Matrix4f PING$PROJECTION = new Matrix4f();
    /** 复用的 PoseStack（渲染线程独占）。 */
    @Unique
    private static final PoseStack PING$BOB_STACK = new PoseStack();

    /**
     * 在 {@code Gui#extractRenderState} 之前抓一帧相机快照。
     *
     * <p>用 {@code @Inject} 而不是 {@code @Redirect}：不改原版行为，只是"顺手抄一份数据"。
     */
    @Inject(
            method = "extract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V"
            )
    )
    private void vmfashion$captureCamera(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        try {
            CameraRenderState cameraState = this.gameRenderState.levelRenderState.cameraRenderState;
            if (cameraState == null || cameraState.pos == null
                    || cameraState.viewRotationMatrix == null || cameraState.projectionMatrix == null) {
                // 还没进世界 / 相机没初始化：清掉上一局的快照，免得画出幽灵标记
                CameraCapture.invalidate();
                return;
            }
            Matrix4f projection = vmfashion$calculateProjectionMatrix(cameraState);
            CameraCapture.capture(
                    cameraState.viewRotationMatrix,
                    projection,
                    cameraState.pos.x,
                    cameraState.pos.y,
                    cameraState.pos.z,
                    this.minecraft.getWindow().getGuiScaledWidth(),
                    this.minecraft.getWindow().getGuiScaledHeight()
            );
        } catch (Throwable t) {
            // 渲染路径里任何异常都不能把游戏搞崩：这一帧不画标点就是了
            CameraCapture.invalidate();
        }
    }

    /**
     * 计算"和画面完全对齐"的投影矩阵：干净投影 × 视角摆动 × 反胃/传送门扭曲。
     *
     * <p>与上游逐行等价；矩阵与 PoseStack 复用静态对象，避免每帧分配。
     */
    @Unique
    private Matrix4f vmfashion$calculateProjectionMatrix(CameraRenderState cameraState) {
        var optionsState = this.gameRenderState.optionsRenderState;
        var playerState = this.gameRenderState.levelRenderState.playerRenderState;

        Matrix4f projection = PING$PROJECTION.set(cameraState.projectionMatrix);

        PoseStack bobStack = PING$BOB_STACK;
        bobStack.pushPose();
        try {
            this.bobHurt(cameraState, bobStack);
            if (optionsState.bobView) {
                this.bobView(cameraState, bobStack);
            }
            projection.mul(bobStack.last().pose());
        } finally {
            bobStack.popPose();
        }

        // 反胃 / 传送门扭曲：原版在渲染世界时会把它乘进投影矩阵，这里保持一致
        float screenEffectScale = optionsState.screenEffectScale;
        float spinningEffectIntensity = Math.max(playerState.portalEffectIntensity, playerState.nauseaEffectIntensity)
                * screenEffectScale * screenEffectScale;
        if (spinningEffectIntensity > 0.0F) {
            float skew = 5.0F / (spinningEffectIntensity * spinningEffectIntensity + 5.0F)
                    - spinningEffectIntensity * 0.04F;
            skew *= skew;
            Vector3f axis = new Vector3f(0.0F, Mth.SQRT_OF_TWO / 2.0F, Mth.SQRT_OF_TWO / 2.0F);
            float angle = playerState.spinningEffectAngle * ((float) Math.PI / 180F);
            projection.rotate(angle, axis);
            projection.scale(1.0F / skew, 1.0F, 1.0F);
            projection.rotate(-angle, axis);
        }
        return projection;
    }
}
