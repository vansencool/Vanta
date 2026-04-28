package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an array access expression (e.g., arr[i]).
 *
 * @param array the array expression
 * @param index the index expression
 * @param line  the source line number
 */
public record ArrayAccessExpression(@NotNull Expression array, @NotNull Expression index,
                                    int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
