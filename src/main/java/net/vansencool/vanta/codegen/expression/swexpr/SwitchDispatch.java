package net.vansencool.vanta.codegen.expression.swexpr;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.exception.CodeGenException;
import net.vansencool.vanta.codegen.expression.util.desc.DescriptorUtils;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Switch-table emission shared by switch statements and switch expressions.
 * Owns the cost heuristic that picks between {@code TABLESWITCH} and
 * {@code LOOKUPSWITCH}, the two-level hash-dispatch used for {@code String}
 * switches, and extraction of literal values for case labels.
 */
public final class SwitchDispatch {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for parenthesis
     *                unwrapping when pulling values out of case labels
     */
    public SwitchDispatch(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Emits the hash-then-equality dispatch for a {@code String} switch.
     * Each unique {@link String#hashCode()} group is probed with
     * {@code String.equals}, and matching case indices are stashed in the
     * ordinal slot so the follow-up {@code TABLESWITCH} jumps to the right
     * case body. Matches the shape javac emits for string switches.
     *
     * @param mv                 method visitor to emit into
     * @param stringSelectorSlot local slot holding the selector string
     * @param stringOrdinalSlot  local slot receiving the matched case ordinal
     * @param caseStrings        unquoted case string values
     * @param keys               parallel hash codes for {@code caseStrings}
     * @param caseLabels         labels aligned with {@code caseStrings}
     * @param defaultLabel       fallthrough label for unmatched selectors
     */
    public void emitStringDispatch(@NotNull MethodVisitor mv, int stringSelectorSlot, int stringOrdinalSlot,
                                   @NotNull List<String> caseStrings, int[] keys,
                                   @NotNull Label[] caseLabels, @NotNull Label defaultLabel) {
        int caseCount = caseStrings.size();
        LinkedHashMap<Integer, List<Integer>> hashGroups = new LinkedHashMap<>();
        for (int i = 0; i < caseCount; i++) {
            hashGroups.computeIfAbsent(keys[i], k -> new ArrayList<>()).add(i);
        }
        int[] dispatchKeys = new int[hashGroups.size()];
        Label[] dispatchLabels = new Label[hashGroups.size()];
        int di = 0;
        for (Integer hash : hashGroups.keySet()) {
            dispatchKeys[di] = hash;
            dispatchLabels[di] = new Label();
            di++;
        }
        DescriptorUtils.sortKeysAndLabels(dispatchKeys, dispatchLabels);
        Label second = new Label();
        emitIntSwitch(mv, dispatchKeys, dispatchLabels, second);
        for (Map.Entry<Integer, List<Integer>> entry : hashGroups.entrySet()) {
            mv.visitLabel(dispatchLabels[lookupDispatchIndex(dispatchKeys, entry.getKey())]);
            List<Integer> caseIdxs = entry.getValue();
            for (int i = 0; i < caseIdxs.size(); i++) {
                int caseIdx = caseIdxs.get(i);
                mv.visitVarInsn(Opcodes.ALOAD, stringSelectorSlot);
                mv.visitLdcInsn(caseStrings.get(caseIdx));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                boolean isLast = i == caseIdxs.size() - 1;
                if (isLast) {
                    mv.visitJumpInsn(Opcodes.IFEQ, second);
                    OpcodeUtils.pushInt(mv, caseIdx);
                    mv.visitVarInsn(Opcodes.ISTORE, stringOrdinalSlot);
                } else {
                    Label next = new Label();
                    mv.visitJumpInsn(Opcodes.IFEQ, next);
                    OpcodeUtils.pushInt(mv, caseIdx);
                    mv.visitVarInsn(Opcodes.ISTORE, stringOrdinalSlot);
                    mv.visitJumpInsn(Opcodes.GOTO, second);
                    mv.visitLabel(next);
                }
            }
            mv.visitJumpInsn(Opcodes.GOTO, second);
        }
        mv.visitLabel(second);
        mv.visitVarInsn(Opcodes.ILOAD, stringOrdinalSlot);
        mv.visitTableSwitchInsn(0, caseCount - 1, defaultLabel, caseLabels);
    }

    /**
     * Emits a {@code TABLESWITCH} or {@code LOOKUPSWITCH} using javac's cost
     * heuristic: compares the space cost of a {@code TABLESWITCH} that covers
     * the full key range ({@code 4 * range + 16} bytes) against a
     * {@code LOOKUPSWITCH} listing every key explicitly ({@code 8 * N + 12}
     * bytes) and picks the smaller. Used for the hash-lookup layer of string
     * switches so dense dispatches emit {@code TABLESWITCH} like javac.
     *
     * @param mv           method visitor to emit into
     * @param sortedKeys   sorted switch keys
     * @param sortedLabels labels aligned with {@code sortedKeys}
     * @param defaultLabel fallthrough label
     */
    public void emitIntSwitch(@NotNull MethodVisitor mv, int @NotNull [] sortedKeys,
                              @NotNull Label[] sortedLabels, @NotNull Label defaultLabel) {
        int n = sortedKeys.length;
        if (n == 0) {
            mv.visitJumpInsn(Opcodes.GOTO, defaultLabel);
            return;
        }
        long range = (long) sortedKeys[n - 1] - (long) sortedKeys[0] + 1L;
        long tableSpace = 4L + range;
        long lookupSpace = 3L + 2L * n;
        long tableTime = 3L;
        if (range < Integer.MAX_VALUE && tableSpace + 3L * tableTime <= lookupSpace + 3L * n) {
            Label[] table = new Label[(int) range];
            int ki = 0;
            for (int i = 0; i < range; i++) {
                int k = sortedKeys[0] + i;
                if (ki < n && sortedKeys[ki] == k) table[i] = sortedLabels[ki++];
                else table[i] = defaultLabel;
            }
            mv.visitTableSwitchInsn(sortedKeys[0], sortedKeys[n - 1], defaultLabel, table);
        } else {
            mv.visitLookupSwitchInsn(defaultLabel, sortedKeys, sortedLabels);
        }
    }

    /**
     * Extracts the literal string value of a case label expression, stripping
     * surrounding double quotes.
     *
     * @param expr case label expression
     * @return the unquoted string value
     * @throws CodeGenException when {@code expr} is not a string literal
     */
    public @NotNull String stringLiteralValue(@NotNull Expression expr) {
        Expression cur = exprGen.unwrapParens(expr);
        if (cur instanceof LiteralExpression lit && lit.literalType() == TokenType.STRING_LITERAL) {
            String v = lit.value();
            if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            return v;
        }
        throw new CodeGenException("Expected string literal in switch case", expr.line());
    }

    /**
     * @param sortedKeys dispatch hash keys, sorted ascending
     * @param hash       hash code to locate
     * @return index of {@code hash} in {@code sortedKeys}, or -1
     */
    private int lookupDispatchIndex(int @NotNull [] sortedKeys, int hash) {
        for (int i = 0; i < sortedKeys.length; i++) if (sortedKeys[i] == hash) return i;
        return -1;
    }
}
