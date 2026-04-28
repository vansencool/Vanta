package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.statement.SwitchCase;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a switch expression (Java 14+).
 *
 * @param selector the selector expression
 * @param cases    the switch cases
 * @param line     the source line number
 */
public record SwitchExpression(@NotNull Expression selector, @NotNull List<SwitchCase> cases,
                               int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
