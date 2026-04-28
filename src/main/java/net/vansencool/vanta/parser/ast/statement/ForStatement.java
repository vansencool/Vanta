package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a traditional for loop statement.
 *
 * @param initializers the initializer statements, or null
 * @param condition    the loop condition, or null (infinite loop)
 * @param updaters     the update expressions, or null
 * @param body         the loop body
 * @param line         the source line number
 */
public record ForStatement(@Nullable List<Statement> initializers, @Nullable Expression condition,
                           @Nullable List<Expression> updaters, @NotNull Statement body,
                           int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
