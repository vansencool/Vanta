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
public record SyntheticFieldSymbol(@NotNull String name, @NotNull TypeRef type, int access,
                                   @Nullable Object constantValue, @NotNull TypeSymbol owner,
                                   @Nullable Position sourcePosition) implements FieldSymbol {

    @Override
    public boolean isStatic() {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    @Override
    public boolean isPrivate() {
        return (access & Opcodes.ACC_PRIVATE) != 0;
    }

    @Override
    public boolean isFinal() {
        return (access & Opcodes.ACC_FINAL) != 0;
    }
}
