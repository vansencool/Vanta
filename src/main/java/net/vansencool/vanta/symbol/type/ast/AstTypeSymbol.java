package net.vansencool.vanta.symbol.type.ast;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.TypeKind;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.parser.ast.type.TypeParameter;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.field.ast.AstFieldSymbol;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.method.ast.AstMethodSymbol;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AstTypeSymbol implements TypeSymbol {

    private final @NotNull ClassDeclaration declaration;
    private final @NotNull String internalName;
    private final @NotNull TypeResolver typeResolver;
    private final @NotNull TypeRegistry registry;
    private final @Nullable String sourceFile;
    private @Nullable List<MethodSymbol> cachedMethods;
    private @Nullable List<FieldSymbol> cachedFields;
    private @Nullable List<TypeSymbol> cachedNestedTypes;
    private @Nullable List<TypeParameterSymbol> cachedTypeParameters;

    public AstTypeSymbol(@NotNull ClassDeclaration declaration, @NotNull String internalName, @NotNull TypeResolver typeResolver, @NotNull TypeRegistry registry, @Nullable String sourceFile) {
        this.declaration = declaration;
        this.internalName = internalName;
        this.typeResolver = typeResolver;
        this.registry = registry;
        this.sourceFile = sourceFile;
    }

    @Override
    public @NotNull String internalName() {
        return internalName;
    }

    @Override
    public @Nullable TypeSymbol superclass() {
        TypeNode sc = declaration.superClass();
        if (sc != null) {
            String resolved = typeResolver.resolveInternalName(sc);
            return registry.lookup(resolved);
        }
        if (declaration.kind() == TypeKind.ENUM) return registry.lookup("java/lang/Enum");
        if (declaration.kind() == TypeKind.RECORD) return registry.lookup("java/lang/Record");
        if (isInterface()) return null;
        return registry.lookup("java/lang/Object");
    }

    @Override
    public @NotNull List<TypeSymbol> interfaces() {
        if (declaration.interfaces().isEmpty()) return List.of();
        List<TypeSymbol> out = new ArrayList<>(declaration.interfaces().size());
        for (TypeNode iface : declaration.interfaces()) {
            TypeSymbol sym = registry.lookup(typeResolver.resolveInternalName(iface));
            if (sym != null) out.add(sym);
        }
        return List.copyOf(out);
    }

    @Override
    public int access() {
        return declaration.modifiers();
    }

    @Override
    public boolean isInterface() {
        return declaration.kind() == TypeKind.INTERFACE || declaration.kind() == TypeKind.ANNOTATION;
    }

    @Override
    public boolean isEnum() {
        return declaration.kind() == TypeKind.ENUM;
    }

    @Override
    public boolean isRecord() {
        return declaration.kind() == TypeKind.RECORD;
    }

    @Override
    public boolean isAbstract() {
        return (declaration.modifiers() & Opcodes.ACC_ABSTRACT) != 0 || isInterface();
    }

    @Override
    public @NotNull List<MethodSymbol> methods() {
        if (cachedMethods != null) return cachedMethods;
        Set<String> typeVariables = ownTypeVariableNames();
        List<MethodSymbol> out = new ArrayList<>();
        for (AstNode m : declaration.members()) {
            if (m instanceof MethodDeclaration md) {
                Position pos = sourceFile != null ? Position.of(sourceFile, md.line()) : null;
                out.add(new AstMethodSymbol(md, this, typeResolver, typeVariables, pos));
            }
        }
        cachedMethods = List.copyOf(out);
        return cachedMethods;
    }

    @Override
    public @NotNull List<FieldSymbol> fields() {
        if (cachedFields != null) return cachedFields;
        Set<String> typeVariables = ownTypeVariableNames();
        List<FieldSymbol> out = new ArrayList<>();
        for (AstNode m : declaration.members()) {
            if (m instanceof FieldDeclaration fd) {
                Position pos = sourceFile != null ? Position.of(sourceFile, fd.line()) : null;
                for (FieldDeclarator declarator : fd.declarators()) {
                    out.add(new AstFieldSymbol(fd, declarator, this, typeResolver, typeVariables, pos));
                }
            }
        }
        cachedFields = List.copyOf(out);
        return cachedFields;
    }

    @Override
    public @NotNull List<TypeSymbol> nestedTypes() {
        if (cachedNestedTypes != null) return cachedNestedTypes;
        List<TypeSymbol> out = new ArrayList<>();
        for (AstNode m : declaration.members()) {
            if (m instanceof ClassDeclaration nested) {
                String nestedInternal = internalName + "$" + nested.name();
                TypeSymbol sym = registry.lookup(nestedInternal);
                if (sym != null) out.add(sym);
            }
        }
        cachedNestedTypes = List.copyOf(out);
        return cachedNestedTypes;
    }

    @Override
    public @NotNull List<TypeParameterSymbol> typeParameters() {
        if (cachedTypeParameters != null) return cachedTypeParameters;
        if (declaration.typeParameters() == null || declaration.typeParameters().isEmpty()) {
            cachedTypeParameters = List.of();
            return cachedTypeParameters;
        }
        Set<String> outerVariables = Set.of();
        List<TypeParameterSymbol> built = new ArrayList<>(declaration.typeParameters().size());
        for (TypeParameter tp : declaration.typeParameters()) {
            built.add(new AstTypeParameterSymbol(tp, typeResolver, outerVariables));
        }
        cachedTypeParameters = List.copyOf(built);
        return cachedTypeParameters;
    }

    @Override
    public @Nullable Position sourcePosition() {
        return sourceFile != null ? Position.of(sourceFile, declaration.line()) : null;
    }

    private @NotNull Set<String> ownTypeVariableNames() {
        if (declaration.typeParameters() == null || declaration.typeParameters().isEmpty()) return Set.of();
        Set<String> set = new HashSet<>();
        for (TypeParameter tp : declaration.typeParameters()) set.add(tp.name());
        return set;
    }
}
