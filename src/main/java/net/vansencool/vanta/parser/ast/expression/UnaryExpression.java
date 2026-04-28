package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a unary expression (e.g., -x, !flag, ++i, i--).
 *
 * @param operator the operator string
 * @param operand  the operand
 * @param isPrefix true if this is a prefix operator
 * @param line     the source line number
 */
public record UnaryExpression(@NotNull String operator, @NotNull Expression operand, boolean isPrefix,
                              int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
