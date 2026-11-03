package net.minecraft.resources;

import java.util.Objects;

/**
 * UI 预览器用的 {@code net.minecraft.resources.Identifier} 精简替身。
 *
 * <p>只保留 UI 层真正用到的成员：解析、命名空间/路径拆分、相等性。
 * 行为与游戏内一致（{@code namespace:path}，缺省命名空间 {@code minecraft}）。
 */
public final class Identifier implements Comparable<Identifier> {
    public static final char NAMESPACE_SEPARATOR = ':';
    public static final String DEFAULT_NAMESPACE = "minecraft";

    private final String namespace;
    private final String path;

    private Identifier(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static Identifier fromNamespaceAndPath(String namespace, String path) {
        return new Identifier(namespace == null || namespace.isEmpty() ? DEFAULT_NAMESPACE : namespace, path);
    }

    public static Identifier parse(String value) {
        int i = value.indexOf(NAMESPACE_SEPARATOR);
        if (i < 0) {
            return new Identifier(DEFAULT_NAMESPACE, value);
        }
        return new Identifier(value.substring(0, i), value.substring(i + 1));
    }

    public static Identifier withDefaultNamespace(String path) {
        return new Identifier(DEFAULT_NAMESPACE, path);
    }

    public static Identifier tryParse(String value) {
        try {
            return parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String getNamespace() {
        return namespace;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return namespace + NAMESPACE_SEPARATOR + path;
    }

    @Override
    public int compareTo(Identifier other) {
        int i = namespace.compareTo(other.namespace);
        return i != 0 ? i : path.compareTo(other.path);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Identifier other)) {
            return false;
        }
        return namespace.equals(other.namespace) && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, path);
    }
}
