package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a statement that consists of a single expression (e.g., method call, assignment).
 *
 * @param expression the expression
 * @param line       the source line number
 */
public record ExpressionStatement(@NotNull Expression expression, int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
