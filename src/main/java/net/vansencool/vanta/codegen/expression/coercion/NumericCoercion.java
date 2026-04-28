package net.vansencool.vanta.codegen.expression.coercion;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.expression.cast.PrimitiveConversionEmitter;
import net.vansencool.vanta.codegen.expression.util.cmp.ComparisonOpcodes;
import net.vansencool.vanta.codegen.expression.util.desc.DescriptorUtils;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Numeric promotion, widening, narrowing, boxing or unboxing coercions
 * applied between operand types and target slots, plus emission of typed
 * comparisons that reduce to a {@code 0}/{@code 1} stack value.
 */
public final class NumericCoercion {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator
     */
    public NumericCoercion(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Emits {@code I2B}/{@code I2S}/{@code I2C} after an int width arithmetic
     * op when the destination is a sub int primitive, so the stored value is
     * properly narrowed back into the field's type.
     *
     * @param mv   method visitor to emit into
     * @param desc destination primitive descriptor
     */
    public static void emitNarrowForSubIntDesc(@NotNull MethodVisitor mv, @NotNull String desc) {
        switch (desc) {
            case "B" -> mv.visitInsn(Opcodes.I2B);
            case "S" -> mv.visitInsn(Opcodes.I2S);
            case "C" -> mv.visitInsn(Opcodes.I2C);
        }
    }

    /**
     * @param left  left operand type
     * @param right right operand type
     * @return descriptor of the JLS binary numeric promotion result
     */
    public @NotNull String promote(@Nullable ResolvedType left, @Nullable ResolvedType right) {
        String l = left != null ? left.descriptor() : "I";
        String r = right != null ? right.descriptor() : "I";
        if ("D".equals(l) || "D".equals(r)) return "D";
        if ("F".equals(l) || "F".equals(r)) return "F";
        if ("J".equals(l) || "J".equals(r)) return "J";
        return "I";
    }

    /**
     * Emits the conversion sequence taking a value of source type to the
     * target descriptor. Handles primitive widening or narrowing and
     * unbox plus checkcast when the source is a wrapper.
     *
     * @param from   source type currently on the stack, or null when unknown
     * @param target target descriptor
     */
    public void widen(@Nullable ResolvedType from, @NotNull String target) {
        String src = from != null ? from.descriptor() : "I";
        if (src.equals(target)) return;
        MethodVisitor mv = exprGen.ctx().mv();
        if (!DescriptorUtils.isPrimitive(src) && DescriptorUtils.isPrimitive(target) && !"V".equals(target)) {
            String wrapper = wrapperInternalName(target);
            if (wrapper != null) exprGen.unboxingEmitter().withCast(mv, target, wrapper);
            return;
        }
        if (!DescriptorUtils.isPrimitive(src) || !DescriptorUtils.isPrimitive(target)) return;
        switch (src + target) {
            case "IJ" -> mv.visitInsn(Opcodes.I2L);
            case "IF" -> mv.visitInsn(Opcodes.I2F);
            case "ID" -> mv.visitInsn(Opcodes.I2D);
            case "JI" -> mv.visitInsn(Opcodes.L2I);
            case "JF" -> mv.visitInsn(Opcodes.L2F);
            case "JD" -> mv.visitInsn(Opcodes.L2D);
            case "FI" -> mv.visitInsn(Opcodes.F2I);
            case "FJ" -> mv.visitInsn(Opcodes.F2L);
            case "FD" -> mv.visitInsn(Opcodes.F2D);
            case "DI" -> mv.visitInsn(Opcodes.D2I);
            case "DJ" -> mv.visitInsn(Opcodes.D2L);
            case "DF" -> mv.visitInsn(Opcodes.D2F);
            case "BI", "SI", "CI" -> {
            }
        }
    }

    /**
     * @param desc wrapper descriptor (e.g. {@code Ljava/lang/Integer;})
     * @return matching primitive descriptor, or null when {@code desc} is not
     * a known wrapper
     */
    public @Nullable String primitiveForWrapperDesc(@NotNull String desc) {
        return switch (desc) {
            case "Ljava/lang/Integer;" -> "I";
            case "Ljava/lang/Long;" -> "J";
            case "Ljava/lang/Float;" -> "F";
            case "Ljava/lang/Double;" -> "D";
            case "Ljava/lang/Boolean;" -> "Z";
            case "Ljava/lang/Byte;" -> "B";
            case "Ljava/lang/Short;" -> "S";
            case "Ljava/lang/Character;" -> "C";
            default -> null;
        };
    }

    /**
     * @param primitiveDesc primitive descriptor
     * @return internal name of the matching wrapper class, or null when
     * {@code primitiveDesc} is not a primitive
     */
    public @Nullable String wrapperInternalName(@NotNull String primitiveDesc) {
        return switch (primitiveDesc) {
            case "I" -> "java/lang/Integer";
            case "J" -> "java/lang/Long";
            case "F" -> "java/lang/Float";
            case "D" -> "java/lang/Double";
            case "Z" -> "java/lang/Boolean";
            case "B" -> "java/lang/Byte";
            case "S" -> "java/lang/Short";
            case "C" -> "java/lang/Character";
            default -> null;
        };
    }

    /**
     * Reduces a numeric comparison to a 0/1 boolean on the stack, threading
     * the right {@code LCMP}/{@code FCMP*}/{@code DCMP*} prologue for non int
     * types.
     *
     * @param mv       method visitor to emit into
     * @param op       comparison operator
     * @param typeDesc promoted operand type descriptor
     */
    public void typedComparison(@NotNull MethodVisitor mv, @NotNull String op, @NotNull String typeDesc) {
        switch (typeDesc) {
            case "J" -> {
                mv.visitInsn(Opcodes.LCMP);
                intComparison(ComparisonOpcodes.zero(op));
            }
            case "F" -> {
                mv.visitInsn(("<".equals(op) || "<=".equals(op)) ? Opcodes.FCMPG : Opcodes.FCMPL);
                intComparison(ComparisonOpcodes.zero(op));
            }
            case "D" -> {
                mv.visitInsn(("<".equals(op) || "<=".equals(op)) ? Opcodes.DCMPG : Opcodes.DCMPL);
                intComparison(ComparisonOpcodes.zero(op));
            }
            default -> {
                switch (op) {
                    case "==" -> intComparison(Opcodes.IF_ICMPEQ);
                    case "!=" -> intComparison(Opcodes.IF_ICMPNE);
                    case "<" -> intComparison(Opcodes.IF_ICMPLT);
                    case "<=" -> intComparison(Opcodes.IF_ICMPLE);
                    case ">" -> intComparison(Opcodes.IF_ICMPGT);
                    case ">=" -> intComparison(Opcodes.IF_ICMPGE);
                }
            }
        }
    }

    /**
     * Reduces a two operand int comparison to a 0/1 boolean on the stack.
     *
     * @param ifOpcode {@code IF_ICMP*} opcode that branches when the
     *                 comparison is true
     */
    public void intComparison(int ifOpcode) {
        MethodVisitor mv = exprGen.ctx().mv();
        Label falseLabel = new Label();
        Label endLabel = new Label();
        mv.visitJumpInsn(ComparisonOpcodes.invertJump(ifOpcode), falseLabel);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);
        mv.visitLabel(falseLabel);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(endLabel);
    }

    /**
     * Coerces the value produced by {@code value} so it can be stored into a
     * slot of {@code target}: emits boxing, unboxing, primitive widening, or
     * downcast as required. No op when no coercion is needed or the value is
     * a null literal.
     *
     * @param target target storage type
     * @param value  source expression already evaluated onto the stack
     */
    public void adaptForStore(@NotNull ResolvedType target, @NotNull Expression value) {
        MethodContext ctx = exprGen.ctx();
        ResolvedType actual = ctx.typeInferrer().infer(value);
        if (actual == null) return;
        if (actual == ResolvedType.NULL) return;
        if (value instanceof LiteralExpression lit && lit.literalType() == TokenType.INT_LITERAL
                && target.isPrimitive() && (target.descriptor().equals("J") || target.descriptor().equals("F") || target.descriptor().equals("D"))) {
            return;
        }
        boolean descriptorsDiffer = !target.descriptor().equals(actual.descriptor());
        if (target.isPrimitive() && !actual.isPrimitive() && actual.internalName() != null) {
            if ("java/lang/Object".equals(actual.internalName())) {
                exprGen.unboxingEmitter().emit(ctx.mv(), target.descriptor());
            } else {
                exprGen.unboxingEmitter().withCast(ctx.mv(), target.descriptor(), actual.internalName());
            }
        } else if (target.isPrimitive() && actual.isPrimitive() && descriptorsDiffer) {
            if (intLiteralFitsTargetNarrow(value, target.descriptor())) return;
            PrimitiveConversionEmitter.emitPrimitiveWidening(ctx.mv(), actual.descriptor(), target.descriptor());
        } else if (!target.isPrimitive() && actual.isPrimitive()) {
            PrimitiveConversionEmitter.emitBoxing(ctx.mv(), actual);
        } else if (!target.isPrimitive() && !actual.isPrimitive() && target.internalName() != null
                && !"java/lang/Object".equals(target.internalName())
                && "java/lang/Object".equals(actual.internalName())) {
            ctx.mv().visitTypeInsn(Opcodes.CHECKCAST, target.internalName());
        } else if (!target.isPrimitive() && !actual.isPrimitive() && target.descriptor().startsWith("[")
                && descriptorsDiffer
                && (actual.descriptor().startsWith("[") || "java/lang/Object".equals(actual.internalName()))) {
            ctx.mv().visitTypeInsn(Opcodes.CHECKCAST, target.descriptor());
        }
    }

    /**
     * @param value      expression that is being stored
     * @param targetDesc destination sub int primitive descriptor
     * @return true when {@code value} is a compile time int literal already in
     * range for {@code targetDesc}, so no narrowing instruction is
     * needed before the store
     */
    private boolean intLiteralFitsTargetNarrow(@NotNull Expression value, @NotNull String targetDesc) {
        Integer v = exprGen.constantEvaluator().simpleIntValue(value);
        if (v == null) return false;
        int n = v;
        return switch (targetDesc) {
            case "B" -> n >= Byte.MIN_VALUE && n <= Byte.MAX_VALUE;
            case "S" -> n >= Short.MIN_VALUE && n <= Short.MAX_VALUE;
            case "C" -> n >= Character.MIN_VALUE && n <= Character.MAX_VALUE;
            default -> false;
        };
    }
}
