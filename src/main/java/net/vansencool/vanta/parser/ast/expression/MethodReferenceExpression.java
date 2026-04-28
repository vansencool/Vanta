package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a method reference expression, e.g. {@code ClassName::methodName} or {@code expr::methodName}.
 *
 * @param target     the target expression (class name or instance), never null
 * @param methodName the method name, or "new" for constructor references
 * @param line       the source line number
 */
public record MethodReferenceExpression(@NotNull Expression target, @NotNull String methodName,
                                        int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
