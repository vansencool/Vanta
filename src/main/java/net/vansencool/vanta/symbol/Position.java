package net.vansencool.vanta.symbol;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Source location attached to a symbol so diagnostics can point back to the
 * original declaration. Symbols backed by reflection or compiled classpath
 * bytes have no source position.
 */
public record Position(@Nullable String file, int line, int column) {

    public static @NotNull Position of(@Nullable String file, int line) {
        return new Position(file, line, 0);
    }
}
