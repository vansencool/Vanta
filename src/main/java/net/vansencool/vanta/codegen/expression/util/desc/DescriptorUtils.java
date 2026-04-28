package net.vansencool.vanta.codegen.expression.util.desc;

import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

/**
 * Descriptor-level predicates and conversions: primitive-tag tests,
 * boxing/unboxing needs, {@code *RETURN} opcode selection, primitive-to-wrapper
 * owners, {@link ResolvedType} from {@link Class}, and switch key/label sort.
 */
public final class DescriptorUtils {

    private DescriptorUtils() {
    }

    /**
     * @param descriptor JVM type descriptor
     * @return true if the descriptor names a JVM primitive
     */
    public static boolean isPrimitive(@NotNull String descriptor) {
        return descriptor.length() == 1 && "IJFDZBCS".indexOf(descriptor.charAt(0)) >= 0;
    }

    /**
     * @param argDesc   descriptor of the value currently on the stack
     * @param paramDesc descriptor of the slot it's being placed into
     * @return true when primitive must be boxed to reach a reference slot
     */
    public static boolean needsBoxing(@NotNull String argDesc, @NotNull String paramDesc) {
        return isPrimitive(argDesc) && !isPrimitive(paramDesc);
    }

    /**
     * @param argDesc   descriptor of the value currently on the stack
     * @param paramDesc descriptor of the slot it's being placed into
     * @return true when a wrapper must be unboxed to reach a primitive slot
     */
    public static boolean needsUnboxing(@NotNull String argDesc, @NotNull String paramDesc) {
        return !isPrimitive(argDesc) && isPrimitive(paramDesc);
    }

    /**
     * @param type return type
     * @return the {@code *RETURN} opcode matching that type's stack category
     */
    public static int returnInsn(@NotNull Class<?> type) {
        if (type == void.class) return Opcodes.RETURN;
        if (type == int.class || type == boolean.class || type == byte.class || type == short.class || type == char.class)
            return Opcodes.IRETURN;
        if (type == long.class) return Opcodes.LRETURN;
        if (type == float.class) return Opcodes.FRETURN;
        if (type == double.class) return Opcodes.DRETURN;
        return Opcodes.ARETURN;
    }

    /**
     * @param name source-level primitive name
     * @return internal name of the matching wrapper class, or null if not a primitive
     */
    public static @Nullable String primitiveClassOwner(@NotNull String name) {
        return switch (name) {
            case "void" -> "java/lang/Void";
            case "boolean" -> "java/lang/Boolean";
            case "byte" -> "java/lang/Byte";
            case "char" -> "java/lang/Character";
            case "short" -> "java/lang/Short";
            case "int" -> "java/lang/Integer";
            case "long" -> "java/lang/Long";
            case "float" -> "java/lang/Float";
            case "double" -> "java/lang/Double";
            default -> null;
        };
    }

    /**
     * @param clazz reflection class
     * @return matching resolved type (primitive or object)
     */
    public static @NotNull ResolvedType resolvedTypeFromClass(@NotNull Class<?> clazz) {
        if (clazz == int.class) return ResolvedType.INT;
        if (clazz == long.class) return ResolvedType.LONG;
        if (clazz == float.class) return ResolvedType.FLOAT;
        if (clazz == double.class) return ResolvedType.DOUBLE;
        if (clazz == boolean.class) return ResolvedType.BOOLEAN;
        if (clazz == byte.class) return ResolvedType.BYTE;
        if (clazz == short.class) return ResolvedType.SHORT;
        if (clazz == char.class) return ResolvedType.CHAR;
        if (clazz == void.class) return ResolvedType.VOID;
        return ResolvedType.ofObject(clazz.getName().replace('.', '/'));
    }

    /**
     * Co-sorts keys and labels by ascending key for {@code LOOKUPSWITCH},
     * which requires sorted keys. Insertion sort is fine here since switch
     * case counts are tiny in practice.
     *
     * @param keys   switch keys
     * @param labels labels aligned with {@code keys}
     */
    public static void sortKeysAndLabels(int @NotNull [] keys, @NotNull Label[] labels) {
        for (int i = 0; i < keys.length - 1; i++) {
            for (int j = i + 1; j < keys.length; j++) {
                if (keys[i] > keys[j]) {
                    int tmpKey = keys[i];
                    keys[i] = keys[j];
                    keys[j] = tmpKey;
                    Label tmpLabel = labels[i];
                    labels[i] = labels[j];
                    labels[j] = tmpLabel;
                }
            }
        }
    }
}
