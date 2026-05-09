package net.vansencool.vanta.symbol.method.synth;

import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.ref.TypeRefs;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * Implicit canonical constructor for a record. Parameter types come from the
 * declared record components in declaration order.
 */
public final class SyntheticCanonicalConstructor implements MethodSymbol {

    private final @NotNull TypeSymbol owner;
    private final @NotNull List<TypeRef> parameterTypes;

    public SyntheticCanonicalConstructor(@NotNull TypeSymbol owner, @NotNull List<TypeRef> parameterTypes) {
        this.owner = owner;
        this.parameterTypes = List.copyOf(parameterTypes);
    }

    @Override
    public @NotNull String name() {
        return "<init>";
    }

    @Override
    public @NotNull String descriptor() {
        StringBuilder sb = new StringBuilder("(");
        for (TypeRef p : parameterTypes) sb.append(p.descriptor());
        sb.append(")V");
        return sb.toString();
    }

    @Override
    public int access() {
        return Opcodes.ACC_PUBLIC;
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
        return false;
    }

    @Override
    public boolean isBridge() {
        return false;
    }

    @Override
    public boolean isSynthetic() {
        return true;
    }

    @Override
    public @NotNull List<TypeRef> parameterTypes() {
        return parameterTypes;
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
        return null;
    }

    @Override
    public @NotNull TypeSymbol owner() {
        return owner;
    }
}
