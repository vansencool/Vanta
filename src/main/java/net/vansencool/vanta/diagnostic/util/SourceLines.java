package net.vansencool.vanta.diagnostic.util;

import org.jetbrains.annotations.NotNull;

/**
 * Extracts a single source line for diagnostic rendering.
 */
public final class SourceLines {

    private SourceLines() {
    }

    /**
     * Lines are 1 based, the trailing newline is excluded, and out of range
     * indices return an empty string.
     */
    public static @NotNull String lineAt(@NotNull String source, int line) {
        if (line < 1) return "";
        int current = 1;
        for (int i = 0; i < source.length(); i++) {
            if (current == line) {
                int end = source.indexOf('\n', i);
                if (end < 0) end = source.length();
                return source.substring(i, end);
            }
            if (source.charAt(i) == '\n') current++;
        }
        return "";
    }

    /**
     * {@code char[]} variant for callers that already hold the source as a
     * char array.
     */
    public static @NotNull String lineAt(char @NotNull [] source, int line) {
        if (line < 1) return "";
        int current = 1;
        for (int i = 0; i < source.length; i++) {
            if (current == line) {
                int end = i;
                while (end < source.length && source[end] != '\n') end++;
                return new String(source, i, end - i);
            }
            if (source[i] == '\n') current++;
        }
        return "";
    }
}
