package com.venus.vmfashion.ping.render;

/**
 * 屏幕边缘指示器的<b>纯几何</b>（不碰 Minecraft，可直接单测）。
 *
 * <p>要解决三件事：
 * <ol>
 *   <li><b>方向角</b>：标点在屏幕外（或在相机背后）时，从屏幕中心指向它的角度
 *       （{@link #angleOf}）。相机背后的点投影出来是镜像位置，调用方要先把方向向量取反，
 *       否则箭头会指向反方向 —— 这是"背向指示"最容易写错的地方；</li>
 *   <li><b>贴边位置</b>：把方向射线和"安全区矩形"求交，得到箭头应该画在哪个像素
 *       （{@link #rectIntersection}）。安全区是屏幕四边各内缩一段（避开热键栏/血量/聊天）；</li>
 *   <li><b>堆叠错开</b>：同一个方向上有多个标点时，箭头会完全重叠 —— 只看得见一个。
 *       这里给每个箭头分配一个 {@code slot}（0、+1、-1、+2、-2…），沿边缘切线方向错开
 *       （{@link #assignSlots}）。</li>
 * </ol>
 *
 * <p>坐标系：屏幕坐标，原点左上，<b>y 向下</b>；角度用弧度，0 = 正右（+x），顺时针为正。
 *
 * <p>合订版适配：只改包名（{@code com.venus.vmfashion.ping.render}），几何算法与独立版逐行一致
 * （有单测覆盖，不随合订调整）。
 */
public final class EdgeIndicatorMath {

    /** 屏幕上方（角度 -90°）。 */
    public static final int SIDE_TOP = 0;
    /** 屏幕右侧（角度 0°）。 */
    public static final int SIDE_RIGHT = 1;
    /** 屏幕下方（角度 90°）。 */
    public static final int SIDE_BOTTOM = 2;
    /** 屏幕左侧（角度 180°）。 */
    public static final int SIDE_LEFT = 3;

    private EdgeIndicatorMath() {
    }

    /**
     * 方向向量 → 角度（弧度）。
     *
     * @param dx 屏幕 x 方向分量（向右为正）
     * @param dy 屏幕 y 方向分量（<b>向下</b>为正）
     */
    public static float angleOf(float dx, float dy) {
        if (dx == 0f && dy == 0f) {
            return (float) Math.PI / 2f; // 正好在中心：默认指向下方，总比 NaN 好
        }
        return (float) Math.atan2(dy, dx);
    }

    /**
     * 从矩形中心沿 {@code angle} 方向射出，求与矩形边框的交点。
     *
     * <p>等价于"把无穷远的点拉回到边框上"。用 {@code t = min(halfW/|dx|, halfH/|dy|)} 求参数，
     * 比上游那套先归一化再分支的写法短，结果一致（角度 0/90/180/270 分别落在
     * 右/下/左/边的中点）。
     *
     * @return {@code {x, y}} 交点坐标；矩形退化（宽或高为 0）时返回中心点
     */
    public static float[] rectIntersection(float angle, float left, float top, float right, float bottom) {
        float centerX = (left + right) * 0.5f;
        float centerY = (top + bottom) * 0.5f;
        float halfWidth = Math.abs(right - left) * 0.5f;
        float halfHeight = Math.abs(bottom - top) * 0.5f;
        float dx = (float) Math.cos(angle);
        float dy = (float) Math.sin(angle);

        float tx = Math.abs(dx) < 1e-6f ? Float.MAX_VALUE : halfWidth / Math.abs(dx);
        float ty = Math.abs(dy) < 1e-6f ? Float.MAX_VALUE : halfHeight / Math.abs(dy);
        float t = Math.min(tx, ty);
        if (t == Float.MAX_VALUE || !Float.isFinite(t)) {
            return new float[]{centerX, centerY};
        }
        return new float[]{centerX + dx * t, centerY + dy * t};
    }

    /**
     * 方向角落在屏幕的哪条边上（用来给堆叠分组：不同边的箭头互不影响）。
     *
     * @return {@link #SIDE_TOP} / {@link #SIDE_RIGHT} / {@link #SIDE_BOTTOM} / {@link #SIDE_LEFT}
     */
    public static int sideOf(float angle) {
        // 归一化到 (-π, π]，再按 45° 分桶
        float normalized = angle % (float) (Math.PI * 2f);
        if (normalized <= -(float) Math.PI) {
            normalized += (float) (Math.PI * 2f);
        } else if (normalized > (float) Math.PI) {
            normalized -= (float) (Math.PI * 2f);
        }
        float degrees = (float) Math.toDegrees(normalized);
        if (degrees >= -45f && degrees < 45f) {
            return SIDE_RIGHT;
        }
        if (degrees >= 45f && degrees < 135f) {
            return SIDE_BOTTOM;
        }
        if (degrees >= -135f && degrees < -45f) {
            return SIDE_TOP;
        }
        return SIDE_LEFT;
    }

    /**
     * 沿边缘切线方向的偏移量（堆叠错开用）。
     *
     * <p>切线取"角度 +90°"方向：{@code (-sin, cos)}。第 0 个不动，之后 +1/-1 交替，
     * 保证视觉重心仍然指向真实方向。
     *
     * @param slot    槽位（{@link #assignSlots} 的结果）
     * @param spacing 相邻槽位的像素间距
     * @return 切线方向上的偏移像素（可正可负）
     */
    public static float slotOffset(int slot, float spacing) {
        return slot * spacing;
    }

    /** 切线方向 x 分量（沿边滑动方向，单位向量）。 */
    public static float tangentX(float angle) {
        return -(float) Math.sin(angle);
    }

    /** 切线方向 y 分量（沿边滑动方向，单位向量）。 */
    public static float tangentY(float angle) {
        return (float) Math.cos(angle);
    }

    /**
     * 给同一侧边上的箭头分配堆叠槽位。
     *
     * <p>算法：按沿边坐标排序，把间距小于 {@code minGap} 的连续项归为一簇，
     * 簇内依次分配 0、+1、-1、+2、-2…（交替向两侧摊开）。不同侧边互不干扰。
     *
     * @param coordinates 每个箭头在边上的坐标（同一侧边内可比大小即可）
     * @param sides       每个箭头所属的侧边（{@link #sideOf}）
     * @param minGap      小于这个间距就算"会重叠"
     * @return 与输入等长的槽位数组
     */
    public static int[] assignSlots(float[] coordinates, int[] sides, float minGap) {
        int count = coordinates == null ? 0 : coordinates.length;
        int[] slots = new int[count];
        if (count == 0) {
            return slots;
        }
        float gap = Math.max(0.01f, minGap);

        // 按侧边分组，组内按坐标排序（索引排序，不动调用方的数组）
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        // 插入排序：count 是"屏幕外标点数"，个位数，比装箱排序更省
        for (int i = 1; i < count; i++) {
            int current = order[i];
            int j = i - 1;
            while (j >= 0 && compare(coordinates, sides, order[j], current) > 0) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = current;
        }

        int i = 0;
        while (i < count) {
            int clusterStart = i;
            int j = i + 1;
            while (j < count
                    && sides[order[j]] == sides[order[clusterStart]]
                    && coordinates[order[j]] - coordinates[order[j - 1]] < gap) {
                j++;
            }
            for (int k = clusterStart; k < j; k++) {
                int indexInCluster = k - clusterStart;
                int magnitude = (indexInCluster + 1) / 2;
                int sign = (indexInCluster % 2 == 1) ? 1 : -1;
                slots[order[k]] = magnitude * sign;
            }
            i = j;
        }
        return slots;
    }

    private static int compare(float[] coordinates, int[] sides, int a, int b) {
        int sideCompare = Integer.compare(sides[a], sides[b]);
        if (sideCompare != 0) {
            return sideCompare;
        }
        return Float.compare(coordinates[a], coordinates[b]);
    }
}
