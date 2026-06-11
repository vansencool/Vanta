package net.vansencool.vanta.symbol.field.asm;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.ref.build.RefFromDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

public final class AsmFieldSymbol implements FieldSymbol {

    private final @NotNull AsmClassInfo.FieldInfo info;
    private final @NotNull TypeSymbol owner;
    private @Nullable TypeRef cachedType;

    public AsmFieldSymbol(@NotNull AsmClassInfo.FieldInfo info, @NotNull TypeSymbol owner) {
        this.info = info;
        this.owner = owner;
    }

    @Override
    public @NotNull String name() {
        return info.name();
    }

    @Override
    public @NotNull TypeRef type() {
        if (cachedType != null) return cachedType;
        cachedType = RefFromDescriptor.from(info.descriptor());
        return cachedType;
    }

    @Override
    public int access() {
        return info.access();
    }

    @Override
    public boolean isStatic() {
        return (info.access() & Opcodes.ACC_STATIC) != 0;
    }

    @Override
    public boolean isPrivate() {
        return (info.access() & Opcodes.ACC_PRIVATE) != 0;
    }

    @Override
    public boolean isFinal() {
        return (info.access() & Opcodes.ACC_FINAL) != 0;
    }

    @Override
    public @Nullable Object constantValue() {
        return info.constantValue();
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
