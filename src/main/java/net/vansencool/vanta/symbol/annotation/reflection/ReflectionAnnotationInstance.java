package net.vansencool.vanta.symbol.annotation.reflection;

import net.vansencool.vanta.symbol.annotation.AnnotationInstance;
import net.vansencool.vanta.symbol.annotation.RetentionPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReflectionAnnotationInstance implements AnnotationInstance {

    private final @NotNull Annotation annotation;
    private final @NotNull String internalName;
    private @Nullable Map<String, Object> cachedValues;

    public ReflectionAnnotationInstance(@NotNull Annotation annotation) {
        this.annotation = annotation;
        this.internalName = annotation.annotationType().getName().replace('.', '/');
    }

    @Override
    public @NotNull String internalName() {
        return internalName;
    }

    @Override
    public @NotNull Map<String, Object> values() {
        if (cachedValues != null) return cachedValues;
        Class<? extends Annotation> type = annotation.annotationType();
        Method[] members = type.getDeclaredMethods();
        if (members.length == 0) {
            cachedValues = Map.of();
            return cachedValues;
        }
        Map<String, Object> out = new LinkedHashMap<>(members.length);
        for (Method m : members) {
            try {
                out.put(m.getName(), m.invoke(annotation));
            } catch (IllegalAccessException | InvocationTargetException ignored) {
            }
        }
        cachedValues = Map.copyOf(out);
        return cachedValues;
    }

    @Override
    public @Nullable RetentionPolicy retention() {
        Retention r = annotation.annotationType().getAnnotation(Retention.class);
        if (r == null) return null;
        return switch (r.value()) {
            case SOURCE -> RetentionPolicy.SOURCE;
            case CLASS -> RetentionPolicy.CLASS;
            case RUNTIME -> RetentionPolicy.RUNTIME;
        };
    }
}
