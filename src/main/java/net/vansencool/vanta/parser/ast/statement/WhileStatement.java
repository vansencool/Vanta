package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a while loop statement.
 *
 * @param condition the loop condition
 * @param body      the loop body
 * @param line      the source line number
 */
public record WhileStatement(@NotNull Expression condition, @NotNull Statement body, int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
