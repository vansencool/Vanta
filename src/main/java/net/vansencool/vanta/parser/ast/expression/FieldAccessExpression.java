package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a field access expression (e.g., obj.field).
 *
 * @param target    the target object
 * @param fieldName the field name
 * @param line      the source line number
 */
public record FieldAccessExpression(@NotNull Expression target, @NotNull String fieldName,
                                    int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
