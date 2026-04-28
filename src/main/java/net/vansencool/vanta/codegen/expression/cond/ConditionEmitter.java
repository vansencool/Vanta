package net.vansencool.vanta.codegen.expression.cond;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.expression.util.cmp.ComparisonOpcodes;
import net.vansencool.vanta.codegen.expression.util.literal.LiteralPredicates;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.InstanceofExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.ParenExpression;
import net.vansencool.vanta.parser.ast.expression.TernaryExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits bytecode for boolean conditions that directly lower to control-flow
 * jumps, avoiding the intermediate {@code ICONST_0}/{@code ICONST_1}
 * materialisation that a naïve pipeline would emit. Also handles
 * {@code instanceof} with pattern bindings, both as a standalone boolean
 * expression ({@link #emitInstanceof}) and as a jumping condition
 * ({@link #emitInstanceofCondition}, {@link #emitInstanceofConditionTrue}).
 */
public final class ConditionEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for operand emission
     *                and numeric-promotion helpers
     */
    public ConditionEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Emits the condition with a direct jump to {@code falseLabel} on false.
     *
     * @param condition  boolean condition expression
     * @param falseLabel target when condition evaluates false
     */
    public void jumpFalse(@NotNull Expression condition, @NotNull Label falseLabel) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        if (condition instanceof BinaryExpression binary) {
            String op = binary.operator();
            switch (op) {
                case "==", "!=", "<", "<=", ">", ">=" -> {
                    if (("==".equals(op) || "!=".equals(op)) && (LiteralPredicates.isNullLiteral(binary.left()) || LiteralPredicates.isNullLiteral(binary.right()))) {
                        exprGen.generate(LiteralPredicates.isNullLiteral(binary.left()) ? binary.right() : binary.left());
                        mv.visitJumpInsn("==".equals(op) ? Opcodes.IFNONNULL : Opcodes.IFNULL, falseLabel);
                    } else if (("==".equals(op) || "!=".equals(op)) && exprGen.isReferenceType(binary.left()) && exprGen.isReferenceType(binary.right())) {
                        exprGen.generate(binary.left());
                        exprGen.generate(binary.right());
                        mv.visitJumpInsn("==".equals(op) ? Opcodes.IF_ACMPNE : Opcodes.IF_ACMPEQ, falseLabel);
                    } else {
                        ResolvedType lt = ctx.typeInferrer().infer(binary.left());
                        ResolvedType rt = ctx.typeInferrer().infer(binary.right());
                        String promoted = exprGen.numericCoercion().promote(lt, rt);
                        if (!"I".equals(promoted)) {
                            ResolvedType pt = ResolvedType.fromDescriptor(promoted);
                            boolean lInPlace = LiteralPredicates.isIntLiteral(binary.left());
                            boolean rInPlace = LiteralPredicates.isIntLiteral(binary.right());
                            exprGen.generate(binary.left(), pt);
                            if (!lInPlace) exprGen.numericCoercion().widen(lt, promoted);
                            exprGen.generate(binary.right(), pt);
                            if (!rInPlace) exprGen.numericCoercion().widen(rt, promoted);
                            ComparisonOpcodes.emitCmpToZero(mv, promoted, op);
                            mv.visitJumpInsn(ComparisonOpcodes.invertZero(op), falseLabel);
                        } else if (exprGen.isIntZeroOrConst(binary.right())) {
                            exprGen.generate(binary.left());
                            exprGen.numericCoercion().widen(lt, "I");
                            mv.visitJumpInsn(ComparisonOpcodes.invertZero(op), falseLabel);
                        } else if (exprGen.isIntZeroOrConst(binary.left())) {
                            exprGen.generate(binary.right());
                            exprGen.numericCoercion().widen(rt, "I");
                            mv.visitJumpInsn(ComparisonOpcodes.invertZero(ComparisonOpcodes.flipSides(op)), falseLabel);
                        } else {
                            exprGen.generate(binary.left());
                            exprGen.numericCoercion().widen(lt, "I");
                            exprGen.generate(binary.right());
                            exprGen.numericCoercion().widen(rt, "I");
                            mv.visitJumpInsn(ComparisonOpcodes.invert(op), falseLabel);
                        }
                    }
                    return;
                }
                case "&&" -> {
                    jumpFalse(binary.left(), falseLabel);
                    jumpFalse(binary.right(), falseLabel);
                    return;
                }
                case "||" -> {
                    Label trueSkip = new Label();
                    jumpTrue(binary.left(), trueSkip);
                    jumpFalse(binary.right(), falseLabel);
                    mv.visitLabel(trueSkip);
                    return;
                }
                default -> {
                }
            }
        } else if (condition instanceof UnaryExpression unary && "!".equals(unary.operator()) && unary.isPrefix()) {
            jumpTrue(unary.operand(), falseLabel);
            return;
        } else if (condition instanceof ParenExpression paren) {
            jumpFalse(paren.expression(), falseLabel);
            return;
        } else if (condition instanceof InstanceofExpression instOf) {
            emitInstanceofCondition(instOf, falseLabel);
            return;
        } else if (condition instanceof TernaryExpression tern) {
            Label elseArm = new Label();
            Label end = new Label();
            jumpFalse(tern.condition(), elseArm);
            jumpFalse(tern.thenExpression(), falseLabel);
            mv.visitJumpInsn(Opcodes.GOTO, end);
            mv.visitLabel(elseArm);
            jumpFalse(tern.elseExpression(), falseLabel);
            mv.visitLabel(end);
            return;
        }
        exprGen.generate(condition);
        ResolvedType condType = ctx.typeInferrer().infer(condition);
        if (condType != null && "Ljava/lang/Boolean;".equals(condType.descriptor())) {
            exprGen.unboxingEmitter().emit(mv, "Z", "java/lang/Boolean");
        }
        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);
    }

    /**
     * Emits the condition with a direct jump to {@code trueLabel} on true.
     *
     * @param condition boolean condition expression
     * @param trueLabel target when condition evaluates true
     */
    public void jumpTrue(@NotNull Expression condition, @NotNull Label trueLabel) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        if (condition instanceof BinaryExpression binary) {
            String op = binary.operator();
            switch (op) {
                case "==", "!=", "<", "<=", ">", ">=" -> {
                    if (("==".equals(op) || "!=".equals(op)) && (LiteralPredicates.isNullLiteral(binary.left()) || LiteralPredicates.isNullLiteral(binary.right()))) {
                        exprGen.generate(LiteralPredicates.isNullLiteral(binary.left()) ? binary.right() : binary.left());
                        mv.visitJumpInsn("==".equals(op) ? Opcodes.IFNULL : Opcodes.IFNONNULL, trueLabel);
                    } else if (("==".equals(op) || "!=".equals(op)) && exprGen.isReferenceType(binary.left()) && exprGen.isReferenceType(binary.right())) {
                        exprGen.generate(binary.left());
                        exprGen.generate(binary.right());
                        mv.visitJumpInsn("==".equals(op) ? Opcodes.IF_ACMPEQ : Opcodes.IF_ACMPNE, trueLabel);
                    } else {
                        ResolvedType lt = ctx.typeInferrer().infer(binary.left());
                        ResolvedType rt = ctx.typeInferrer().infer(binary.right());
                        String promoted = exprGen.numericCoercion().promote(lt, rt);
                        if (!"I".equals(promoted)) {
                            ResolvedType pt = ResolvedType.fromDescriptor(promoted);
                            boolean lInPlace = LiteralPredicates.isIntLiteral(binary.left());
                            boolean rInPlace = LiteralPredicates.isIntLiteral(binary.right());
                            exprGen.generate(binary.left(), pt);
                            if (!lInPlace) exprGen.numericCoercion().widen(lt, promoted);
                            exprGen.generate(binary.right(), pt);
                            if (!rInPlace) exprGen.numericCoercion().widen(rt, promoted);
                            ComparisonOpcodes.emitCmpToZero(mv, promoted, op);
                            mv.visitJumpInsn(ComparisonOpcodes.zero(op), trueLabel);
                        } else if (exprGen.isIntZeroOrConst(binary.right())) {
                            exprGen.generate(binary.left());
                            exprGen.numericCoercion().widen(lt, "I");
                            mv.visitJumpInsn(ComparisonOpcodes.zero(op), trueLabel);
                        } else if (exprGen.isIntZeroOrConst(binary.left())) {
                            exprGen.generate(binary.right());
                            exprGen.numericCoercion().widen(rt, "I");
                            mv.visitJumpInsn(ComparisonOpcodes.zero(ComparisonOpcodes.flipSides(op)), trueLabel);
                        } else {
                            exprGen.generate(binary.left());
                            exprGen.numericCoercion().widen(lt, "I");
                            exprGen.generate(binary.right());
                            exprGen.numericCoercion().widen(rt, "I");
                            mv.visitJumpInsn(ComparisonOpcodes.branch(op), trueLabel);
                        }
                    }
                    return;
                }
                case "&&" -> {
                    Label checkRight = new Label();
                    jumpFalse(binary.left(), checkRight);
                    jumpTrue(binary.right(), trueLabel);
                    mv.visitLabel(checkRight);
                    return;
                }
                case "||" -> {
                    jumpTrue(binary.left(), trueLabel);
                    jumpTrue(binary.right(), trueLabel);
                    return;
                }
                default -> {
                }
            }
        } else if (condition instanceof UnaryExpression unary && "!".equals(unary.operator()) && unary.isPrefix()) {
            jumpFalse(unary.operand(), trueLabel);
            return;
        } else if (condition instanceof ParenExpression paren) {
            jumpTrue(paren.expression(), trueLabel);
            return;
        } else if (condition instanceof InstanceofExpression instOf) {
            emitInstanceofConditionTrue(instOf, trueLabel);
            return;
        } else if (condition instanceof TernaryExpression tern) {
            Label elseArm = new Label();
            Label end = new Label();
            jumpFalse(tern.condition(), elseArm);
            jumpTrue(tern.thenExpression(), trueLabel);
            mv.visitJumpInsn(Opcodes.GOTO, end);
            mv.visitLabel(elseArm);
            jumpTrue(tern.elseExpression(), trueLabel);
            mv.visitLabel(end);
            return;
        }
        exprGen.generate(condition);
        ResolvedType condType = ctx.typeInferrer().infer(condition);
        if (condType != null && "Ljava/lang/Boolean;".equals(condType.descriptor())) {
            exprGen.unboxingEmitter().withCast(mv, "Z", "java/lang/Boolean");
        }
        mv.visitJumpInsn(Opcodes.IFNE, trueLabel);
    }

    /**
     * Emits a standalone {@code instanceof} that leaves a boolean-as-int on
     * the stack, also binding the pattern variable when one is declared.
     *
     * @param instanceOf instanceof expression
     */
    public void emitInstanceof(@NotNull InstanceofExpression instanceOf) {
        MethodContext ctx = exprGen.ctx();
        exprGen.generate(instanceOf.expression());
        String internalName = ctx.typeResolver().resolveInternalName(instanceOf.type());
        ctx.mv().visitTypeInsn(Opcodes.INSTANCEOF, internalName);

        if (instanceOf.patternVariable() != null) {
            MethodVisitor mv = ctx.mv();
            mv.visitInsn(Opcodes.DUP);
            Label falseLabel = new Label();
            Label endLabel = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);

            exprGen.generate(instanceOf.expression());
            mv.visitTypeInsn(Opcodes.CHECKCAST, internalName);
            ResolvedType patternType = ResolvedType.ofObject(internalName);
            LocalVariable patternVar = ctx.declareLocal(instanceOf.patternVariable(), patternType);
            mv.visitVarInsn(Opcodes.ASTORE, patternVar.index());

            mv.visitLabel(falseLabel);
            mv.visitLabel(endLabel);
        }
    }

    /**
     * Emits {@code instanceof} as a jumping condition that branches to
     * {@code jumpFalse} on mismatch. When a pattern variable is declared,
     * also binds it on the success path.
     *
     * @param instanceOf instanceof expression
     * @param jumpFalse  target when the operand isn't an instance
     */
    public void emitInstanceofCondition(@NotNull InstanceofExpression instanceOf, @NotNull Label jumpFalse) {
        MethodContext ctx = exprGen.ctx();
        if (instanceOf.patternVariable() == null) {
            emitInstanceof(instanceOf);
            ctx.mv().visitJumpInsn(Opcodes.IFEQ, jumpFalse);
            return;
        }
        String internalName = ctx.typeResolver().resolveInternalName(instanceOf.type());
        MethodVisitor mv = ctx.mv();
        ResolvedType patternType = ResolvedType.ofObject(internalName);
        LocalVariable patternVar = ctx.declareLocal(instanceOf.patternVariable(), patternType);
        Expression expr = exprGen.unwrapParens(instanceOf.expression());
        if (expr instanceof NameExpression name && ctx.scope().resolve(name.name()) != null) {
            exprGen.generate(instanceOf.expression());
            mv.visitTypeInsn(Opcodes.INSTANCEOF, internalName);
            mv.visitJumpInsn(Opcodes.IFEQ, jumpFalse);
            exprGen.generate(instanceOf.expression());
            mv.visitTypeInsn(Opcodes.CHECKCAST, internalName);
            mv.visitVarInsn(Opcodes.ASTORE, patternVar.index());
            return;
        }
        exprGen.generate(instanceOf.expression());
        ResolvedType exprType = ctx.typeInferrer().infer(instanceOf.expression());
        ResolvedType tempType = exprType != null ? exprType : ResolvedType.ofObject("java/lang/Object");
        LocalVariable tempVar = ctx.declareLocal("$pattmp" + ctx.scope().nextLocalIndex(), tempType);
        mv.visitVarInsn(Opcodes.ASTORE, tempVar.index());
        mv.visitVarInsn(Opcodes.ALOAD, tempVar.index());
        mv.visitTypeInsn(Opcodes.INSTANCEOF, internalName);
        mv.visitJumpInsn(Opcodes.IFEQ, jumpFalse);
        mv.visitVarInsn(Opcodes.ALOAD, tempVar.index());
        mv.visitTypeInsn(Opcodes.CHECKCAST, internalName);
        mv.visitVarInsn(Opcodes.ASTORE, patternVar.index());
    }

    /**
     * Mirror of {@link #emitInstanceofCondition} that jumps to
     * {@code jumpTrue} on match instead of jumping on mismatch.
     *
     * @param instanceOf instanceof expression
     * @param jumpTrue   target when the operand is an instance
     */
    public void emitInstanceofConditionTrue(@NotNull InstanceofExpression instanceOf, @NotNull Label jumpTrue) {
        MethodContext ctx = exprGen.ctx();
        if (instanceOf.patternVariable() == null) {
            emitInstanceof(instanceOf);
            ctx.mv().visitJumpInsn(Opcodes.IFNE, jumpTrue);
            return;
        }
        String internalName = ctx.typeResolver().resolveInternalName(instanceOf.type());
        MethodVisitor mv = ctx.mv();
        ResolvedType patternType = ResolvedType.ofObject(internalName);
        LocalVariable patternVar = ctx.declareLocal(instanceOf.patternVariable(), patternType);
        Expression expr = exprGen.unwrapParens(instanceOf.expression());
        if (expr instanceof NameExpression name && ctx.scope().resolve(name.name()) != null) {
            exprGen.generate(instanceOf.expression());
            mv.visitTypeInsn(Opcodes.INSTANCEOF, internalName);
            Label falseLabel = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);
            exprGen.generate(instanceOf.expression());
            mv.visitTypeInsn(Opcodes.CHECKCAST, internalName);
            mv.visitVarInsn(Opcodes.ASTORE, patternVar.index());
            mv.visitJumpInsn(Opcodes.GOTO, jumpTrue);
            mv.visitLabel(falseLabel);
            return;
        }
        exprGen.generate(instanceOf.expression());
        ResolvedType exprType = ctx.typeInferrer().infer(instanceOf.expression());
        ResolvedType tempType = exprType != null ? exprType : ResolvedType.ofObject("java/lang/Object");
        LocalVariable tempVar = ctx.declareLocal("$pattmp" + ctx.scope().nextLocalIndex(), tempType);
        mv.visitVarInsn(Opcodes.ASTORE, tempVar.index());
        mv.visitVarInsn(Opcodes.ALOAD, tempVar.index());
        mv.visitTypeInsn(Opcodes.INSTANCEOF, internalName);
        Label falseLabel = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);
        mv.visitVarInsn(Opcodes.ALOAD, tempVar.index());
        mv.visitTypeInsn(Opcodes.CHECKCAST, internalName);
        mv.visitVarInsn(Opcodes.ASTORE, patternVar.index());
        mv.visitJumpInsn(Opcodes.GOTO, jumpTrue);
        mv.visitLabel(falseLabel);
    }
}
