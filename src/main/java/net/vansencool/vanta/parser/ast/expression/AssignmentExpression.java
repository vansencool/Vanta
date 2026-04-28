package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an assignment expression (e.g., x = 5, x += 3).
 *
 * @param target   the assignment target
 * @param operator the assignment operator (=, +=, etc.)
 * @param value    the value being assigned
 * @param line     the source line number
 */
public record AssignmentExpression(@NotNull Expression target, @NotNull String operator, @NotNull Expression value,
                                   int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
