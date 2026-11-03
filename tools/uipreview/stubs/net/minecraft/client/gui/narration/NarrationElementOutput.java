package net.minecraft.client.gui.narration;

/**
 * 朗读输出 —— UI 预览器里是空实现。
 *
 * <p>游戏内它把控件状态喂给 Narrator（无障碍朗读）；预览不需要朗读，
 * 但接口必须存在，否则控件实现不了 {@link NarratableEntry}。
 */
public interface NarrationElementOutput {
    NarrationElementOutput NONE = new NarrationElementOutput() {
    };

    default void add(NarrationThunk thunk) {
    }

    /** 朗读片段（预览器不关心内容）。 */
    interface NarrationThunk {
    }
}
