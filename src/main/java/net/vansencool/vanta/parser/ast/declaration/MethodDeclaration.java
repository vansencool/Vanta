package net.vansencool.vanta.parser.ast.declaration;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.parser.ast.type.TypeParameter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a method declaration.
 *
 * @param name           the method name
 * @param modifiers      the modifier bitmask
 * @param returnType     the return type
 * @param typeParameters the generic type parameters, or null
 * @param parameters     the method parameters
 * @param body           the method body, or null for abstract/native/interface methods
 * @param defaultValue   the annotation default value, or null
 * @param annotations    the annotations
 * @param isVarargs      true if the last parameter is varargs
 * @param line           the source line number
 */
public record MethodDeclaration(@NotNull String name, int modifiers, @NotNull TypeNode returnType,
                                @Nullable List<TypeParameter> typeParameters, @NotNull List<Parameter> parameters,
                                @Nullable BlockStatement body, @Nullable Expression defaultValue,
                                @NotNull List<AnnotationNode> annotations, boolean isVarargs,
                                int line) implements AstNode {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
