package net.vansencool.vanta.codegen.expression.unary;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.classes.literal.LiteralParser;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.exception.CodeGenException;
import net.vansencool.vanta.codegen.expression.coercion.NumericCoercion;
import net.vansencool.vanta.codegen.expression.util.arith.ArithmeticOpcodes;
import net.vansencool.vanta.codegen.expression.util.dup.DupOpcodes;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.ArrayAccessExpression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits bytecode for unary expressions ({@code -}, {@code +}, {@code ~},
 * {@code !}, prefix/postfix {@code ++}/{@code --}). Handles boxed operands
 * by unboxing, applying the primitive op, and re-boxing so the result keeps
 * the source-declared wrapper type. Increment/decrement emission supports
 * locals, instance/static fields, and array elements.
 */
public final class UnaryExpressionEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for operand emission
     *                and unbox/widen helpers
     */
    public UnaryExpressionEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Emits a unary expression.
     *
     * @param unary unary expression node
     */
    public void emit(@NotNull UnaryExpression unary) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        String op = unary.operator();

        if (unary.isPrefix()) {
            switch (op) {
                case "-" -> {
                    if (unary.operand() instanceof LiteralExpression lit && lit.literalType() == TokenType.INT_LITERAL) {
                        OpcodeUtils.pushInt(mv, -LiteralParser.parseIntLiteral(lit.value()));
                    } else {
                        exprGen.generate(unary.operand());
                        ResolvedType operandType = ctx.typeInferrer().infer(unary.operand());
                        String desc = operandType != null ? operandType.descriptor() : "I";
                        String prim = exprGen.numericCoercion().primitiveForWrapperDesc(desc);
                        String wrapper = null;
                        String unboxedDesc = desc;
                        if (prim != null) {
                            wrapper = desc.substring(1, desc.length() - 1);
                            exprGen.unboxingEmitter().withCast(mv, prim, wrapper);
                            unboxedDesc = prim;
                        }
                        mv.visitInsn(switch (unboxedDesc) {
                            case "J" -> Opcodes.LNEG;
                            case "F" -> Opcodes.FNEG;
                            case "D" -> Opcodes.DNEG;
                            default -> Opcodes.INEG;
                        });
                        if (wrapper != null) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper, "valueOf", "(" + unboxedDesc + ")L" + wrapper + ";", false);
                        }
                    }
                }
                case "+" -> exprGen.generate(unary.operand());
                case "~" -> {
                    Long folded = exprGen.constantEvaluator().longValue(unary);
                    ResolvedType operandType = ctx.typeInferrer().infer(unary.operand());
                    String desc = operandType != null ? operandType.descriptor() : "I";
                    String prim = exprGen.numericCoercion().primitiveForWrapperDesc(desc);
                    boolean isLong = "J".equals(desc) || "Ljava/lang/Long;".equals(desc);
                    if (folded != null) {
                        if (isLong) mv.visitLdcInsn(folded);
                        else OpcodeUtils.pushInt(mv, folded.intValue());
                    } else {
                        exprGen.generate(unary.operand());
                        String wrapper = null;
                        if (prim != null) {
                            wrapper = desc.substring(1, desc.length() - 1);
                            exprGen.unboxingEmitter().withCast(mv, prim, wrapper);
                        }
                        if (isLong) {
                            mv.visitLdcInsn(-1L);
                            mv.visitInsn(Opcodes.LXOR);
                        } else {
                            mv.visitInsn(Opcodes.ICONST_M1);
                            mv.visitInsn(Opcodes.IXOR);
                        }
                        if (wrapper != null) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper, "valueOf", "(" + prim + ")L" + wrapper + ";", false);
                        }
                    }
                }
                case "!" -> {
                    exprGen.generate(unary.operand());
                    Label trueLabel = new Label();
                    Label endLabel = new Label();
                    mv.visitJumpInsn(Opcodes.IFEQ, trueLabel);
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitJumpInsn(Opcodes.GOTO, endLabel);
                    mv.visitLabel(trueLabel);
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitLabel(endLabel);
                }
                case "++" -> emitIncDec(unary, 1, true);
                case "--" -> emitIncDec(unary, -1, true);
                default -> throw new CodeGenException("Unknown prefix operator: " + op, unary.line());
            }
        } else {
            switch (op) {
                case "++" -> emitIncDec(unary, 1, false);
                case "--" -> emitIncDec(unary, -1, false);
                default -> throw new CodeGenException("Unknown postfix operator: " + op, unary.line());
            }
        }
    }

    /**
     * Emits bytecode for {@code ++x} / {@code --x} / {@code x++} / {@code x--}
     * against a local, instance/static field, or array element. Leaves the
     * pre-op (postfix) or post-op (prefix) value on the operand stack.
     *
     * @param unary  unary node carrying the operand to increment
     * @param delta  {@code +1} for increment, {@code -1} for decrement
     * @param prefix true for prefix form, false for postfix
     */
    private void emitIncDec(@NotNull UnaryExpression unary, int delta, boolean prefix) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        if (unary.operand() instanceof NameExpression nameExpr) {
            LocalVariable local = ctx.scope().resolve(nameExpr.name());
            if (local != null) {
                if (prefix) {
                    mv.visitIincInsn(local.index(), delta);
                    mv.visitVarInsn(Opcodes.ILOAD, local.index());
                } else {
                    mv.visitVarInsn(Opcodes.ILOAD, local.index());
                    mv.visitIincInsn(local.index(), delta);
                }
                return;
            }
            ResolvedType fieldType = ctx.typeInferrer().inferField(nameExpr.name());
            if (fieldType != null) {
                MethodResolver.ResolvedField resolved = ctx.methodResolver().resolveField(ctx.classInternalName(), nameExpr.name());
                boolean isStatic = ctx.isStatic() || (resolved != null && resolved.isStatic());
                String desc = fieldType.descriptor();
                if (!isStatic) mv.visitVarInsn(Opcodes.ALOAD, 0);
                emitFieldIncDec(mv, ctx.classInternalName(), nameExpr.name(), desc, isStatic, delta, prefix);
                return;
            }
            return;
        }
        if (unary.operand() instanceof FieldAccessExpression fa) {
            ResolvedType targetType = ctx.typeInferrer().infer(fa.target());
            if (targetType == null || targetType.internalName() == null) return;
            MethodResolver.ResolvedField resolved = ctx.methodResolver().resolveField(targetType.internalName(), fa.fieldName());
            if (resolved == null) return;
            if (resolved.isStatic()) {
                emitFieldIncDec(mv, resolved.owner(), resolved.name(), resolved.descriptor(), true, delta, prefix);
            } else {
                exprGen.generate(fa.target());
                emitFieldIncDec(mv, resolved.owner(), resolved.name(), resolved.descriptor(), false, delta, prefix);
            }
            return;
        }
        if (unary.operand() instanceof ArrayAccessExpression ae) {
            ResolvedType elemType = ctx.typeInferrer().infer(ae);
            String desc = elemType != null ? elemType.descriptor() : "I";
            exprGen.generate(ae.array());
            exprGen.generate(ae.index());
            mv.visitInsn(Opcodes.DUP2);
            mv.visitInsn(OpcodeUtils.arrayLoadOpcode(elemType != null ? elemType : ResolvedType.INT));
            if (!prefix) mv.visitInsn(DupOpcodes.dupX2(desc));
            ArithmeticOpcodes.emitDeltaPush(mv, desc, delta);
            mv.visitInsn(ArithmeticOpcodes.addOrSub(desc, delta));
            if (prefix) mv.visitInsn(DupOpcodes.dupX2(desc));
            mv.visitInsn(OpcodeUtils.arrayStoreOpcode(elemType != null ? elemType : ResolvedType.INT));
        }
    }

    /**
     * Emits the inc/dec sequence for a field. Variants handle static vs
     * instance access; the instance path assumes the receiver is already on
     * the stack so the caller can control how that receiver is loaded.
     *
     * @param mv       target method visitor
     * @param owner    field owner internal name
     * @param name     field name
     * @param desc     field descriptor
     * @param isStatic true for static fields
     * @param delta    {@code +1} or {@code -1}
     * @param prefix   true for prefix form (stack keeps post-op value)
     */
    private void emitFieldIncDec(@NotNull MethodVisitor mv, @NotNull String owner, @NotNull String name, @NotNull String desc, boolean isStatic, int delta, boolean prefix) {
        if (isStatic) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, name, desc);
            if (!prefix) mv.visitInsn(DupOpcodes.dup(desc));
            ArithmeticOpcodes.emitDeltaPush(mv, desc, delta);
            mv.visitInsn(ArithmeticOpcodes.addOrSub(desc, delta));
            NumericCoercion.emitNarrowForSubIntDesc(mv, desc);
            if (prefix) mv.visitInsn(DupOpcodes.dup(desc));
            mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, name, desc);
        } else {
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, name, desc);
            if (!prefix) {
                mv.visitInsn(DupOpcodes.dupX1(desc));
            }
            ArithmeticOpcodes.emitDeltaPush(mv, desc, delta);
            mv.visitInsn(ArithmeticOpcodes.addOrSub(desc, delta));
            NumericCoercion.emitNarrowForSubIntDesc(mv, desc);
            if (prefix) mv.visitInsn(DupOpcodes.dupX1(desc));
            mv.visitFieldInsn(Opcodes.PUTFIELD, owner, name, desc);
        }
    }
}
