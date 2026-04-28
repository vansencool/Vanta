package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a labeled statement (e.g., outer: for (...)).
 *
 * @param label     the label name
 * @param statement the labeled statement
 * @param line      the source line number
 */
public record LabeledStatement(@NotNull String label, @NotNull Statement statement, int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
