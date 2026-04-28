package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.statement.Statement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a lambda expression.
 *
 * @param parameters     the lambda parameters
 * @param body           the block body (if block lambda)
 * @param expressionBody the expression body (if expression lambda)
 * @param line           the source line number
 */
public record LambdaExpression(@NotNull List<Parameter> parameters, @Nullable Statement body,
                               @Nullable Expression expressionBody, int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
