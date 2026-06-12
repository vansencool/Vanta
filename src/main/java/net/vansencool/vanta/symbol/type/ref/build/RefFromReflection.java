package net.vansencool.vanta.symbol.type.ref.build;

import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.ref.TypeRefs;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@link TypeRef} instances from {@link Type}
 * shapes. Wildcards collapse to their upper bound; type variables become
 * {@code TypeVariableRef} so generic substitution can resolve them later.
 */
public final class RefFromReflection {

    private RefFromReflection() {
    }

    public static @NotNull TypeRef from(@NotNull Type type) {
        if (type instanceof Class<?> c) return fromClass(c);
        if (type instanceof ParameterizedType pt) return fromParameterized(pt);
        if (type instanceof GenericArrayType gat) {
            TypeRef element = from(gat.getGenericComponentType());
            return TypeRefs.ofArray(element, 1);
        }
        if (type instanceof TypeVariable<?> tv) {
            Type[] bounds = tv.getBounds();
            Type first = bounds.length > 0 ? bounds[0] : null;
            Class<?> raw = first instanceof Class<?> c ? c : first instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> rc ? rc : null;
            if (raw != null && raw != Object.class) return TypeRefs.ofTypeVariable(tv.getName(), raw.getName().replace('.', '/'));
            return TypeRefs.ofTypeVariable(tv.getName());
        }
        if (type instanceof WildcardType wt) {
            Type[] upper = wt.getUpperBounds();
            if (upper.length > 0) return from(upper[0]);
            return TypeRefs.ofObject("java/lang/Object");
        }
        return TypeRefs.ofObject("java/lang/Object");
    }

    public static @NotNull TypeRef fromClass(@NotNull Class<?> c) {
        if (c.isPrimitive()) {
            String name = c.getName();
            return TypeRefs.ofPrimitive(switch (name) {
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "short" -> "S";
                case "char" -> "C";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                case "void" -> "V";
                default -> "Ljava/lang/Object;";
            });
        }
        if (c.isArray()) {
            int dimensions = 0;
            Class<?> walk = c;
            while (walk.isArray()) {
                dimensions++;
                walk = walk.getComponentType();
            }
            return TypeRefs.ofArray(fromClass(walk), dimensions);
        }
        return TypeRefs.ofObject(c.getName().replace('.', '/'));
    }

    private static @NotNull TypeRef fromParameterized(@NotNull ParameterizedType pt) {
        if (!(pt.getRawType() instanceof Class<?> raw)) return TypeRefs.ofObject("java/lang/Object");
        if (pt.getActualTypeArguments().length == 0) return fromClass(raw);
        List<TypeRef> args = new ArrayList<>(pt.getActualTypeArguments().length);
        for (Type t : pt.getActualTypeArguments()) args.add(from(t));
        return TypeRefs.ofObject(raw.getName().replace('.', '/'), args);
    }
}
