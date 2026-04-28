package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a yield statement (used in switch expressions).
 *
 * @param value the yield value
 * @param line  the source line number
 */
public record YieldStatement(@NotNull Expression value, int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
