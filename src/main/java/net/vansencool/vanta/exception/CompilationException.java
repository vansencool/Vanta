package net.vansencool.vanta.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when compilation fails. Wraps parser, codegen, and lexer errors with source context
 * to produce human readable error messages including the offending source line.
 */
public class CompilationException extends RuntimeException {

    private final int line;
    private final int column;

    /**
     * Creates a compilation exception with source context.
     *
     * @param message the formatted error message with source context
     * @param line    the 1 based line number
     * @param column  the 1 based column number
     * @param cause   the original exception
     */
    public CompilationException(@NotNull String message, int line, int column, @NotNull Throwable cause) {
        super(message, cause);
        this.line = line;
        this.column = column;
    }

    /**
     * Formats an error message with the offending source line and a pointer to the error location.
     *
     * @param source     the full source code
     * @param sourceFile the source file name, or null
     * @param message    the error message
     * @param line       the 1 based line number
     * @param column     the 1 based column number
     * @param phase      the compilation phase
     * @return the formatted error string
     */
    public static @NotNull String formatError(@NotNull String source, @Nullable String sourceFile, @NotNull String message, int line, int column, @NotNull String phase) {
        StringBuilder sb = new StringBuilder();
        String location = sourceFile != null ? sourceFile + ":" + line + ":" + column : "line " + line + ":" + column;
        sb.append(phase).append(" error at ").append(location).append('\n');
        sb.append("  ").append(message).append('\n');

        String[] lines = source.split("\n", -1);
        if (line >= 1 && line <= lines.length) {
            String sourceLine = lines[line - 1];
            sb.append('\n');
            String lineNum = String.valueOf(line);
            sb.append("  ").append(lineNum).append(" | ").append(sourceLine).append('\n');
            if (column >= 1) {
                sb.append("  ").append(" ".repeat(lineNum.length())).append(" | ").append(" ".repeat(column - 1)).append('^').append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * @return the 1 based line number
     */
    public int line() {
        return line;
    }

    /**
     * @return the 1 based column number
     */
    public int column() {
        return column;
    }
}
