package net.vansencool.vanta.symbol.method.ast;

import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.ref.TypeRefs;
import net.vansencool.vanta.symbol.type.ref.build.RefFromAst;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Constructor symbol that adds the synthetic {@code this$0} outer parameter
 * for non static inner classes, matching the descriptor codegen emits.
 */
public final class AstConstructorSymbol implements MethodSymbol {

    private final @NotNull MethodDeclaration declaration;
    private final @NotNull TypeSymbol owner;
    private final @NotNull TypeResolver typeResolver;
    private final @NotNull Set<String> typeVariables;
    private final @NotNull Map<String, String> typeVariableErasures;
    private final @Nullable Position sourcePosition;
    private final @Nullable String enclosingOuterInternal;
    private @Nullable List<TypeRef> cachedParameterTypes;
    private @Nullable String cachedDescriptor;

    public AstConstructorSymbol(@NotNull MethodDeclaration declaration, @NotNull TypeSymbol owner, @NotNull TypeResolver typeResolver, @NotNull Set<String> typeVariables, @Nullable Position sourcePosition, @Nullable String enclosingOuterInternal) {
        this(declaration, owner, typeResolver, typeVariables, Map.of(), sourcePosition, enclosingOuterInternal);
    }

    public AstConstructorSymbol(@NotNull MethodDeclaration declaration, @NotNull TypeSymbol owner, @NotNull TypeResolver typeResolver, @NotNull Set<String> typeVariables, @NotNull Map<String, String> typeVariableErasures, @Nullable Position sourcePosition, @Nullable String enclosingOuterInternal) {
        this.declaration = declaration;
        this.owner = owner;
        this.typeResolver = typeResolver;
        this.typeVariables = typeVariables;
        this.typeVariableErasures = typeVariableErasures;
        this.sourcePosition = sourcePosition;
        this.enclosingOuterInternal = enclosingOuterInternal;
    }

    @Override
    public @NotNull String name() {
        return "<init>";
    }

    @Override
    public @NotNull String descriptor() {
        if (cachedDescriptor != null) return cachedDescriptor;
        StringBuilder sb = new StringBuilder("(");
        for (TypeRef p : parameterTypes()) sb.append(p.descriptor());
        sb.append(")V");
        cachedDescriptor = sb.toString();
        return cachedDescriptor;
    }

    @Override
    public int access() {
        return declaration.modifiers();
    }

    @Override
    public boolean isStatic() {
        return false;
    }

    @Override
    public boolean isAbstract() {
        return false;
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
        List<TypeRef> out = new ArrayList<>();
        if (enclosingOuterInternal != null) out.add(TypeRefs.ofObject(enclosingOuterInternal));
        for (Parameter p : declaration.parameters())
            out.add(RefFromAst.from(p.type(), typeResolver, typeVariables, typeVariableErasures));
        cachedParameterTypes = List.copyOf(out);
        return cachedParameterTypes;
    }

    @Override
    public @NotNull TypeRef returnType() {
        return TypeRefs.ofPrimitive("V");
    }

    @Override
    public @NotNull List<TypeParameterSymbol> typeParameters() {
        return List.of();
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
