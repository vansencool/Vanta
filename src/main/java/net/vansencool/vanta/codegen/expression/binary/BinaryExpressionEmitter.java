package net.vansencool.vanta.codegen.expression.binary;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.classes.literal.LiteralParser;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.exception.CodeGenException;
import net.vansencool.vanta.codegen.expression.lambda.LambdaEmitter;
import net.vansencool.vanta.codegen.expression.util.arith.ArithmeticOpcodes;
import net.vansencool.vanta.codegen.expression.util.cmp.ComparisonOpcodes;
import net.vansencool.vanta.codegen.expression.util.literal.LiteralPredicates;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits bytecode for binary expressions: arithmetic, bitwise, shift,
 * comparison, reference equality, short-circuit logical ({@code &&}/{@code ||}),
 * and string concatenation via {@code invokedynamic} +
 * {@link java.lang.invoke.StringConcatFactory#makeConcatWithConstants}.
 * Routes each operator to the right JVM opcode after numeric promotion of
 * the operands.
 */
public final class BinaryExpressionEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for operand emission
     *                and shared numeric-promotion helpers
     */
    public BinaryExpressionEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Emits a binary expression. Dispatches to short-circuit + string-concat
     * paths first, then tries constant folding for integral operands, then
     * falls through to standard numeric-promoted operator emission.
     *
     * @param binary binary expression node
     */
    public void emit(@NotNull BinaryExpression binary) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        String op = binary.operator();

        if ("&&".equals(op)) {
            emitShortCircuitAnd(binary);
            return;
        }
        if ("||".equals(op)) {
            emitShortCircuitOr(binary);
            return;
        }

        if ("+".equals(op) && exprGen.isStringConcat(binary)) {
            emitStringConcat(binary);
            return;
        }

        Long folded = exprGen.constantEvaluator().foldLong(binary);
        if (folded != null) {
            ResolvedType t = ctx.typeInferrer().infer(binary);
            if (t != null && t.equals(ResolvedType.LONG)) {
                long lv = folded;
                if (lv == 0L) mv.visitInsn(Opcodes.LCONST_0);
                else if (lv == 1L) mv.visitInsn(Opcodes.LCONST_1);
                else mv.visitLdcInsn(lv);
            } else {
                int iv = folded.intValue();
                OpcodeUtils.pushInt(mv, iv);
            }
            return;
        }

        if ("==".equals(op) || "!=".equals(op)) {
            boolean isNull = LiteralPredicates.isNullLiteral(binary.left()) || LiteralPredicates.isNullLiteral(binary.right());
            if (isNull) {
                if (LiteralPredicates.isNullLiteral(binary.left())) {
                    exprGen.generate(binary.right());
                } else {
                    exprGen.generate(binary.left());
                }
                exprGen.numericCoercion().intComparison("==".equals(op) ? Opcodes.IFNULL : Opcodes.IFNONNULL);
                return;
            }
            boolean isRef = exprGen.isReferenceType(binary.left()) && exprGen.isReferenceType(binary.right());
            if (isRef) {
                exprGen.generate(binary.left());
                exprGen.generate(binary.right());
                exprGen.numericCoercion().intComparison("==".equals(op) ? Opcodes.IF_ACMPEQ : Opcodes.IF_ACMPNE);
                return;
            }
        }

        ResolvedType leftType = ctx.typeInferrer().infer(binary.left());
        ResolvedType rightType = ctx.typeInferrer().infer(binary.right());
        boolean isShift = "<<".equals(op) || ">>".equals(op) || ">>>".equals(op);
        String typeDesc = isShift ? (leftType != null && "J".equals(leftType.descriptor()) ? "J" : "I") : exprGen.numericCoercion().promote(leftType, rightType);

        boolean isCompareOp = "<".equals(op) || "<=".equals(op) || ">".equals(op) || ">=".equals(op) || "==".equals(op) || "!=".equals(op);
        if (isCompareOp && "I".equals(typeDesc) && exprGen.isIntZeroOrConst(binary.right())) {
            exprGen.generate(binary.left());
            exprGen.numericCoercion().widen(leftType, "I");
            exprGen.numericCoercion().intComparison(ComparisonOpcodes.zero(op));
            return;
        }
        if (isCompareOp && "I".equals(typeDesc) && exprGen.isIntZeroOrConst(binary.left())) {
            exprGen.generate(binary.right());
            exprGen.numericCoercion().widen(rightType, "I");
            exprGen.numericCoercion().intComparison(ComparisonOpcodes.flippedZero(op));
            return;
        }

        ResolvedType promoted = ResolvedType.fromDescriptor(typeDesc);
        boolean leftPromotedInPlace = LiteralPredicates.isIntLiteral(binary.left()) && !"I".equals(typeDesc);
        exprGen.generate(binary.left(), promoted);
        if (!leftPromotedInPlace) exprGen.numericCoercion().widen(leftType, typeDesc);
        if (isShift) {
            exprGen.generate(binary.right(), ResolvedType.INT);
            exprGen.numericCoercion().widen(rightType, "I");
        } else {
            boolean rightPromotedInPlace = LiteralPredicates.isIntLiteral(binary.right()) && !"I".equals(typeDesc);
            exprGen.generate(binary.right(), promoted);
            if (!rightPromotedInPlace) exprGen.numericCoercion().widen(rightType, typeDesc);
        }

        if (isCompareOp) {
            exprGen.numericCoercion().typedComparison(mv, op, typeDesc);
            return;
        }

        switch (op) {
            case "+" -> mv.visitInsn(ArithmeticOpcodes.add(typeDesc));
            case "-" -> mv.visitInsn(ArithmeticOpcodes.sub(typeDesc));
            case "*" -> mv.visitInsn(ArithmeticOpcodes.mul(typeDesc));
            case "/" -> mv.visitInsn(ArithmeticOpcodes.div(typeDesc));
            case "%" -> mv.visitInsn(ArithmeticOpcodes.rem(typeDesc));
            case "&" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LAND : Opcodes.IAND);
            case "|" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LOR : Opcodes.IOR);
            case "^" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LXOR : Opcodes.IXOR);
            case "<<" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LSHL : Opcodes.ISHL);
            case ">>" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LSHR : Opcodes.ISHR);
            case ">>>" -> mv.visitInsn("J".equals(typeDesc) ? Opcodes.LUSHR : Opcodes.IUSHR);
            default -> throw new CodeGenException("Unknown binary operator: " + op, binary.line());
        }
    }

    /**
     * Emits a short-circuit AND ({@code &&}) producing a boolean-as-int.
     *
     * @param binary binary expression with operator {@code &&}
     */
    private void emitShortCircuitAnd(@NotNull BinaryExpression binary) {
        MethodVisitor mv = exprGen.ctx().mv();
        Label falseLabel = new Label();
        Label endLabel = new Label();

        exprGen.conditionEmitter().jumpFalse(binary.left(), falseLabel);
        exprGen.conditionEmitter().jumpFalse(binary.right(), falseLabel);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        mv.visitLabel(falseLabel);
        mv.visitInsn(Opcodes.ICONST_0);

        mv.visitLabel(endLabel);
    }

    /**
     * Emits a short-circuit OR ({@code ||}) producing a boolean-as-int.
     *
     * @param binary binary expression with operator {@code ||}
     */
    private void emitShortCircuitOr(@NotNull BinaryExpression binary) {
        MethodVisitor mv = exprGen.ctx().mv();
        Label trueLabel = new Label();
        Label falseLabel = new Label();
        Label endLabel = new Label();

        exprGen.conditionEmitter().jumpTrue(binary.left(), trueLabel);
        exprGen.conditionEmitter().jumpFalse(binary.right(), falseLabel);

        mv.visitLabel(trueLabel);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        mv.visitLabel(falseLabel);
        mv.visitInsn(Opcodes.ICONST_0);

        mv.visitLabel(endLabel);
    }

    /**
     * Flattens a {@code +} chain of string-valued operands into a single
     * {@code makeConcatWithConstants} invokedynamic call so javac-compatible
     * concat layout is produced.
     *
     * @param binary binary expression whose whole chain concats into a string
     */
    private void emitStringConcat(@NotNull BinaryExpression binary) {
        MethodContext ctx = exprGen.ctx();
        List<Expression> parts = new ArrayList<>();
        exprGen.flattenConcat(binary, parts);

        StringBuilder recipe = new StringBuilder();
        StringBuilder descriptor = new StringBuilder("(");

        for (Expression part : parts) {
            if (part instanceof LiteralExpression lit && lit.literalType() == TokenType.STRING_LITERAL) {
                recipe.append(LiteralParser.stripStringQuotes(lit.value()));
            } else if (part instanceof LiteralExpression lit && lit.literalType() == TokenType.CHAR_LITERAL) {
                recipe.append(LiteralParser.parseCharLiteral(lit.value()));
            } else {
                exprGen.generate(part);
                recipe.append('\1');
                ResolvedType type = ctx.typeInferrer().infer(part);
                if (type == null || (type.internalName() != null && "java/lang/String".equals(type.internalName()))) {
                    descriptor.append("Ljava/lang/String;");
                } else {
                    String d = type.descriptor();
                    descriptor.append(LambdaEmitter.isValidDescriptor(d) ? d : "Ljava/lang/Object;");
                }
            }
        }
        descriptor.append(")Ljava/lang/String;");
        ctx.mv().visitInvokeDynamicInsn("makeConcatWithConstants", descriptor.toString(), exprGen.stringConcatHandle, recipe.toString());
    }
}
