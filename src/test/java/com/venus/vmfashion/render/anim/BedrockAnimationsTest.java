package com.venus.vmfashion.render.anim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code anim.json} 解析（Bedrock 格式子集）。
 *
 * <p>重点覆盖"坏数据只跳过、不抛异常"，以及度数→弧度、关键帧排序、{@code pre/post} 取值。
 */
class BedrockAnimationsTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void parsesSimpleLoopingAnimation() {
        Map<String, BedrockAnimation> animations = BedrockAnimations.parse(json("""
                {
                  "format_version": "1.8.0",
                  "animations": {
                    "animation.cape_wave": {
                      "loop": true,
                      "animation_length": 2.0,
                      "bones": {
                        "cape": {
                          "rotation": {"0.0": [0, 0, 0], "1.0": [0, 0, 90], "2.0": [0, 0, 0]}
                        }
                      }
                    }
                  }
                }
                """), null);

        assertEquals(1, animations.size());
        BedrockAnimation animation = animations.get("animation.cape_wave");
        assertNotNull(animation);
        assertTrue(animation.loop());
        assertEquals(2.0, animation.length(), 1.0e-9);
        BedrockAnimation.BoneTracks tracks = animation.bones().get("cape");
        assertNotNull(tracks);
        assertNotNull(tracks.rotation());
        assertEquals(3, tracks.rotation().size());

        // 度数 → 弧度：t=1.0 时 Z 轴 90° = π/2
        float[] out = new float[3];
        tracks.rotation().sample(1.0, out);
        assertEquals((float) (Math.PI / 2.0), out[2], 1.0e-5F);
    }

    @Test
    void boneNamesAreLowercased() {
        Map<String, BedrockAnimation> animations = BedrockAnimations.parse(json("""
                {"animations": {"a": {"animation_length": 1.0,
                  "bones": {"Cape": {"rotation": {"0.0": [1, 2, 3]}}}}}}
                """), null);
        assertNotNull(animations.get("a").bones().get("cape"));
        assertNull(animations.get("a").bones().get("Cape"));
    }

    @Test
    void lengthFallsBackToLastKeyframe() {
        Map<String, BedrockAnimation> animations = BedrockAnimations.parse(json("""
                {"animations": {"a": {"bones": {"b": {"position": {"0.0": [0,0,0], "1.5": [0,1,0]}}}}}}
                """), null);
        assertEquals(1.5, animations.get("a").length(), 1.0e-9);
    }

    @Test
    void prePostFormTakesPost() {
        Map<String, BedrockAnimation> animations = BedrockAnimations.parse(json("""
                {"animations": {"a": {"animation_length": 1.0, "bones": {"b": {"rotation": {
                  "0.0": {"pre": [0, 0, 0], "post": [0, 0, 45]},
                  "1.0": {"pre": [0, 0, 45], "post": [0, 0, 45]}
                }}}}}}
                """), null);
        float[] out = new float[3];
        animations.get("a").bones().get("b").rotation().sample(0.0, out);
        assertEquals((float) (Math.PI / 4.0), out[2], 1.0e-5F);
    }

    @Test
    void constantArrayValueBecomesSingleKeyframe() {
        Map<String, BedrockAnimation> animations = BedrockAnimations.parse(json("""
                {"animations": {"a": {"animation_length": 1.0, "bones": {"b": {"scale": [2, 2, 2]}}}}}
                """), null);
        AnimationTrack scale = animations.get("a").bones().get("b").scale();
        assertNotNull(scale);
        assertEquals(1, scale.size());
        float[] out = new float[3];
        scale.sample(0.7, out);
        assertEquals(2.0F, out[0]);
    }

    @Test
    void keyframesAreSortedByTime() {
        Map<String, BedrockAnimation> animations = BedrockAnimations.parse(json("""
                {"animations": {"a": {"animation_length": 2.0, "bones": {"b": {"rotation": {
                  "1.0": [0, 0, 10], "0.0": [0, 0, 0]
                }}}}}}
                """), null);
        AnimationTrack rotation = animations.get("a").bones().get("b").rotation();
        float[] out = new float[3];
        rotation.sample(0.5, out);
        assertEquals((float) Math.toRadians(5.0), out[2], 1.0e-5F);
    }

    @Test
    void badKeyframesAreSkippedNotFatal() {
        List<String> warnings = new ArrayList<>();
        Map<String, BedrockAnimation> animations = BedrockAnimations.parse(json("""
                {"animations": {"a": {"animation_length": 1.0, "bones": {
                  "b": {"rotation": {
                    "abc": [1, 2, 3],
                    "-1.0": [1, 2, 3],
                    "0.5": [1, 2],
                    "0.6": "nope",
                    "0.7": [0, 0, 90]
                  }},
                  "c": {"rotation": 42}
                }}}}
                """), warnings::add);

        BedrockAnimation animation = animations.get("a");
        assertNotNull(animation);
        AnimationTrack rotation = animation.bones().get("b").rotation();
        assertNotNull(rotation);
        // 只有 0.7 那条是合法的
        assertEquals(1, rotation.size());
        assertNull(animation.bones().get("c"));
        assertFalse(warnings.isEmpty(), "非法关键帧应该产生警告");
    }

    @Test
    void missingOrBrokenRootReturnsEmpty() {
        assertTrue(BedrockAnimations.parse(null, null).isEmpty());
        assertTrue(BedrockAnimations.parse(json("{}"), null).isEmpty());
        assertTrue(BedrockAnimations.parse(json("{\"animations\": 3}"), null).isEmpty());
        assertTrue(BedrockAnimations.parse(json("{\"animations\": {\"a\": \"x\"}}"), null).isEmpty());
    }

    @Test
    void animationWithoutUsableContentIsDropped() {
        // 没有 bones / bones 里没有可用通道 / 没有时长 → 都不该出现在结果里
        assertTrue(BedrockAnimations.parse(json("""
                {"animations": {"a": {"animation_length": 1.0}}}
                """), null).isEmpty());
        assertTrue(BedrockAnimations.parse(json("""
                {"animations": {"a": {"bones": {"b": {}}}}}
                """), null).isEmpty());
    }

    @Test
    void loopDefaultsToTrueAndCanBeDisabled() {
        Map<String, BedrockAnimation> animations = BedrockAnimations.parse(json("""
                {"animations": {
                  "default": {"animation_length": 1.0, "bones": {"b": {"rotation": {"0.0": [0,0,0]}}}},
                  "once": {"loop": false, "animation_length": 1.0,
                           "bones": {"b": {"rotation": {"0.0": [0,0,0]}}}}
                }}
                """), null);
        assertTrue(animations.get("default").loop());
        assertFalse(animations.get("once").loop());
    }
}
