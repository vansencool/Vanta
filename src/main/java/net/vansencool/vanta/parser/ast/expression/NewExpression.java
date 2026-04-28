package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a new object creation expression (e.g., new Foo(args)).
 *
 * @param type               the type being instantiated
 * @param arguments          the constructor arguments
 * @param anonymousClassBody the anonymous class body, or null
 * @param line               the source line number
 */
public record NewExpression(@NotNull TypeNode type, @NotNull List<Expression> arguments,
                            @Nullable List<AstNode> anonymousClassBody, int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
