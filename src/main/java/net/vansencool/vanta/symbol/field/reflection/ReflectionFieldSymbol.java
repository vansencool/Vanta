package net.vansencool.vanta.symbol.field.reflection;

import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.ref.build.RefFromReflection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class ReflectionFieldSymbol implements FieldSymbol {

    private final @NotNull Field field;
    private final @NotNull TypeSymbol owner;
    private @Nullable TypeRef cachedType;

    public ReflectionFieldSymbol(@NotNull Field field, @NotNull TypeSymbol owner) {
        this.field = field;
        this.owner = owner;
    }

    @Override
    public @NotNull String name() {
        return field.getName();
    }

    @Override
    public @NotNull TypeRef type() {
        if (cachedType != null) return cachedType;
        try {
            cachedType = RefFromReflection.from(field.getGenericType());
        } catch (Throwable ignored) {
            cachedType = RefFromReflection.fromClass(field.getType());
        }
        return cachedType;
    }

    @Override
    public int access() {
        return field.getModifiers();
    }

    @Override
    public boolean isStatic() {
        return Modifier.isStatic(field.getModifiers());
    }

    @Override
    public boolean isFinal() {
        return Modifier.isFinal(field.getModifiers());
    }

    @Override
    public @Nullable Object constantValue() {
        if (!isStatic() || !isFinal()) return null;
        Class<?> type = field.getType();
        if (!type.isPrimitive() && type != String.class) return null;
        try {
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
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
