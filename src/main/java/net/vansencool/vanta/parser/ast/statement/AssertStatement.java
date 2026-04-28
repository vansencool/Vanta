package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an assert statement.
 *
 * @param condition the assertion condition
 * @param message   the optional error message expression
 * @param line      the source line number
 */
public record AssertStatement(@NotNull Expression condition, @Nullable Expression message,
                              int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
