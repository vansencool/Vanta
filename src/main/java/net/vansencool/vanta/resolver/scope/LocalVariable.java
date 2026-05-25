package net.vansencool.vanta.resolver.scope;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a local variable in a scope, with its type and JVM local slot index.
 *
 * @param name        the variable name
 * @param type        the resolved type
 * @param index       the local variable slot index
 * @param isFinal     true when the local was declared {@code final}
 * @param declaration AST node where this local was declared, or null for synthetic locals
 */
public record LocalVariable(@NotNull String name, @NotNull ResolvedType type, int index, boolean isFinal,
                            @Nullable AstNode declaration) {

    public LocalVariable(@NotNull String name, @NotNull ResolvedType type, int index) {
        this(name, type, index, false, null);
    }

    public LocalVariable(@NotNull String name, @NotNull ResolvedType type, int index, boolean isFinal) {
        this(name, type, index, isFinal, null);
    }
}
