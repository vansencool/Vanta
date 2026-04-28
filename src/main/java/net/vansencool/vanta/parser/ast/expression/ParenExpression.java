package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a parenthesized expression (e.g., (a + b)).
 *
 * @param expression the inner expression
 * @param line       the source line number
 */
public record ParenExpression(@NotNull Expression expression, int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
