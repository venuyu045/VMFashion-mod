package com.venus.vmfashion.cosmetic;

import java.nio.file.Path;
import java.util.Set;

/**
 * 一件时装的静态定义（从 pack.json 解析而来）。
 *
 * <p>这里只描述"资源在哪、挂在哪"，不包含任何渲染逻辑，也不持有任何 Minecraft 对象，
 * 因此可以在任意线程解析和校验。
 */
public final class CosmeticDefinition {

    /** 全局唯一 ID，格式 {@code <packId>:<localId>}。 */
    private final String id;
    private final String localId;
    private final String packId;
    private final String name;
    private final CosmeticCategory category;

    private final Path modelFile;
    private final Path textureFile;
    private final Path emissiveFile;
    private final Path animationFile;

    /** 挂载到玩家模型的哪根骨骼，小写；缺省 {@code body}。 */
    private final String attachedBone;
    /** 穿上后要隐藏的原版部件（head/body/right_arm/left_arm/right_leg/left_leg）。 */
    private final Set<String> hideParts;
    private final float scale;
    /**
     * 是否用半透明管线渲染（{@code pack.json} / {@code meta.json} 的 {@code material:"translucent"}）。
     *
     * <p>默认 {@code false} = cutout（alpha 只分 0/1），适合绝大多数时装；纱裙、半透翅膀、
     * 玻璃质感挂件需要 {@code translucent}。半透明自定义几何<strong>不按距离排序</strong>
     * （见 {@code CosmeticRenderDispatcher} 的 TODO），同屏多件半透明时前后关系由提交顺序决定。
     */
    private final boolean translucent;
    /**
     * 指定播放哪个动画（{@code anim.json} 里 {@code animations} 的 key）；空串 = 播第一个。
     *
     * <p>一件时装里有多个动画（待机 / 漂浮 / 特殊动作）时靠这个字段选，而不是只能播第一个。
     */
    private final String animationName;

    /** 兼容旧调用（不带半透明 / 动画名）：等价于 cutout + 播第一个动画。 */
    public CosmeticDefinition(String packId, String localId, String name, CosmeticCategory category,
                              Path modelFile, Path textureFile, Path emissiveFile, Path animationFile,
                              String attachedBone, Set<String> hideParts, float scale) {
        this(packId, localId, name, category, modelFile, textureFile, emissiveFile, animationFile,
                attachedBone, hideParts, scale, false, "");
    }

    public CosmeticDefinition(String packId, String localId, String name, CosmeticCategory category,
                              Path modelFile, Path textureFile, Path emissiveFile, Path animationFile,
                              String attachedBone, Set<String> hideParts, float scale,
                              boolean translucent, String animationName) {
        this.packId = packId;
        this.localId = localId;
        this.id = packId + ":" + localId;
        this.name = name;
        this.category = category;
        this.modelFile = modelFile;
        this.textureFile = textureFile;
        this.emissiveFile = emissiveFile;
        this.animationFile = animationFile;
        this.attachedBone = attachedBone;
        this.hideParts = Set.copyOf(hideParts);
        this.scale = scale;
        this.translucent = translucent;
        this.animationName = animationName == null ? "" : animationName.trim();
    }

    public String id() {
        return id;
    }

    public String localId() {
        return localId;
    }

    public String packId() {
        return packId;
    }

    public String name() {
        return name;
    }

    public CosmeticCategory category() {
        return category;
    }

    public Path modelFile() {
        return modelFile;
    }

    public Path textureFile() {
        return textureFile;
    }

    /** 发光层贴图，可能为 {@code null}。 */
    public Path emissiveFile() {
        return emissiveFile;
    }

    /** Bedrock 动画文件，可能为 {@code null}。 */
    public Path animationFile() {
        return animationFile;
    }

    public String attachedBone() {
        return attachedBone;
    }

    public Set<String> hideParts() {
        return hideParts;
    }

    public float scale() {
        return scale;
    }

    /** 是否走半透明管线（{@code material:"translucent"}）；缺省 {@code false} = cutout。 */
    public boolean translucent() {
        return translucent;
    }

    /** 指定播放的动画名（{@code anim.json} 的 key）；空串表示播第一个。 */
    public String animationName() {
        return animationName;
    }

    @Override
    public String toString() {
        return "CosmeticDefinition[" + id + " (" + category + ")]";
    }
}
