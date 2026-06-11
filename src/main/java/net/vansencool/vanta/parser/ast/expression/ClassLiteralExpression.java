package net.vansencool.vanta.parser.ast.expression;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an array class literal, e.g. {@code String[].class}.
 *
 * @param type the array type including its dimensions
 * @param line the source line number
 */
public record ClassLiteralExpression(@NotNull TypeNode type, int line) implements Expression {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
