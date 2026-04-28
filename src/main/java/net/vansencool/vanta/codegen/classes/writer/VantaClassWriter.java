package net.vansencool.vanta.codegen.classes.writer;

import net.vansencool.vanta.classpath.ClasspathManager;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;

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
            Class<?> c1 = cp.loadClass(type1);
            Class<?> c2 = cp.loadClass(type2);
            if (c1 == null || c2 == null) result = "java/lang/Object";
            else if (c1.isAssignableFrom(c2)) result = type1;
            else if (c2.isAssignableFrom(c1)) result = type2;
            else if (c1.isInterface() || c2.isInterface()) result = "java/lang/Object";
            else {
                Class<?> c = c1;
                do {
                    c = c.getSuperclass();
                } while (c != null && !c.isAssignableFrom(c2));
                result = c == null ? "java/lang/Object" : c.getName().replace('.', '/');
            }
        } catch (Throwable t) {
            result = "java/lang/Object";
        }
        cp.commonSuperCache().put(key, result);
        return result;
    }
}
