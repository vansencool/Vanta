package net.vansencool.vanta.symbol.type.reflection;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.symbol.Position;
import net.vansencool.vanta.symbol.annotation.AnnotationInstance;
import net.vansencool.vanta.symbol.annotation.reflection.ReflectionAnnotationInstance;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.field.reflection.ReflectionFieldSymbol;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.method.reflection.ReflectionConstructorSymbol;
import net.vansencool.vanta.symbol.method.reflection.ReflectionMethodSymbol;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReflectionTypeSymbol implements TypeSymbol {

    private final @NotNull Class<?> clazz;
    private final @NotNull String internalName;
    private final @NotNull TypeRegistry registry;
    private final @NotNull ClasspathManager classpathManager;
    private @Nullable List<MethodSymbol> cachedMethods;
    private @Nullable List<FieldSymbol> cachedFields;
    private @Nullable List<TypeSymbol> cachedNestedTypes;
    private @Nullable List<TypeParameterSymbol> cachedTypeParameters;
    private @Nullable List<TypeSymbol> cachedInterfaces;

    public ReflectionTypeSymbol(@NotNull Class<?> clazz, @NotNull TypeRegistry registry, @NotNull ClasspathManager classpathManager) {
        this.clazz = clazz;
        this.internalName = clazz.getName().replace('.', '/');
        this.registry = registry;
        this.classpathManager = classpathManager;
    }

    public @NotNull Class<?> reflective() {
        return clazz;
    }

    @Override
    public @NotNull String internalName() {
        return internalName;
    }

    @Override
    public @Nullable TypeSymbol superclass() {
        Class<?> sc = clazz.getSuperclass();
        if (sc == null) return null;
        return registry.lookup(sc.getName().replace('.', '/'));
    }

    @Override
    public @NotNull List<TypeSymbol> interfaces() {
        if (cachedInterfaces != null) return cachedInterfaces;
        Class<?>[] reflectionInterfaces = clazz.getInterfaces();
        if (reflectionInterfaces.length == 0) {
            cachedInterfaces = List.of();
            return cachedInterfaces;
        }
        List<TypeSymbol> out = new ArrayList<>(reflectionInterfaces.length);
        for (Class<?> i : reflectionInterfaces) {
            TypeSymbol sym = registry.lookup(i.getName().replace('.', '/'));
            if (sym != null) out.add(sym);
        }
        cachedInterfaces = List.copyOf(out);
        return cachedInterfaces;
    }

    @Override
    public int access() {
        return clazz.getModifiers();
    }

    @Override
    public boolean isInterface() {
        return clazz.isInterface();
    }

    @Override
    public boolean isEnum() {
        return clazz.isEnum();
    }

    @Override
    public boolean isRecord() {
        return clazz.isRecord();
    }

    @Override
    public boolean isAbstract() {
        return Modifier.isAbstract(clazz.getModifiers());
    }

    @Override
    public @NotNull List<MethodSymbol> methods() {
        if (cachedMethods != null) return cachedMethods;
        Method[] publicMethods = classpathManager.cachedMethods(clazz);
        Method[] declaredMethods;
        try {
            declaredMethods = clazz.getDeclaredMethods();
        } catch (LinkageError e) {
            declaredMethods = new Method[0];
        }
        Constructor<?>[] ctors;
        try {
            ctors = classpathManager.cachedDeclaredConstructors(clazz);
        } catch (LinkageError e) {
            ctors = new Constructor<?>[0];
        }
        List<MethodSymbol> out = new ArrayList<>(publicMethods.length + declaredMethods.length + ctors.length);
        Set<String> seen = new HashSet<>();
        for (Method m : publicMethods) {
            String descriptor = classpathManager.methodDescriptor(m);
            seen.add(m.getName() + descriptor);
            out.add(new ReflectionMethodSymbol(m, this, registry, descriptor));
        }
        for (Method m : declaredMethods) {
            String descriptor = classpathManager.methodDescriptor(m);
            if (!seen.add(m.getName() + descriptor)) continue;
            out.add(new ReflectionMethodSymbol(m, this, registry, descriptor));
        }
        for (Constructor<?> c : ctors) {
            String descriptor = Type.getConstructorDescriptor(c);
            out.add(new ReflectionConstructorSymbol(c, this, descriptor));
        }
        cachedMethods = List.copyOf(out);
        return cachedMethods;
    }

    @Override
    public @NotNull List<FieldSymbol> fields() {
        if (cachedFields != null) return cachedFields;
        try {
            Field[] declared = clazz.getDeclaredFields();
            List<FieldSymbol> out = new ArrayList<>(declared.length);
            for (Field f : declared) {
                if (f.isSynthetic()) continue;
                out.add(new ReflectionFieldSymbol(f, this));
            }
            cachedFields = List.copyOf(out);
        } catch (LinkageError e) {
            cachedFields = List.of();
        }
        return cachedFields;
    }

    @Override
    public @NotNull List<TypeSymbol> nestedTypes() {
        if (cachedNestedTypes != null) return cachedNestedTypes;
        try {
            Class<?>[] declared = clazz.getDeclaredClasses();
            List<TypeSymbol> out = new ArrayList<>(declared.length);
            for (Class<?> nested : declared) {
                TypeSymbol sym = registry.lookup(nested.getName().replace('.', '/'));
                if (sym != null) out.add(sym);
            }
            cachedNestedTypes = List.copyOf(out);
        } catch (LinkageError e) {
            cachedNestedTypes = List.of();
        }
        return cachedNestedTypes;
    }

    @Override
    public @NotNull List<TypeParameterSymbol> typeParameters() {
        if (cachedTypeParameters != null) return cachedTypeParameters;
        TypeVariable<?>[] variables = clazz.getTypeParameters();
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
    public @NotNull List<AnnotationInstance> annotations() {
        try {
            Annotation[] declared = clazz.getDeclaredAnnotations();
            if (declared.length == 0) return List.of();
            List<AnnotationInstance> out = new ArrayList<>(declared.length);
            for (Annotation a : declared) out.add(new ReflectionAnnotationInstance(a));
            return List.copyOf(out);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    @Override
    public @Nullable Position sourcePosition() {
        return null;
    }
}
