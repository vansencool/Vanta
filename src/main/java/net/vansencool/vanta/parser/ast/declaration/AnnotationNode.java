package net.vansencool.vanta.parser.ast.declaration;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Represents an annotation usage on a declaration or type.
 *
 * @param name       the annotation type name
 * @param attributes the annotation attributes (name to value), or null for marker annotations
 * @param line       the source line number
 */
public record AnnotationNode(@NotNull String name, @Nullable Map<String, Expression> attributes,
                             int line) implements AstNode {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
