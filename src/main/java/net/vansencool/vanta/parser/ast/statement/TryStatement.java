package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a try/catch/finally statement, optionally with resources (try-with-resources).
 *
 * @param resources    the resource declarations (empty if not try-with-resources)
 * @param tryBlock     the try block
 * @param catchClauses the catch clauses
 * @param finallyBlock the finally block, or null
 * @param line         the source line number
 */
public record TryStatement(@NotNull List<ResourceDeclaration> resources, @NotNull BlockStatement tryBlock,
                           @NotNull List<CatchClause> catchClauses, @Nullable BlockStatement finallyBlock,
                           int line) implements Statement {

    public TryStatement(@NotNull BlockStatement tryBlock, @NotNull List<CatchClause> catchClauses, @Nullable BlockStatement finallyBlock, int line) {
        this(List.of(), tryBlock, catchClauses, finallyBlock, line);
    }

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
