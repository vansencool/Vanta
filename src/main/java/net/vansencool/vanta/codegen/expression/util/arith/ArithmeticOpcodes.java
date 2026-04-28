package net.vansencool.vanta.codegen.expression.util.arith;

import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Mapping from JVM numeric type descriptors to their arithmetic opcode
 * variants ({@code IADD}/{@code LADD}/{@code FADD}/{@code DADD}, etc.).
 */
public final class ArithmeticOpcodes {

    private ArithmeticOpcodes() {
    }

    /**
     * @param t numeric type descriptor
     * @return the {@code *ADD} opcode for that type; defaults to {@code IADD}
     */
    public static int add(@NotNull String t) {
        return switch (t) {
            case "J" -> Opcodes.LADD;
            case "F" -> Opcodes.FADD;
            case "D" -> Opcodes.DADD;
            default -> Opcodes.IADD;
        };
    }

    /**
     * @param t numeric type descriptor
     * @return the {@code *SUB} opcode for that type; defaults to {@code ISUB}
     */
    public static int sub(@NotNull String t) {
        return switch (t) {
            case "J" -> Opcodes.LSUB;
            case "F" -> Opcodes.FSUB;
            case "D" -> Opcodes.DSUB;
            default -> Opcodes.ISUB;
        };
    }

    /**
     * @param t numeric type descriptor
     * @return the {@code *MUL} opcode for that type; defaults to {@code IMUL}
     */
    public static int mul(@NotNull String t) {
        return switch (t) {
            case "J" -> Opcodes.LMUL;
            case "F" -> Opcodes.FMUL;
            case "D" -> Opcodes.DMUL;
            default -> Opcodes.IMUL;
        };
    }

    /**
     * @param t numeric type descriptor
     * @return the {@code *DIV} opcode for that type; defaults to {@code IDIV}
     */
    public static int div(@NotNull String t) {
        return switch (t) {
            case "J" -> Opcodes.LDIV;
            case "F" -> Opcodes.FDIV;
            case "D" -> Opcodes.DDIV;
            default -> Opcodes.IDIV;
        };
    }

    /**
     * @param t numeric type descriptor
     * @return the {@code *REM} opcode for that type; defaults to {@code IREM}
     */
    public static int rem(@NotNull String t) {
        return switch (t) {
            case "J" -> Opcodes.LREM;
            case "F" -> Opcodes.FREM;
            case "D" -> Opcodes.DREM;
            default -> Opcodes.IREM;
        };
    }

    /**
     * Picks add or sub based on the sign of {@code delta}. Used by compound
     * increment/decrement ({@code ++}/{@code --}/{@code +=}/{@code -=}) where
     * the same code path can handle both directions by flipping the opcode.
     *
     * @param desc  numeric type descriptor
     * @param delta signed delta; negative selects subtract
     * @return matching add or sub opcode
     */
    public static int addOrSub(@NotNull String desc, int delta) {
        boolean sub = delta < 0;
        return switch (desc) {
            case "J" -> sub ? Opcodes.LSUB : Opcodes.LADD;
            case "F" -> sub ? Opcodes.FSUB : Opcodes.FADD;
            case "D" -> sub ? Opcodes.DSUB : Opcodes.DADD;
            default -> sub ? Opcodes.ISUB : Opcodes.IADD;
        };
    }

    /**
     * Pushes the absolute value of {@code delta} onto the operand stack using
     * the widest constant form available for {@code desc}.
     *
     * @param mv    method visitor to emit into
     * @param desc  numeric type descriptor of the resulting constant
     * @param delta signed delta whose magnitude is pushed
     */
    public static void emitDeltaPush(@NotNull MethodVisitor mv, @NotNull String desc, int delta) {
        int abs = Math.abs(delta);
        switch (desc) {
            case "J" -> mv.visitLdcInsn((long) abs);
            case "F" -> mv.visitLdcInsn((float) abs);
            case "D" -> mv.visitLdcInsn((double) abs);
            default -> OpcodeUtils.pushInt(mv, abs);
        }
    }
}
