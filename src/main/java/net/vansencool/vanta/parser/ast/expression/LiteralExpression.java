package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.AstVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a literal value (integer, long, float, double, string, char, boolean, null).
 *
 * @param literalType the type of literal
 * @param value       the raw string value
 * @param line        the source line number
 */
public record LiteralExpression(@NotNull TokenType literalType, @NotNull String value, int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
