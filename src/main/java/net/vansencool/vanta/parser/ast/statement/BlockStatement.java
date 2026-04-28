package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a block of statements enclosed in curly braces.
 *
 * @param statements the statements in this block
 * @param line       the source line number
 */
public record BlockStatement(@NotNull List<Statement> statements, int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
