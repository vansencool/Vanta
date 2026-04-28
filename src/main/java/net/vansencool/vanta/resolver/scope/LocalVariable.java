package net.vansencool.vanta.resolver.scope;

import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a local variable in a scope, with its type and JVM local slot index.
 *
 * @param name  the variable name
 * @param type  the resolved type
 * @param index the local variable slot index
 */
public record LocalVariable(@NotNull String name, @NotNull ResolvedType type, int index) {
}
