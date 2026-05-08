package net.vansencool.vanta.symbol.type.ref.var;

import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A reference to a generic type parameter ({@code T}, {@code E}, etc.). The
 * descriptor erases to {@code java/lang/Object} until generic substitution
 * resolves it against a concrete enclosing type.
 */
public final class TypeVariableRef implements TypeRef {

    private final @NotNull String name;

    public TypeVariableRef(@NotNull String name) {
        this.name = name;
    }

    @Override
    public @NotNull String descriptor() {
        return "Ljava/lang/Object;";
    }

    @Override
    public @NotNull String internalName() {
        return "java/lang/Object";
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
        return false;
    }

    @Override
    public boolean isTypeVariable() {
        return true;
    }

    @Override
    public @NotNull String typeVariableName() {
        return name;
    }

    @Override
    public @Nullable TypeSymbol resolve(@NotNull TypeRegistry registry) {
        return registry.lookup("java/lang/Object");
    }
}
