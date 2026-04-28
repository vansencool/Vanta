package net.vansencool.vanta.codegen.expression.util.literal;

import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import org.jetbrains.annotations.NotNull;

/**
 * AST shape predicates for recognising literal-valued operands. Callers use
 * these to gate literal-specific fast paths like in-place numeric promotion
 * and null-comparison lowering.
 */
public final class LiteralPredicates {

    private LiteralPredicates() {
    }

    /**
     * @param expr expression under test
     * @return true if the expression is an integer literal node
     */
    public static boolean isIntLiteral(@NotNull Expression expr) {
        return expr instanceof LiteralExpression lit && lit.literalType() == TokenType.INT_LITERAL;
    }

    /**
     * @param expr expression under test
     * @return true if the expression is the {@code null} literal
     */
    public static boolean isNullLiteral(@NotNull Expression expr) {
        return expr instanceof LiteralExpression lit && lit.literalType() == TokenType.NULL;
    }

    /**
     * @param expr expression under test
     * @return true if the expression is the integer literal {@code 0}
     */
    public static boolean isIntZero(@NotNull Expression expr) {
        return expr instanceof LiteralExpression lit && lit.literalType() == TokenType.INT_LITERAL && "0".equals(lit.value());
    }
}
