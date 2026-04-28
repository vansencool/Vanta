package net.vansencool.vanta.codegen.expression.cast;

import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Pure static helpers for emitting primitive-to-wrapper boxing and
 * primitive-to-primitive widening/narrowing sequences. Split from the
 * unboxing helpers because unboxing needs access to the surrounding
 * {@code lastEmittedCheckcast} state machine for CHECKCAST elision, while
 * boxing and widening are stateless opcode tables.
 */
public final class PrimitiveConversionEmitter {

    private PrimitiveConversionEmitter() {
    }

    /**
     * Emits the {@code xxx.valueOf(prim)} call that boxes {@code type}'s
     * primitive form into its matching wrapper. No-op for reference types.
     *
     * @param mv   target method visitor
     * @param type resolved source type whose descriptor drives wrapper selection
     */
    public static void emitBoxing(@NotNull MethodVisitor mv, @NotNull ResolvedType type) {
        String desc = type.descriptor();
        switch (desc) {
            case "I" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            case "J" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            case "F" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            case "D" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
            case "Z" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            case "B" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
            case "C" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
            case "S" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
        }
    }

    /**
     * Emits the JVM conversion opcodes needed to convert the value currently
     * on the stack from {@code from}'s primitive type to {@code to}'s. Covers
     * widenings, narrowings, and the compound narrow-then-coerce cases
     * (e.g. {@code J} → {@code B} is {@code L2I} then {@code I2B}).
     *
     * @param mv   target method visitor
     * @param from single-char JVM primitive descriptor of the stack top
     * @param to   single-char JVM primitive descriptor to convert to
     */
    public static void emitPrimitiveWidening(@NotNull MethodVisitor mv, @NotNull String from, @NotNull String to) {
        if (from.equals(to)) return;
        switch (from + to) {
            case "IJ", "BJ", "SJ", "CJ" -> mv.visitInsn(Opcodes.I2L);
            case "IF", "BF", "SF", "CF" -> mv.visitInsn(Opcodes.I2F);
            case "ID", "BD", "SD", "CD" -> mv.visitInsn(Opcodes.I2D);
            case "JF" -> mv.visitInsn(Opcodes.L2F);
            case "JD" -> mv.visitInsn(Opcodes.L2D);
            case "FD" -> mv.visitInsn(Opcodes.F2D);
            case "IB", "SB", "CB" -> mv.visitInsn(Opcodes.I2B);
            case "IS", "CS" -> mv.visitInsn(Opcodes.I2S);
            case "IC", "SC", "BC" -> mv.visitInsn(Opcodes.I2C);
            case "JI", "JB", "JS", "JC" -> {
                mv.visitInsn(Opcodes.L2I);
                switch (to) {
                    case "B" -> mv.visitInsn(Opcodes.I2B);
                    case "S" -> mv.visitInsn(Opcodes.I2S);
                    case "C" -> mv.visitInsn(Opcodes.I2C);
                }
            }
            case "FI", "FB", "FS", "FC" -> {
                mv.visitInsn(Opcodes.F2I);
                switch (to) {
                    case "B" -> mv.visitInsn(Opcodes.I2B);
                    case "S" -> mv.visitInsn(Opcodes.I2S);
                    case "C" -> mv.visitInsn(Opcodes.I2C);
                }
            }
            case "FJ" -> mv.visitInsn(Opcodes.F2L);
            case "DI", "DB", "DS", "DC" -> {
                mv.visitInsn(Opcodes.D2I);
                switch (to) {
                    case "B" -> mv.visitInsn(Opcodes.I2B);
                    case "S" -> mv.visitInsn(Opcodes.I2S);
                    case "C" -> mv.visitInsn(Opcodes.I2C);
                }
            }
            case "DJ" -> mv.visitInsn(Opcodes.D2L);
            case "DF" -> mv.visitInsn(Opcodes.D2F);
        }
    }
}
