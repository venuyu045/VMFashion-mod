package net.minecraft.client.gui.components.events;

/** UI 预览器用的 {@code AbstractContainerEventHandler} 精简替身。 */
public abstract class AbstractContainerEventHandler implements ContainerEventHandler {

    private boolean dragging;
    private GuiEventListener focused;

    @Override
    public final boolean isDragging() {
        return dragging;
    }

    @Override
    public final void setDragging(boolean dragging) {
        this.dragging = dragging;
    }

    @Override
    public GuiEventListener getFocused() {
        return focused;
    }

    @Override
    public void setFocused(GuiEventListener focused) {
        this.focused = focused;
    }

    @Override
    public boolean isFocused() {
        return focused != null;
    }
}
