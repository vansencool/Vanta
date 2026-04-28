package net.vansencool.vanta.codegen.expression.util.cmp;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Mapping tables between Java comparison operators ({@code ==}, {@code !=},
 * {@code <}, {@code <=}, {@code >}, {@code >=}) and JVM conditional-branch
 * opcodes. Covers straight form, inverted form, zero-form (for comparisons
 * against constant zero), and flipped-operand forms.
 */
public final class ComparisonOpcodes {

    private ComparisonOpcodes() {
    }

    /**
     * Emits the long/float/double comparison instruction that reduces a
     * two-operand numeric compare to an integer for branching. No-op for
     * {@code I} since the JVM has {@code IF_ICMP*} directly.
     *
     * @param mv   method visitor to emit into
     * @param type numeric type descriptor ({@code J}, {@code F}, {@code D}, or {@code I})
     * @param op   source-level comparison operator driving NaN handling
     */
    public static void emitCmpToZero(@NotNull MethodVisitor mv, @NotNull String type, @NotNull String op) {
        switch (type) {
            case "J" -> mv.visitInsn(Opcodes.LCMP);
            case "F" -> mv.visitInsn((("<".equals(op) || "<=".equals(op))) ? Opcodes.FCMPG : Opcodes.FCMPL);
            case "D" -> mv.visitInsn((("<".equals(op) || "<=".equals(op))) ? Opcodes.DCMPG : Opcodes.DCMPL);
        }
    }

    /**
     * @param op Java comparison operator
     * @return {@code IF_ICMP*} opcode that branches when the comparison is <em>false</em>
     */
    public static int invert(@NotNull String op) {
        return switch (op) {
            case "==" -> Opcodes.IF_ICMPNE;
            case "!=" -> Opcodes.IF_ICMPEQ;
            case "<" -> Opcodes.IF_ICMPGE;
            case "<=" -> Opcodes.IF_ICMPGT;
            case ">" -> Opcodes.IF_ICMPLE;
            case ">=" -> Opcodes.IF_ICMPLT;
            default -> throw new IllegalArgumentException("Not a comparison: " + op);
        };
    }

    /**
     * @param op Java comparison operator
     * @return {@code IF*} zero-form opcode that branches when the comparison is <em>false</em>
     */
    public static int invertZero(@NotNull String op) {
        return switch (op) {
            case "==" -> Opcodes.IFNE;
            case "!=" -> Opcodes.IFEQ;
            case "<" -> Opcodes.IFGE;
            case "<=" -> Opcodes.IFGT;
            case ">" -> Opcodes.IFLE;
            case ">=" -> Opcodes.IFLT;
            default -> throw new IllegalArgumentException("Not a comparison: " + op);
        };
    }

    /**
     * @param op Java comparison operator
     * @return {@code IF*} zero-form opcode that branches when the comparison is <em>true</em>
     */
    public static int zero(@NotNull String op) {
        return switch (op) {
            case "==" -> Opcodes.IFEQ;
            case "!=" -> Opcodes.IFNE;
            case "<" -> Opcodes.IFLT;
            case "<=" -> Opcodes.IFLE;
            case ">" -> Opcodes.IFGT;
            case ">=" -> Opcodes.IFGE;
            default -> throw new IllegalArgumentException("Not a comparison: " + op);
        };
    }

    /**
     * Returns the operator that would result from swapping the operands on
     * either side of the comparison.
     *
     * @param op source-level comparison operator
     * @return flipped-operand form
     */
    public static @NotNull String flipSides(@NotNull String op) {
        return switch (op) {
            case "<" -> ">";
            case "<=" -> ">=";
            case ">" -> "<";
            case ">=" -> "<=";
            default -> op;
        };
    }

    /**
     * @param op Java comparison operator
     * @return zero-form branch-when-true opcode with operands treated as flipped
     */
    public static int flippedZero(@NotNull String op) {
        return switch (op) {
            case "==" -> Opcodes.IFEQ;
            case "!=" -> Opcodes.IFNE;
            case "<" -> Opcodes.IFGT;
            case "<=" -> Opcodes.IFGE;
            case ">" -> Opcodes.IFLT;
            case ">=" -> Opcodes.IFLE;
            default -> throw new IllegalArgumentException("Not a comparison: " + op);
        };
    }

    /**
     * @param op Java comparison operator
     * @return {@code IF_ICMP*} opcode that branches when the comparison is <em>true</em>
     */
    public static int branch(@NotNull String op) {
        return switch (op) {
            case "==" -> Opcodes.IF_ICMPEQ;
            case "!=" -> Opcodes.IF_ICMPNE;
            case "<" -> Opcodes.IF_ICMPLT;
            case "<=" -> Opcodes.IF_ICMPLE;
            case ">" -> Opcodes.IF_ICMPGT;
            case ">=" -> Opcodes.IF_ICMPGE;
            default -> throw new IllegalArgumentException("Not a comparison: " + op);
        };
    }

    /**
     * @param op JVM jump opcode
     * @return opcode that jumps on the logically-opposite condition
     */
    public static int invertJump(int op) {
        return switch (op) {
            case Opcodes.IFEQ -> Opcodes.IFNE;
            case Opcodes.IFNE -> Opcodes.IFEQ;
            case Opcodes.IFLT -> Opcodes.IFGE;
            case Opcodes.IFGE -> Opcodes.IFLT;
            case Opcodes.IFGT -> Opcodes.IFLE;
            case Opcodes.IFLE -> Opcodes.IFGT;
            case Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPNE;
            case Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPEQ;
            case Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGE;
            case Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLT;
            case Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLE;
            case Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGT;
            case Opcodes.IF_ACMPEQ -> Opcodes.IF_ACMPNE;
            case Opcodes.IF_ACMPNE -> Opcodes.IF_ACMPEQ;
            case Opcodes.IFNULL -> Opcodes.IFNONNULL;
            case Opcodes.IFNONNULL -> Opcodes.IFNULL;
            default -> throw new IllegalArgumentException("Not a jump opcode: " + op);
        };
    }
}
