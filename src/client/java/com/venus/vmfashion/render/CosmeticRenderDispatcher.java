package com.venus.vmfashion.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.loadout.LoadoutService;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Quaternionf;

import java.util.Map;
import java.util.UUID;

/**
 * 时装渲染入口：由 {@code CapeLayerMixin} 在 {@code CapeLayer#submit} 的 HEAD 调用。
 *
 * <p>此时 {@link PoseStack} 已经处在"原版玩家模型空间"（原点是脖子/身体顶部，Y 轴向下，Z 轴向后），
 * 而 GeckoLib 烘焙出来的模型用的是"脚底原点、Y 轴向上"的空间，所以每次渲染都要先做一次
 * {@link #applyModelSpace(PoseStack)} 换算；{@code attachedBone} 则借用 {@link PlayerModel}
 * 对应 {@link ModelPart} 的 pivot / 旋转，跟着头部、手臂一起动。
 *
 * <p><b>26.x 是提交式管线</b>：{@code submitCustomGeometry} 只是把 {@code Pose} 快照和回调存起来，
 * 回调要等整帧收集完、flush 时才执行。所以几何遍历<strong>必须</strong>写在 lambda 里，
 * 不能在 submit 阶段就画（那时 {@code PoseStack} 已经不是回调时的那一份了）。
 */
public final class CosmeticRenderDispatcher {

    private static final int WHITE = 0xFFFFFFFF;

    /**
     * 复用的四元数。渲染线程独占；{@code PoseStack.rotate} 会立刻把数值乘进矩阵，
     * 不持有引用，所以单实例在嵌套调用下也安全。
     */
    private static final Quaternionf ROTATION = new Quaternionf();

    private CosmeticRenderDispatcher() {
    }

    /**
     * 往渲染管线提交该玩家身上的所有时装。
     *
     * @param playerModel 父模型（{@code CapeLayer#getParentModel()}），用于 attachedBone
     */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                              AvatarRenderState state, PlayerModel playerModel) {
        if (!shouldRender(state)) {
            return;
        }
        UUID uuid = uuidOf(state);
        // 每 tick 只取一次快照：loadoutFor 对本地玩家会拷贝整个 Map（见 RenderCache 注释）
        Map<String, String> loadout = RenderCache.loadout(uuid);
        if (loadout.isEmpty()) {
            return;
        }
        LoadoutService loadouts = LoadoutService.getInstance();
        float globalScale = config().getScale();
        for (String cosmeticId : loadout.values()) {
            CosmeticDefinition definition = loadouts.resolve(cosmeticId);
            if (definition == null) {
                continue;
            }
            CosmeticAssets.Entry entry = CosmeticAssets.get(definition);
            if (entry == null) {
                continue;
            }
            submitOne(poseStack, collector, packedLight, definition, entry, playerModel, globalScale);
        }
    }

    private static void submitOne(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                                  CosmeticDefinition definition, CosmeticAssets.Entry entry,
                                  PlayerModel playerModel, float globalScale) {
        float scale = definition.scale() * globalScale;

        poseStack.pushPose();
        try {
            applyModelSpace(poseStack);
            // 缩放必须传进 applyAttachment：它要在"移到 pivot"和"移回原点"之间做，
            // 才是绕挂载点缩放（路线图 #12，原来缩放绕脚底）
            applyAttachment(poseStack, playerModel, definition.attachedBone(), scale);

            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(entry.texture()),
                    (pose, consumer) -> GeoModelRenderer.render(
                            entry.model(), pose, consumer, packedLight, OverlayTexture.NO_OVERLAY, WHITE));

            // emissive：只提交"emissive 贴图在该 UV 上确实不透明"的四边形。
            // 遮罩为空（没有 emissive / 全透明）时整趟 pass 都不提交。
            EmissiveMask mask = EmissiveMask.get(entry, definition);
            if (mask != null && !mask.isEmpty()) {
                // 用 eyes 管线而不是 entityTranslucentEmissive：两者的 RenderSetup 都
                // 不调 useLightmap（= 全亮），但 eyes 的 outline 属性是 NONE，
                // 不会给发光玩家额外描一圈（javap 核对 RenderTypes.lambda$static$21 / eyes）。
                collector.submitCustomGeometry(poseStack, RenderTypes.eyes(entry.emissive()),
                        (pose, consumer) -> GeoModelRenderer.renderEmissive(
                                entry.model(), pose, consumer, packedLight, OverlayTexture.NO_OVERLAY,
                                WHITE, mask));
            }
            // TODO(渲染，下一轮)：以下三项需要改架构，本轮只记录取舍：
            //  1) 半透明排序：自定义几何的发光节点进 SubmitNodeCollection 的
            //     translucentCustomGeometry（SimpleFeatureRenderPhase），只按 feature 分组、
            //     **不按距离排序**；和原版半透明模型/水面/染色玻璃的前后关系由阶段顺序决定。
            //     要修得改成 TranslucentFeatureRenderPhase（原版半透明模型走的那条），
            //     但那要替换 SubmitNodeCollector 的提交路径，不属于单件时装的改动范围。
            //  2) 合批：每件时装提交 2 次 submitCustomGeometry，每次 Pose.copy()，多件不合批；
            //     要合批得把同一玩家的多件时装合并成一次提交（需要先统一 RenderType/贴图）。
            //  3) 贴图图集：每件时装一张 DynamicTexture，多玩家时按 RenderType 反复切纹理绑定。
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * 把"Bedrock / GeckoLib 模型空间"换成"原版模型空间"。
     *
     * <p>两套坐标系的差别：
     * <ul>
     *   <li>GeckoLib 烘焙出来的模型用世界空间语义：原点在<strong>脚底</strong>，<strong>Y 轴向上</strong>
     *       （Blockbench 的 Bedrock 几何就是这么画的，加载器已经帮我们镜像了 X）；</li>
     *   <li>{@code CapeLayer#submit} 里拿到的 PoseStack 是原版玩家模型空间：原点在
     *       <strong>脖子（脚上方 1.501 格）</strong>，<strong>Y 轴向下</strong>。</li>
     * </ul>
     * 所以先上移 1.501 格、再把 X/Y 一起翻转，之后坐标就和 GeckoLib 的模型完全一致了
     * （X 也要翻：GeckoLib 加载时已经把 JSON 的 X 取反过一次，这里必须翻回来，
     * 否则模型会左右镜像——左脚的东西长到右脚）。
     */
    private static void applyModelSpace(PoseStack poseStack) {
        poseStack.translate(0.0F, 1.501F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
    }

    /**
     * 按 attachedBone 把模型挂到原版模型的对应部位上（不带额外缩放）。
     *
     * <p>保留这个 3 参数版本是因为 {@code tools/verify/Verify.java} 用反射按
     * {@code (PoseStack, PlayerModel, String)} 签名调它做 attachedBone 的数学验证。
     */
    private static void applyAttachment(PoseStack poseStack, PlayerModel model, String bone) {
        applyAttachment(poseStack, model, bone, 1.0F);
    }

    /**
     * 按 attachedBone 把模型挂到原版模型的对应部位上，并<strong>绕挂载 pivot 缩放</strong>。
     *
     * <p>pivot 和旋转都从原版 {@link ModelPart} 取，但要换算到上面说的"脚底原点 / Y 向上"坐标系，
     * 这样转头、摆手时时装才会跟着动。
     *
     * <p>缩放位置是关键（路线图 §3.5② / #12）：{@code translate(px,py,pz) → scale → translate(-px,…)}
     * 才是"以 pivot 为中心缩放"。旧代码把 scale 放在整个函数之后，此时已经回到脚底原点，
     * 于是 {@code attachedBone:head} 的皇冠放大 2 倍会离头 1.5 格。
     *
     * @param scale 额外缩放；{@code 1.0} 表示不缩放
     */
    private static void applyAttachment(PoseStack poseStack, PlayerModel model, String bone, float scale) {
        ModelPart part = partFor(model, bone);
        if (part == null) {
            // 没有父模型（理论上不会发生）：退化成"只缩放"，行为与旧代码一致
            if (scale != 1.0F) {
                poseStack.scale(scale, scale, scale);
            }
            return;
        }
        // 换算到"脚底原点 / Y 向上"坐标系：pivot 的 x、y 都取反（x 还要再上移 1.501），
        // 旋转同理——X/Y 轴角度取反，Z 轴不变（这是 F·R·F 共轭，F = scale(-1,-1,1)）
        float px = -part.x / 16.0F;
        float py = 1.501F - part.y / 16.0F;
        float pz = part.z / 16.0F;
        // 绕骨骼 pivot 旋转：先移到 pivot，旋转/缩放，再移回来，
        // 这样模型仍然用"脚底原点"的坐标书写（和 Blockbench 里看到的一致）
        poseStack.translate(px, py, pz);
        if (part.xRot != 0.0F || part.yRot != 0.0F || part.zRot != 0.0F) {
            poseStack.rotate(ROTATION.rotationZYX(part.zRot, -part.yRot, -part.xRot));
        }
        if (part.xScale != 1.0F || part.yScale != 1.0F || part.zScale != 1.0F) {
            poseStack.scale(part.xScale, part.yScale, part.zScale);
        }
        // 均匀缩放在这里（pivot 处）：和 part 的旋转可交换，所以放在旋转之后不影响结果
        if (scale != 1.0F) {
            poseStack.scale(scale, scale, scale);
        }
        poseStack.translate(-px, -py, -pz);
    }

    /**
     * attachedBone → 原版 {@link ModelPart}。
     *
     * <p>用 {@code equalsIgnoreCase} 而不是 {@code switch (bone.toLowerCase())}：
     * 后者每帧每件时装都分配一个 String（路线图 §3.6 的热路径分配）。
     */
    private static ModelPart partFor(PlayerModel model, String bone) {
        if (model == null) {
            return null;
        }
        if (bone == null) {
            return model.body;
        }
        if (bone.equalsIgnoreCase("head") || bone.equalsIgnoreCase("hat")
                || bone.equalsIgnoreCase("helmet")) {
            return model.head;
        }
        if (bone.equalsIgnoreCase("right_arm") || bone.equalsIgnoreCase("rightarm")) {
            return model.rightArm;
        }
        if (bone.equalsIgnoreCase("left_arm") || bone.equalsIgnoreCase("leftarm")) {
            return model.leftArm;
        }
        if (bone.equalsIgnoreCase("right_leg") || bone.equalsIgnoreCase("rightleg")) {
            return model.rightLeg;
        }
        if (bone.equalsIgnoreCase("left_leg") || bone.equalsIgnoreCase("leftleg")) {
            return model.leftLeg;
        }
        // body / torso / back / cape / wings / 未知值都挂到躯干
        return model.body;
    }

    /**
     * 隐藏原版部件（{@code hideParts}）。
     *
     * <p>由 {@code PlayerModelMixin} 在 {@code PlayerModel#setupAnim} 的 TAIL 调用：
     * 原版每帧都会重设 visible，我们最后覆盖，所以脱下时装后自动恢复。
     * 同时把结果写进 {@link FashionRenderState}，让盔甲 / 手持 / 披风 / 鞘翅 / 头戴 /
     * 鹦鹉 / 箭 / 蜂刺 / 旋转攻击这些<strong>各用各模型</strong>的渲染层也能一起跳过
     * （它们从不看父模型的 {@code visible}）。
     */
    public static void applyHideParts(PlayerModel model, AvatarRenderState state) {
        int mask = hiddenMaskFor(state);
        if (mask == HiddenParts.NONE || model == null) {
            return;
        }
        if (HiddenParts.hidden(mask, HiddenParts.HEAD)) {
            model.head.visible = false;
            model.hat.visible = false;
        }
        if (HiddenParts.hidden(mask, HiddenParts.BODY)) {
            model.body.visible = false;
            model.jacket.visible = false;
        }
        if (HiddenParts.hidden(mask, HiddenParts.RIGHT_ARM)) {
            model.rightArm.visible = false;
            model.rightSleeve.visible = false;
        }
        if (HiddenParts.hidden(mask, HiddenParts.LEFT_ARM)) {
            model.leftArm.visible = false;
            model.leftSleeve.visible = false;
        }
        if (HiddenParts.hidden(mask, HiddenParts.RIGHT_LEG)) {
            model.rightLeg.visible = false;
            model.rightPants.visible = false;
        }
        if (HiddenParts.hidden(mask, HiddenParts.LEFT_LEG)) {
            model.leftLeg.visible = false;
            model.leftPants.visible = false;
        }
    }

    /**
     * 算出本帧的 hideParts 掩码并写进渲染状态（各渲染层从那里读）。
     *
     * <p>写是<strong>无条件</strong>的：不需要隐藏时也要写 0，否则上一帧的掩码会留在
     * 被复用的渲染状态对象上，脱下时装后别人会一直是隐形人。
     *
     * @return 掩码，见 {@link HiddenParts}
     */
    public static int hiddenMaskFor(AvatarRenderState state) {
        int mask = HiddenParts.NONE;
        if (state != null && shouldRender(state)) {
            mask = RenderCache.hiddenMask(uuidOf(state));
        }
        if (state instanceof FashionRenderState fashion) {
            fashion.vmfashion$setHiddenParts(mask);
        }
        return mask;
    }

    /**
     * 读渲染状态上的 hideParts 掩码（各渲染层 mixin 用）。
     *
     * <p>非玩家状态（{@code FashionRenderState} 不是它的父类型）返回 {@link HiddenParts#NONE}，
     * 所以同一个 mixin 挂在通用渲染层（例如 {@code ItemInHandLayer}）上也不会影响生物。
     */
    public static int hiddenParts(Object state) {
        return state instanceof FashionRenderState fashion
                ? fashion.vmfashion$getHiddenParts()
                : HiddenParts.NONE;
    }

    /**
     * 从穿戴列表算出 hideParts 并集（供 {@link RenderCache} 每 tick 调一次）。
     *
     * <p>只有"模型/贴图真的加载成功"的时装才参与：否则资源损坏时玩家会变成隐形人
     * （连原版皮肤都看不到）。这里直接用位或累加，不建中间 Set。
     */
    static int computeHiddenMask(Map<String, String> loadout) {
        if (loadout == null || loadout.isEmpty()) {
            return HiddenParts.NONE;
        }
        LoadoutService loadouts = LoadoutService.getInstance();
        int mask = HiddenParts.NONE;
        for (String cosmeticId : loadout.values()) {
            CosmeticDefinition definition = loadouts.resolve(cosmeticId);
            if (definition != null && CosmeticAssets.get(definition) != null) {
                mask |= HiddenParts.of(definition.hideParts());
            }
        }
        return mask;
    }

    /**
     * 统一的"这件时装到底画不画"判定。
     *
     * <p>渲染（{@link #submit}）和隐藏原版部件（{@link #hiddenMaskFor}）<strong>必须</strong>
     * 用同一套判定：否则关掉"渲染他人"或超出渲染距离时，别人的原版模型被藏了、
     * 时装却没画，玩家会变成隐形人。
     */
    private static boolean shouldRender(AvatarRenderState state) {
        ModConfig config = config();
        if (config == null || !config.isEnabled()) {
            return false;
        }
        // 隐身时不渲染（和披风/盔甲层保持一致）
        if (state == null || state.isInvisible) {
            return false;
        }
        UUID uuid = uuidOf(state);
        if (uuid == null) {
            return false;
        }
        LoadoutService loadouts = LoadoutService.getInstance();
        if (!loadouts.isLocalPlayer(uuid) && !config.isRenderOtherPlayers()) {
            return false;
        }
        double distance = config.getRenderDistance();
        // 距离剔除。
        //
        // 关于"视锥剔除"（路线图 §3.7 最后一条）：核对 26.3 字节码后确认**不需要**再加一层。
        // 实体在进入渲染层之前就已经被原版剔除过了：
        //   EntityRenderDispatcher#shouldRender(Entity, Frustum, double, double, double, float)
        //     -> EntityRenderer#shouldRender(...) 用实体 AABB 做 frustum.isVisible 测试。
        // 也就是说本方法能被执行，等价于"这个玩家的 AABB 已经在视锥里"；时装挂在玩家身上，
        // 不可能比玩家更可见。再加近似锥体测试的唯一效果是"屏幕边缘的玩家突然没时装"（误剔除），
        // 收益为 0，所以这里只保留距离。Camera#getCullFrustum() 虽然是 public，同样不建议用。
        return distance <= 0 || state.distanceToCameraSq <= distance * distance;
    }

    private static UUID uuidOf(AvatarRenderState state) {
        return state instanceof FashionRenderState fashion ? fashion.vmfashion$getUuid() : null;
    }

    private static ModConfig config() {
        VMFashionClient client = VMFashionClient.getInstance();
        return client == null ? null : client.getConfig();
    }
}
