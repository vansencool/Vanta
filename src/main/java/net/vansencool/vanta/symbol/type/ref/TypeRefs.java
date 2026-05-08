package net.vansencool.vanta.symbol.type.ref;

import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.ref.array.ArrayRef;
import net.vansencool.vanta.symbol.type.ref.object.ObjectRef;
import net.vansencool.vanta.symbol.type.ref.primitive.PrimitiveRef;
import net.vansencool.vanta.symbol.type.ref.var.TypeVariableRef;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory for the built in {@link TypeRef} kinds. Callers build refs through
 * here so the registry sees a single canonical shape per type.
 */
public final class TypeRefs {

    private TypeRefs() {
    }

    public static @NotNull TypeRef ofPrimitive(@NotNull String descriptor) {
        return new PrimitiveRef(descriptor);
    }

    public static @NotNull TypeRef ofObject(@NotNull String internalName) {
        return new ObjectRef(internalName);
    }

    public static @NotNull TypeRef ofObject(@NotNull String internalName, @NotNull List<TypeRef> typeArguments) {
        return new ObjectRef(internalName, typeArguments);
    }

    public static @NotNull TypeRef ofArray(@NotNull TypeRef element, int dimensions) {
        return new ArrayRef(element, dimensions);
    }

    public static @NotNull TypeRef ofTypeVariable(@NotNull String name) {
        return new TypeVariableRef(name);
    }
}
