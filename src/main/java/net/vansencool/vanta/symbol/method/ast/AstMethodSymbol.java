package net.vansencool.vanta.symbol.method.ast;

import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AstMethodSymbol implements MethodSymbol {

    private final @NotNull MethodDeclaration declaration;
    private final @NotNull TypeSymbol owner;
    private final @NotNull TypeResolver typeResolver;
    private final @NotNull Set<String> outerTypeVariables;
    private final @Nullable Position sourcePosition;
    private @Nullable List<TypeParameterSymbol> cachedTypeParameters;
    private @Nullable List<TypeRef> cachedParameterTypes;
    private @Nullable TypeRef cachedReturnType;
    private @Nullable String cachedDescriptor;

    public AstMethodSymbol(@NotNull MethodDeclaration declaration, @NotNull TypeSymbol owner, @NotNull TypeResolver typeResolver, @NotNull Set<String> outerTypeVariables, @Nullable Position sourcePosition) {
        this.declaration = declaration;
        this.owner = owner;
        this.typeResolver = typeResolver;
        this.outerTypeVariables = outerTypeVariables;
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
        return (declaration.modifiers() & Opcodes.ACC_ABSTRACT) != 0 || declaration.body() == null && !owner.isInterface();
    }

    @Override
    public boolean isVarargs() {
        return declaration.isVarargs();
    }

    @Override
    public @NotNull List<TypeRef> parameterTypes() {
        if (cachedParameterTypes != null) return cachedParameterTypes;
        Set<String> scope = combinedTypeVariables();
        List<TypeRef> out = new ArrayList<>(declaration.parameters().size());
        for (Parameter p : declaration.parameters()) {
            out.add(RefFromAst.from(p.type(), typeResolver, scope));
        }
        cachedParameterTypes = List.copyOf(out);
        return cachedParameterTypes;
    }

    @Override
    public @NotNull TypeRef returnType() {
        if (cachedReturnType != null) return cachedReturnType;
        Set<String> scope = combinedTypeVariables();
        cachedReturnType = RefFromAst.from(declaration.returnType(), typeResolver, scope);
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
}
