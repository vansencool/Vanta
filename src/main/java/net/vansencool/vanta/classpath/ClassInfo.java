package net.vansencool.vanta.classpath;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Stores resolved information about an external class from the classpath.
 *
 * @param internalName           the internal class name
 * @param superInternalName      the super class internal name, or null for Object
 * @param interfaceInternalNames the interface internal names
 * @param accessFlags            the access flags
 * @param isInterface            true if this is an interface
 */
public record ClassInfo(@NotNull String internalName, @Nullable String superInternalName,
                        @NotNull String[] interfaceInternalNames, int accessFlags, boolean isInterface) {

    /**
     * Creates ClassInfo from a reflected Class.
     *
     * @param clazz the class
     * @return the class info
     */
    public static @NotNull ClassInfo fromReflection(@NotNull Class<?> clazz) {
        String internalName = clazz.getName().replace('.', '/');
        String superInternal = clazz.getSuperclass() != null ? clazz.getSuperclass().getName().replace('.', '/') : null;
        Class<?>[] ifaces = clazz.getInterfaces();
        String[] ifaceNames = new String[ifaces.length];
        for (int i = 0; i < ifaces.length; i++) {
            ifaceNames[i] = ifaces[i].getName().replace('.', '/');
        }
        return new ClassInfo(internalName, superInternal, ifaceNames, clazz.getModifiers(), clazz.isInterface());
    }
}
