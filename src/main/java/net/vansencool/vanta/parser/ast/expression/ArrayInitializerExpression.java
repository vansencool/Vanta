package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents an array initializer (e.g., {1, 2, 3}).
 *
 * @param elements the initializer elements
 * @param line     the source line number
 */
public record ArrayInitializerExpression(@NotNull List<Expression> elements, int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
