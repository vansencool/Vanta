package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a synchronized statement.
 *
 * @param lock the lock expression
 * @param body the synchronized block body
 * @param line the source line number
 */
public record SynchronizedStatement(@NotNull Expression lock, @NotNull BlockStatement body,
                                    int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
