package net.vansencool.vanta.parser.ast.type;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a type parameter declaration (e.g., T, T extends Comparable&lt;T&gt;).
 *
 * @param name   the parameter name
 * @param bounds the upper bounds, or null if unbounded
 * @param line   the source line number
 */
public record TypeParameter(@NotNull String name, @Nullable List<TypeNode> bounds, int line) {

}
