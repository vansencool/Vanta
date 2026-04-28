package net.vansencool.vanta.codegen.statement.loop;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.StatementGenerator;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.ParenExpression;
import net.vansencool.vanta.parser.ast.statement.DoWhileStatement;
import net.vansencool.vanta.parser.ast.statement.ForEachStatement;
import net.vansencool.vanta.parser.ast.statement.ForStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.statement.WhileStatement;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits bytecode for {@code while}, {@code do while}, {@code for}, and
 * enhanced for each loops. Handles array vs iterable for each dispatch and
 * elides the dead condition check on infinite literal {@code while (true)}
 * loops to match javac's output.
 */
public final class LoopStatementEmitter {

    private final @NotNull StatementGenerator stmtGen;
    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param stmtGen owning statement generator used to recurse into bodies
     * @param exprGen expression generator used to evaluate conditions and
     *                iterables
     */
    public LoopStatementEmitter(@NotNull StatementGenerator stmtGen, @NotNull ExpressionGenerator exprGen) {
        this.stmtGen = stmtGen;
        this.exprGen = exprGen;
    }

    /**
     * @param whileStmt while statement node
     */
    public void emitWhile(@NotNull WhileStatement whileStmt) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        ctx.emitLine(whileStmt.line());

        Label conditionLabel = new Label();
        Label endLabel = new Label();

        ctx.labelContext().pushLoop(endLabel, conditionLabel, stmtGen.consumePendingLabel());

        mv.visitLabel(conditionLabel);
        if (!isTrueLiteral(whileStmt.condition())) {
            exprGen.conditionEmitter().jumpFalse(whileStmt.condition(), endLabel);
        }

        stmtGen.generate(whileStmt.body());
        mv.visitJumpInsn(Opcodes.GOTO, conditionLabel);
        ctx.markUnreachable();

        ctx.markReachable();
        mv.visitLabel(endLabel);
        ctx.labelContext().popLoop(null);
    }

    /**
     * @param doWhile do while statement node
     */
    public void emitDoWhile(@NotNull DoWhileStatement doWhile) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        ctx.emitLine(doWhile.line());

        Label bodyLabel = new Label();
        Label conditionLabel = new Label();
        Label endLabel = new Label();

        ctx.labelContext().pushLoop(endLabel, conditionLabel, stmtGen.consumePendingLabel());

        mv.visitLabel(bodyLabel);
        stmtGen.generate(doWhile.body());

        ctx.markReachable();
        mv.visitLabel(conditionLabel);
        exprGen.conditionEmitter().jumpTrue(doWhile.condition(), bodyLabel);

        ctx.markReachable();
        mv.visitLabel(endLabel);
        ctx.labelContext().popLoop(null);
    }

    /**
     * @param forStmt for statement node
     */
    public void emitFor(@NotNull ForStatement forStmt) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        if (forStmt.initializers() == null || forStmt.initializers().isEmpty()) {
            ctx.emitLine(forStmt.line());
        }

        int savedSlot = ctx.scope().nextLocalIndex();
        Label conditionLabel = new Label();
        Label updateLabel = new Label();
        Label endLabel = new Label();

        ctx.labelContext().pushLoop(endLabel, updateLabel, stmtGen.consumePendingLabel());

        if (forStmt.initializers() != null) {
            for (Statement init : forStmt.initializers()) {
                stmtGen.generate(init);
            }
        }

        mv.visitLabel(conditionLabel);

        if (forStmt.condition() != null) {
            exprGen.conditionEmitter().jumpFalse(forStmt.condition(), endLabel);
        }

        stmtGen.generate(forStmt.body());

        ctx.markReachable();
        mv.visitLabel(updateLabel);
        if (forStmt.updaters() != null && !forStmt.updaters().isEmpty()) {
            mv.visitLineNumber(forStmt.line(), updateLabel);
            for (Expression updater : forStmt.updaters()) {
                exprGen.generateAndDiscard(updater);
            }
        }
        mv.visitJumpInsn(Opcodes.GOTO, conditionLabel);
        ctx.markUnreachable();

        ctx.markReachable();
        mv.visitLabel(endLabel);
        ctx.labelContext().popLoop(null);
        ctx.scope().syncNextLocalIndex(savedSlot);
    }

    /**
     * @param forEach enhanced for each statement node
     */
    public void emitForEach(@NotNull ForEachStatement forEach) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        ctx.emitLine(forEach.line());

        ResolvedType iterableType = ctx.typeInferrer().infer(forEach.iterable());
        if (iterableType != null && iterableType.descriptor().startsWith("[")) {
            emitForEachArray(forEach, mv, iterableType);
        } else {
            emitForEachIterable(forEach, mv, iterableType);
        }
    }

    /**
     * @param forEach   for each statement
     * @param mv        method visitor to emit into
     * @param arrayType array selector type
     */
    private void emitForEachArray(@NotNull ForEachStatement forEach, @NotNull MethodVisitor mv, @NotNull ResolvedType arrayType) {
        MethodContext ctx = exprGen.ctx();
        int savedSlot = ctx.scope().nextLocalIndex();
        Label conditionLabel = new Label();
        Label continueLabel = new Label();
        Label endLabel = new Label();
        ctx.labelContext().pushLoop(endLabel, continueLabel, stmtGen.consumePendingLabel());

        exprGen.generate(forEach.iterable());
        LocalVariable arrVar = ctx.declareLocal("$arr" + forEach.line(), arrayType);
        mv.visitVarInsn(Opcodes.ASTORE, arrVar.index());

        LocalVariable lenVar = ctx.declareLocal("$len" + forEach.line(), ResolvedType.INT);
        mv.visitVarInsn(Opcodes.ALOAD, arrVar.index());
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitVarInsn(Opcodes.ISTORE, lenVar.index());

        LocalVariable idxVar = ctx.declareLocal("$idx" + forEach.line(), ResolvedType.INT);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, idxVar.index());

        mv.visitLabel(conditionLabel);
        mv.visitVarInsn(Opcodes.ILOAD, idxVar.index());
        mv.visitVarInsn(Opcodes.ILOAD, lenVar.index());
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, endLabel);

        ResolvedType varType;
        if ("var".equals(forEach.variableType().name())) {
            if (arrayType.descriptor().startsWith("[") && arrayType.descriptor().length() > 1) {
                varType = ResolvedType.fromDescriptor(arrayType.descriptor().substring(1));
            } else {
                varType = ResolvedType.ofObject("java/lang/Object");
            }
        } else {
            varType = ctx.typeResolver().resolve(forEach.variableType());
        }
        mv.visitVarInsn(Opcodes.ALOAD, arrVar.index());
        mv.visitVarInsn(Opcodes.ILOAD, idxVar.index());
        mv.visitInsn(OpcodeUtils.arrayLoadOpcode(varType));
        LocalVariable loopVar = ctx.declareLocal(forEach.variableName(), varType);
        mv.visitVarInsn(OpcodeUtils.storeOpcode(varType), loopVar.index());

        stmtGen.generate(forEach.body());
        mv.visitLabel(continueLabel);
        mv.visitIincInsn(idxVar.index(), 1);
        mv.visitJumpInsn(Opcodes.GOTO, conditionLabel);
        ctx.markUnreachable();

        ctx.markReachable();
        mv.visitLabel(endLabel);
        ctx.labelContext().popLoop(null);
        ctx.scope().syncNextLocalIndex(savedSlot);
    }

    /**
     * @param forEach      for each statement
     * @param mv           method visitor to emit into
     * @param iterableType iterable selector type, or null when unknown
     */
    private void emitForEachIterable(@NotNull ForEachStatement forEach, @NotNull MethodVisitor mv, @Nullable ResolvedType iterableType) {
        MethodContext ctx = exprGen.ctx();
        int savedSlot = ctx.scope().nextLocalIndex();
        Label conditionLabel = new Label();
        Label endLabel = new Label();

        ctx.labelContext().pushLoop(endLabel, conditionLabel, stmtGen.consumePendingLabel());

        exprGen.generate(forEach.iterable());
        String iterableOwner = "java/lang/Iterable";
        boolean ownerIsInterface = true;
        if (iterableType != null && iterableType.internalName() != null && !"java/lang/Iterable".equals(iterableType.internalName())) {
            iterableOwner = iterableType.internalName();
            Class<?> ownerClass = ctx.methodResolver().classpathManager().loadClass(iterableOwner);
            if (ownerClass != null) ownerIsInterface = ownerClass.isInterface();
            else {
                AsmClassInfo info = ctx.methodResolver().classpathManager().asmClassInfo(iterableOwner);
                if (info != null) ownerIsInterface = info.isInterface();
            }
        }
        mv.visitMethodInsn(ownerIsInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, iterableOwner, "iterator", "()Ljava/util/Iterator;", ownerIsInterface);

        ResolvedType iteratorType = ResolvedType.ofObject("java/util/Iterator");
        LocalVariable iterVar = ctx.declareLocal("$iter" + forEach.line(), iteratorType);
        mv.visitVarInsn(Opcodes.ASTORE, iterVar.index());

        mv.visitLabel(conditionLabel);
        mv.visitVarInsn(Opcodes.ALOAD, iterVar.index());
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, endLabel);

        mv.visitVarInsn(Opcodes.ALOAD, iterVar.index());
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);

        ResolvedType varType;
        if ("var".equals(forEach.variableType().name())) {
            varType = ResolvedType.ofObject("java/lang/Object");
            if (iterableType != null && iterableType.typeArguments() != null && !iterableType.typeArguments().isEmpty()) {
                varType = iterableType.typeArguments().get(0);
            }
        } else {
            varType = ctx.typeResolver().resolve(forEach.variableType());
        }
        if (varType.internalName() != null && !"java/lang/Object".equals(varType.internalName())) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, varType.internalName());
        }

        LocalVariable loopVar = ctx.declareLocal(forEach.variableName(), varType);
        mv.visitVarInsn(OpcodeUtils.storeOpcode(varType), loopVar.index());

        stmtGen.generate(forEach.body());
        mv.visitJumpInsn(Opcodes.GOTO, conditionLabel);
        ctx.markUnreachable();

        ctx.markReachable();
        mv.visitLabel(endLabel);
        ctx.labelContext().popLoop(null);
        ctx.scope().syncNextLocalIndex(savedSlot);
    }

    /**
     * Detects a condition that evaluates to the constant {@code true},
     * including the raw literal and trivial parenthesized variants. javac
     * treats such loops as infinite and skips emitting the condition check.
     *
     * @param expr condition expression
     * @return true when {@code expr} reduces to the literal {@code true}
     */
    private boolean isTrueLiteral(@NotNull Expression expr) {
        Expression cur = expr;
        while (cur instanceof ParenExpression p) cur = p.expression();
        if (cur instanceof LiteralExpression lit) {
            return lit.literalType() == TokenType.TRUE;
        }
        return false;
    }
}
