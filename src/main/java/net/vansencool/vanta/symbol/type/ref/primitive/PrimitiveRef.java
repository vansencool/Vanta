package net.vansencool.vanta.symbol.type.ref.primitive;

import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A reference to a JVM primitive ({@code int}, {@code long}, etc.).
 */
public record PrimitiveRef(@NotNull String descriptor) implements TypeRef {

    @Override
    public @Nullable String internalName() {
        return null;
    }

    @Override
    public int arrayDimensions() {
        return 0;
    }

    @Override
    public @NotNull List<TypeRef> typeArguments() {
        return List.of();
    }

    @Override
    public boolean isPrimitive() {
        return true;
    }

    @Override
    public boolean isTypeVariable() {
        return false;
    }

    @Override
    public @Nullable String typeVariableName() {
        return null;
    }

    @Override
    public @Nullable TypeSymbol resolve(@NotNull TypeRegistry registry) {
        return null;
    }
}
