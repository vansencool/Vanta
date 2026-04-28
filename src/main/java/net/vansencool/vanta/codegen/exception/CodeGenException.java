package net.vansencool.vanta.codegen.exception;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when bytecode generation encounters an error.
 */
public class CodeGenException extends RuntimeException {

    private final int line;
    private final @NotNull String rawMessage;

    /**
     * Creates a codegen exception.
     *
     * @param message the error message
     * @param line    the source line number
     */
    public CodeGenException(@NotNull String message, int line) {
        super(message + " at line " + line);
        this.rawMessage = message;
        this.line = line;
    }

    /**
     * @return the source line number
     */
    public int line() {
        return line;
    }

    /**
     * @return the raw error message without location info
     */
    public @NotNull String rawMessage() {
        return rawMessage;
    }
}
