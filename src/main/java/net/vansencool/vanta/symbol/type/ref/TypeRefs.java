package net.vansencool.vanta.symbol.type.ref;

import net.vansencool.vanta.resolver.type.ResolvedType;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.ref.array.ArrayRef;
import net.vansencool.vanta.symbol.type.ref.object.ObjectRef;
import net.vansencool.vanta.symbol.type.ref.primitive.PrimitiveRef;
import net.vansencool.vanta.symbol.type.ref.var.TypeVariableRef;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
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

    public static @NotNull TypeRef ofTypeVariable(@NotNull String name, @NotNull String erasureInternalName) {
        return new TypeVariableRef(name, erasureInternalName);
    }

    /**
     * Lowers {@code ref} to a {@link ResolvedType}, carrying its type arguments through recursively.
     */
    public static @NotNull ResolvedType toResolved(@NotNull TypeRef ref) {
        if (ref.isPrimitive() || ref.descriptor().startsWith("[")) return ResolvedType.fromDescriptor(ref.descriptor());
        String internal = ref.internalName();
        if (internal == null) return ResolvedType.ofObject("java/lang/Object");
        if (ref.typeArguments().isEmpty()) return ResolvedType.ofObject(internal);
        List<ResolvedType> args = new ArrayList<>(ref.typeArguments().size());
        for (TypeRef ta : ref.typeArguments()) args.add(toResolved(ta));
        return ResolvedType.ofObject(internal).withTypeArguments(args);
    }
}
