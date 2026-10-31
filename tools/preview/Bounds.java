import java.util.List;

/**
 * 轴对齐包围盒（E 空间：脚底原点、Y 向上、+Z 背后、单位 1/16 格）。
 *
 * <p>用途有三处：相机自动取景、比例参考剪影的占地、以及自检报告里的包围盒输出。
 */
final class Bounds {

    double minX = Double.POSITIVE_INFINITY;
    double minY = Double.POSITIVE_INFINITY;
    double minZ = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY;
    double maxY = Double.NEGATIVE_INFINITY;
    double maxZ = Double.NEGATIVE_INFINITY;

    static Bounds of(List<QuadRecorder.Quad> quads) {
        Bounds bounds = new Bounds();
        for (QuadRecorder.Quad quad : quads) {
            for (int i = 0; i < 4; i++) {
                bounds.add(quad.pos[i][0], quad.pos[i][1], quad.pos[i][2]);
            }
        }
        return bounds;
    }

    static Bounds ofBox(double x0, double y0, double z0, double x1, double y1, double z1) {
        Bounds bounds = new Bounds();
        bounds.add(x0, y0, z0);
        bounds.add(x1, y1, z1);
        return bounds;
    }

    void add(double x, double y, double z) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
    }

    void union(Bounds other) {
        if (other == null || other.isEmpty()) {
            return;
        }
        add(other.minX, other.minY, other.minZ);
        add(other.maxX, other.maxY, other.maxZ);
    }

    boolean isEmpty() {
        return !(maxX >= minX && maxY >= minY && maxZ >= minZ);
    }

    double sizeX() {
        return isEmpty() ? 0.0 : maxX - minX;
    }

    double sizeY() {
        return isEmpty() ? 0.0 : maxY - minY;
    }

    double sizeZ() {
        return isEmpty() ? 0.0 : maxZ - minZ;
    }

    /** 最长边：用来判断"这件时装相对玩家算大还是算小"。 */
    double maxSize() {
        return Math.max(sizeX(), Math.max(sizeY(), sizeZ()));
    }

    double centerX() {
        return (minX + maxX) * 0.5;
    }

    double centerY() {
        return (minY + maxY) * 0.5;
    }

    double centerZ() {
        return (minZ + maxZ) * 0.5;
    }

    @Override
    public String toString() {
        return String.format("x[%.2f, %.2f] y[%.2f, %.2f] z[%.2f, %.2f]",
                minX, maxX, minY, maxY, minZ, maxZ);
    }
}
