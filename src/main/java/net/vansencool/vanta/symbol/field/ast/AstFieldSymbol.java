package net.vansencool.vanta.symbol.field.ast;

import net.vansencool.vanta.codegen.classes.constant.ConstantFolder;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.ast.AstTypeSymbol;
import net.vansencool.vanta.symbol.type.ref.build.RefFromAst;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.Set;

/**
 * AST backed field symbol. Constant value folds lazily on demand using a
 * fresh {@link ConstantFolder} primed against the owning class declaration.
 */
public final class AstFieldSymbol implements FieldSymbol {

    private static final Object NO_CONSTANT = new Object();

    private final @NotNull FieldDeclaration declaration;
    private final @NotNull FieldDeclarator declarator;
    private final @NotNull TypeSymbol owner;
    private final @NotNull TypeResolver typeResolver;
    private final @NotNull Set<String> typeVariables;
    private final @NotNull Map<String, String> typeVariableErasures;
    private final @Nullable Position sourcePosition;
    private @Nullable TypeRef cachedType;
    private @Nullable Object cachedConstantValue;

    public AstFieldSymbol(@NotNull FieldDeclaration declaration, @NotNull FieldDeclarator declarator, @NotNull TypeSymbol owner, @NotNull TypeResolver typeResolver, @NotNull Set<String> typeVariables, @Nullable Position sourcePosition) {
        this(declaration, declarator, owner, typeResolver, typeVariables, Map.of(), sourcePosition);
    }

    public AstFieldSymbol(@NotNull FieldDeclaration declaration, @NotNull FieldDeclarator declarator, @NotNull TypeSymbol owner, @NotNull TypeResolver typeResolver, @NotNull Set<String> typeVariables, @NotNull Map<String, String> typeVariableErasures, @Nullable Position sourcePosition) {
        this.declaration = declaration;
        this.declarator = declarator;
        this.owner = owner;
        this.typeResolver = typeResolver;
        this.typeVariables = typeVariables;
        this.typeVariableErasures = typeVariableErasures;
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
        cachedType = RefFromAst.from(effective, typeResolver, typeVariables, typeVariableErasures);
        return cachedType;
    }

    @Override
    public int access() {
        return declaration.modifiers();
    }

    @Override
    public boolean isStatic() {
        if ((declaration.modifiers() & Opcodes.ACC_STATIC) != 0) return true;
        return owner.isInterface();
    }

    @Override
    public boolean isFinal() {
        if ((declaration.modifiers() & Opcodes.ACC_FINAL) != 0) return true;
        return owner.isInterface();
    }

    @Override
    public @Nullable Object constantValue() {
        if (cachedConstantValue == NO_CONSTANT) return null;
        if (cachedConstantValue != null) return cachedConstantValue;
        Object resolved = resolveConstantValue();
        cachedConstantValue = resolved != null ? resolved : NO_CONSTANT;
        return resolved;
    }

    private @Nullable Object resolveConstantValue() {
        if (!(owner instanceof AstTypeSymbol astOwner)) return null;
        if (declarator.initializer() == null) return null;
        ConstantFolder folder = new ConstantFolder(typeResolver, typeResolver.classpathManager());
        folder.currentClassDecl(astOwner.declaration());
        folder.currentOwnerKind(astOwner.declaration().kind());
        return folder.resolveConstantValue(declaration, declarator);
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
