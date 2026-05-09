package net.vansencool.vanta.codegen.classes.writer;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;

import java.util.HashSet;
import java.util.Set;

/**
 * Specialised {@link ClassWriter} whose {@code getCommonSuperClass} hook
 * consults the shared {@link ClasspathManager} cache instead of doing a
 * fresh {@link Class} lookup on every invocation. Frame computation fires
 * {@code getCommonSuperClass} for every basic-block merge, so caching the
 * result keeps frame-map computation from dominating compile time on large
 * classes.
 */
public final class VantaClassWriter extends ClassWriter {

    private final @NotNull ClasspathManager cp;

    /**
     * Creates a writer wired to {@code cp}'s common-super cache and
     * configured with {@code COMPUTE_FRAMES} so stack-map frames and max
     * locals/stack are generated automatically.
     *
     * @param cp classpath manager used to load classes and cache super lookups
     */
    public VantaClassWriter(@NotNull ClasspathManager cp) {
        super(COMPUTE_FRAMES);
        this.cp = cp;
    }

    @Override
    protected @NotNull String getCommonSuperClass(@NotNull String type1, @NotNull String type2) {
        if (type1.equals(type2)) return type1;
        long key = ((long) type1.hashCode() << 32) ^ (type2.hashCode() & 0xffffffffL);
        String cached = cp.commonSuperCache().get(key);
        if (cached != null) return cached;
        String result;
        try {
            TypeSymbol s1 = cp.typeRegistry().lookup(type1);
            TypeSymbol s2 = cp.typeRegistry().lookup(type2);
            if (s1 == null || s2 == null) result = "java/lang/Object";
            else if (isAssignableFrom(s1, s2)) result = type1;
            else if (isAssignableFrom(s2, s1)) result = type2;
            else if (s1.isInterface() || s2.isInterface()) result = "java/lang/Object";
            else result = walkUntilAssignable(s1, s2);
        } catch (Throwable t) {
            result = "java/lang/Object";
        }
        cp.commonSuperCache().put(key, result);
        return result;
    }

    private boolean isAssignableFrom(@NotNull TypeSymbol parent, @NotNull TypeSymbol child) {
        if (parent.internalName().equals(child.internalName())) return true;
        if ("java/lang/Object".equals(parent.internalName())) return true;
        Set<String> visited = new HashSet<>();
        return walkSupers(child, parent.internalName(), visited);
    }

    private boolean walkSupers(@NotNull TypeSymbol type, @NotNull String target, @NotNull Set<String> visited) {
        if (!visited.add(type.internalName())) return false;
        if (type.internalName().equals(target)) return true;
        TypeSymbol superclass = type.superclass();
        if (superclass != null && walkSupers(superclass, target, visited)) return true;
        for (TypeSymbol iface : type.interfaces()) {
            if (walkSupers(iface, target, visited)) return true;
        }
        return false;
    }

    private @NotNull String walkUntilAssignable(@NotNull TypeSymbol s1, @NotNull TypeSymbol s2) {
        TypeSymbol cur = s1.superclass();
        while (cur != null && !isAssignableFrom(cur, s2)) cur = cur.superclass();
        return cur == null ? "java/lang/Object" : cur.internalName();
    }
}
