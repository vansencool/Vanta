package net.vansencool.vanta.symbol.type.ref.object;

import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A reference to a class, interface, enum, or record. Carries optional
 * generic type arguments.
 */
public record ObjectRef(@NotNull String internalName, @NotNull List<TypeRef> typeArguments) implements TypeRef {

    public ObjectRef(@NotNull String internalName) {
        this(internalName, List.of());
    }

    public ObjectRef(@NotNull String internalName, @NotNull List<TypeRef> typeArguments) {
        this.internalName = internalName;
        this.typeArguments = List.copyOf(typeArguments);
    }

    @Override
    public @NotNull String descriptor() {
        return "L" + internalName + ";";
    }

    @Override
    public int arrayDimensions() {
        return 0;
    }

    @Override
    public boolean isPrimitive() {
        return false;
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
        return registry.lookup(internalName);
    }
}
