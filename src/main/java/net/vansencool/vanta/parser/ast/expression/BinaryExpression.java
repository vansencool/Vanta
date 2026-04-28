package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a binary expression (e.g., a + b, x && y).
 *
 * @param left     the left operand
 * @param operator the operator string
 * @param right    the right operand
 * @param line     the source line number
 */
public record BinaryExpression(@NotNull Expression left, @NotNull String operator, @NotNull Expression right,
                               int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
