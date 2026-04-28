package net.vansencool.vanta.codegen.expression.util.generic;

import net.vansencool.vanta.codegen.expression.util.desc.DescriptorUtils;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lowers a reflection {@link java.lang.reflect.Type} (possibly parameterised,
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
    public static @NotNull ResolvedType resolve(@NotNull java.lang.reflect.Type t, @NotNull Map<String, ResolvedType> tvSubst) {
        if (t instanceof Class<?> c) return DescriptorUtils.resolvedTypeFromClass(c);
        if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> rc) {
            ResolvedType base = DescriptorUtils.resolvedTypeFromClass(rc);
            List<ResolvedType> args = new ArrayList<>();
            for (java.lang.reflect.Type inner : pt.getActualTypeArguments()) {
                args.add(resolve(inner, tvSubst));
            }
            return base.withTypeArguments(args);
        }
        if (t instanceof WildcardType wt) {
            java.lang.reflect.Type[] lower = wt.getLowerBounds();
            if (lower.length > 0) return resolve(lower[0], tvSubst);
            java.lang.reflect.Type[] upper = wt.getUpperBounds();
            if (upper.length > 0) return resolve(upper[0], tvSubst);
        }
        if (t instanceof TypeVariable<?> tv) {
            ResolvedType s = tvSubst.get(tv.getName());
            if (s != null) return s;
        }
        return ResolvedType.ofObject("java/lang/Object");
    }
}
