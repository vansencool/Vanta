package net.vansencool.vanta.parser.ast.declaration;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a single enum constant (e.g., RED(255, 0, 0) { ... }).
 *
 * @param name        the constant name
 * @param arguments   the constructor arguments, or empty
 * @param classBody   the anonymous class body members, or null
 * @param annotations the annotations on this constant
 * @param line        the source line number
 */
public record EnumConstant(@NotNull String name, @NotNull List<Expression> arguments, @Nullable List<AstNode> classBody,
                           @NotNull List<AnnotationNode> annotations, int line) implements AstNode {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
