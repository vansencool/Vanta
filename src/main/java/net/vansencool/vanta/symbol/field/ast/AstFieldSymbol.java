package net.vansencool.vanta.symbol.field.ast;

import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.ref.build.RefFromAst;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.util.Set;

/**
 * AST backed field symbol. The constant value is currently never folded; a
 * later phase wires lazy cross file constant evaluation through here.
 */
public final class AstFieldSymbol implements FieldSymbol {

    private final @NotNull FieldDeclaration declaration;
    private final @NotNull FieldDeclarator declarator;
    private final @NotNull TypeSymbol owner;
    private final @NotNull TypeResolver typeResolver;
    private final @NotNull Set<String> typeVariables;
    private final @Nullable Position sourcePosition;
    private @Nullable TypeRef cachedType;

    public AstFieldSymbol(@NotNull FieldDeclaration declaration, @NotNull FieldDeclarator declarator, @NotNull TypeSymbol owner, @NotNull TypeResolver typeResolver, @NotNull Set<String> typeVariables, @Nullable Position sourcePosition) {
        this.declaration = declaration;
        this.declarator = declarator;
        this.owner = owner;
        this.typeResolver = typeResolver;
        this.typeVariables = typeVariables;
        this.sourcePosition = sourcePosition;
    }

    @Override
    public @NotNull String name() {
        return declarator.name();
    }

    @Override
    public @NotNull TypeRef type() {
        if (cachedType != null) return cachedType;
        TypeNode effective = declaration.type();
        if (declarator.extraArrayDimensions() > 0) {
            effective = effective.withExtraDimensions(declarator.extraArrayDimensions());
        }
        cachedType = RefFromAst.from(effective, typeResolver, typeVariables);
        return cachedType;
    }

    @Override
    public int access() {
        return declaration.modifiers();
    }

    @Override
    public boolean isStatic() {
        return (declaration.modifiers() & Opcodes.ACC_STATIC) != 0;
    }

    @Override
    public boolean isFinal() {
        return (declaration.modifiers() & Opcodes.ACC_FINAL) != 0;
    }

    @Override
    public @Nullable Object constantValue() {
        return null;
    }

    @Override
    public @Nullable Position sourcePosition() {
        return sourcePosition;
    }

    @Override
    public @NotNull TypeSymbol owner() {
        return owner;
    }
}
