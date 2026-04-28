package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an if/else statement.
 *
 * @param condition  the condition expression
 * @param thenBranch the then branch
 * @param elseBranch the else branch, or null
 * @param line       the source line number
 */
public record IfStatement(@NotNull Expression condition, @NotNull Statement thenBranch, @Nullable Statement elseBranch,
                          int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
