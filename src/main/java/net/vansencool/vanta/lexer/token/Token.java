package net.vansencool.vanta.lexer.token;

import org.jetbrains.annotations.NotNull;

/**
 * Represents a single token produced by the lexer.
 * Immutable and lightweight for fast allocation and traversal.
 *
 * @param type   the token type
 * @param value  the raw text of the token
 * @param line   the 1 based line number where this token starts
 * @param column the 1 based column number where this token starts
 */
public record Token(@NotNull TokenType type, @NotNull String value, int line, int column) {

    @Override
    public @NotNull String toString() {
        return type + "(" + value + ") at " + line + ":" + column;
    }
}
