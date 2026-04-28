package net.vansencool.vanta.codegen.expression.literal;

import net.vansencool.vanta.codegen.classes.literal.LiteralParser;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.exception.CodeGenException;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits bytecode for a single {@link LiteralExpression}. Handles every
 * literal kind with the most compact constant-push instruction available:
 * {@code ICONST_*}/{@code BIPUSH}/{@code SIPUSH}/{@code LDC} for ints,
 * {@code LCONST_*}/{@code LDC} for longs, {@code FCONST_*}/{@code DCONST_*}
 * for float/double, and widens int literals directly to long/float/double
 * when the expected type demands it so callers can skip a trailing
 * conversion instruction.
 */
public final class LiteralEmitter {

    private LiteralEmitter() {
    }

    /**
     * Emits the literal {@code lit} onto {@code mv}.
     *
     * @param mv  method visitor receiving the constant push
     * @param lit parsed literal token
     * @param exp expected type the value will flow into (drives int→long/
     *            float/double widening), or null to use the literal's raw type
     */
    public static void emit(@NotNull MethodVisitor mv, @NotNull LiteralExpression lit, @Nullable ResolvedType exp) {
        switch (lit.literalType()) {
            case INT_LITERAL -> {
                int value = LiteralParser.parseIntLiteral(lit.value());
                if (exp != null && exp.descriptor().equals("J")) {
                    if (value == 0) mv.visitInsn(Opcodes.LCONST_0);
                    else if (value == 1) mv.visitInsn(Opcodes.LCONST_1);
                    else mv.visitLdcInsn((long) value);
                } else if (exp != null && exp.descriptor().equals("F")) {
                    mv.visitLdcInsn((float) value);
                } else if (exp != null && exp.descriptor().equals("D")) {
                    mv.visitLdcInsn((double) value);
                } else {
                    OpcodeUtils.pushInt(mv, value);
                }
            }
            case LONG_LITERAL -> {
                long value = LiteralParser.parseLongLiteral(lit.value());
                if (value == 0L) mv.visitInsn(Opcodes.LCONST_0);
                else if (value == 1L) mv.visitInsn(Opcodes.LCONST_1);
                else mv.visitLdcInsn(value);
            }
            case FLOAT_LITERAL -> {
                float value = Float.parseFloat(lit.value().replace("_", "").replace("f", "").replace("F", ""));
                if (value == 0.0f) mv.visitInsn(Opcodes.FCONST_0);
                else if (value == 1.0f) mv.visitInsn(Opcodes.FCONST_1);
                else if (value == 2.0f) mv.visitInsn(Opcodes.FCONST_2);
                else mv.visitLdcInsn(value);
            }
            case DOUBLE_LITERAL -> {
                double value = Double.parseDouble(lit.value().replace("_", "").replace("d", "").replace("D", ""));
                if (value == 0.0) mv.visitInsn(Opcodes.DCONST_0);
                else if (value == 1.0) mv.visitInsn(Opcodes.DCONST_1);
                else mv.visitLdcInsn(value);
            }
            case CHAR_LITERAL -> {
                char c = LiteralParser.parseCharLiteral(lit.value());
                OpcodeUtils.pushInt(mv, c);
            }
            case STRING_LITERAL, TEXT_BLOCK -> mv.visitLdcInsn(LiteralParser.stripStringQuotes(lit.value()));
            case TRUE -> mv.visitInsn(Opcodes.ICONST_1);
            case FALSE -> mv.visitInsn(Opcodes.ICONST_0);
            case NULL -> mv.visitInsn(Opcodes.ACONST_NULL);
            default -> throw new CodeGenException("Unknown literal type: " + lit.literalType(), lit.line());
        }
    }
}
