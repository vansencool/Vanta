package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a method call expression (e.g., obj.method(args), method(args)).
 *
 * @param target        the target object, or null for unqualified calls
 * @param methodName    the method name
 * @param arguments     the arguments
 * @param typeArguments the explicit type arguments, or null
 * @param line          the source line number
 */
public record MethodCallExpression(@Nullable Expression target, @NotNull String methodName,
                                   @NotNull List<Expression> arguments, @Nullable List<TypeNode> typeArguments,
                                   int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
