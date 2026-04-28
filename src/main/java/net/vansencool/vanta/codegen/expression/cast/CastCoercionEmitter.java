package net.vansencool.vanta.codegen.expression.cast;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.parser.ast.expression.CastExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits bytecode for {@code (T) expr} cast expressions. Covers primitive
 * narrowing/widening, unbox-then-cast for wrapper-to-primitive casts,
 * auto-boxing for primitive-to-wrapper casts, and reference {@code CHECKCAST}
 * emission with the short-circuit paths javac uses to elide redundant casts
 * when static types already match.
 */
public final class CastCoercionEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for operand emission
     *                and shared unbox/widen helpers
     */
    public CastCoercionEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Emits a cast expression. Folds literal-cast combinations to direct
     * constant pushes when possible so {@code (long) 1} lowers to
     * {@code LCONST_1} instead of {@code ICONST_1; I2L}.
     *
     * @param cast cast node
     */
    public void emit(@NotNull CastExpression cast) {
        MethodContext ctx = exprGen.ctx();
        ResolvedType targetType = ctx.typeResolver().resolve(cast.type());
        if (targetType.isPrimitive()) {
            Long folded = exprGen.constantEvaluator().longValue(cast.expression());
            if (folded != null) {
                MethodVisitor mvLit = ctx.mv();
                long lv = folded;
                switch (targetType.descriptor()) {
                    case "J" -> {
                        if (lv == 0L) mvLit.visitInsn(Opcodes.LCONST_0);
                        else if (lv == 1L) mvLit.visitInsn(Opcodes.LCONST_1);
                        else mvLit.visitLdcInsn(lv);
                        return;
                    }
                    case "I", "B", "S", "C" -> {
                        OpcodeUtils.pushInt(mvLit, (int) lv);
                        return;
                    }
                    case "F" -> {
                        mvLit.visitLdcInsn((float) lv);
                        return;
                    }
                    case "D" -> {
                        mvLit.visitLdcInsn((double) lv);
                        return;
                    }
                }
            }
        }
        boolean prevSuppress = exprGen.suppressGenericReturnCheckcast();
        if (!targetType.isPrimitive()) exprGen.suppressGenericReturnCheckcast(true);
        try {
            exprGen.generate(cast.expression());
        } finally {
            exprGen.suppressGenericReturnCheckcast(prevSuppress);
        }
        ResolvedType sourceType = ctx.typeInferrer().infer(cast.expression());

        if (targetType.isPrimitive()) {
            if (sourceType != null && !sourceType.isPrimitive() && sourceType.internalName() != null) {
                String wrapper = sourceType.internalName();
                if ("java/lang/Object".equals(wrapper) || "java/lang/Number".equals(wrapper)) {
                    String mapped = exprGen.numericCoercion().wrapperInternalName(targetType.descriptor());
                    if (mapped != null) wrapper = mapped;
                }
                exprGen.unboxingEmitter().withCast(ctx.mv(), targetType.descriptor(), wrapper);
            } else if (sourceType == null) {
                String wrapper = exprGen.numericCoercion().wrapperInternalName(targetType.descriptor());
                if (wrapper != null) {
                    exprGen.unboxingEmitter().withCast(ctx.mv(), targetType.descriptor(), wrapper);
                } else {
                    emitPrimitiveCast(null, targetType);
                }
            } else {
                emitPrimitiveCast(sourceType, targetType);
            }
        } else {
            boolean boxed = false;
            if (sourceType != null && sourceType.isPrimitive()) {
                PrimitiveConversionEmitter.emitBoxing(ctx.mv(), sourceType);
                boxed = true;
            }
            String internalName;
            if (targetType.internalName() != null) internalName = targetType.internalName();
            else if (targetType.descriptor().startsWith("[")) internalName = targetType.descriptor();
            else internalName = "java/lang/Object";
            if ("java/lang/Object".equals(internalName)) return;
            ResolvedType effectiveSource = sourceType;
            if (boxed) {
                String wrap = exprGen.numericCoercion().wrapperInternalName(sourceType.descriptor());
                if (wrap != null) effectiveSource = ResolvedType.ofObject(wrap);
            }
            boolean castFromRawObject = castInnerReturnsRawObject(cast.expression());
            if (castFromRawObject || effectiveSource == null || effectiveSource == ResolvedType.NULL || !isSourceAssignableToTarget(effectiveSource, internalName)) {
                ctx.mv().visitTypeInsn(Opcodes.CHECKCAST, internalName);
                exprGen.lastCheckcastType(internalName);
            }
        }
    }

    /**
     * True when {@code expr} is a method call whose erased return descriptor
     * is {@code Object}, so the stack top at cast time is {@code Object}
     * regardless of any generic inference. Used to force a {@code CHECKCAST}
     * on raw {@code List.get}/{@code Map.get}/{@code Iterator.next}-style
     * downcasts, matching javac.
     *
     * @param expr inner expression being cast
     * @return true when the inner call returns raw {@code Object}
     */
    private boolean castInnerReturnsRawObject(@NotNull Expression expr) {
        MethodContext ctx = exprGen.ctx();
        Expression cur = exprGen.unwrapParens(expr);
        if (!(cur instanceof MethodCallExpression call)) return false;
        if (call.target() == null) return false;
        ResolvedType recv = ctx.typeInferrer().infer(call.target());
        if (recv == null || recv.internalName() == null) return false;
        MethodResolver.ResolvedMethod m = ctx.methodResolver().resolveMethod(recv.internalName(), call.methodName(), call.arguments().size());
        if (m == null) return false;
        String ret = m.descriptor().substring(m.descriptor().indexOf(')') + 1);
        return "Ljava/lang/Object;".equals(ret);
    }

    /**
     * Decides whether {@code CHECKCAST} can be elided by checking if the
     * source type is already a subtype of the target.
     *
     * @param sourceType     inferred source type
     * @param targetInternal target class internal name
     * @return true when the cast is statically safe and can be skipped
     */
    private boolean isSourceAssignableToTarget(@NotNull ResolvedType sourceType, @NotNull String targetInternal) {
        MethodContext ctx = exprGen.ctx();
        if (targetInternal.equals(sourceType.internalName())) return true;
        if (sourceType.internalName() == null) return false;
        Class<?> src = ctx.methodResolver().classpathManager().loadClass(sourceType.internalName());
        Class<?> tgt = ctx.methodResolver().classpathManager().loadClass(targetInternal);
        if (src == null || tgt == null) return false;
        try {
            return tgt.isAssignableFrom(src);
        } catch (LinkageError e) {
            return false;
        }
    }

    /**
     * Emits primitive widening/narrowing conversion opcodes between {@code source}
     * and {@code target}. Sub-int source types normalise to {@code I} first so
     * the switch table stays compact.
     *
     * @param source source primitive type, or null when unknown
     * @param target target primitive type
     */
    private void emitPrimitiveCast(@Nullable ResolvedType source, @NotNull ResolvedType target) {
        MethodVisitor mv = exprGen.ctx().mv();
        String src = source != null && source.isPrimitive() ? source.descriptor() : "I";
        String tgt = target.descriptor();
        if (src.equals(tgt)) return;
        if ("B".equals(src) || "S".equals(src) || "C".equals(src) || "Z".equals(src)) src = "I";
        if (src.equals(tgt)) return;
        switch (src + tgt) {
            case "IB" -> mv.visitInsn(Opcodes.I2B);
            case "IS" -> mv.visitInsn(Opcodes.I2S);
            case "IC" -> mv.visitInsn(Opcodes.I2C);
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
        }
    }
}
