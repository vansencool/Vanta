package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an instanceof expression, including pattern matching (Java 16+).
 *
 * @param expression      the expression being tested
 * @param type            the type to test against
 * @param patternVariable the pattern variable name for pattern matching, or null
 * @param line            the source line number
 */
public record InstanceofExpression(@NotNull Expression expression, @NotNull TypeNode type,
                                   @Nullable String patternVariable, int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
