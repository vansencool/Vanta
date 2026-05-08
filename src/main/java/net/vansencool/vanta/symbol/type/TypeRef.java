package net.vansencool.vanta.symbol.type;

import net.vansencool.vanta.symbol.registry.TypeRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A type reference: the form a type takes when used at a call site, field
 * declaration, or generic argument. Distinct from {@link TypeSymbol}, which
 * is the declaration. Two refs to the same {@code List} pointing at different
 * type arguments share the same underlying symbol.
 */
public interface TypeRef {

    @NotNull String descriptor();

    @Nullable String internalName();

    int arrayDimensions();

    @NotNull List<TypeRef> typeArguments();

    boolean isPrimitive();

    boolean isTypeVariable();

    @Nullable String typeVariableName();

    @Nullable TypeSymbol resolve(@NotNull TypeRegistry registry);
}
