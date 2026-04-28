package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a cast expression (e.g., (int) x).
 *
 * @param type       the target type
 * @param expression the expression being cast
 * @param line       the source line number
 */
public record CastExpression(@NotNull TypeNode type, @NotNull Expression expression, int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
