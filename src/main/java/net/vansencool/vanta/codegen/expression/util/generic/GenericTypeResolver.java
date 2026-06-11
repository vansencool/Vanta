package net.vansencool.vanta.codegen.expression.util.generic;

import net.vansencool.vanta.codegen.expression.util.desc.DescriptorUtils;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers a reflection {@link Type} (possibly parameterised,
 * wildcarded, or referring to a type variable) into a {@link ResolvedType}.
 * A type-variable substitution map threads concrete receiver-side arguments
 * through so nested type-variable references resolve against the outer call
 * site rather than falling back to {@code Object}.
 */
public final class GenericTypeResolver {

    private GenericTypeResolver() {
    }

    /**
     * @param t       reflection type to lower
     * @param tvSubst type-variable substitution map, typically built from the
     *                receiver's runtime type arguments
     * @return matching resolved type, falling back to {@code java/lang/Object}
     * for unresolvable type variables
     */
    public static @NotNull ResolvedType resolve(@NotNull Type t, @NotNull Map<String, ResolvedType> tvSubst) {
        if (t instanceof Class<?> c) return DescriptorUtils.resolvedTypeFromClass(c);
        if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> rc) {
            ResolvedType base = DescriptorUtils.resolvedTypeFromClass(rc);
            List<ResolvedType> args = new ArrayList<>();
            for (Type inner : pt.getActualTypeArguments()) {
                args.add(resolve(inner, tvSubst));
            }
            return base.withTypeArguments(args);
        }
        if (t instanceof WildcardType wt) {
            Type[] lower = wt.getLowerBounds();
            if (lower.length > 0) return resolve(lower[0], tvSubst);
            Type[] upper = wt.getUpperBounds();
            if (upper.length > 0) return resolve(upper[0], tvSubst);
        }
        if (t instanceof TypeVariable<?> tv) {
            ResolvedType s = tvSubst.get(tv.getName());
            if (s != null) return s;
        }
        return ResolvedType.ofObject("java/lang/Object");
    }

    /**
     * Projects a receiver class's type-variable bindings up the generic
     * super chain to the class that declares the method being called, so a
     * {@code List<Diff>} receiver binds {@code Iterable}'s {@code T} when
     * resolving {@code Iterable.forEach(Consumer<? super T>)}.
     */
    public static @NotNull Map<String, ResolvedType> projectTypeVars(@NotNull Class<?> from, @NotNull Map<String, ResolvedType> fromMap, @NotNull Class<?> to) {
        Deque<Class<?>> classes = new ArrayDeque<>();
        Deque<Map<String, ResolvedType>> maps = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        classes.add(from);
        maps.add(fromMap);
        while (!classes.isEmpty()) {
            Class<?> cur = classes.poll();
            Map<String, ResolvedType> curMap = maps.poll();
            if (!visited.add(cur)) continue;
            if (cur.equals(to)) return curMap;
            List<Type> supers = new ArrayList<>();
            if (cur.getGenericSuperclass() != null) supers.add(cur.getGenericSuperclass());
            supers.addAll(List.of(cur.getGenericInterfaces()));
            for (Type st : supers) {
                if (st instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
                    TypeVariable<?>[] params = raw.getTypeParameters();
                    Type[] actuals = pt.getActualTypeArguments();
                    Map<String, ResolvedType> next = new HashMap<>();
                    for (int i = 0; i < params.length && i < actuals.length; i++) {
                        next.put(params[i].getName(), resolve(actuals[i], curMap));
                    }
                    classes.add(raw);
                    maps.add(next);
                } else if (st instanceof Class<?> raw) {
                    classes.add(raw);
                    maps.add(Map.of());
                }
            }
        }
        return fromMap;
    }
}
