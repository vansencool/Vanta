package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a switch statement.
 *
 * @param selector the selector expression
 * @param cases    the switch cases
 * @param line     the source line number
 */
public record SwitchStatement(@NotNull Expression selector, @NotNull List<SwitchCase> cases,
                              int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
