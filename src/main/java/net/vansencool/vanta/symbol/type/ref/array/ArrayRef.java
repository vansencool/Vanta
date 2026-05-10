package net.vansencool.vanta.symbol.type.ref.array;

import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A reference to a Java array, wrapping any element {@link TypeRef}. The
 * array dimensions of the wrapped element are folded in: an array of an
 * existing array compounds the dimensions instead of nesting.
 */
public final class ArrayRef implements TypeRef {

    private final @NotNull TypeRef element;
    private final int dimensions;

    public ArrayRef(@NotNull TypeRef element, int dimensions) {
        this.element = element;
        this.dimensions = dimensions + element.arrayDimensions();
    }

    public @NotNull TypeRef element() {
        return element;
    }

    @Override
    public @NotNull String descriptor() {
        String elementDescriptor;
        if (element.arrayDimensions() > 0) {
            String full = element.descriptor();
            int leading = 0;
            while (leading < full.length() && full.charAt(leading) == '[') leading++;
            elementDescriptor = full.substring(leading);
        } else {
            elementDescriptor = element.descriptor();
        }
        return "[".repeat(dimensions) + elementDescriptor;
    }

    @Override
    public @Nullable String internalName() {
        return element.internalName();
    }

    @Override
    public int arrayDimensions() {
        return dimensions;
    }

    @Override
    public @NotNull List<TypeRef> typeArguments() {
        return element.typeArguments();
    }

    @Override
    public boolean isPrimitive() {
        return false;
    }

    @Override
    public boolean isTypeVariable() {
        return element.isTypeVariable();
    }

    @Override
    public @Nullable String typeVariableName() {
        return element.typeVariableName();
    }

    @Override
    public @Nullable TypeSymbol resolve(@NotNull TypeRegistry registry) {
        return element.resolve(registry);
    }
}
