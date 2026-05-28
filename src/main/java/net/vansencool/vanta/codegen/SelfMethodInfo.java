package net.vansencool.vanta.codegen;

import org.jetbrains.annotations.NotNull;

/**
 * Holds metadata about a method in the class being compiled,
 * used to resolve self-calls without reflection.
 */
public record SelfMethodInfo(@NotNull String owner, @NotNull String name, @NotNull String descriptor,
                             boolean isStatic, boolean isVarargs) {

    /**
     * Convenience for callers that do not track varargs (treated as non-varargs).
     */
    public SelfMethodInfo(@NotNull String owner, @NotNull String name, @NotNull String descriptor, boolean isStatic) {
        this(owner, name, descriptor, isStatic, false);
    }
}
