package net.vansencool.vanta.parser.ast.comment;

import org.jetbrains.annotations.NotNull;

/**
 * Source comment preserved by the lexer.
 *
 * @param kind      line, block, or doc style
 * @param text      raw text without leading or trailing markers
 * @param startLine 1 based start line
 * @param endLine   1 based end line (equal to start for line comments)
 */
public record Comment(@NotNull Kind kind, @NotNull String text, int startLine, int endLine) {

    /**
     * Distinguishes the three comment forms recognised by the lexer.
     */
    public enum Kind {
        /**
         * Single line comment introduced by {@code //}, terminated at the
         * end of line.
         */
        LINE,
        /**
         * Multi line comment delimited by {@code /*} and {@code *}{@code /},
         * not preceded by a second {@code *}.
         */
        BLOCK,
        /**
         * Javadoc style comment delimited by {@code /**} and {@code *}{@code /}.
         */
        DOC
    }
}
