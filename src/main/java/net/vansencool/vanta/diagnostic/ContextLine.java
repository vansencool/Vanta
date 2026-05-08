package net.vansencool.vanta.diagnostic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record ContextLine(int line, @NotNull String source, int columnStart, int columnEnd, @Nullable String label) {
}
