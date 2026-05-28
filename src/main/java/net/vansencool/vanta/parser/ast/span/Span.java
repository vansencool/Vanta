package net.vansencool.vanta.parser.ast.span;

import org.jetbrains.annotations.NotNull;

/**
 * Source range of an AST node.
 *
 * @param startLine   1 based start line
 * @param startColumn 1 based start column
 * @param endLine     1 based end line (inclusive)
 * @param endColumn   1 based end column (inclusive, points at the last char)
 */
public record Span(int startLine, int startColumn, int endLine, int endColumn) {

    public static @NotNull Span single(int line, int column) {
        return new Span(line, column, line, column);
    }
}
