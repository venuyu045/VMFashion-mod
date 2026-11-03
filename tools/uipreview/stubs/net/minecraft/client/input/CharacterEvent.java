package net.minecraft.client.input;

/** 字符输入事件（用于搜索框打字）。 */
public record CharacterEvent(int codepoint) {
    public String codepointAsString() {
        return new String(Character.toChars(codepoint));
    }
}
