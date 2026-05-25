package net.vansencool.vanta.diagnostic.fix;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A single source edit suggested as part of a {@link Fix}. Column positions
 * are zero based byte offsets within the line.
 *
 * @param line        one based source line where the edit anchors
 * @param colStart    inclusive start column of the affected range
 * @param colEnd      exclusive end column of the affected range
 *                    ({@code colStart == colEnd} means a pure insertion at that column)
 * @param replacement text to splice into the range; empty for a delete
 * @param kind        classification of the edit, used for rendering
 * @param label       short label shown next to the underline (e.g. {@code "insert here"})
 */
public record Edit(int line, int colStart, int colEnd, @NotNull String replacement, @NotNull EditKind kind,
                   @Nullable String label) {

    public static @NotNull Edit insert(int line, int col, @NotNull String text, @Nullable String label) {
        return new Edit(line, col, col, text, EditKind.INSERT, label);
    }

    public static @NotNull Edit replace(int line, int colStart, int colEnd, @NotNull String replacement, @Nullable String label) {
        return new Edit(line, colStart, colEnd, replacement, EditKind.REPLACE, label);
    }

    public static @NotNull Edit delete(int line, int colStart, int colEnd, @Nullable String label) {
        return new Edit(line, colStart, colEnd, "", EditKind.DELETE, label);
    }

    public static @NotNull Edit insertLineAfter(int afterLine, @NotNull String newLine, @Nullable String label) {
        return new Edit(afterLine, 0, 0, newLine, EditKind.INSERT_LINE_AFTER, label);
    }

    public static @NotNull Edit insertLineBefore(int beforeLine, @NotNull String newLine, @Nullable String label) {
        return new Edit(beforeLine, 0, 0, newLine, EditKind.INSERT_LINE_BEFORE, label);
    }
}
