package com.venus.vmfashion.render.anim;

import java.util.Map;

/**
 * 一条 Bedrock 动画（{@code anim.json} 里 {@code animations} 下的一个条目）。
 *
 * <p>只支持时装真正用得上的子集：{@code loop} / {@code animation_length} /
 * {@code bones.<name>.{rotation,position,scale}}。
 * <b>不支持</b>：molang 表达式、{@code timeline}、音效关键帧、{@code animation_controllers} ——
 * 遇到这些字段直接忽略（解析器不报错），日志里记一次。
 *
 * @param name       动画名（{@code animation.xxx} 这种原始 key）
 * @param loop       是否循环（缺省 true）
 * @param length     动画时长（秒）；缺省取最后一个关键帧时间
 * @param bones      骨骼名（小写）→ 该骨骼的三条时间轴
 */
public record BedrockAnimation(String name, boolean loop, double length,
                               Map<String, BoneTracks> bones) {

    public boolean isEmpty() {
        return bones.isEmpty() || length <= 0.0;
    }

    /**
     * 单个骨骼的动画轨道。
     *
     * <p>三个字段都可能是 {@code null}（该骨骼没有对应通道的动画）。
     */
    public record BoneTracks(AnimationTrack rotation, AnimationTrack position, AnimationTrack scale) {

        public boolean isEmpty() {
            return rotation == null && position == null && scale == null;
        }
    }
}
