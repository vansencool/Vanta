package net.vansencool.vanta.exception;

import net.vansencool.vanta.diagnostic.Diagnostic;
import org.jetbrains.annotations.NotNull;

public class CompilationException extends RuntimeException {

    private final @NotNull Diagnostic diagnostic;

    public CompilationException(@NotNull Diagnostic diagnostic) {
        super(diagnostic.format(), null, false, false);
        this.diagnostic = diagnostic;
    }

    public @NotNull Diagnostic diagnostic() {
        return diagnostic;
    }

    public int line() {
        return diagnostic.line();
    }

    public int column() {
        return diagnostic.columnStart() + 1;
    }
}
