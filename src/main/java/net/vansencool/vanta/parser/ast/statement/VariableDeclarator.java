package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a single variable declarator within a local variable declaration.
 *
 * @param name                 the variable name
 * @param extraArrayDimensions extra array dimensions after the name
 * @param initializer          the initializer expression, or null
 */
public record VariableDeclarator(@NotNull String name, int extraArrayDimensions, @Nullable Expression initializer) {

}
