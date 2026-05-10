package net.vansencool.vanta.symbol.method.reflection;

import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.ref.build.RefFromReflection;
import net.vansencool.vanta.symbol.type.ref.primitive.PrimitiveRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public final class ReflectionConstructorSymbol implements MethodSymbol {

    private final @NotNull Constructor<?> ctor;
    private final @NotNull TypeSymbol owner;
    private final @NotNull String descriptor;
    private @Nullable List<TypeRef> cachedParameterTypes;

    public ReflectionConstructorSymbol(@NotNull Constructor<?> ctor, @NotNull TypeSymbol owner, @NotNull String descriptor) {
        this.ctor = ctor;
        this.owner = owner;
        this.descriptor = descriptor;
    }

    @Override
    public @NotNull String name() {
        return "<init>";
    }

    @Override
    public @NotNull String descriptor() {
        return descriptor;
    }

    @Override
    public int access() {
        return ctor.getModifiers();
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
        return ctor.isVarArgs();
    }

    @Override
    public boolean isBridge() {
        return false;
    }

    @Override
    public boolean isSynthetic() {
        return ctor.isSynthetic();
    }

    @Override
    public @NotNull List<TypeRef> parameterTypes() {
        if (cachedParameterTypes != null) return cachedParameterTypes;
        Type[] generic;
        try {
            generic = ctor.getGenericParameterTypes();
        } catch (Throwable ignored) {
            generic = ctor.getParameterTypes();
        }
        List<TypeRef> out = new ArrayList<>(generic.length);
        for (Type t : generic) out.add(RefFromReflection.from(t));
        cachedParameterTypes = List.copyOf(out);
        return cachedParameterTypes;
    }

    @Override
    public @NotNull TypeRef returnType() {
        return new PrimitiveRef("V");
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

    public @NotNull Constructor<?> reflective() {
        return ctor;
    }
}
