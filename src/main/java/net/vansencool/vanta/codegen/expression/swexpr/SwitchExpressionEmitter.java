package net.vansencool.vanta.codegen.expression.swexpr;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.StatementGenerator;
import net.vansencool.vanta.codegen.classes.literal.LiteralParser;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.expression.cast.PrimitiveConversionEmitter;
import net.vansencool.vanta.codegen.expression.util.desc.DescriptorUtils;
import net.vansencool.vanta.codegen.expression.util.literal.LiteralPredicates;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.SwitchExpression;
import net.vansencool.vanta.parser.ast.expression.TernaryExpression;
import net.vansencool.vanta.parser.ast.statement.ExpressionStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.statement.SwitchCase;
import net.vansencool.vanta.parser.ast.statement.ThrowStatement;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Emits bytecode for ternary expressions, switch expressions, and the
 * string-switch dispatch machinery they share. Keeps all branch-merging
 * logic together so the primitive-to-wrapper boxing rules used to merge
 * arms stay coherent across both forms.
 */
public final class SwitchExpressionEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for operand emission
     *                and shared int-switch emission
     */
    public SwitchExpressionEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Emits a ternary expression. The branches are merged into a single
     * stack-compatible type so the verifier sees matching types at the join
     * point, boxing primitive arms when the other arm is a reference or
     * {@code null}.
     *
     * @param ternary  ternary node
     * @param expected expected target type from the surrounding context, or null
     */
    public void emitTernary(@NotNull TernaryExpression ternary, @Nullable ResolvedType expected) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        Label elseLabel = new Label();
        Label endLabel = new Label();

        ResolvedType unified = expected != null ? expected : unifyTernaryArms(ternary);

        exprGen.conditionEmitter().jumpFalse(ternary.condition(), elseLabel);

        exprGen.generate(ternary.thenExpression(), unified);
        narrowBranchToExpected(ternary.thenExpression(), unified);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        mv.visitLabel(elseLabel);
        exprGen.generate(ternary.elseExpression(), unified);
        narrowBranchToExpected(ternary.elseExpression(), unified);

        mv.visitLabel(endLabel);
    }

    /**
     * Emits a switch expression. Handles int/char/short/byte selectors with a
     * direct {@code tableswitch}/{@code lookupswitch}, enum selectors via
     * {@code ordinal()} (or the synthetic {@code $SwitchMap} when the enum is
     * external), and string selectors via the hash-then-equals dispatch
     * pattern javac emits.
     *
     * @param switchExpr switch expression node
     * @param expected   expected target type from the surrounding context, or null
     */
    public void emitSwitch(@NotNull SwitchExpression switchExpr, @Nullable ResolvedType expected) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        Label endLabel = new Label();
        ctx.labelContext().pushLoop(endLabel, null, null);

        ResolvedType selType = ctx.typeInferrer().infer(switchExpr.selector());
        boolean isEnumSelector = selType != null && selType.internalName() != null;
        if (isEnumSelector) {
            Class<?> c = ctx.methodResolver().classpathManager().loadClass(selType.internalName());
            isEnumSelector = c != null && c.isEnum();
        }
        boolean isStringSelector = !isEnumSelector && selType != null && "java/lang/String".equals(selType.internalName());

        int stringSelectorSlot = -1;
        int stringOrdinalSlot = -1;
        if (isStringSelector) {
            exprGen.generate(switchExpr.selector());
            stringSelectorSlot = ctx.scope().declare("$switchStr" + switchExpr.line() + "$" + System.identityHashCode(switchExpr), ResolvedType.ofObject("java/lang/String")).index();
            mv.visitVarInsn(Opcodes.ASTORE, stringSelectorSlot);
            stringOrdinalSlot = ctx.scope().declare("$switchOrd" + switchExpr.line() + "$" + System.identityHashCode(switchExpr), ResolvedType.INT).index();
            mv.visitInsn(Opcodes.ICONST_M1);
            mv.visitVarInsn(Opcodes.ISTORE, stringOrdinalSlot);
            mv.visitVarInsn(Opcodes.ALOAD, stringSelectorSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false);
        } else {
            boolean useSwitchMap = isEnumSelector && ctx.classGenerator() != null
                    && ctx.classGenerator().hasExternalEnumSwitch(selType.internalName());
            if (useSwitchMap) {
                String syn = ctx.classGenerator().switchMapSyntheticName();
                String field = ctx.classGenerator().switchMapFieldFor(selType.internalName());
                mv.visitFieldInsn(Opcodes.GETSTATIC, syn, field, "[I");
                exprGen.generate(switchExpr.selector());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, selType.internalName(), "ordinal", "()I", false);
                mv.visitInsn(Opcodes.IALOAD);
            } else {
                exprGen.generate(switchExpr.selector());
                if (isEnumSelector) {
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, selType.internalName(), "ordinal", "()I", false);
                }
            }
        }

        int caseCount = 0;
        for (SwitchCase sc : switchExpr.cases()) {
            if (!sc.isDefault() && sc.labels() != null) caseCount += sc.labels().size();
        }

        boolean hasExplicitDefault = false;
        boolean hasReferenceArm = expected != null && !expected.isPrimitive();
        for (SwitchCase sc : switchExpr.cases()) {
            if (sc.isDefault()) hasExplicitDefault = true;
            if (sc.isArrow() && sc.statements().size() == 1 && sc.statements().get(0) instanceof ExpressionStatement es) {
                ResolvedType at = ctx.typeInferrer().infer(es.expression());
                if (at == null || !at.isPrimitive()) hasReferenceArm = true;
            } else if (!(sc.isArrow() && sc.statements().size() == 1 && sc.statements().get(0) instanceof ThrowStatement)) {
                hasReferenceArm = true;
            }
        }
        boolean needsBoxing = hasReferenceArm;
        if (needsBoxing) ctx.pushSwitchExpected(expected);
        else ctx.pushSwitchExpected(null);
        Label defaultLabel = new Label();
        Label[] caseLabels = new Label[caseCount];
        int[] keys = new int[caseCount];
        int idx = 0;

        for (SwitchCase sc : switchExpr.cases()) {
            if (sc.isDefault()) continue;
            if (sc.labels() != null) {
                for (Expression lab : sc.labels()) {
                    caseLabels[idx] = new Label();
                    keys[idx] = caseKey(lab, isEnumSelector, selType);
                    idx++;
                }
            }
        }

        Label[] sortedCaseLabels = caseLabels.clone();
        int[] sortedKeys = keys.clone();
        if (isStringSelector && caseCount > 0) {
            emitStringDispatch(mv, switchExpr, stringSelectorSlot, stringOrdinalSlot, sortedKeys, sortedCaseLabels, defaultLabel);
        } else if (caseCount > 0) {
            DescriptorUtils.sortKeysAndLabels(sortedKeys, sortedCaseLabels);
            exprGen.switchDispatch().emitIntSwitch(mv, sortedKeys, sortedCaseLabels, defaultLabel);
        } else {
            mv.visitJumpInsn(Opcodes.GOTO, defaultLabel);
        }

        StatementGenerator stmtGen = new StatementGenerator(ctx, exprGen);
        idx = 0;
        for (SwitchCase sc : switchExpr.cases()) {
            if (sc.isDefault()) {
                ctx.markReachable();
                mv.visitLabel(defaultLabel);
            } else if (sc.labels() != null) {
                for (int i = 0; i < sc.labels().size(); i++) {
                    ctx.markReachable();
                    mv.visitLabel(caseLabels[idx]);
                    idx++;
                }
            }

            if (sc.isArrow() && sc.statements().size() == 1 && sc.statements().get(0) instanceof ExpressionStatement exprStmt) {
                ResolvedType armTarget = needsBoxing ? expected : null;
                exprGen.generate(exprStmt.expression(), armTarget);
                if (needsBoxing) {
                    ResolvedType armType = ctx.typeInferrer().infer(exprStmt.expression());
                    if (armType != null && armType.isPrimitive()) PrimitiveConversionEmitter.emitBoxing(mv, armType);
                }
            } else {
                for (Statement stmt : sc.statements()) {
                    stmtGen.generate(stmt);
                }
            }

            if (ctx.isReachable()) {
                mv.visitJumpInsn(Opcodes.GOTO, endLabel);
                ctx.markUnreachable();
            }
        }

        if (!hasExplicitDefault) {
            ctx.markReachable();
            mv.visitLabel(defaultLabel);
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/IncompatibleClassChangeError");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IncompatibleClassChangeError", "<init>", "()V", false);
            mv.visitInsn(Opcodes.ATHROW);
            ctx.markUnreachable();
        }

        ctx.markReachable();
        mv.visitLabel(endLabel);
        ctx.labelContext().popLoop(null);
        ctx.popSwitchExpected();
    }

    /**
     * Merges the two ternary arms into a single stack-compatible type when
     * no external expected type is supplied. If one arm is primitive and the
     * other is a reference or {@code null}, returns the primitive's wrapper
     * so {@link #narrowBranchToExpected} boxes the primitive arm before the
     * join.
     *
     * @param ternary ternary expression being merged
     * @return merged type, or null when no coercion is needed
     */
    private @Nullable ResolvedType unifyTernaryArms(@NotNull TernaryExpression ternary) {
        MethodContext ctx = exprGen.ctx();
        ResolvedType t = ctx.typeInferrer().infer(ternary.thenExpression());
        ResolvedType e = ctx.typeInferrer().infer(ternary.elseExpression());
        boolean tRef = t != null && !t.isPrimitive();
        boolean eRef = e != null && !e.isPrimitive();
        boolean tNull = t == ResolvedType.NULL || LiteralPredicates.isNullLiteral(ternary.thenExpression());
        boolean eNull = e == ResolvedType.NULL || LiteralPredicates.isNullLiteral(ternary.elseExpression());
        if ((t != null && t.isPrimitive()) && (eRef || eNull)) {
            return ResolvedType.ofObject(Objects.requireNonNull(exprGen.numericCoercion().wrapperInternalName(t.descriptor())));
        }
        if ((e != null && e.isPrimitive()) && (tRef || tNull)) {
            return ResolvedType.ofObject(Objects.requireNonNull(exprGen.numericCoercion().wrapperInternalName(e.descriptor())));
        }
        return null;
    }

    /**
     * Coerces one ternary/switch arm's stack top to match the merged expected
     * type: boxes primitives, or inserts a {@code CHECKCAST} when the branch
     * widens to {@code Object} but the merge narrows back to a specific type.
     *
     * @param branch   branch expression being narrowed
     * @param expected merged expected type, or null
     */
    private void narrowBranchToExpected(@NotNull Expression branch, @Nullable ResolvedType expected) {
        if (expected == null) return;
        MethodContext ctx = exprGen.ctx();
        ResolvedType branchType = ctx.typeInferrer().infer(branch);
        if (branchType == null || branchType == ResolvedType.NULL) return;
        if (!expected.isPrimitive() && branchType.isPrimitive()) {
            PrimitiveConversionEmitter.emitBoxing(ctx.mv(), branchType);
            return;
        }
        if (expected.internalName() == null || expected.isPrimitive()) return;
        if ("java/lang/Object".equals(expected.internalName())) return;
        if (expected.internalName().equals(branchType.internalName())) return;
        if ("java/lang/Object".equals(branchType.internalName())) {
            ctx.mv().visitTypeInsn(Opcodes.CHECKCAST, expected.internalName());
        }
    }

    /**
     * Resolves a switch case label to its integer dispatch key. Enum cases
     * use the external-switch-map index when the enum is externally compiled
     * or fall back to the raw {@code ordinal()}; int/char literals take their
     * parsed value; string literals use {@code hashCode()} for the
     * hash-then-equals dispatch; static-final constants fold to their int
     * value.
     *
     * @param label   case label expression
     * @param enumSel true when the switch selector is an enum type
     * @param selType selector type, used for enum-ordinal lookup
     * @return integer dispatch key (defaults to 0 when no match)
     */
    private int caseKey(@NotNull Expression label, boolean enumSel, @Nullable ResolvedType selType) {
        MethodContext ctx = exprGen.ctx();
        if (enumSel && selType != null && selType.internalName() != null && label instanceof NameExpression ne) {
            String selInternal = selType.internalName();
            if (ctx.classGenerator() != null && ctx.classGenerator().hasExternalEnumSwitch(selInternal)) {
                Integer mapped = ctx.classGenerator().externalEnumSwitchKey(selInternal, ne.name());
                if (mapped != null) return mapped;
            }
            Integer ord = exprGen.constantEvaluator().enumOrdinalFor(selInternal, ne.name());
            if (ord != null) return ord;
        }
        if (label instanceof LiteralExpression lit) {
            if (lit.literalType() == TokenType.INT_LITERAL) return Integer.parseInt(lit.value().replace("_", ""));
            if (lit.literalType() == TokenType.CHAR_LITERAL) return LiteralParser.parseCharLiteral(lit.value());
            if (lit.literalType() == TokenType.STRING_LITERAL) {
                String v = lit.value();
                if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                return v.hashCode();
            }
        }
        Integer folded = exprGen.constantEvaluator().simpleIntValue(label);
        if (folded != null) return folded;
        Object fc = exprGen.constantEvaluator().staticFinalValue(label);
        if (fc instanceof Integer i) return i;
        if (fc instanceof Character c) return c;
        if (fc instanceof Short s) return s;
        if (fc instanceof Byte b) return b;
        return 0;
    }

    /**
     * Emits the string-switch dispatch for an expression-form switch. Collects
     * every literal case value, then delegates to the shared
     * {@code hashCode}/{@code equals} dispatch on {@link ExpressionGenerator}.
     *
     * @param mv                 target method visitor
     * @param switchExpr         switch expression node (source of literal values)
     * @param stringSelectorSlot local slot holding the selector string
     * @param stringOrdinalSlot  local slot scratch-registering the matched ordinal
     * @param keys               hash keys (one per case label)
     * @param caseLabels         labels for each case body
     * @param defaultLabel       label to fall through to on no match
     */
    private void emitStringDispatch(@NotNull MethodVisitor mv, @NotNull SwitchExpression switchExpr,
                                    int stringSelectorSlot, int stringOrdinalSlot,
                                    int[] keys, @NotNull Label[] caseLabels,
                                    @NotNull Label defaultLabel) {
        List<String> caseStrings = new ArrayList<>();
        for (SwitchCase sc : switchExpr.cases()) {
            if (sc.isDefault() || sc.labels() == null) continue;
            for (Expression lab : sc.labels()) caseStrings.add(exprGen.switchDispatch().stringLiteralValue(lab));
        }
        exprGen.switchDispatch().emitStringDispatch(mv, stringSelectorSlot, stringOrdinalSlot, caseStrings, keys, caseLabels, defaultLabel);
    }
}
