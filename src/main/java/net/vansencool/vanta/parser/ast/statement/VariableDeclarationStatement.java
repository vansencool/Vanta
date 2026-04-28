package net.vansencool.vanta.parser.ast.statement;

import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.declaration.AnnotationNode;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a local variable declaration statement.
 *
 * @param type        the variable type
 * @param declarators the variable declarators
 * @param modifiers   the modifier bitmask
 * @param annotations the annotations
 * @param line        the source line number
 */
public record VariableDeclarationStatement(@NotNull TypeNode type, @NotNull List<VariableDeclarator> declarators,
                                           int modifiers, @NotNull List<AnnotationNode> annotations,
                                           int line) implements Statement {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
