package net.vansencool.vanta.symbol.method.ast;

import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.parser.ast.type.TypeParameter;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.ast.AstTypeParameterSymbol;
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

public final class AstMethodSymbol implements MethodSymbol {

    private final @NotNull MethodDeclaration declaration;
    private final @NotNull TypeSymbol owner;
    private final @NotNull TypeResolver typeResolver;
    private final @NotNull Set<String> outerTypeVariables;
    private final @NotNull Map<String, String> outerTypeVariableErasures;
    private final @Nullable Position sourcePosition;
    private @Nullable List<TypeParameterSymbol> cachedTypeParameters;
    private @Nullable List<TypeRef> cachedParameterTypes;
    private @Nullable TypeRef cachedReturnType;
    private @Nullable String cachedDescriptor;

    public AstMethodSymbol(@NotNull MethodDeclaration declaration, @NotNull TypeSymbol owner, @NotNull TypeResolver typeResolver, @NotNull Set<String> outerTypeVariables, @Nullable Position sourcePosition) {
        this(declaration, owner, typeResolver, outerTypeVariables, Map.of(), sourcePosition);
    }

    public AstMethodSymbol(@NotNull MethodDeclaration declaration, @NotNull TypeSymbol owner, @NotNull TypeResolver typeResolver, @NotNull Set<String> outerTypeVariables, @NotNull Map<String, String> outerTypeVariableErasures, @Nullable Position sourcePosition) {
        this.declaration = declaration;
        this.owner = owner;
        this.typeResolver = typeResolver;
        this.outerTypeVariables = outerTypeVariables;
        this.outerTypeVariableErasures = outerTypeVariableErasures;
        this.sourcePosition = sourcePosition;
    }

    @Override
    public @NotNull String name() {
        return declaration.name();
    }

    @Override
    public @NotNull String descriptor() {
        if (cachedDescriptor != null) return cachedDescriptor;
        StringBuilder sb = new StringBuilder("(");
        for (TypeRef p : parameterTypes()) sb.append(p.descriptor());
        sb.append(')');
        sb.append(returnType().descriptor());
        cachedDescriptor = sb.toString();
        return cachedDescriptor;
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
    public boolean isAbstract() {
        if ((declaration.modifiers() & Opcodes.ACC_ABSTRACT) != 0) return true;
        if (declaration.body() != null) return false;
        if (isStatic()) return false;
        return owner.isInterface();
    }

    @Override
    public boolean isVarargs() {
        return declaration.isVarargs();
    }

    @Override
    public boolean isBridge() {
        return false;
    }

    @Override
    public boolean isSynthetic() {
        return (declaration.modifiers() & Opcodes.ACC_SYNTHETIC) != 0;
    }

    @Override
    public @NotNull List<TypeRef> parameterTypes() {
        if (cachedParameterTypes != null) return cachedParameterTypes;
        Set<String> scope = combinedTypeVariables();
        Map<String, String> erasures = combinedErasures();
        List<TypeRef> out = new ArrayList<>(declaration.parameters().size());
        for (Parameter p : declaration.parameters()) {
            out.add(RefFromAst.from(p.type(), typeResolver, scope, erasures));
        }
        cachedParameterTypes = List.copyOf(out);
        return cachedParameterTypes;
    }

    @Override
    public @NotNull TypeRef returnType() {
        if (cachedReturnType != null) return cachedReturnType;
        Set<String> scope = combinedTypeVariables();
        Map<String, String> erasures = combinedErasures();
        cachedReturnType = RefFromAst.from(declaration.returnType(), typeResolver, scope, erasures);
        return cachedReturnType;
    }

    @Override
    public @NotNull List<TypeParameterSymbol> typeParameters() {
        if (cachedTypeParameters != null) return cachedTypeParameters;
        if (declaration.typeParameters() == null || declaration.typeParameters().isEmpty()) {
            cachedTypeParameters = List.of();
            return cachedTypeParameters;
        }
        Set<String> scope = combinedTypeVariables();
        List<TypeParameterSymbol> built = new ArrayList<>(declaration.typeParameters().size());
        for (TypeParameter tp : declaration.typeParameters()) {
            built.add(new AstTypeParameterSymbol(tp, typeResolver, scope));
        }
        cachedTypeParameters = List.copyOf(built);
        return cachedTypeParameters;
    }

    @Override
    public @Nullable Position sourcePosition() {
        return sourcePosition;
    }

    @Override
    public @NotNull TypeSymbol owner() {
        return owner;
    }

    private @NotNull Set<String> combinedTypeVariables() {
        if (declaration.typeParameters() == null || declaration.typeParameters().isEmpty()) return outerTypeVariables;
        Set<String> combined = new HashSet<>(outerTypeVariables);
        for (TypeParameter tp : declaration.typeParameters()) combined.add(tp.name());
        return combined;
    }

    private @NotNull Map<String, String> combinedErasures() {
        if (declaration.typeParameters() == null || declaration.typeParameters().isEmpty())
            return outerTypeVariableErasures;
        Map<String, String> combined = new HashMap<>(outerTypeVariableErasures);
        for (TypeParameter tp : declaration.typeParameters()) {
            combined.put(tp.name(), erasureOf(tp));
        }
        return combined;
    }

    private @NotNull String erasureOf(@NotNull TypeParameter tp) {
        if (tp.bounds() == null || tp.bounds().isEmpty()) return "java/lang/Object";
        TypeNode firstBound = tp.bounds().get(0);
        if (outerTypeVariables.contains(firstBound.name())) {
            String chained = outerTypeVariableErasures.get(firstBound.name());
            return chained != null ? chained : "java/lang/Object";
        }
        return typeResolver.resolveInternalName(firstBound);
    }
}
