package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a ternary (conditional) expression (e.g., a ? b : c).
 *
 * @param condition      the condition
 * @param thenExpression the then branch
 * @param elseExpression the else branch
 * @param line           the source line number
 */
public record TernaryExpression(@NotNull Expression condition, @NotNull Expression thenExpression,
                                @NotNull Expression elseExpression, int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
