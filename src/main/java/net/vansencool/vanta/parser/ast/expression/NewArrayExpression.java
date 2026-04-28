package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a new array expression (e.g., new int[5], new int[]{1, 2}).
 *
 * @param elementType          the element type
 * @param dimensionExpressions the dimension size expressions
 * @param extraDimensions      extra empty dimensions
 * @param initializer          the array initializer, or null
 * @param line                 the source line number
 */
public record NewArrayExpression(@NotNull TypeNode elementType, @NotNull List<Expression> dimensionExpressions,
                                 int extraDimensions, @Nullable ArrayInitializerExpression initializer,
                                 int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
