package net.vansencool.vanta.codegen.classes.opcode;

import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Pure static helpers that map {@link ResolvedType}s and JVM type descriptors
 * to their corresponding JVM opcodes. Shared between class-level and
 * expression-level code generators so bytecode emission never hand-codes
 * opcode tables.
 */
public final class OpcodeUtils {

    private OpcodeUtils() {
    }

    /**
     * Picks the load opcode matching the JVM type category of {@code type},
     * so a value of that type can be read from a local slot onto the stack.
     *
     * @param type resolved type of the local slot being read
     * @return one of {@code ILOAD}/{@code LLOAD}/{@code FLOAD}/{@code DLOAD}/{@code ALOAD}
     */
    public static int loadOpcode(@NotNull ResolvedType type) {
        if (type.equals(ResolvedType.INT) || type.equals(ResolvedType.BOOLEAN) || type.equals(ResolvedType.BYTE) || type.equals(ResolvedType.SHORT) || type.equals(ResolvedType.CHAR))
            return Opcodes.ILOAD;
        if (type.equals(ResolvedType.LONG)) return Opcodes.LLOAD;
        if (type.equals(ResolvedType.FLOAT)) return Opcodes.FLOAD;
        if (type.equals(ResolvedType.DOUBLE)) return Opcodes.DLOAD;
        return Opcodes.ALOAD;
    }

    /**
     * Inverse of {@link #loadOpcode(ResolvedType)}: picks the store opcode
     * that pops a value of {@code type} off the stack into a local slot.
     *
     * @param type resolved type of the value being stored
     * @return one of {@code ISTORE}/{@code LSTORE}/{@code FSTORE}/{@code DSTORE}/{@code ASTORE}
     */
    public static int storeOpcode(@NotNull ResolvedType type) {
        if (type.equals(ResolvedType.INT) || type.equals(ResolvedType.BOOLEAN) || type.equals(ResolvedType.BYTE) || type.equals(ResolvedType.SHORT) || type.equals(ResolvedType.CHAR))
            return Opcodes.ISTORE;
        if (type.equals(ResolvedType.LONG)) return Opcodes.LSTORE;
        if (type.equals(ResolvedType.FLOAT)) return Opcodes.FSTORE;
        if (type.equals(ResolvedType.DOUBLE)) return Opcodes.DSTORE;
        return Opcodes.ASTORE;
    }

    /**
     * Array-element store opcode for an array whose component type is
     * {@code elementType}.
     *
     * @param elementType resolved type of one array element
     * @return matching {@code xASTORE} opcode (e.g. {@code IASTORE} for {@code int[]})
     */
    public static int arrayStoreOpcode(@NotNull ResolvedType elementType) {
        if (elementType.equals(ResolvedType.INT)) return Opcodes.IASTORE;
        if (elementType.equals(ResolvedType.LONG)) return Opcodes.LASTORE;
        if (elementType.equals(ResolvedType.FLOAT)) return Opcodes.FASTORE;
        if (elementType.equals(ResolvedType.DOUBLE)) return Opcodes.DASTORE;
        if (elementType.equals(ResolvedType.BYTE) || elementType.equals(ResolvedType.BOOLEAN)) return Opcodes.BASTORE;
        if (elementType.equals(ResolvedType.SHORT)) return Opcodes.SASTORE;
        if (elementType.equals(ResolvedType.CHAR)) return Opcodes.CASTORE;
        return Opcodes.AASTORE;
    }

    /**
     * Array-element load opcode mirroring
     * {@link #arrayStoreOpcode(ResolvedType)} for reads.
     *
     * @param elementType resolved type of one array element
     * @return matching {@code xALOAD} opcode (e.g. {@code IALOAD} for {@code int[]})
     */
    public static int arrayLoadOpcode(@NotNull ResolvedType elementType) {
        if (elementType.equals(ResolvedType.INT)) return Opcodes.IALOAD;
        if (elementType.equals(ResolvedType.LONG)) return Opcodes.LALOAD;
        if (elementType.equals(ResolvedType.FLOAT)) return Opcodes.FALOAD;
        if (elementType.equals(ResolvedType.DOUBLE)) return Opcodes.DALOAD;
        if (elementType.equals(ResolvedType.BYTE) || elementType.equals(ResolvedType.BOOLEAN)) return Opcodes.BALOAD;
        if (elementType.equals(ResolvedType.SHORT)) return Opcodes.SALOAD;
        if (elementType.equals(ResolvedType.CHAR)) return Opcodes.CALOAD;
        return Opcodes.AALOAD;
    }

    /**
     * Emits the shortest possible push for {@code value}, preferring
     * {@code ICONST_*} then {@code BIPUSH}/{@code SIPUSH} before falling back
     * to {@code LDC}, matching javac's compact encoding.
     *
     * @param mv    method visitor receiving the push instruction
     * @param value integer constant to push onto the operand stack
     */
    public static void pushInt(@NotNull MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    /**
     * Return opcode for a method whose declared return type is {@code type}.
     *
     * @param type resolved return type (including {@code void})
     * @return one of {@code IRETURN}/{@code LRETURN}/{@code FRETURN}/{@code DRETURN}/{@code RETURN}/{@code ARETURN}
     */
    public static int returnOpcode(@NotNull ResolvedType type) {
        if (type.equals(ResolvedType.INT) || type.equals(ResolvedType.BOOLEAN) || type.equals(ResolvedType.BYTE) || type.equals(ResolvedType.SHORT) || type.equals(ResolvedType.CHAR))
            return Opcodes.IRETURN;
        if (type.equals(ResolvedType.LONG)) return Opcodes.LRETURN;
        if (type.equals(ResolvedType.FLOAT)) return Opcodes.FRETURN;
        if (type.equals(ResolvedType.DOUBLE)) return Opcodes.DRETURN;
        if (type.isVoid()) return Opcodes.RETURN;
        return Opcodes.ARETURN;
    }

    /**
     * Descriptor-based counterpart to {@link #returnOpcode(ResolvedType)} used
     * where only the JVM descriptor is in hand (bridge emission, ASM-resolved
     * methods).
     *
     * @param d single-type JVM descriptor (e.g. {@code "I"}, {@code "V"}, {@code "Ljava/lang/String;"})
     * @return matching {@code xRETURN} opcode
     */
    public static int returnOpcodeForDescriptor(@NotNull String d) {
        return switch (d) {
            case "I", "Z", "B", "S", "C" -> Opcodes.IRETURN;
            case "J" -> Opcodes.LRETURN;
            case "F" -> Opcodes.FRETURN;
            case "D" -> Opcodes.DRETURN;
            case "V" -> Opcodes.RETURN;
            default -> Opcodes.ARETURN;
        };
    }

    /**
     * Descriptor-based counterpart to {@link #loadOpcode(ResolvedType)}.
     *
     * @param d single-type JVM descriptor of the local slot being read
     * @return matching {@code xLOAD} opcode
     */
    public static int loadOpcodeForDescriptor(@NotNull String d) {
        return switch (d) {
            case "I", "Z", "B", "S", "C" -> Opcodes.ILOAD;
            case "J" -> Opcodes.LLOAD;
            case "F" -> Opcodes.FLOAD;
            case "D" -> Opcodes.DLOAD;
            default -> Opcodes.ALOAD;
        };
    }

    /**
     * Returns 2 for {@code long}/{@code double} descriptors (category-2 types)
     * and 1 for everything else, matching JVM local-slot accounting.
     *
     * @param d single-type JVM descriptor
     * @return number of local slots the value occupies
     */
    public static int descriptorStackSize(@NotNull String d) {
        return d.equals("J") || d.equals("D") ? 2 : 1;
    }
}
