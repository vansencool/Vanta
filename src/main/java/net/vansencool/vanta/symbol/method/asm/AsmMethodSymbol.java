package net.vansencool.vanta.symbol.method.asm;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.ref.build.RefFromDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

public final class AsmMethodSymbol implements MethodSymbol {

    private final @NotNull AsmClassInfo.MethodInfo info;
    private final @NotNull TypeSymbol owner;
    private @Nullable List<TypeRef> cachedParameterTypes;
    private @Nullable TypeRef cachedReturnType;

    public AsmMethodSymbol(@NotNull AsmClassInfo.MethodInfo info, @NotNull TypeSymbol owner) {
        this.info = info;
        this.owner = owner;
    }

    @Override
    public @NotNull String name() {
        return info.name();
    }

    @Override
    public @NotNull String descriptor() {
        return info.descriptor();
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
    public boolean isAbstract() {
        return (info.access() & Opcodes.ACC_ABSTRACT) != 0;
    }

    @Override
    public boolean isVarargs() {
        return info.isVarArgs();
    }

    @Override
    public @NotNull List<TypeRef> parameterTypes() {
        if (cachedParameterTypes != null) return cachedParameterTypes;
        Type[] params = Type.getArgumentTypes(info.descriptor());
        List<TypeRef> out = new ArrayList<>(params.length);
        for (Type p : params) out.add(RefFromDescriptor.from(p.getDescriptor()));
        cachedParameterTypes = List.copyOf(out);
        return cachedParameterTypes;
    }

    @Override
    public @NotNull TypeRef returnType() {
        if (cachedReturnType != null) return cachedReturnType;
        cachedReturnType = RefFromDescriptor.from(Type.getReturnType(info.descriptor()).getDescriptor());
        return cachedReturnType;
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
