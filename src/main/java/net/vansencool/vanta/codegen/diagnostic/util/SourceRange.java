package net.vansencool.vanta.codegen.diagnostic.util;

import net.vansencool.vanta.parser.ast.span.Span;
import org.jetbrains.annotations.NotNull;

/**
 * 0 based half open column range derived from a {@link Span}.
 *
 * @param startCol inclusive 0 based start column on the start line
 * @param endCol   exclusive 0 based end column on the start line
 */
public record SourceRange(int startCol, int endCol) {

    /**
     * Single line span trimmed to one column when start equals end.
     *
     * @param span        source span
     * @param lineLength  length of the source line containing the span start
     */
    public static @NotNull SourceRange ofSingleLine(@NotNull Span span, int lineLength) {
        int start = Math.max(0, span.startColumn() - 1);
        int end = span.endLine() == span.startLine() ? span.endColumn() : lineLength;
        if (end <= start) end = Math.min(lineLength, start + 1);
        return new SourceRange(start, end);
    }
}
