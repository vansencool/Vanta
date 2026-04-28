package net.vansencool.vanta.codegen;

import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.exception.CodeGenException;
import net.vansencool.vanta.codegen.expression.cast.PrimitiveConversionEmitter;
import net.vansencool.vanta.codegen.statement.loop.LoopStatementEmitter;
import net.vansencool.vanta.codegen.statement.pattern.PatternBindingHelper;
import net.vansencool.vanta.codegen.statement.sw.SwitchStatementEmitter;
import net.vansencool.vanta.codegen.statement.tryblock.TryStatementEmitter;
import net.vansencool.vanta.parser.ast.expression.ArrayInitializerExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.NewArrayExpression;
import net.vansencool.vanta.parser.ast.expression.SwitchExpression;
import net.vansencool.vanta.parser.ast.expression.TernaryExpression;
import net.vansencool.vanta.parser.ast.statement.AssertStatement;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.BreakStatement;
import net.vansencool.vanta.parser.ast.statement.ContinueStatement;
import net.vansencool.vanta.parser.ast.statement.DoWhileStatement;
import net.vansencool.vanta.parser.ast.statement.ExpressionStatement;
import net.vansencool.vanta.parser.ast.statement.ForEachStatement;
import net.vansencool.vanta.parser.ast.statement.ForStatement;
import net.vansencool.vanta.parser.ast.statement.IfStatement;
import net.vansencool.vanta.parser.ast.statement.LabeledStatement;
import net.vansencool.vanta.parser.ast.statement.ReturnStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.statement.SwitchStatement;
import net.vansencool.vanta.parser.ast.statement.SynchronizedStatement;
import net.vansencool.vanta.parser.ast.statement.ThrowStatement;
import net.vansencool.vanta.parser.ast.statement.TryStatement;
import net.vansencool.vanta.parser.ast.statement.VariableDeclarationStatement;
import net.vansencool.vanta.parser.ast.statement.VariableDeclarator;
import net.vansencool.vanta.parser.ast.statement.WhileStatement;
import net.vansencool.vanta.parser.ast.statement.YieldStatement;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Generates bytecode for all statement types.
 */
public final class StatementGenerator {

    private final @NotNull MethodContext ctx;
    private final @NotNull ExpressionGenerator exprGen;
    private final @NotNull SwitchStatementEmitter switchStatementEmitter;
    private final @NotNull TryStatementEmitter tryStatementEmitter;
    private final @NotNull LoopStatementEmitter loopStatementEmitter;
    private final @NotNull PatternBindingHelper patternBindingHelper;
    private @Nullable String pendingLabelName;

    /**
     * Creates a statement generator.
     *
     * @param ctx     the method context
     * @param exprGen the expression generator
     */
    public StatementGenerator(@NotNull MethodContext ctx, @NotNull ExpressionGenerator exprGen) {
        this.ctx = ctx;
        this.exprGen = exprGen;
        this.switchStatementEmitter = new SwitchStatementEmitter(this, exprGen);
        this.tryStatementEmitter = new TryStatementEmitter(this, exprGen);
        this.loopStatementEmitter = new LoopStatementEmitter(this, exprGen);
        this.patternBindingHelper = new PatternBindingHelper(ctx);
    }

    /**
     * Generates bytecode for any statement by dispatching.
     *
     * @param stmt the statement
     */
    public void generate(@NotNull Statement stmt) {
        if (stmt instanceof BlockStatement block) {
            generateBlock(block);
        } else if (stmt instanceof ExpressionStatement exprStmt) {
            generateExpressionStatement(exprStmt);
        } else if (stmt instanceof VariableDeclarationStatement varDecl) {
            generateVariableDeclaration(varDecl);
        } else if (stmt instanceof ReturnStatement ret) {
            generateReturn(ret);
        } else if (stmt instanceof IfStatement ifStmt) {
            generateIf(ifStmt);
        } else if (stmt instanceof WhileStatement whileStmt) {
            loopStatementEmitter.emitWhile(whileStmt);
        } else if (stmt instanceof DoWhileStatement doWhile) {
            loopStatementEmitter.emitDoWhile(doWhile);
        } else if (stmt instanceof ForStatement forStmt) {
            loopStatementEmitter.emitFor(forStmt);
        } else if (stmt instanceof ForEachStatement forEach) {
            loopStatementEmitter.emitForEach(forEach);
        } else if (stmt instanceof SwitchStatement switchStmt) {
            switchStatementEmitter.emit(switchStmt);
        } else if (stmt instanceof TryStatement tryStmt) {
            tryStatementEmitter.emit(tryStmt);
        } else if (stmt instanceof ThrowStatement throwStmt) {
            generateThrow(throwStmt);
        } else if (stmt instanceof BreakStatement breakStmt) {
            generateBreak(breakStmt);
        } else if (stmt instanceof ContinueStatement continueStmt) {
            generateContinue(continueStmt);
        } else if (stmt instanceof LabeledStatement labeled) {
            generateLabeled(labeled);
        } else if (stmt instanceof YieldStatement yield) {
            generateYield(yield);
        } else if (stmt instanceof SynchronizedStatement syncStmt) {
            generateSynchronized(syncStmt);
        } else if (stmt instanceof AssertStatement assertStmt) {
            generateAssert(assertStmt);
        } else {
            throw new CodeGenException("Unsupported statement type: " + stmt.getClass().getSimpleName(), stmt.line());
        }
    }

    /**
     * Generates bytecode for a block statement, creating a new scope.
     *
     * @param block the block statement
     */
    private void generateBlock(@NotNull BlockStatement block) {
        int savedSlot = ctx.scope().nextLocalIndex();
        for (Statement stmt : block.statements()) {
            generate(stmt);
        }
        ctx.scope().removeVariablesFrom(savedSlot);
        ctx.scope().syncNextLocalIndex(savedSlot);
    }

    /**
     * Generates bytecode for an expression statement (generates and discards result).
     *
     * @param exprStmt the expression statement
     */
    private void generateExpressionStatement(@NotNull ExpressionStatement exprStmt) {
        ctx.emitLine(exprStmt.line());
        exprGen.generateAndDiscard(exprStmt.expression());
    }

    /**
     * Generates bytecode for a variable declaration statement.
     *
     * @param varDecl the variable declaration statement
     */
    private void generateVariableDeclaration(@NotNull VariableDeclarationStatement varDecl) {
        MethodVisitor mv = ctx.mv();
        ctx.emitLine(varDecl.line());

        for (VariableDeclarator declarator : varDecl.declarators()) {
            TypeNode effectiveType = varDecl.type();
            if (declarator.extraArrayDimensions() > 0) {
                effectiveType = effectiveType.withExtraDimensions(declarator.extraArrayDimensions());
            }
            ResolvedType resolvedType;
            if ("var".equals(effectiveType.name()) && effectiveType.arrayDimensions() == 0 && declarator.initializer() != null) {
                ResolvedType inferred = ctx.typeInferrer().infer(declarator.initializer());
                resolvedType = inferred != null && inferred != ResolvedType.NULL ? inferred : ResolvedType.ofObject("java/lang/Object");
            } else {
                resolvedType = ctx.typeResolver().resolve(effectiveType);
            }
            LocalVariable local = ctx.declareLocal(declarator.name(), resolvedType);

            if (declarator.initializer() != null) {
                exprGen.generate(declarator.initializer(), resolvedType);
                ResolvedType initType = ctx.typeInferrer().infer(declarator.initializer());
                boolean initIsNull = initType == ResolvedType.NULL;
                boolean literalCoerced = declarator.initializer() instanceof LiteralExpression lit && exprGen.litHandledExpectedType(lit, resolvedType.descriptor());
                boolean descriptorsDiffer = initType != null && !resolvedType.descriptor().equals(initType.descriptor());
                if (resolvedType.isPrimitive() && initType != null && !initType.isPrimitive() && initType.internalName() != null && !initIsNull) {
                    exprGen.unboxingEmitter().withCast(mv, resolvedType.descriptor(), initType.internalName());
                } else if (resolvedType.isPrimitive() && initType != null && initType.isPrimitive() && descriptorsDiffer && !literalCoerced) {
                    PrimitiveConversionEmitter.emitPrimitiveWidening(mv, initType.descriptor(), resolvedType.descriptor());
                } else if (!initIsNull && !resolvedType.isPrimitive() && !resolvedType.isArray() && resolvedType.internalName() != null && !"java/lang/Object".equals(resolvedType.internalName())) {
                    if (initType != null && !initType.isPrimitive() && initType.internalName() != null && !initType.internalName().equals(resolvedType.internalName()) && !exprGen.isSubtypeOf(initType.internalName(), resolvedType.internalName())) {
                        mv.visitTypeInsn(Opcodes.CHECKCAST, resolvedType.internalName());
                    }
                } else if (!initIsNull && resolvedType.isArray() && initType != null && !initType.isPrimitive()
                        && descriptorsDiffer
                        && "java/lang/Object".equals(initType.internalName())
                        && !(declarator.initializer() instanceof NewArrayExpression)
                        && !(declarator.initializer() instanceof ArrayInitializerExpression)) {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, resolvedType.descriptor());
                }
                mv.visitVarInsn(OpcodeUtils.storeOpcode(resolvedType), local.index());
            }
        }
    }

    /**
     * Generates bytecode for a return statement.
     *
     * @param ret the return statement
     */
    private void generateReturn(@NotNull ReturnStatement ret) {
        MethodVisitor mv = ctx.mv();
        ctx.emitLine(ret.line());

        if (ret.value() != null) {
            exprGen.generate(ret.value(), ctx.returnType());
            ResolvedType type = ctx.typeInferrer().infer(ret.value());
            ResolvedType methodReturn = ctx.returnType();
            boolean valueAlreadyCoerced = ret.value() instanceof TernaryExpression
                    || ret.value() instanceof SwitchExpression;
            if (type != null && type != ResolvedType.NULL && methodReturn != null && "Ljava/lang/Object;".equals(type.descriptor()) && methodReturn.internalName() != null && !"java/lang/Object".equals(methodReturn.internalName()) && !methodReturn.descriptor().startsWith("[")) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, methodReturn.internalName());
            }
            if (type != null && type != ResolvedType.NULL && methodReturn != null && methodReturn.descriptor().startsWith("[")
                    && !methodReturn.descriptor().equals(type.descriptor())
                    && (type.descriptor().startsWith("[") || "java/lang/Object".equals(type.internalName()))) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, methodReturn.descriptor());
            }
            if (type != null && type.isPrimitive() && methodReturn != null && !methodReturn.isPrimitive() && !methodReturn.isVoid() && !valueAlreadyCoerced) {
                PrimitiveConversionEmitter.emitBoxing(mv, type);
                type = ResolvedType.ofObject("java/lang/Object");
            } else if (type != null && !type.isPrimitive() && methodReturn != null && methodReturn.isPrimitive() && !methodReturn.isVoid()) {
                exprGen.unboxingEmitter().forReturn(mv, type, methodReturn);
                type = methodReturn;
            }
            if (valueAlreadyCoerced && methodReturn != null && !methodReturn.isPrimitive()) {
                type = methodReturn;
            }
            int returnSlot;
            ResolvedType retLocalType = methodReturn != null ? methodReturn : ResolvedType.ofObject("java/lang/Object");
            LocalVariable retLocal;
            if (!ctx.runAllFinalliesIsEmpty()) {
                retLocal = ctx.declareLocal("$retval" + ret.line(), retLocalType);
                returnSlot = retLocal.index();
                mv.visitVarInsn(OpcodeUtils.storeOpcode(retLocalType), returnSlot);
                ctx.runAllFinallies();
                mv.visitVarInsn(OpcodeUtils.loadOpcode(retLocalType), returnSlot);
            }
            ResolvedType effectiveType = type != null ? type : methodReturn;
            if (effectiveType != null && (effectiveType.equals(ResolvedType.INT) || effectiveType.equals(ResolvedType.BOOLEAN) || effectiveType.equals(ResolvedType.BYTE) || effectiveType.equals(ResolvedType.SHORT) || effectiveType.equals(ResolvedType.CHAR))) {
                mv.visitInsn(Opcodes.IRETURN);
            } else if (effectiveType != null && effectiveType.equals(ResolvedType.LONG)) {
                mv.visitInsn(Opcodes.LRETURN);
            } else if (effectiveType != null && effectiveType.equals(ResolvedType.FLOAT)) {
                mv.visitInsn(Opcodes.FRETURN);
            } else if (effectiveType != null && effectiveType.equals(ResolvedType.DOUBLE)) {
                mv.visitInsn(Opcodes.DRETURN);
            } else {
                mv.visitInsn(Opcodes.ARETURN);
            }
        } else {
            ctx.runAllFinallies();
            mv.visitInsn(Opcodes.RETURN);
        }
        ctx.markUnreachable();
    }

    /**
     * Generates bytecode for an if statement.
     *
     * @param ifStmt the if statement
     */
    private void generateIf(@NotNull IfStatement ifStmt) {
        generateIf(ifStmt, null);
    }

    /**
     * Generates an if statement, optionally reusing {@code sharedEndLabel} as the
     * end-of-if jump target. Sharing lets nested {@code else if} chains all jump to a
     * single end label, matching javac's bytecode layout.
     */
    private void generateIf(@NotNull IfStatement ifStmt, @Nullable Label sharedEndLabel) {
        MethodVisitor mv = ctx.mv();
        ctx.emitLine(ifStmt.line());

        Label elseLabel = new Label();
        Label endLabel = sharedEndLabel != null ? sharedEndLabel : new Label();

        int savedSlot = ctx.scope().nextLocalIndex();

        exprGen.conditionEmitter().jumpFalse(ifStmt.condition(), ifStmt.elseBranch() != null ? elseLabel : endLabel);
        int slotAfterCondition = ctx.scope().nextLocalIndex();

        generate(ifStmt.thenBranch());
        boolean thenAbrupt = !ctx.isReachable();

        if (ifStmt.elseBranch() != null) {
            boolean thenReachable = ctx.isReachable();
            if (thenReachable) {
                mv.visitJumpInsn(Opcodes.GOTO, endLabel);
                ctx.markUnreachable();
            }
            boolean conditionHasPattern = patternBindingHelper.patternVarSlotEnd(ifStmt.condition()) > savedSlot;
            int elseBaseSlot = conditionHasPattern ? Math.max(savedSlot, patternBindingHelper.patternVarSlotEnd(ifStmt.condition())) : savedSlot;
            ctx.scope().syncNextLocalIndex(elseBaseSlot);
            ctx.markReachable();
            mv.visitLabel(elseLabel);
            Statement elseBranch = ifStmt.elseBranch();
            Statement elseInner = elseBranch;
            if (elseInner instanceof BlockStatement bs && bs.statements().size() == 1) {
                elseInner = bs.statements().get(0);
            }
            if (elseInner instanceof IfStatement chained) {
                generateIf(chained, endLabel);
            } else {
                generate(elseBranch);
            }
            boolean elseReachable = ctx.isReachable();
            if (sharedEndLabel == null) {
                if (thenReachable || elseReachable) {
                    if (!ctx.isReachable()) ctx.markReachable();
                    mv.visitLabel(endLabel);
                } else {
                    mv.visitLabel(endLabel);
                }
            }
            ctx.scope().syncNextLocalIndex(savedSlot);
        } else {
            if (sharedEndLabel == null) {
                ctx.markReachable();
                mv.visitLabel(endLabel);
            } else {
                ctx.markReachable();
            }
            if (thenAbrupt && patternBindingHelper.hasNegatedPatternBinding(ifStmt.condition())) {
                int patternEnd = patternBindingHelper.patternVarSlotEnd(ifStmt.condition());
                if (patternEnd > savedSlot) {
                    ctx.scope().syncNextLocalIndex(patternEnd);
                } else {
                    ctx.scope().syncNextLocalIndex(slotAfterCondition);
                }
            } else {
                ctx.scope().syncNextLocalIndex(savedSlot);
            }
        }
    }

    /**
     * Generates bytecode for a throw statement.
     *
     * @param throwStmt the throw statement
     */
    private void generateThrow(@NotNull ThrowStatement throwStmt) {
        ctx.emitLine(throwStmt.line());
        exprGen.generate(throwStmt.expression());
        ctx.mv().visitInsn(Opcodes.ATHROW);
        ctx.markUnreachable();
    }

    /**
     * Generates bytecode for a break statement.
     *
     * @param breakStmt the break statement
     */
    private void generateBreak(@NotNull BreakStatement breakStmt) {
        ctx.mv().visitJumpInsn(Opcodes.GOTO, ctx.labelContext().breakLabel(breakStmt.label()));
        ctx.markUnreachable();
    }

    /**
     * Generates bytecode for a continue statement.
     *
     * @param continueStmt the continue statement
     */
    private void generateContinue(@NotNull ContinueStatement continueStmt) {
        Label target = ctx.labelContext().continueLabel(continueStmt.label());
        if (target != null) {
            ctx.mv().visitJumpInsn(Opcodes.GOTO, target);
            ctx.markUnreachable();
        }
    }

    /**
     * Generates bytecode for a labeled statement.
     *
     * @param labeled the labeled statement
     */
    private void generateLabeled(@NotNull LabeledStatement labeled) {
        pendingLabelName = labeled.label();
        generate(labeled.statement());
        pendingLabelName = null;
    }

    /**
     * @return pending label name set by an enclosing labeled statement, then
     * cleared so it does not leak into a sibling statement
     */
    public @Nullable String consumePendingLabel() {
        String r = pendingLabelName;
        pendingLabelName = null;
        return r;
    }

    /**
     * Generates bytecode for a yield statement (used inside switch expressions).
     *
     * @param yield the yield statement
     */
    private void generateYield(@NotNull YieldStatement yield) {
        ctx.emitLine(yield.line());
        ResolvedType switchExpected = ctx.currentSwitchExpected();
        exprGen.generate(yield.value(), switchExpected);
        boolean valueAlreadyCoerced = yield.value() instanceof TernaryExpression
                || yield.value() instanceof SwitchExpression;
        if (switchExpected != null && !switchExpected.isPrimitive() && !valueAlreadyCoerced) {
            ResolvedType yieldType = ctx.typeInferrer().infer(yield.value());
            if (yieldType != null && yieldType.isPrimitive()) {
                PrimitiveConversionEmitter.emitBoxing(ctx.mv(), yieldType);
            }
        }
        ctx.mv().visitJumpInsn(Opcodes.GOTO, ctx.labelContext().breakLabel(null));
        ctx.markUnreachable();
    }

    /**
     * Generates bytecode for a synchronized statement.
     * Uses MONITORENTER/MONITOREXIT with proper exception handler for unlock.
     *
     * @param syncStmt the synchronized statement
     */
    private void generateSynchronized(@NotNull SynchronizedStatement syncStmt) {
        MethodVisitor mv = ctx.mv();
        ctx.emitLine(syncStmt.line());

        exprGen.generate(syncStmt.lock());
        mv.visitInsn(Opcodes.DUP);
        LocalVariable lockVar = ctx.declareLocal("$lock" + syncStmt.line(), ResolvedType.ofObject("java/lang/Object"));
        mv.visitVarInsn(Opcodes.ASTORE, lockVar.index());
        mv.visitInsn(Opcodes.MONITORENTER);

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label endLabel = new Label();

        mv.visitTryCatchBlock(tryStart, tryEnd, handler, null);

        Runnable unlockEmitter = () -> {
            mv.visitVarInsn(Opcodes.ALOAD, lockVar.index());
            mv.visitInsn(Opcodes.MONITOREXIT);
        };
        ctx.pushFinally(unlockEmitter);

        mv.visitLabel(tryStart);
        for (Statement stmt : syncStmt.body().statements()) {
            generate(stmt);
        }
        ctx.popFinally();
        mv.visitVarInsn(Opcodes.ALOAD, lockVar.index());
        mv.visitInsn(Opcodes.MONITOREXIT);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);
        ctx.markUnreachable();

        ctx.markReachable();
        mv.visitLabel(handler);
        LocalVariable handlerEx = ctx.declareLocal("$syncEx" + syncStmt.line(), ResolvedType.ofObject("java/lang/Throwable"));
        mv.visitVarInsn(Opcodes.ASTORE, handlerEx.index());
        mv.visitVarInsn(Opcodes.ALOAD, lockVar.index());
        mv.visitInsn(Opcodes.MONITOREXIT);
        mv.visitVarInsn(Opcodes.ALOAD, handlerEx.index());
        mv.visitInsn(Opcodes.ATHROW);
        ctx.markUnreachable();

        ctx.markReachable();
        mv.visitLabel(endLabel);
    }

    /**
     * Generates bytecode for an assert statement.
     * Always evaluates the condition and throws AssertionError if false.
     *
     * @param assertStmt the assert statement
     */
    private void generateAssert(@NotNull AssertStatement assertStmt) {
        MethodVisitor mv = ctx.mv();
        ctx.emitLine(assertStmt.line());

        Label endLabel = new Label();

        if (ctx.classGenerator() != null) ctx.classGenerator().assertUsed(true);
        mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.classInternalName(), "$assertionsDisabled", "Z");
        mv.visitJumpInsn(Opcodes.IFNE, endLabel);

        exprGen.conditionEmitter().jumpTrue(assertStmt.condition(), endLabel);

        mv.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
        mv.visitInsn(Opcodes.DUP);
        if (assertStmt.message() != null) {
            exprGen.generate(assertStmt.message());
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false);
        }
        mv.visitInsn(Opcodes.ATHROW);
        ctx.markUnreachable();

        ctx.markReachable();
        mv.visitLabel(endLabel);
    }
}
