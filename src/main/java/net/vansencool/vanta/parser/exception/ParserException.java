package net.vansencool.vanta.parser.exception;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when the parser encounters unexpected tokens or invalid syntax.
 */
public class ParserException extends RuntimeException {

    private final int line;
    private final int column;
    private final @NotNull String rawMessage;

    /**
     * Creates a new parser exception.
     *
     * @param message the error message
     * @param line    the 1 based line number
     * @param column  the 1 based column number
     */
    public ParserException(@NotNull String message, int line, int column) {
        super(message + " at " + line + ":" + column);
        this.rawMessage = message;
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

    /**
     * @return the raw error message without location info
     */
    public @NotNull String rawMessage() {
        return rawMessage;
    }
}
