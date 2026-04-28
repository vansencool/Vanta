package net.vansencool.vanta.codegen.statement.tryblock;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.StatementGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.CatchClause;
import net.vansencool.vanta.parser.ast.statement.ResourceDeclaration;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.statement.TryStatement;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * Emits bytecode for {@code try}/{@code catch}/{@code finally} including try
 * with resources. Owns the synthetic finally generation that javac splices
 * into both the success path and the abrupt completion handler so all paths
 * close resources and run user finally bodies.
 */
public final class TryStatementEmitter {

    private final @NotNull StatementGenerator stmtGen;
    private final @NotNull ExpressionGenerator exprGen;
    private final @NotNull ResourceCloseEmitter resourceCloseEmitter;

    /**
     * @param stmtGen owning statement generator used to recurse into try,
     *                catch, and finally bodies
     * @param exprGen expression generator used for resource initializers and
     *                throw expressions
     */
    public TryStatementEmitter(@NotNull StatementGenerator stmtGen, @NotNull ExpressionGenerator exprGen) {
        this.stmtGen = stmtGen;
        this.exprGen = exprGen;
        this.resourceCloseEmitter = new ResourceCloseEmitter(exprGen.ctx());
    }

    /**
     * @param tryStmt try statement node
     */
    public void emit(@NotNull TryStatement tryStmt) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        ctx.emitLine(tryStmt.line());

        int[] resourceSlots = new int[tryStmt.resources().size()];
        String[] resourceOwners = new String[tryStmt.resources().size()];
        boolean[] resourceOwnerIsInterface = new boolean[tryStmt.resources().size()];
        for (int i = 0; i < tryStmt.resources().size(); i++) {
            ResourceDeclaration res = tryStmt.resources().get(i);
            ResolvedType resType;
            if ("var".equals(res.type().name()) && res.type().arrayDimensions() == 0) {
                ResolvedType inferred = ctx.typeInferrer().infer(res.initializer());
                resType = inferred != null && inferred != ResolvedType.NULL ? inferred : ResolvedType.ofObject("java/lang/Object");
            } else {
                resType = ctx.typeResolver().resolve(res.type());
            }
            LocalVariable resVar = ctx.declareLocal(res.name(), resType);
            resourceSlots[i] = resVar.index();
            resourceOwners[i] = resType.internalName() != null ? resType.internalName() : "java/lang/AutoCloseable";
            resourceOwnerIsInterface[i] = resourceCloseEmitter.isResourceOwnerInterface(resourceOwners[i]);
            exprGen.generate(res.initializer());
            mv.visitVarInsn(Opcodes.ASTORE, resVar.index());
        }

        boolean hasResources = !tryStmt.resources().isEmpty();
        boolean hasFinally = tryStmt.finallyBlock() != null || hasResources;

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label finallyLabel = hasFinally ? new Label() : null;

        Label[] catchLabels = new Label[tryStmt.catchClauses().size()];
        for (int i = 0; i < tryStmt.catchClauses().size(); i++) {
            catchLabels[i] = new Label();
        }

        for (int i = 0; i < tryStmt.catchClauses().size(); i++) {
            CatchClause catchClause = tryStmt.catchClauses().get(i);
            for (TypeNode exType : catchClause.exceptionTypes()) {
                String internalName = ctx.typeResolver().resolveInternalName(exType);
                mv.visitTryCatchBlock(tryStart, tryEnd, catchLabels[i], internalName);
            }
        }

        if (finallyLabel != null) {
            mv.visitTryCatchBlock(tryStart, tryEnd, finallyLabel, null);
        }

        int savedNextIndex = ctx.scope().nextLocalIndex();

        Runnable finallyEmitter = () -> {
            int saved = ctx.scope().nextLocalIndex();
            ctx.scope().syncNextLocalIndex(Math.max(savedNextIndex, saved));
            resourceCloseEmitter.emitClose(mv, resourceSlots, resourceOwners, resourceOwnerIsInterface);
            if (tryStmt.finallyBlock() != null) emitFinallyBody(tryStmt.finallyBlock());
            ctx.scope().syncNextLocalIndex(saved);
        };
        if (finallyLabel != null) ctx.pushFinally(finallyEmitter);

        mv.visitLabel(tryStart);
        for (Statement stmt : tryStmt.tryBlock().statements()) {
            stmtGen.generate(stmt);
        }
        mv.visitLabel(tryEnd);
        if (finallyLabel != null) ctx.popFinally();

        int watermark = ctx.scope().nextLocalIndex();

        Label endLabel = new Label();
        if (finallyLabel != null) {
            ctx.scope().syncNextLocalIndex(savedNextIndex);
            resourceCloseEmitter.emitClose(mv, resourceSlots, resourceOwners, resourceOwnerIsInterface);
            if (tryStmt.finallyBlock() != null) emitFinallyBody(tryStmt.finallyBlock());
        }
        if (ctx.isReachable()) mv.visitJumpInsn(Opcodes.GOTO, endLabel);
        ctx.markUnreachable();

        for (int i = 0; i < tryStmt.catchClauses().size(); i++) {
            ctx.markReachable();
            mv.visitLabel(catchLabels[i]);

            CatchClause catchClause = tryStmt.catchClauses().get(i);
            ctx.scope().syncNextLocalIndex(savedNextIndex);
            List<TypeNode> exTypes = catchClause.exceptionTypes();
            ResolvedType exType;
            if (exTypes.isEmpty()) {
                exType = ResolvedType.ofObject("java/lang/Throwable");
            } else if (exTypes.size() == 1) {
                exType = ctx.typeResolver().resolve(exTypes.get(0));
            } else {
                exType = ResolvedType.ofObject("java/lang/Throwable");
            }
            LocalVariable exVar = ctx.declareLocal(catchClause.variableName(), exType);
            mv.visitVarInsn(Opcodes.ASTORE, exVar.index());

            if (finallyLabel != null) ctx.pushFinally(finallyEmitter);
            for (Statement stmt : catchClause.body().statements()) {
                stmtGen.generate(stmt);
            }
            if (finallyLabel != null) ctx.popFinally();
            ctx.scope().removeVariablesFrom(savedNextIndex);

            if (finallyLabel != null && ctx.isReachable()) {
                ctx.scope().syncNextLocalIndex(savedNextIndex);
                resourceCloseEmitter.emitClose(mv, resourceSlots, resourceOwners, resourceOwnerIsInterface);
                if (tryStmt.finallyBlock() != null) emitFinallyBody(tryStmt.finallyBlock());
            }
            boolean isLastCatch = i == tryStmt.catchClauses().size() - 1;
            boolean skipTrailingGoto = isLastCatch && finallyLabel == null;
            if (ctx.isReachable() && !skipTrailingGoto) mv.visitJumpInsn(Opcodes.GOTO, endLabel);
            ctx.markUnreachable();
        }

        if (finallyLabel != null) {
            ctx.markReachable();
            mv.visitLabel(finallyLabel);
            ctx.scope().syncNextLocalIndex(watermark);
            LocalVariable exVar = ctx.declareLocal("$finally" + tryStmt.line(), ResolvedType.ofObject("java/lang/Throwable"));
            mv.visitVarInsn(Opcodes.ASTORE, exVar.index());
            resourceCloseEmitter.emitCloseWithSuppressed(mv, resourceSlots, resourceOwners, resourceOwnerIsInterface, exVar.index());
            if (tryStmt.finallyBlock() != null) emitFinallyBody(tryStmt.finallyBlock());
            mv.visitVarInsn(Opcodes.ALOAD, exVar.index());
            mv.visitInsn(Opcodes.ATHROW);
            ctx.markUnreachable();
        }

        ctx.markReachable();
        mv.visitLabel(endLabel);
        ctx.scope().syncNextLocalIndex(savedNextIndex);
    }

    /**
     * @param finallyBlock finally block to walk
     */
    private void emitFinallyBody(@NotNull BlockStatement finallyBlock) {
        for (Statement stmt : finallyBlock.statements()) {
            stmtGen.generate(stmt);
        }
    }
}
