package net.vansencool.vanta.symbol.method.synth;

import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * Implicit zero argument accessor for a record component. Returns the
 * declared component type and is publicly visible per JLS records spec.
 */
public final class SyntheticAccessorMethod implements MethodSymbol {

    private final @NotNull String name;
    private final @NotNull TypeRef returnType;
    private final @NotNull TypeSymbol owner;

    public SyntheticAccessorMethod(@NotNull String name, @NotNull TypeRef returnType, @NotNull TypeSymbol owner) {
        this.name = name;
        this.returnType = returnType;
        this.owner = owner;
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public @NotNull String descriptor() {
        return "()" + returnType.descriptor();
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
        return false;
    }

    @Override
    public @NotNull List<TypeRef> parameterTypes() {
        return List.of();
    }

    @Override
    public @NotNull TypeRef returnType() {
        return returnType;
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
