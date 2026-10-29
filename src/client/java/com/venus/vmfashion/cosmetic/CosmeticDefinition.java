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

    public CosmeticDefinition(String packId, String localId, String name, CosmeticCategory category,
                              Path modelFile, Path textureFile, Path emissiveFile, Path animationFile,
                              String attachedBone, Set<String> hideParts, float scale) {
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

    @Override
    public String toString() {
        return "CosmeticDefinition[" + id + " (" + category + ")]";
    }
}
