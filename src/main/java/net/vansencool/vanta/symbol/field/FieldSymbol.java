package net.vansencool.vanta.symbol.field;

import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface FieldSymbol {

    @NotNull String name();

    @NotNull TypeRef type();

    int access();

    boolean isStatic();

    boolean isFinal();

    /**
     * The compile time constant value when the field is {@code static final}
     * and its initializer folds into a primitive or {@link String}, otherwise
     * {@code null}. Backing implementations may compute this lazily.
     */
    @Nullable Object constantValue();

    @Nullable Position sourcePosition();

    @NotNull TypeSymbol owner();
}
