package net.vansencool.vanta.resolver.type;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a resolved type during compilation.
 * Knows its internal name, descriptor, and category for stack computation.
 */
public final class ResolvedType {

    public static final @NotNull ResolvedType VOID = new ResolvedType("void", "V", 0);
    public static final @NotNull ResolvedType BOOLEAN = new ResolvedType("boolean", "Z", 1);
    public static final @NotNull ResolvedType BYTE = new ResolvedType("byte", "B", 1);
    public static final @NotNull ResolvedType SHORT = new ResolvedType("short", "S", 1);
    public static final @NotNull ResolvedType CHAR = new ResolvedType("char", "C", 1);
    public static final @NotNull ResolvedType INT = new ResolvedType("int", "I", 1);
    public static final @NotNull ResolvedType LONG = new ResolvedType("long", "J", 2);
    public static final @NotNull ResolvedType FLOAT = new ResolvedType("float", "F", 1);
    public static final @NotNull ResolvedType DOUBLE = new ResolvedType("double", "D", 2);
    public static final @NotNull ResolvedType NULL = new ResolvedType("null", "Ljava/lang/Object;", 1);

    private static final @NotNull ConcurrentHashMap<String, ResolvedType> OBJECT_CACHE = new ConcurrentHashMap<>(256);

    private final @NotNull String name;
    private final @NotNull String descriptor;
    private final int stackSize;
    private final @Nullable String internalName;
    private @Nullable List<ResolvedType> typeArguments;

    /**
     * Creates a resolved type.
     *
     * @param name       the human readable name
     * @param descriptor the JVM descriptor
     * @param stackSize  the stack slot size (0 for void, 1 for most, 2 for long/double)
     */
    public ResolvedType(@NotNull String name, @NotNull String descriptor, int stackSize) {
        this.name = name;
        this.descriptor = descriptor;
        this.stackSize = stackSize;
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            this.internalName = descriptor.substring(1, descriptor.length() - 1);
        } else {
            this.internalName = null;
        }
    }

    /**
     * Creates a resolved object type from an internal name.
     *
     * @param internalName the internal name (e.g., "java/lang/String")
     * @return the resolved type
     */
    public static @NotNull ResolvedType ofObject(@NotNull String internalName) {
        ResolvedType cached = OBJECT_CACHE.get(internalName);
        if (cached != null) return cached;
        ResolvedType created = new ResolvedType(internalName.replace('/', '.'), "L" + internalName + ";", 1);
        ResolvedType prev = OBJECT_CACHE.putIfAbsent(internalName, created);
        return prev != null ? prev : created;
    }

    /**
     * Resolves a primitive type from its name.
     *
     * @return the resolved type, or null if not a primitive
     */
    public static @NotNull ResolvedType fromDescriptor(@NotNull String descriptor) {
        return switch (descriptor) {
            case "V" -> VOID;
            case "Z" -> BOOLEAN;
            case "B" -> BYTE;
            case "S" -> SHORT;
            case "C" -> CHAR;
            case "I" -> INT;
            case "J" -> LONG;
            case "F" -> FLOAT;
            case "D" -> DOUBLE;
            default -> {
                if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                    yield ofObject(descriptor.substring(1, descriptor.length() - 1));
                }
                if (descriptor.startsWith("[")) {
                    int dims = 0;
                    while (dims < descriptor.length() && descriptor.charAt(dims) == '[') dims++;
                    yield fromDescriptor(descriptor.substring(dims)).asArray(dims);
                }
                yield new ResolvedType(descriptor, descriptor, 1);
            }
        };
    }

    public static @Nullable ResolvedType fromPrimitiveName(@NotNull String name) {
        return switch (name) {
            case "void" -> VOID;
            case "boolean" -> BOOLEAN;
            case "byte" -> BYTE;
            case "short" -> SHORT;
            case "char" -> CHAR;
            case "int" -> INT;
            case "long" -> LONG;
            case "float" -> FLOAT;
            case "double" -> DOUBLE;
            default -> null;
        };
    }

    /**
     * Creates an array type from this element type.
     *
     * @param dimensions the number of array dimensions
     * @return the array type
     */
    public @NotNull ResolvedType asArray(int dimensions) {
        return new ResolvedType(name + "[]".repeat(dimensions), "[".repeat(dimensions) + descriptor, 1);
    }

    /**
     * @return the human readable name
     */
    public @NotNull String name() {
        return name;
    }

    /**
     * @return the JVM type descriptor
     */
    public @NotNull String descriptor() {
        return descriptor;
    }

    /**
     * @return the stack slot size
     */
    public int stackSize() {
        return stackSize;
    }

    /**
     * @return the internal name for object types, or null for primitives
     */
    public @Nullable String internalName() {
        return internalName;
    }

    /**
     * @return true if this is a primitive type
     */
    public boolean isPrimitive() {
        return descriptor.length() == 1 && !"V".equals(descriptor);
    }

    /**
     * @return true if this is void
     */
    public boolean isVoid() {
        return "V".equals(descriptor);
    }

    /**
     * @return true if this is an array type
     */
    public boolean isArray() {
        return descriptor.startsWith("[");
    }

    public @Nullable List<ResolvedType> typeArguments() {
        return typeArguments;
    }

    public @NotNull ResolvedType withTypeArguments(@Nullable List<ResolvedType> args) {
        ResolvedType copy = new ResolvedType(name, descriptor, stackSize);
        copy.typeArguments = args;
        return copy;
    }

    @Override
    public @NotNull String toString() {
        return name;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof ResolvedType that)) return false;
        return descriptor.equals(that.descriptor);
    }

    @Override
    public int hashCode() {
        return descriptor.hashCode();
    }
}
