package net.vansencool.vanta.symbol.field.synth;

import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

/**
 * A field symbol synthesised by Vanta itself (enum constants, the
 * {@code $VALUES} array, record components projected as fields). Carries a
 * fixed type and access mask, no AST or reflection backing.
 */
public final class SyntheticFieldSymbol implements FieldSymbol {

    private final @NotNull String name;
    private final @NotNull TypeRef type;
    private final int access;
    private final @Nullable Object constantValue;
    private final @NotNull TypeSymbol owner;
    private final @Nullable Position sourcePosition;

    public SyntheticFieldSymbol(@NotNull String name, @NotNull TypeRef type, int access, @Nullable Object constantValue, @NotNull TypeSymbol owner, @Nullable Position sourcePosition) {
        this.name = name;
        this.type = type;
        this.access = access;
        this.constantValue = constantValue;
        this.owner = owner;
        this.sourcePosition = sourcePosition;
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public @NotNull TypeRef type() {
        return type;
    }

    @Override
    public int access() {
        return access;
    }

    @Override
    public boolean isStatic() {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    @Override
    public boolean isFinal() {
        return (access & Opcodes.ACC_FINAL) != 0;
    }

    @Override
    public @Nullable Object constantValue() {
        return constantValue;
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
