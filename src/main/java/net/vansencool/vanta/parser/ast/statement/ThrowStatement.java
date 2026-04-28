package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a throw statement.
 *
 * @param expression the expression to throw
 * @param line       the source line number
 */
public record ThrowStatement(@NotNull Expression expression, int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
