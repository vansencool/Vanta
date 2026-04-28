package net.vansencool.vanta.parser.ast.declaration;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a field declaration.
 *
 * @param type        the field type
 * @param declarators the variable declarators
 * @param modifiers   the modifier bitmask
 * @param annotations the annotations
 * @param line        the source line number
 */
public record FieldDeclaration(@NotNull TypeNode type, @NotNull List<FieldDeclarator> declarators, int modifiers,
                               @NotNull List<AnnotationNode> annotations, int line) implements AstNode {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
