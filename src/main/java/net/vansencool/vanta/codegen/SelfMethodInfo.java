package net.vansencool.vanta.codegen;

import org.jetbrains.annotations.NotNull;

/**
 * Holds metadata about a method in the class being compiled,
 * used to resolve self-calls without reflection.
 */
public record SelfMethodInfo(@NotNull String owner, @NotNull String name, @NotNull String descriptor,
                             boolean isStatic) {
}
