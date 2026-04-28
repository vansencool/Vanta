package net.vansencool.vanta.parser.ast.type;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a type reference in the AST.
 * Handles simple types, fully qualified types, array types, and generic types.
 *
 * @param name            the type name (e.g. "int", "String", "List")
 * @param typeArguments   generic type arguments, or null if not generic
 * @param arrayDimensions number of array dimensions (0 for non arrays)
 * @param line            the source line number
 */
public record TypeNode(@NotNull String name, @Nullable List<TypeNode> typeArguments, int arrayDimensions, int line) {

    /**
     * Creates a copy of this type with additional array dimensions.
     *
     * @param extraDimensions the number of extra dimensions to add
     * @return a new TypeNode with the additional dimensions
     */
    public @NotNull TypeNode withExtraDimensions(int extraDimensions) {
        return new TypeNode(name, typeArguments, arrayDimensions + extraDimensions, line);
    }

    /**
     * @return true if this represents the void type
     */
    public boolean isVoid() {
        return "void".equals(name) && arrayDimensions == 0;
    }

    @Override
    public @NotNull String toString() {
        StringBuilder sb = new StringBuilder(name);
        if (typeArguments != null && !typeArguments.isEmpty()) {
            sb.append('<');
            for (int i = 0; i < typeArguments.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeArguments.get(i));
            }
            sb.append('>');
        }
        sb.append("[]".repeat(Math.max(0, arrayDimensions)));
        return sb.toString();
    }
}
