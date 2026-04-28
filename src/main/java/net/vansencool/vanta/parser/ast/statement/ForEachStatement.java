package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an enhanced for (for each) loop statement.
 *
 * @param variableType the loop variable type
 * @param variableName the loop variable name
 * @param iterable     the iterable expression
 * @param body         the loop body
 * @param modifiers    the variable modifiers (e.g. final)
 * @param line         the source line number
 */
public record ForEachStatement(@NotNull TypeNode variableType, @NotNull String variableName,
                               @NotNull Expression iterable, @NotNull Statement body, int modifiers,
                               int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
