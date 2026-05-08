package net.vansencool.vanta.symbol.method.reflection;

import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.ref.build.RefFromReflection;
import net.vansencool.vanta.symbol.type.reflection.ReflectionTypeParameterSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;

public final class ReflectionMethodSymbol implements MethodSymbol {

    private final @NotNull Method method;
    private final @NotNull TypeSymbol owner;
    private final @NotNull String descriptor;
    private @Nullable List<TypeRef> cachedParameterTypes;
    private @Nullable TypeRef cachedReturnType;
    private @Nullable List<TypeParameterSymbol> cachedTypeParameters;

    public ReflectionMethodSymbol(@NotNull Method method, @NotNull TypeSymbol owner, @NotNull String descriptor) {
        this.method = method;
        this.owner = owner;
        this.descriptor = descriptor;
    }

    public @NotNull Method reflective() {
        return method;
    }

    @Override
    public @NotNull String name() {
        return method.getName();
    }

    @Override
    public @NotNull String descriptor() {
        return descriptor;
    }

    @Override
    public int access() {
        return method.getModifiers();
    }

    @Override
    public boolean isStatic() {
        return Modifier.isStatic(method.getModifiers());
    }

    @Override
    public boolean isAbstract() {
        return Modifier.isAbstract(method.getModifiers());
    }

    @Override
    public boolean isVarargs() {
        return method.isVarArgs();
    }

    @Override
    public boolean isBridge() {
        return method.isBridge();
    }

    @Override
    public boolean isSynthetic() {
        return method.isSynthetic();
    }

    @Override
    public @NotNull List<TypeRef> parameterTypes() {
        if (cachedParameterTypes != null) return cachedParameterTypes;
        Type[] generic;
        try {
            generic = method.getGenericParameterTypes();
        } catch (Throwable ignored) {
            generic = method.getParameterTypes();
        }
        List<TypeRef> out = new ArrayList<>(generic.length);
        for (Type t : generic) out.add(RefFromReflection.from(t));
        cachedParameterTypes = List.copyOf(out);
        return cachedParameterTypes;
    }

    @Override
    public @NotNull TypeRef returnType() {
        if (cachedReturnType != null) return cachedReturnType;
        Type t;
        try {
            t = method.getGenericReturnType();
        } catch (Throwable ignored) {
            t = method.getReturnType();
        }
        cachedReturnType = RefFromReflection.from(t);
        return cachedReturnType;
    }

    @Override
    public @NotNull List<TypeParameterSymbol> typeParameters() {
        if (cachedTypeParameters != null) return cachedTypeParameters;
        TypeVariable<?>[] variables = method.getTypeParameters();
        if (variables.length == 0) {
            cachedTypeParameters = List.of();
            return cachedTypeParameters;
        }
        List<TypeParameterSymbol> built = new ArrayList<>(variables.length);
        for (TypeVariable<?> tv : variables) built.add(new ReflectionTypeParameterSymbol(tv));
        cachedTypeParameters = List.copyOf(built);
        return cachedTypeParameters;
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
