package net.vansencool.vanta.parser.ast.declaration;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.AstVisitor;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.parser.ast.type.TypeParameter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a type declaration: class, interface, enum, or record.
 *
 * @param name             the type name
 * @param modifiers        the access and other modifiers as a bitmask
 * @param typeParameters   the generic type parameters, or null
 * @param superClass       the superclass, or null if extending Object
 * @param interfaces       the implemented interfaces (or extended interfaces for interface declarations)
 * @param members          the type members (methods, fields, nested types)
 * @param annotations      the annotations on this type
 * @param kind             the kind of type declaration
 * @param enumConstants    the enum constants, or null if not an enum
 * @param recordComponents the record components, or null if not a record
 * @param line             the source line number
 */
public record ClassDeclaration(@NotNull String name, int modifiers, @Nullable List<TypeParameter> typeParameters,
                               @Nullable TypeNode superClass, @NotNull List<TypeNode> interfaces,
                               @NotNull List<AstNode> members, @NotNull List<AnnotationNode> annotations,
                               @NotNull TypeKind kind, @Nullable List<EnumConstant> enumConstants,
                               @Nullable List<RecordComponent> recordComponents, int line) implements AstNode {

    @Override
    public void accept(@NotNull AstVisitor visitor) {
        visitor.visit(this);
    }
}
