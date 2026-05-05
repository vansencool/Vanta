package net.vansencool.vanta.codegen.expression.assign;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.exception.CodeGenException;
import net.vansencool.vanta.codegen.expression.cast.PrimitiveConversionEmitter;
import net.vansencool.vanta.codegen.expression.coercion.NumericCoercion;
import net.vansencool.vanta.codegen.expression.util.arith.ArithmeticOpcodes;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.ArrayAccessExpression;
import net.vansencool.vanta.parser.ast.expression.AssignmentExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits bytecode for assignment expressions. Separates the value-producing
 * path ({@code emit}) from the statement-level discard path
 * ({@code emitDiscard}) because the former must leave the assigned value on
 * the stack while the latter can emit the more compact {@code iinc} or
 * plain-store forms when the result isn't observed.
 */
public final class AssignmentEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for value emission
     *                and compound-op / narrowing helpers
     */
    public AssignmentEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Emits an assignment whose result is consumed by an enclosing expression,
     * so the assigned value must be left on the operand stack.
     *
     * @param assignment assignment expression node
     */
    public void emit(@NotNull AssignmentExpression assignment) {
        MethodContext ctx = exprGen.ctx();
        if (assignment.target() instanceof NameExpression nameExpr) {
            LocalVariable local = ctx.scope().resolve(nameExpr.name());
            if (local != null) {
                if ("=".equals(assignment.operator())) {
                    exprGen.generate(assignment.value());
                } else {
                    ctx.mv().visitVarInsn(OpcodeUtils.loadOpcode(local.type()), local.index());
                    exprGen.generate(assignment.value());
                    adaptCompoundRhs(assignment.operator(), local.type(), assignment.value());
                    emitCompoundAssignOp(assignment.operator(), local.type().descriptor());
                    NumericCoercion.emitNarrowForSubIntDesc(ctx.mv(), local.type().descriptor());
                }
                ctx.mv().visitInsn(local.type().stackSize() == 2 ? Opcodes.DUP2 : Opcodes.DUP);
                ctx.mv().visitVarInsn(OpcodeUtils.storeOpcode(local.type()), local.index());
            } else {
                String fieldDesc = exprGen.resolveFieldDescriptor(nameExpr.name());
                ResolvedType fieldType = ResolvedType.fromDescriptor(fieldDesc);
                boolean hasImplicitThis = !ctx.isStatic() || ctx.scope().resolve("this") != null;
                boolean isSelfStaticField = ctx.typeInferrer().isStaticField(nameExpr.name());
                if (hasImplicitThis && !isSelfStaticField) {
                    ctx.mv().visitVarInsn(Opcodes.ALOAD, 0);
                    if (!"=".equals(assignment.operator())) {
                        ctx.mv().visitInsn(Opcodes.DUP);
                        ctx.mv().visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), nameExpr.name(), fieldDesc);
                        exprGen.generate(assignment.value());
                        adaptCompoundRhs(assignment.operator(), fieldType, assignment.value());
                        emitCompoundAssignOp(assignment.operator(), fieldDesc);
                        NumericCoercion.emitNarrowForSubIntDesc(ctx.mv(), fieldDesc);
                    } else {
                        exprGen.generate(assignment.value());
                    }
                    ctx.mv().visitInsn(Opcodes.DUP_X1);
                    ctx.mv().visitFieldInsn(Opcodes.PUTFIELD, ctx.classInternalName(), nameExpr.name(), fieldDesc);
                } else {
                    if (!"=".equals(assignment.operator())) {
                        ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, ctx.classInternalName(), nameExpr.name(), fieldDesc);
                        exprGen.generate(assignment.value());
                        adaptCompoundRhs(assignment.operator(), fieldType, assignment.value());
                        emitCompoundAssignOp(assignment.operator(), fieldDesc);
                        NumericCoercion.emitNarrowForSubIntDesc(ctx.mv(), fieldDesc);
                    } else {
                        exprGen.generate(assignment.value());
                    }
                    ctx.mv().visitInsn(fieldType.stackSize() == 2 ? Opcodes.DUP2 : Opcodes.DUP);
                    ctx.mv().visitFieldInsn(Opcodes.PUTSTATIC, ctx.classInternalName(), nameExpr.name(), fieldDesc);
                }
            }
        } else if (assignment.target() instanceof FieldAccessExpression fieldAccess) {
            MethodResolver.ResolvedField staticResolved = exprGen.resolveStaticFieldAccess(fieldAccess);
            if (staticResolved != null && staticResolved.isStatic()) {
                String fieldDesc = staticResolved.descriptor();
                String fieldOwner = staticResolved.owner();
                ResolvedType fieldType = ResolvedType.fromDescriptor(fieldDesc);
                if ("=".equals(assignment.operator())) {
                    exprGen.generate(assignment.value());
                    exprGen.numericCoercion().adaptForStore(fieldType, assignment.value());
                } else {
                    ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, fieldOwner, staticResolved.name(), fieldDesc);
                    exprGen.generate(assignment.value());
                    adaptCompoundRhs(assignment.operator(), fieldType, assignment.value());
                    emitCompoundAssignOp(assignment.operator(), fieldDesc);
                    NumericCoercion.emitNarrowForSubIntDesc(ctx.mv(), fieldDesc);
                }
                ctx.mv().visitInsn(fieldType.stackSize() == 2 ? Opcodes.DUP2 : Opcodes.DUP);
                ctx.mv().visitFieldInsn(Opcodes.PUTSTATIC, fieldOwner, staticResolved.name(), fieldDesc);
            } else {
                ResolvedType targetType = ctx.typeInferrer().infer(fieldAccess);
                String fieldDesc = targetType != null ? targetType.descriptor() : exprGen.resolveFieldDescriptor(fieldAccess.fieldName());
                ResolvedType fieldType = targetType != null ? targetType : ResolvedType.fromDescriptor(fieldDesc);
                String fieldOwner = ctx.classInternalName();
                ResolvedType recvType = ctx.typeInferrer().infer(fieldAccess.target());
                if (recvType != null && recvType.internalName() != null) fieldOwner = recvType.internalName();
                exprGen.generate(fieldAccess.target());
                if ("=".equals(assignment.operator())) {
                    exprGen.generate(assignment.value());
                    if (targetType != null) exprGen.numericCoercion().adaptForStore(targetType, assignment.value());
                } else {
                    ctx.mv().visitInsn(Opcodes.DUP);
                    ctx.mv().visitFieldInsn(Opcodes.GETFIELD, fieldOwner, fieldAccess.fieldName(), fieldDesc);
                    exprGen.generate(assignment.value());
                    adaptCompoundRhs(assignment.operator(), fieldType, assignment.value());
                    emitCompoundAssignOp(assignment.operator(), fieldDesc);
                    NumericCoercion.emitNarrowForSubIntDesc(ctx.mv(), fieldDesc);
                }
                ctx.mv().visitInsn(Opcodes.DUP_X1);
                ctx.mv().visitFieldInsn(Opcodes.PUTFIELD, fieldOwner, fieldAccess.fieldName(), fieldDesc);
            }
        } else if (assignment.target() instanceof ArrayAccessExpression arrayAccess) {
            ResolvedType elemType = ctx.typeInferrer().infer(arrayAccess);
            exprGen.generate(arrayAccess.array());
            exprGen.generate(arrayAccess.index());
            exprGen.generate(assignment.value(), elemType);
            if (elemType != null) exprGen.numericCoercion().adaptForStore(elemType, assignment.value());
            ctx.mv().visitInsn(Opcodes.DUP_X2);
            ctx.mv().visitInsn(exprGen.arrayEmitter().arrayStoreOpcodeFor(arrayAccess));
        }
    }

    /**
     * Emits an assignment whose result is discarded (statement-level).
     * Allowed to use the compact {@code iinc} and plain-store forms that
     * leave nothing on the stack.
     *
     * @param assignment assignment expression node
     */
    public void emitDiscard(@NotNull AssignmentExpression assignment) {
        MethodContext ctx = exprGen.ctx();
        if (assignment.target() instanceof NameExpression nameExpr) {
            LocalVariable local = ctx.scope().resolve(nameExpr.name());
            if (local != null) {
                if ("=".equals(assignment.operator())) {
                    exprGen.generate(assignment.value(), local.type());
                    exprGen.numericCoercion().adaptForStore(local.type(), assignment.value());
                } else if (tryEmitIinc(assignment.operator(), local, assignment.value())) {
                    return;
                } else {
                    ctx.mv().visitVarInsn(OpcodeUtils.loadOpcode(local.type()), local.index());
                    exprGen.generate(assignment.value());
                    adaptCompoundRhs(assignment.operator(), local.type(), assignment.value());
                    emitCompoundAssignOp(assignment.operator(), local.type().descriptor());
                    NumericCoercion.emitNarrowForSubIntDesc(ctx.mv(), local.type().descriptor());
                }
                ctx.mv().visitVarInsn(OpcodeUtils.storeOpcode(local.type()), local.index());
            } else {
                String fieldDesc = exprGen.resolveFieldDescriptor(nameExpr.name());
                ResolvedType fieldType = ctx.typeInferrer().inferField(nameExpr.name());
                ResolvedType compoundLhsType = fieldType != null ? fieldType : ResolvedType.fromDescriptor(fieldDesc);
                boolean hasImplicitThis = !ctx.isStatic() || ctx.scope().resolve("this") != null;
                boolean isSelfStaticField = ctx.typeInferrer().isStaticField(nameExpr.name());
                if (hasImplicitThis && !isSelfStaticField) {
                    ctx.mv().visitVarInsn(Opcodes.ALOAD, 0);
                    if (!"=".equals(assignment.operator())) {
                        ctx.mv().visitInsn(Opcodes.DUP);
                        ctx.mv().visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), nameExpr.name(), fieldDesc);
                        exprGen.generate(assignment.value());
                        adaptCompoundRhs(assignment.operator(), compoundLhsType, assignment.value());
                        emitCompoundAssignOp(assignment.operator(), fieldDesc);
                        NumericCoercion.emitNarrowForSubIntDesc(ctx.mv(), fieldDesc);
                    } else {
                        exprGen.generate(assignment.value(), fieldType);
                        if (fieldType != null) exprGen.numericCoercion().adaptForStore(fieldType, assignment.value());
                    }
                    ctx.mv().visitFieldInsn(Opcodes.PUTFIELD, ctx.classInternalName(), nameExpr.name(), fieldDesc);
                } else {
                    if (!"=".equals(assignment.operator())) {
                        ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, ctx.classInternalName(), nameExpr.name(), fieldDesc);
                        exprGen.generate(assignment.value());
                        adaptCompoundRhs(assignment.operator(), compoundLhsType, assignment.value());
                        emitCompoundAssignOp(assignment.operator(), fieldDesc);
                        NumericCoercion.emitNarrowForSubIntDesc(ctx.mv(), fieldDesc);
                    } else {
                        exprGen.generate(assignment.value(), fieldType);
                        if (fieldType != null) exprGen.numericCoercion().adaptForStore(fieldType, assignment.value());
                    }
                    ctx.mv().visitFieldInsn(Opcodes.PUTSTATIC, ctx.classInternalName(), nameExpr.name(), fieldDesc);
                }
            }
        } else if (assignment.target() instanceof FieldAccessExpression fieldAccess) {
            MethodResolver.ResolvedField staticResolved = exprGen.resolveStaticFieldAccess(fieldAccess);
            if (staticResolved != null && staticResolved.isStatic()) {
                String fieldDesc = staticResolved.descriptor();
                String fieldOwner = staticResolved.owner();
                ResolvedType fieldType = ResolvedType.fromDescriptor(fieldDesc);
                if ("=".equals(assignment.operator())) {
                    exprGen.generate(assignment.value(), fieldType);
                    exprGen.numericCoercion().adaptForStore(fieldType, assignment.value());
                } else {
                    ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, fieldOwner, staticResolved.name(), fieldDesc);
                    exprGen.generate(assignment.value());
                    adaptCompoundRhs(assignment.operator(), fieldType, assignment.value());
                    emitCompoundAssignOp(assignment.operator(), fieldDesc);
                    NumericCoercion.emitNarrowForSubIntDesc(ctx.mv(), fieldDesc);
                }
                ctx.mv().visitFieldInsn(Opcodes.PUTSTATIC, fieldOwner, staticResolved.name(), fieldDesc);
            } else {
                ResolvedType targetType = ctx.typeInferrer().infer(fieldAccess);
                String fieldDesc = targetType != null ? targetType.descriptor() : exprGen.resolveFieldDescriptor(fieldAccess.fieldName());
                ResolvedType compoundLhsType = targetType != null ? targetType : ResolvedType.fromDescriptor(fieldDesc);
                String fieldOwner = ctx.classInternalName();
                ResolvedType recvType = ctx.typeInferrer().infer(fieldAccess.target());
                if (recvType != null && recvType.internalName() != null) fieldOwner = recvType.internalName();
                exprGen.generate(fieldAccess.target());
                if ("=".equals(assignment.operator())) {
                    exprGen.generate(assignment.value(), targetType);
                    if (targetType != null) exprGen.numericCoercion().adaptForStore(targetType, assignment.value());
                } else {
                    ctx.mv().visitInsn(Opcodes.DUP);
                    ctx.mv().visitFieldInsn(Opcodes.GETFIELD, fieldOwner, fieldAccess.fieldName(), fieldDesc);
                    exprGen.generate(assignment.value());
                    adaptCompoundRhs(assignment.operator(), compoundLhsType, assignment.value());
                    emitCompoundAssignOp(assignment.operator(), fieldDesc);
                    NumericCoercion.emitNarrowForSubIntDesc(ctx.mv(), fieldDesc);
                }
                ctx.mv().visitFieldInsn(Opcodes.PUTFIELD, fieldOwner, fieldAccess.fieldName(), fieldDesc);
            }
        } else if (assignment.target() instanceof ArrayAccessExpression arrayAccess) {
            ResolvedType elemType = ctx.typeInferrer().infer(arrayAccess);
            if ("=".equals(assignment.operator())) {
                exprGen.generate(arrayAccess.array());
                exprGen.generate(arrayAccess.index());
                exprGen.generate(assignment.value(), elemType);
                if (elemType != null) exprGen.numericCoercion().adaptForStore(elemType, assignment.value());
            } else {
                exprGen.generate(arrayAccess.array());
                exprGen.generate(arrayAccess.index());
                ctx.mv().visitInsn(Opcodes.DUP2);
                ctx.mv().visitInsn(exprGen.arrayEmitter().arrayLoadOpcodeFor(arrayAccess));
                exprGen.generate(assignment.value());
                if (elemType != null) adaptCompoundRhs(assignment.operator(), elemType, assignment.value());
                emitCompoundAssignOp(assignment.operator(), elemType != null ? elemType.descriptor() : "I");
            }
            ctx.mv().visitInsn(exprGen.arrayEmitter().arrayStoreOpcodeFor(arrayAccess));
        }
    }

    /**
     * Emits the arithmetic op that makes a compound-assignment compound.
     * Short-circuits the {@code String +=} path to {@link #emitStringConcat}.
     *
     * @param operator compound operator (e.g. {@code +=})
     * @param typeDesc JVM type descriptor driving opcode selection
     */
    /**
     * Coerces the RHS of a compound assignment to match the LHS type. Unlike
     * {@code adaptForStore} this is unconditional for arithmetic operators:
     * a same-typed compound op needs both operands at LHS width even when the
     * source-level RHS is an int literal that {@code adaptForStore} would
     * skip. Shift operators keep an {@code int} RHS regardless of LHS width.
     */
    private void adaptCompoundRhs(@NotNull String operator, @NotNull ResolvedType lhsType, @NotNull Expression value) {
        if ("<<=".equals(operator) || ">>=".equals(operator) || ">>>=".equals(operator)) return;
        String lhsDesc = lhsType.descriptor();
        if ("+=".equals(operator) && "Ljava/lang/String;".equals(lhsDesc)) return;
        if (!"J".equals(lhsDesc) && !"F".equals(lhsDesc) && !"D".equals(lhsDesc)) return;
        ResolvedType actual = exprGen.ctx().typeInferrer().infer(value);
        if (actual == null) return;
        if (actual.isPrimitive() && !actual.descriptor().equals(lhsDesc)) {
            PrimitiveConversionEmitter.emitPrimitiveWidening(exprGen.ctx().mv(), actual.descriptor(), lhsDesc);
        } else {
            exprGen.numericCoercion().adaptForStore(lhsType, value);
        }
    }

    private void emitCompoundAssignOp(@NotNull String operator, @NotNull String typeDesc) {
        MethodVisitor mv = exprGen.ctx().mv();
        if ("+=".equals(operator) && "Ljava/lang/String;".equals(typeDesc)) {
            emitStringConcat(mv);
            return;
        }
        switch (operator) {
            case "+=" -> mv.visitInsn(ArithmeticOpcodes.add(typeDesc));
            case "-=" -> mv.visitInsn(ArithmeticOpcodes.sub(typeDesc));
            case "*=" -> mv.visitInsn(ArithmeticOpcodes.mul(typeDesc));
            case "/=" -> mv.visitInsn(ArithmeticOpcodes.div(typeDesc));
            case "%=" -> mv.visitInsn(ArithmeticOpcodes.rem(typeDesc));
            case "&=" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LAND : Opcodes.IAND);
            case "|=" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LOR : Opcodes.IOR);
            case "^=" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LXOR : Opcodes.IXOR);
            case "<<=" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LSHL : Opcodes.ISHL);
            case ">>=" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LSHR : Opcodes.ISHR);
            case ">>>=" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LUSHR : Opcodes.IUSHR);
            default -> throw new CodeGenException("Unknown compound assignment: " + operator, 0);
        }
    }

    /**
     * Emits a {@code String + String} concat given two String values already
     * on the stack, using {@code makeConcatWithConstants} with a bare recipe
     * so no StringBuilder scaffolding is needed.
     *
     * @param mv target method visitor
     */
    private void emitStringConcat(@NotNull MethodVisitor mv) {
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false);
        mv.visitInvokeDynamicInsn("makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                bsm, "");
    }

    /**
     * Attempts to collapse a {@code +=} / {@code -=} against a local to an
     * {@code iinc} instruction when the RHS is a short-range int literal.
     *
     * @param operator compound operator
     * @param local    target local
     * @param value    right-hand side
     * @return true when iinc was emitted (no fallback needed)
     */
    private boolean tryEmitIinc(@NotNull String operator, @NotNull LocalVariable local, @NotNull Expression value) {
        if (local.type().stackSize() != 1) return false;
        if (!(value instanceof LiteralExpression lit) || lit.literalType() != TokenType.INT_LITERAL) return false;
        if (!("+=".equals(operator) || "-=".equals(operator))) return false;
        try {
            int delta = Integer.parseInt(lit.value());
            if ("-=".equals(operator)) delta = -delta;
            if (delta < Short.MIN_VALUE || delta > Short.MAX_VALUE) return false;
            exprGen.ctx().mv().visitIincInsn(local.index(), delta);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
