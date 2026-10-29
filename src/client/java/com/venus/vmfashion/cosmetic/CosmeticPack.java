package com.venus.vmfashion.cosmetic;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 一个时装包（磁盘上的一个目录）。
 *
 * <p>包只描述资源，不含任何运行期状态；{@link PackLoader} 负责解析，{@link CosmeticRegistry} 负责汇总。
 */
public final class CosmeticPack {

    private final String id;
    private final String name;
    private final String version;
    private final String author;
    private final String license;
    private final Path root;
    private final List<CosmeticDefinition> cosmetics;

    public CosmeticPack(String id, String name, String version, String author, String license,
                        Path root, List<CosmeticDefinition> cosmetics) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.license = license;
        this.root = root;
        this.cosmetics = Collections.unmodifiableList(List.copyOf(cosmetics));
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public String author() {
        return author;
    }

    public String license() {
        return license;
    }

    /** 包的根目录，所有资源路径都相对于它。 */
    public Path root() {
        return root;
    }

    public List<CosmeticDefinition> cosmetics() {
        return cosmetics;
    }

    @Override
    public String toString() {
        return "CosmeticPack[" + id + " v" + version + ", " + cosmetics.size() + " 件]";
    }
}
