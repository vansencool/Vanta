package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a do while loop statement.
 *
 * @param body      the loop body
 * @param condition the loop condition
 * @param line      the source line number
 */
public record DoWhileStatement(@NotNull Statement body, @NotNull Expression condition, int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
