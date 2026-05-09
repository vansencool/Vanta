package net.vansencool.vanta.symbol.type.ast;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.AnnotationNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.EnumConstant;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.RecordComponent;
import net.vansencool.vanta.parser.ast.declaration.TypeKind;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.parser.ast.type.TypeParameter;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.annotation.AnnotationInstance;
import net.vansencool.vanta.symbol.annotation.ast.AstAnnotationInstance;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.field.ast.AstFieldSymbol;
import net.vansencool.vanta.symbol.field.synth.SyntheticFieldSymbol;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.method.ast.AstConstructorSymbol;
import net.vansencool.vanta.symbol.method.ast.AstMethodSymbol;
import net.vansencool.vanta.symbol.method.synth.SyntheticDefaultConstructor;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.ref.TypeRefs;
import net.vansencool.vanta.symbol.type.ref.build.RefFromAst;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AstTypeSymbol implements TypeSymbol {

    private final @NotNull ClassDeclaration declaration;
    private final @NotNull String internalName;
    private final @NotNull TypeResolver typeResolver;
    private final @NotNull TypeRegistry registry;
    private final @Nullable String sourceFile;
    private final @Nullable String enclosingNonStaticOuter;
    private @Nullable List<MethodSymbol> cachedMethods;
    private @Nullable List<FieldSymbol> cachedFields;
    private @Nullable List<TypeSymbol> cachedNestedTypes;
    private @Nullable List<TypeParameterSymbol> cachedTypeParameters;

    public AstTypeSymbol(@NotNull ClassDeclaration declaration, @NotNull String internalName, @NotNull TypeResolver typeResolver, @NotNull TypeRegistry registry, @Nullable String sourceFile, @Nullable String enclosingNonStaticOuter) {
        this.declaration = declaration;
        this.internalName = internalName;
        this.typeResolver = typeResolver;
        this.registry = registry;
        this.sourceFile = sourceFile;
        this.enclosingNonStaticOuter = enclosingNonStaticOuter;
    }

    public @NotNull ClassDeclaration declaration() {
        return declaration;
    }

    public @NotNull TypeResolver typeResolver() {
        return typeResolver;
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
        Map<String, String> typeVariableErasures = ownTypeVariableErasures();
        List<MethodSymbol> out = new ArrayList<>();
        boolean sawConstructor = false;
        for (AstNode m : declaration.members()) {
            if (m instanceof MethodDeclaration md) {
                Position pos = sourceFile != null ? Position.of(sourceFile, md.line()) : null;
                if ("<init>".equals(md.name())) {
                    sawConstructor = true;
                    out.add(new AstConstructorSymbol(md, this, typeResolver, typeVariables, typeVariableErasures, pos, enclosingNonStaticOuter));
                } else {
                    out.add(new AstMethodSymbol(md, this, typeResolver, typeVariables, typeVariableErasures, pos));
                }
            }
        }
        if (!sawConstructor && !isInterface() && !isEnum() && !isRecord()) {
            out.add(new SyntheticDefaultConstructor(this, enclosingNonStaticOuter));
        }
        cachedMethods = List.copyOf(out);
        return cachedMethods;
    }

    @Override
    public @NotNull List<FieldSymbol> fields() {
        if (cachedFields != null) return cachedFields;
        Set<String> typeVariables = ownTypeVariableNames();
        Map<String, String> typeVariableErasures = ownTypeVariableErasures();
        List<FieldSymbol> out = new ArrayList<>();
        if (isEnum() && declaration.enumConstants() != null) {
            int enumAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM;
            for (EnumConstant ec : declaration.enumConstants()) {
                Position pos = sourceFile != null ? Position.of(sourceFile, ec.line()) : null;
                out.add(new SyntheticFieldSymbol(ec.name(), TypeRefs.ofObject(internalName), enumAccess, null, this, pos));
            }
        }
        if (isRecord() && declaration.recordComponents() != null) {
            int recordAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;
            for (RecordComponent rc : declaration.recordComponents()) {
                out.add(new SyntheticFieldSymbol(rc.name(), RefFromAst.from(rc.type(), typeResolver, typeVariables, typeVariableErasures), recordAccess, null, this, null));
            }
        }
        for (AstNode m : declaration.members()) {
            if (m instanceof FieldDeclaration fd) {
                Position pos = sourceFile != null ? Position.of(sourceFile, fd.line()) : null;
                for (FieldDeclarator declarator : fd.declarators()) {
                    out.add(new AstFieldSymbol(fd, declarator, this, typeResolver, typeVariables, typeVariableErasures, pos));
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
    public @NotNull List<AnnotationInstance> annotations() {
        if (declaration.annotations().isEmpty()) return List.of();
        List<AnnotationInstance> out = new ArrayList<>(declaration.annotations().size());
        for (AnnotationNode ann : declaration.annotations()) {
            out.add(new AstAnnotationInstance(ann, typeResolver));
        }
        return List.copyOf(out);
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

    private @NotNull Map<String, String> ownTypeVariableErasures() {
        if (declaration.typeParameters() == null || declaration.typeParameters().isEmpty()) return Map.of();
        Map<String, String> map = new HashMap<>();
        for (TypeParameter tp : declaration.typeParameters()) {
            String erasure = "java/lang/Object";
            if (tp.bounds() != null && !tp.bounds().isEmpty()) {
                erasure = typeResolver.resolveInternalName(tp.bounds().get(0));
            }
            map.put(tp.name(), erasure);
        }
        return map;
    }
}
