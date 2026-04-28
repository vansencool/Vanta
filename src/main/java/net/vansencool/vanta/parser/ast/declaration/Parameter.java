package net.vansencool.vanta.parser.ast.declaration;

import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a method or constructor parameter.
 *
 * @param type        the parameter type
 * @param name        the parameter name
 * @param modifiers   the modifier bitmask (e.g. final)
 * @param annotations the annotations
 * @param isVarargs   true if this is a varargs parameter
 */
public record Parameter(@NotNull TypeNode type, @NotNull String name, int modifiers,
                        @NotNull List<AnnotationNode> annotations, boolean isVarargs) {

}
