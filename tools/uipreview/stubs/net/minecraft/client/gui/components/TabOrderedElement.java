package net.minecraft.client.gui.components;

/** UI 预览器用的 {@code TabOrderedElement} 精简替身。 */
public interface TabOrderedElement {
    default int getTabOrderGroup() {
        return 0;
    }
}
