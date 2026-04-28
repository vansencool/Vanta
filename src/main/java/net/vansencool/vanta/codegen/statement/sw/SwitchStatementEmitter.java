package net.vansencool.vanta.codegen.statement.sw;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.StatementGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.expression.util.desc.DescriptorUtils;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.statement.SwitchCase;
import net.vansencool.vanta.parser.ast.statement.SwitchStatement;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits bytecode for {@code switch} statements over int, enum, and string
 * selectors. Uses {@code SwitchDispatch} for the actual int and string
 * dispatch tables, then walks each arm to lower the body.
 */
public final class SwitchStatementEmitter {

    private final @NotNull StatementGenerator stmtGen;
    private final @NotNull ExpressionGenerator exprGen;
    private final @NotNull SwitchKeyResolver keyResolver;

    /**
     * @param stmtGen owning statement generator used to recurse into case
     *                bodies
     * @param exprGen expression generator used to evaluate the selector and
     *                reach shared switch dispatch helpers
     */
    public SwitchStatementEmitter(@NotNull StatementGenerator stmtGen, @NotNull ExpressionGenerator exprGen) {
        this.stmtGen = stmtGen;
        this.exprGen = exprGen;
        this.keyResolver = new SwitchKeyResolver(exprGen.ctx());
    }

    /**
     * @param switchStmt switch statement node
     */
    public void emit(@NotNull SwitchStatement switchStmt) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        ctx.emitLine(switchStmt.line());

        ResolvedType selType = ctx.typeInferrer().infer(switchStmt.selector());
        boolean isEnumSelector = selType != null && selType.internalName() != null && keyResolver.isEnum(selType.internalName());
        boolean isStringSelector = !isEnumSelector && selType != null && "java/lang/String".equals(selType.internalName());

        Label endLabel = new Label();
        ctx.labelContext().pushLoop(endLabel, null, stmtGen.consumePendingLabel());

        int stringSelectorSlot = -1;
        int stringOrdinalSlot = -1;
        if (isStringSelector) {
            exprGen.generate(switchStmt.selector());
            stringSelectorSlot = ctx.scope().declare("$switchStr" + switchStmt.line() + "$" + System.identityHashCode(switchStmt), ResolvedType.ofObject("java/lang/String")).index();
            mv.visitVarInsn(Opcodes.ASTORE, stringSelectorSlot);
            stringOrdinalSlot = ctx.scope().declare("$switchOrd" + switchStmt.line() + "$" + System.identityHashCode(switchStmt), ResolvedType.INT).index();
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
                exprGen.generate(switchStmt.selector());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, selType.internalName(), "ordinal", "()I", false);
                mv.visitInsn(Opcodes.IALOAD);
            } else {
                exprGen.generate(switchStmt.selector());
                if (isEnumSelector) {
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, selType.internalName(), "ordinal", "()I", false);
                }
            }
        }

        int caseCount = 0;
        for (SwitchCase sc : switchStmt.cases()) {
            if (!sc.isDefault() && sc.labels() != null) caseCount += sc.labels().size();
        }

        Label defaultLabel = endLabel;
        Label[] caseLabelsSource = new Label[caseCount];
        int[] keys = new int[caseCount];
        int idx = 0;

        for (SwitchCase sc : switchStmt.cases()) {
            if (sc.isDefault()) {
                defaultLabel = new Label();
                continue;
            }
            if (sc.labels() != null) {
                for (Expression lab : sc.labels()) {
                    caseLabelsSource[idx] = new Label();
                    keys[idx] = keyResolver.caseKey(lab, isEnumSelector, selType);
                    idx++;
                }
            }
        }

        Label[] caseLabels = caseLabelsSource.clone();

        if (isStringSelector && caseCount > 0) {
            List<String> caseStrings = new ArrayList<>();
            for (SwitchCase sc : switchStmt.cases()) {
                if (sc.isDefault() || sc.labels() == null) continue;
                for (Expression lab : sc.labels()) caseStrings.add(exprGen.switchDispatch().stringLiteralValue(lab));
            }
            exprGen.switchDispatch().emitStringDispatch(mv, stringSelectorSlot, stringOrdinalSlot, caseStrings, keys, caseLabelsSource, defaultLabel);
        } else if (caseCount > 0) {
            DescriptorUtils.sortKeysAndLabels(keys, caseLabels);
            exprGen.switchDispatch().emitIntSwitch(mv, keys, caseLabels, defaultLabel);
        } else {
            mv.visitJumpInsn(Opcodes.GOTO, defaultLabel);
        }

        idx = 0;
        for (SwitchCase sc : switchStmt.cases()) {
            if (sc.isDefault()) {
                ctx.markReachable();
                mv.visitLabel(defaultLabel);
            } else if (sc.labels() != null) {
                for (int i = 0; i < sc.labels().size(); i++) {
                    ctx.markReachable();
                    mv.visitLabel(caseLabelsSource[idx]);
                    idx++;
                }
            }
            for (Statement stmt : sc.statements()) {
                stmtGen.generate(stmt);
            }
            if (sc.isArrow() && ctx.isReachable()) {
                mv.visitJumpInsn(Opcodes.GOTO, endLabel);
                ctx.markUnreachable();
            }
        }

        ctx.markReachable();
        mv.visitLabel(endLabel);
        ctx.labelContext().popLoop(null);
    }
}
