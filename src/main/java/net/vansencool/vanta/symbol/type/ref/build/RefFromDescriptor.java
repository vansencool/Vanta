package net.vansencool.vanta.symbol.type.ref.build;

import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.ref.TypeRefs;
import org.jetbrains.annotations.NotNull;

/**
 * Builds {@link TypeRef} instances from raw JVM descriptors. Used by the ASM
 * backed symbol path where only erased descriptors are available; generic
 * arguments stay empty.
 */
public final class RefFromDescriptor {

    private RefFromDescriptor() {
    }

    public static @NotNull TypeRef from(@NotNull String descriptor) {
        int dimensions = 0;
        while (dimensions < descriptor.length() && descriptor.charAt(dimensions) == '[') dimensions++;
        String element = descriptor.substring(dimensions);
        TypeRef base = baseFromElement(element);
        return dimensions == 0 ? base : TypeRefs.ofArray(base, dimensions);
    }

    private static @NotNull TypeRef baseFromElement(@NotNull String element) {
        if (element.length() == 1) return TypeRefs.ofPrimitive(element);
        if (element.startsWith("L") && element.endsWith(";")) {
            return TypeRefs.ofObject(element.substring(1, element.length() - 1));
        }
        return TypeRefs.ofObject("java/lang/Object");
    }
}
