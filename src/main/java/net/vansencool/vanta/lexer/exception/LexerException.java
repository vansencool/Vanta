package net.vansencool.vanta.lexer.exception;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when the lexer encounters an invalid character sequence or malformed literal.
 */
public class LexerException extends RuntimeException {

    private final int line;
    private final int column;

    /**
     * Creates a new lexer exception.
     *
     * @param message the error message
     * @param line    the 1 based line number where the error occurred
     * @param column  the 1 based column number where the error occurred
     */
    public LexerException(@NotNull String message, int line, int column) {
        super(message + " at " + line + ":" + column);
        this.line = line;
        this.column = column;
    }

    /**
     * @return the 1 based line number of the error
     */
    public int line() {
        return line;
    }

    /**
     * @return the 1 based column number of the error
     */
    public int column() {
        return column;
    }
}
