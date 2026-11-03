package net.minecraft.client.gui.narration;

import net.minecraft.client.gui.components.TabOrderedElement;

import java.util.Collection;
import java.util.List;

/** UI 预览器用的 {@code NarratableEntry} 精简替身。 */
public interface NarratableEntry extends NarrationSupplier, TabOrderedElement {

    NarrationPriority narrationPriority();

    default boolean isActive() {
        return true;
    }

    default Collection<? extends NarratableEntry> getNarratables() {
        return List.of(this);
    }

    /** 朗读优先级（预览器不朗读，仅为满足接口）。 */
    enum NarrationPriority {
        NONE,
        HOVERED,
        FOCUSED
    }
}
