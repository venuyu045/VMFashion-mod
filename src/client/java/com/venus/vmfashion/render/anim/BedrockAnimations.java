package com.venus.vmfashion.render.anim;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code anim.json}（Bedrock 动画格式）解析器。
 *
 * <p>纯逻辑：只吃 {@link JsonObject}，不碰任何 Minecraft / 文件 API，方便单测。
 *
 * <p>防御原则与包解析一致：<b>任何坏数据都只跳过</b>，绝不让一个畸形动画文件
 * 把整件时装变成加载失败。具体表现：
 * <ul>
 *   <li>时间不是有限非负数 → 跳过该关键帧；</li>
 *   <li>值不是长度 ≥3 的数字数组 → 跳过该关键帧；</li>
 *   <li>关键帧按时间排序后去重（同时间后者覆盖前者）；</li>
 *   <li>{@code pre}/{@code post}/{@code lerp_mode} 形式只取 {@code post}；</li>
 *   <li>整个文件没有任何可用动画 → 返回空表。</li>
 * </ul>
 */
public final class BedrockAnimations {

    /** Bedrock 的旋转单位是度，GeckoLib 内部用弧度。 */
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private BedrockAnimations() {
    }

    /**
     * 解析一个 {@code anim.json}。
     *
     * @param root   文件根对象
     * @param warn   警告回调（可为 {@code null}），用于把"跳过了什么"记进日志
     * @return 动画名 → 动画；永远不为 {@code null}
     */
    public static Map<String, BedrockAnimation> parse(JsonObject root, Consumer<String> warn) {
        if (root == null || !root.has("animations") || !root.get("animations").isJsonObject()) {
            return Map.of();
        }
        Map<String, BedrockAnimation> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("animations").entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            try {
                BedrockAnimation animation = parseOne(entry.getKey(), entry.getValue().getAsJsonObject(), warn);
                if (animation != null && !animation.isEmpty()) {
                    result.put(entry.getKey(), animation);
                }
            } catch (Exception e) {
                if (warn != null) {
                    warn.accept("动画 " + entry.getKey() + " 解析失败，已跳过: " + e.getMessage());
                }
            }
        }
        return Map.copyOf(result);
    }

    private static BedrockAnimation parseOne(String name, JsonObject object, Consumer<String> warn) {
        boolean loop = !object.has("loop") || object.get("loop").isJsonNull() || getBoolean(object, "loop", true);
        double length = optDouble(object, "animation_length", -1.0);

        Map<String, BedrockAnimation.BoneTracks> bones = new LinkedHashMap<>();
        double maxTime = 0.0;
        if (object.has("bones") && object.get("bones").isJsonObject()) {
            for (Map.Entry<String, JsonElement> boneEntry : object.getAsJsonObject("bones").entrySet()) {
                if (boneEntry.getValue() == null || !boneEntry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject bone = boneEntry.getValue().getAsJsonObject();
                AnimationTrack rotation = parseTrack(bone.get("rotation"), true, warn, name + "/" + boneEntry.getKey());
                AnimationTrack position = parseTrack(bone.get("position"), false, warn, name + "/" + boneEntry.getKey());
                AnimationTrack scale = parseTrack(bone.get("scale"), false, warn, name + "/" + boneEntry.getKey());
                BedrockAnimation.BoneTracks tracks = new BedrockAnimation.BoneTracks(rotation, position, scale);
                if (tracks.isEmpty()) {
                    continue;
                }
                maxTime = Math.max(maxTime, maxOf(rotation, position, scale));
                bones.put(boneEntry.getKey().toLowerCase(Locale.ROOT), tracks);
            }
        }
        if (!(length > 0.0)) {
            length = maxTime;
        }
        if (length <= 0.0) {
            // 没有时长 = 没有可播放的内容（常量姿态由 base rotation 表达，不需要动画）
            return null;
        }
        return new BedrockAnimation(name, loop, length, Map.copyOf(bones));
    }

    private static double maxOf(AnimationTrack... tracks) {
        double max = 0.0;
        for (AnimationTrack track : tracks) {
            if (track != null) {
                max = Math.max(max, track.lastTime());
            }
        }
        return max;
    }

    /**
     * 解析一条时间轴。
     *
     * <p>JSON 里允许两种写法：
     * <pre>
     * "rotation": [0, 0, 0]                                  // 常量
     * "rotation": { "0.0": [0,0,0], "1.0": [0,0,10] }        // 关键帧表
     * "rotation": { "1.0": { "post": [...], "pre": [...] } } // 带插值模式
     * </pre>
     */
    private static AnimationTrack parseTrack(JsonElement element, boolean degrees, Consumer<String> warn, String context) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        List<double[]> keyframes = new ArrayList<>(); // [time, x, y, z]

        if (element.isJsonArray()) {
            float[] value = readVec3(element, degrees);
            if (value == null) {
                if (warn != null) {
                    warn.accept(context + " 的值不是长度 3 的数字数组，已跳过");
                }
                return null;
            }
            keyframes.add(new double[]{0.0, value[0], value[1], value[2]});
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                double time = parseTime(entry.getKey());
                if (Double.isNaN(time)) {
                    continue;
                }
                JsonElement valueElement = entry.getValue();
                if (valueElement != null && valueElement.isJsonObject()) {
                    // {pre, post, lerp_mode}：只取 post（没有 post 就退回 pre）
                    JsonObject keyframe = valueElement.getAsJsonObject();
                    valueElement = keyframe.has("post") ? keyframe.get("post") : keyframe.get("pre");
                }
                float[] value = readVec3(valueElement, degrees);
                if (value == null) {
                    if (warn != null) {
                        warn.accept(context + " 在 t=" + entry.getKey() + " 的关键帧无效，已跳过");
                    }
                    continue;
                }
                keyframes.add(new double[]{time, value[0], value[1], value[2]});
            }
        } else {
            return null;
        }

        if (keyframes.isEmpty()) {
            return null;
        }
        keyframes.sort(Comparator.comparingDouble(a -> a[0]));
        // 同一时间点只保留最后一个（JSON 里重复 key 不可能出现，但 pre/post 形式可能撞）
        int unique = 0;
        for (int i = 0; i < keyframes.size(); i++) {
            if (unique == 0 || keyframes.get(i)[0] != keyframes.get(unique - 1)[0]) {
                keyframes.set(unique++, keyframes.get(i));
            } else {
                keyframes.set(unique - 1, keyframes.get(i));
            }
        }
        double[] times = new double[unique];
        float[] xs = new float[unique];
        float[] ys = new float[unique];
        float[] zs = new float[unique];
        for (int i = 0; i < unique; i++) {
            double[] frame = keyframes.get(i);
            times[i] = frame[0];
            xs[i] = (float) frame[1];
            ys[i] = (float) frame[2];
            zs[i] = (float) frame[3];
        }
        return new AnimationTrack(times, xs, ys, zs);
    }

    /** 关键帧的时间 key 是字符串形式的秒数（{@code "0.5"}），非法值返回 NaN。 */
    private static double parseTime(String key) {
        if (key == null || key.isBlank()) {
            return Double.NaN;
        }
        try {
            double value = Double.parseDouble(key.trim());
            return Double.isFinite(value) && value >= 0.0 ? value : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static float[] readVec3(JsonElement element, boolean degrees) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < 3) {
            return null;
        }
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            try {
                float value = element.getAsJsonArray().get(i).getAsFloat();
                if (!Float.isFinite(value)) {
                    return null;
                }
                out[i] = degrees ? value * DEG_TO_RAD : value;
            } catch (Exception e) {
                return null;
            }
        }
        return out;
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double optDouble(JsonObject object, String key, double fallback) {
        try {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                return fallback;
            }
            double value = object.get(key).getAsDouble();
            return Double.isFinite(value) ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
