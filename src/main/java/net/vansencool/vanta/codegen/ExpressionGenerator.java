package net.vansencool.vanta.codegen;

import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.exception.CodeGenException;
import net.vansencool.vanta.codegen.expression.array.ArrayEmitter;
import net.vansencool.vanta.codegen.expression.assign.AssignmentEmitter;
import net.vansencool.vanta.codegen.expression.binary.BinaryExpressionEmitter;
import net.vansencool.vanta.codegen.expression.call.MethodArgumentEmitter;
import net.vansencool.vanta.codegen.expression.call.MethodCallEmitter;
import net.vansencool.vanta.codegen.expression.call.MethodResolutionHelper;
import net.vansencool.vanta.codegen.expression.cast.CastCoercionEmitter;
import net.vansencool.vanta.codegen.expression.cast.UnboxingEmitter;
import net.vansencool.vanta.codegen.expression.coercion.NumericCoercion;
import net.vansencool.vanta.codegen.expression.cond.ConditionEmitter;
import net.vansencool.vanta.codegen.expression.constant.ConstantEvaluator;
import net.vansencool.vanta.codegen.expression.constant.ConstantInliner;
import net.vansencool.vanta.codegen.expression.field.FieldAccessEmitter;
import net.vansencool.vanta.codegen.expression.lambda.LambdaEmitter;
import net.vansencool.vanta.codegen.expression.literal.LiteralEmitter;
import net.vansencool.vanta.codegen.expression.newobj.ObjectCreationEmitter;
import net.vansencool.vanta.codegen.expression.swexpr.SwitchDispatch;
import net.vansencool.vanta.codegen.expression.swexpr.SwitchExpressionEmitter;
import net.vansencool.vanta.codegen.expression.unary.UnaryExpressionEmitter;
import net.vansencool.vanta.codegen.expression.util.arith.ArithmeticOpcodes;
import net.vansencool.vanta.codegen.expression.util.literal.LiteralPredicates;
import net.vansencool.vanta.codegen.expression.walker.ExpressionWalker;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.ArrayAccessExpression;
import net.vansencool.vanta.parser.ast.expression.ArrayInitializerExpression;
import net.vansencool.vanta.parser.ast.expression.AssignmentExpression;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.CastExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.InstanceofExpression;
import net.vansencool.vanta.parser.ast.expression.LambdaExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.expression.MethodReferenceExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.NewArrayExpression;
import net.vansencool.vanta.parser.ast.expression.NewExpression;
import net.vansencool.vanta.parser.ast.expression.ParenExpression;
import net.vansencool.vanta.parser.ast.expression.SuperExpression;
import net.vansencool.vanta.parser.ast.expression.SwitchExpression;
import net.vansencool.vanta.parser.ast.expression.TernaryExpression;
import net.vansencool.vanta.parser.ast.expression.ThisExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.Map;

/**
 * Generates bytecode for all expression types.
 * Each generate method pushes a value onto the operand stack.
 */
@SuppressWarnings("DuplicateExpressions")
public final class ExpressionGenerator {

    private static final String STRING_CONCAT_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";
    public final @NotNull Handle stringConcatHandle = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants", STRING_CONCAT_DESC, false);
    private final @NotNull MethodContext ctx;
    private final @NotNull LambdaEmitter lambdaEmitter = new LambdaEmitter(this);
    private final @NotNull ObjectCreationEmitter objectCreationEmitter = new ObjectCreationEmitter(this);
    private final @NotNull BinaryExpressionEmitter binaryExpressionEmitter = new BinaryExpressionEmitter(this);
    private final @NotNull UnaryExpressionEmitter unaryExpressionEmitter = new UnaryExpressionEmitter(this);
    private final @NotNull AssignmentEmitter assignmentEmitter = new AssignmentEmitter(this);
    private final @NotNull ArrayEmitter arrayEmitter = new ArrayEmitter(this);
    private final @NotNull FieldAccessEmitter fieldAccessEmitter = new FieldAccessEmitter(this);
    private final @NotNull CastCoercionEmitter castCoercionEmitter = new CastCoercionEmitter(this);
    private final @NotNull MethodCallEmitter methodCallEmitter = new MethodCallEmitter(this);
    private final @NotNull SwitchExpressionEmitter switchExpressionEmitter = new SwitchExpressionEmitter(this);
    private final @NotNull ConditionEmitter conditionEmitter = new ConditionEmitter(this);
    private final @NotNull UnboxingEmitter unboxingEmitter = new UnboxingEmitter(this);
    private final @NotNull ConstantEvaluator constantEvaluator = new ConstantEvaluator(this);
    private final @NotNull ConstantInliner constantInliner = new ConstantInliner(this);
    private final @NotNull SwitchDispatch switchDispatch = new SwitchDispatch(this);
    private final @NotNull ExpressionWalker expressionWalker = new ExpressionWalker(this);
    private final @NotNull MethodResolutionHelper methodResolutionHelper = new MethodResolutionHelper(this);
    private final @NotNull MethodArgumentEmitter methodArgumentEmitter = new MethodArgumentEmitter(this);
    private final @NotNull NumericCoercion numericCoercion = new NumericCoercion(this);
    private int discardDepth = 0;
    private @Nullable String lastCheckcastType = null;
    private @Nullable String lastEmittedAnonInternal = null;
    private @Nullable ResolvedType currentReceiverType;
    private boolean suppressGenericReturnCheckcast = false;

    /**
     * Creates an expression generator.
     *
     * @param ctx the method context
     */
    public ExpressionGenerator(@NotNull MethodContext ctx) {
        this.ctx = ctx;
    }

    /**
     * @return array emitter shared with other expression emitters
     */
    public @NotNull ArrayEmitter arrayEmitter() {
        return arrayEmitter;
    }

    /**
     * @return shared static final inliner
     */
    public @NotNull ConstantInliner constantInliner() {
        return constantInliner;
    }

    /**
     * @return shared numeric promotion / widening / narrowing helper
     */
    public @NotNull NumericCoercion numericCoercion() {
        return numericCoercion;
    }

    /**
     * @return AST walker shared for lambda/anonymous-class capture detection
     */
    public @NotNull ExpressionWalker expressionWalker() {
        return expressionWalker;
    }

    /**
     * @return shared method and constructor resolution helper
     */
    public @NotNull MethodResolutionHelper methodResolutionHelper() {
        return methodResolutionHelper;
    }

    /**
     * @return shared method call argument emission helper
     */
    public @NotNull MethodArgumentEmitter methodArgumentEmitter() {
        return methodArgumentEmitter;
    }

    /**
     * @return shared constant-folding / static-final resolver
     */
    public @NotNull ConstantEvaluator constantEvaluator() {
        return constantEvaluator;
    }

    /**
     * @return switch-dispatch emitter shared by expression and statement forms
     */
    public @NotNull SwitchDispatch switchDispatch() {
        return switchDispatch;
    }

    /**
     * @return condition emitter used for jump-form lowering of boolean conditions
     */
    public @NotNull ConditionEmitter conditionEmitter() {
        return conditionEmitter;
    }

    /**
     * @return unboxing emitter shared with other expression emitters
     */
    public @NotNull UnboxingEmitter unboxingEmitter() {
        return unboxingEmitter;
    }

    /**
     * @return object-creation emitter used to walk outer-class chains
     */
    public @NotNull ObjectCreationEmitter objectCreationEmitter() {
        return objectCreationEmitter;
    }

    /**
     * @param internal internal name of the most-recently emitted anonymous class
     */
    public void lastEmittedAnonInternal(@Nullable String internal) {
        this.lastEmittedAnonInternal = internal;
    }

    /**
     * @param internal internal name of the most-recently emitted {@code CHECKCAST} target
     */
    public void lastCheckcastType(@Nullable String internal) {
        this.lastCheckcastType = internal;
    }

    /**
     * @param suppress whether cast emission should skip the trailing generic-return
     *                 {@code CHECKCAST}
     */
    public void suppressGenericReturnCheckcast(boolean suppress) {
        this.suppressGenericReturnCheckcast = suppress;
    }

    /**
     * @return current generic-return CHECKCAST suppression flag
     */
    public boolean suppressGenericReturnCheckcast() {
        return suppressGenericReturnCheckcast;
    }

    /**
     * @return current discard nesting depth used to skip CHECKCAST when result is unused
     */
    public int discardDepth() {
        return discardDepth;
    }

    /**
     * @param depth new discard-nesting depth
     */
    public void discardDepth(int depth) {
        this.discardDepth = depth;
    }

    /**
     * @return internal name of the most-recently emitted anonymous class, or null
     */
    public @Nullable String lastEmittedAnonInternal() {
        return lastEmittedAnonInternal;
    }

    /**
     * @return currently-active receiver type when emitting a method call, or null
     */
    public @Nullable ResolvedType currentReceiverType() {
        return currentReceiverType;
    }

    /**
     * @param type receiver type for the currently-emitting method call
     */
    public void currentReceiverType(@Nullable ResolvedType type) {
        this.currentReceiverType = type;
    }

    public boolean isIntZeroOrConst(@NotNull Expression expr) {
        if (LiteralPredicates.isIntZero(expr)) return true;
        Integer v = constantEvaluator.intValue(expr);
        return v != null && v == 0;
    }

    /**
     * Flattens {@code FieldAccessExpression} chain or {@code NameExpression}
     * into dotted name like {@code "Date"}, so {@code Foo.Bar.class}
     * literals resolve. Returns null when any node breaks the chain (method
     * call, array access, etc.), so callers fall back to normal field access.
     */
    public @Nullable String flattenTypeName(@NotNull Expression expr) {
        if (expr instanceof NameExpression ne) return ne.name();
        if (expr instanceof FieldAccessExpression fa) {
            String inner = flattenTypeName(fa.target());
            if (inner == null) return null;
            return inner + "." + fa.fieldName();
        }
        return null;
    }

    public boolean isSubtypeOf(@NotNull String childInternal, @NotNull String parentInternal) {
        if (childInternal.equals(parentInternal)) return true;
        Class<?> c = ctx.methodResolver().classpathManager().loadClass(childInternal);
        Class<?> p = ctx.methodResolver().classpathManager().loadClass(parentInternal);
        if (c != null && p != null) return p.isAssignableFrom(c);
        return false;
    }

    public @NotNull MethodContext ctx() {
        return ctx;
    }

    /**
     * Generates bytecode for any expression with no expected coercion target.
     * Equivalent to {@code generate(expr, null)}.
     *
     * @param expr the expression
     */
    public void generate(@NotNull Expression expr) {
        generate(expr, null);
    }

    /**
     * Generates bytecode for any expression, dispatching to the appropriate method.
     * The {@code expected} type is the slot the produced value will flow into and is
     * threaded only to consumers that need it (literals for I2L/I2F/I2D coercion,
     * lambdas/method-references for SAM target, generic-erased return checkcast, etc).
     * It is NOT propagated to nested subexpressions automatically.
     *
     * @param expr     the expression
     * @param expected the expected target type for {@code expr}, or null if none
     */
    public void generate(@NotNull Expression expr, @Nullable ResolvedType expected) {
        if (expr instanceof LiteralExpression lit) {
            LiteralEmitter.emit(ctx.mv(), lit, expected);
        } else if (expr instanceof NameExpression name) {
            generateName(name);
        } else if (expr instanceof BinaryExpression binary) {
            binaryExpressionEmitter.emit(binary);
        } else if (expr instanceof UnaryExpression unary) {
            unaryExpressionEmitter.emit(unary);
        } else if (expr instanceof AssignmentExpression assignment) {
            assignmentEmitter.emit(assignment);
        } else if (expr instanceof MethodCallExpression call) {
            methodCallEmitter.emit(call);
        } else if (expr instanceof FieldAccessExpression fieldAccess) {
            fieldAccessEmitter.emit(fieldAccess, expected);
        } else if (expr instanceof ArrayAccessExpression arrayAccess) {
            arrayEmitter.emitArrayAccess(arrayAccess);
        } else if (expr instanceof NewExpression newExpr) {
            objectCreationEmitter.emitNew(newExpr);
        } else if (expr instanceof NewArrayExpression newArray) {
            arrayEmitter.emitNewArray(newArray);
        } else if (expr instanceof CastExpression cast) {
            castCoercionEmitter.emit(cast);
        } else if (expr instanceof InstanceofExpression instanceOf) {
            conditionEmitter.emitInstanceof(instanceOf);
        } else if (expr instanceof TernaryExpression ternary) {
            switchExpressionEmitter.emitTernary(ternary, expected);
        } else if (expr instanceof ThisExpression) {
            generateThis();
        } else if (expr instanceof SuperExpression) {
            generateSuper();
        } else if (expr instanceof ParenExpression paren) {
            generate(paren.expression(), expected);
        } else if (expr instanceof ArrayInitializerExpression arrayInit) {
            arrayEmitter.emitArrayInitializer(arrayInit, expected);
        } else if (expr instanceof SwitchExpression switchExpr) {
            switchExpressionEmitter.emitSwitch(switchExpr, expected);
        } else if (expr instanceof LambdaExpression lambda) {
            lambdaEmitter.emitLambda(lambda, expected);
        } else if (expr instanceof MethodReferenceExpression methodRef) {
            lambdaEmitter.emitMethodReference(methodRef, expected);
        } else {
            throw new CodeGenException("Unsupported expression type: " + expr.getClass().getSimpleName(), expr.line());
        }
    }

    /**
     * Generates bytecode for an expression and discards the result (pop from stack).
     * Used for expression statements.
     *
     * @param expr the expression
     */
    public void generateAndDiscard(@NotNull Expression expr) {
        if (expr instanceof AssignmentExpression assignment) {
            assignmentEmitter.emitDiscard(assignment);
            return;
        }
        if (expr instanceof MethodCallExpression call) {
            discardDepth++;
            boolean produced;
            try {
                produced = methodCallEmitter.emit(call);
            } finally {
                discardDepth--;
            }
            if (produced) {
                ResolvedType returnType = ctx.typeInferrer().infer(call);
                if (returnType != null && (returnType.equals(ResolvedType.LONG) || returnType.equals(ResolvedType.DOUBLE))) {
                    ctx.mv().visitInsn(Opcodes.POP2);
                } else {
                    ctx.mv().visitInsn(Opcodes.POP);
                }
            }
            return;
        }
        if (expr instanceof UnaryExpression unary && ("++".equals(unary.operator()) || "--".equals(unary.operator())) && unary.operand() instanceof NameExpression nameExpr) {
            int delta = "++".equals(unary.operator()) ? 1 : -1;
            LocalVariable local = ctx.scope().resolve(nameExpr.name());
            if (local != null) {
                String localDesc = local.type().descriptor();
                MethodVisitor mv = ctx.mv();
                if ("J".equals(localDesc) || "F".equals(localDesc) || "D".equals(localDesc)) {
                    mv.visitVarInsn(OpcodeUtils.loadOpcode(local.type()), local.index());
                    ArithmeticOpcodes.emitDeltaPush(mv, localDesc, delta);
                    mv.visitInsn(ArithmeticOpcodes.addOrSub(localDesc, delta));
                    mv.visitVarInsn(OpcodeUtils.storeOpcode(local.type()), local.index());
                } else {
                    mv.visitIincInsn(local.index(), delta);
                }
                return;
            }
            ResolvedType fieldType = ctx.typeInferrer().inferField(nameExpr.name());
            if (fieldType != null) {
                MethodResolver.ResolvedField resolved = ctx.methodResolver().resolveField(ctx.classInternalName(), nameExpr.name());
                boolean isStatic = ctx.isStatic() || (resolved != null && resolved.isStatic());
                String desc = fieldType.descriptor();
                MethodVisitor mv = ctx.mv();
                if (isStatic) {
                    mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.classInternalName(), nameExpr.name(), desc);
                    ArithmeticOpcodes.emitDeltaPush(mv, desc, delta);
                    mv.visitInsn(ArithmeticOpcodes.addOrSub(desc, delta));
                    NumericCoercion.emitNarrowForSubIntDesc(mv, desc);
                    mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.classInternalName(), nameExpr.name(), desc);
                } else {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), nameExpr.name(), desc);
                    ArithmeticOpcodes.emitDeltaPush(mv, desc, delta);
                    mv.visitInsn(ArithmeticOpcodes.addOrSub(desc, delta));
                    NumericCoercion.emitNarrowForSubIntDesc(mv, desc);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, ctx.classInternalName(), nameExpr.name(), desc);
                }
                return;
            }
        }
        if (expr instanceof UnaryExpression unary && ("++".equals(unary.operator()) || "--".equals(unary.operator())) && unary.operand() instanceof ArrayAccessExpression ae) {
            int delta = "++".equals(unary.operator()) ? 1 : -1;
            ResolvedType elemType = ctx.typeInferrer().infer(ae);
            String desc = elemType != null ? elemType.descriptor() : "I";
            MethodVisitor mv = ctx.mv();
            generate(ae.array());
            generate(ae.index());
            mv.visitInsn(Opcodes.DUP2);
            mv.visitInsn(OpcodeUtils.arrayLoadOpcode(elemType != null ? elemType : ResolvedType.INT));
            ArithmeticOpcodes.emitDeltaPush(mv, desc, delta);
            mv.visitInsn(ArithmeticOpcodes.addOrSub(desc, delta));
            NumericCoercion.emitNarrowForSubIntDesc(mv, desc);
            mv.visitInsn(OpcodeUtils.arrayStoreOpcode(elemType != null ? elemType : ResolvedType.INT));
            return;
        }
        if (expr instanceof UnaryExpression unary && ("++".equals(unary.operator()) || "--".equals(unary.operator())) && unary.operand() instanceof FieldAccessExpression fa) {
            int delta = "++".equals(unary.operator()) ? 1 : -1;
            ResolvedType targetType = ctx.typeInferrer().infer(fa.target());
            if (targetType != null && targetType.internalName() != null) {
                MethodResolver.ResolvedField resolved = ctx.methodResolver().resolveField(targetType.internalName(), fa.fieldName());
                if (resolved != null) {
                    MethodVisitor mv = ctx.mv();
                    if (resolved.isStatic()) {
                        mv.visitFieldInsn(Opcodes.GETSTATIC, resolved.owner(), resolved.name(), resolved.descriptor());
                        ArithmeticOpcodes.emitDeltaPush(mv, resolved.descriptor(), delta);
                        mv.visitInsn(ArithmeticOpcodes.addOrSub(resolved.descriptor(), delta));
                        NumericCoercion.emitNarrowForSubIntDesc(mv, resolved.descriptor());
                        mv.visitFieldInsn(Opcodes.PUTSTATIC, resolved.owner(), resolved.name(), resolved.descriptor());
                    } else {
                        generate(fa.target());
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitFieldInsn(Opcodes.GETFIELD, resolved.owner(), resolved.name(), resolved.descriptor());
                        ArithmeticOpcodes.emitDeltaPush(mv, resolved.descriptor(), delta);
                        mv.visitInsn(ArithmeticOpcodes.addOrSub(resolved.descriptor(), delta));
                        NumericCoercion.emitNarrowForSubIntDesc(mv, resolved.descriptor());
                        mv.visitFieldInsn(Opcodes.PUTFIELD, resolved.owner(), resolved.name(), resolved.descriptor());
                    }
                    return;
                }
            }
        }
        if (expr instanceof UnaryExpression unary && !unary.isPrefix()) {
            unaryExpressionEmitter.emit(unary);
            ctx.mv().visitInsn(Opcodes.POP);
            return;
        }
        generate(expr);
        ctx.mv().visitInsn(Opcodes.POP);
    }

    /**
     * True when a field by this name is declared on the current class or
     * inherited through its superclass chain. Used by {@link #generateName}
     * so inherited fields take precedence over captured locals in an anonymous
     * class body, matching javac's resolution order.
     */
    private boolean hasInheritedField(@NotNull String fieldName) {
        MethodResolver.ResolvedField self = ctx.methodResolver().resolveField(ctx.classInternalName(), fieldName);
        if (self != null) return true;
        if (!"java/lang/Object".equals(ctx.superInternalName())) {
            MethodResolver.ResolvedField sup = ctx.methodResolver().resolveField(ctx.superInternalName(), fieldName);
            return sup != null;
        }
        return false;
    }

    private void generateName(@NotNull NameExpression name) {
        LocalVariable local = ctx.scope().resolve(name.name());
        if (local != null) {
            int opcode = OpcodeUtils.loadOpcode(local.type());
            ctx.mv().visitVarInsn(opcode, local.index());
        } else if (ctx.capturedFields() != null && ctx.capturedFields().containsKey(name.name())
                && !hasInheritedField(name.name())) {
            ResolvedType capType = ctx.capturedFields().get(name.name());
            ctx.mv().visitVarInsn(Opcodes.ALOAD, 0);
            ctx.mv().visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), "val$" + name.name(), capType.descriptor());
        } else {
            ResolvedType fieldType = ctx.typeInferrer().infer(name);
            MethodResolver.ResolvedField resolved = ctx.methodResolver().resolveField(ctx.classInternalName(), name.name());
            if (resolved == null && !"java/lang/Object".equals(ctx.superInternalName())) {
                resolved = ctx.methodResolver().resolveField(ctx.superInternalName(), name.name());
            }
            String descriptor = fieldType != null ? fieldType.descriptor() : (resolved != null ? resolved.descriptor() : "I");
            if (resolved == null) {
                String staticOwner = ctx.typeResolver().resolveStaticFieldOwner(name.name());
                if (staticOwner != null) {
                    MethodResolver.ResolvedField staticResolved = ctx.methodResolver().resolveField(staticOwner, name.name());
                    if (staticResolved != null) {
                        if (staticResolved.isStatic() && constantInliner.tryInline(staticResolved.owner(), staticResolved.name()))
                            return;
                        ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, staticResolved.owner(), staticResolved.name(), staticResolved.descriptor());
                        return;
                    }
                }
                String enclosingOuter = objectCreationEmitter.enclosingOuterFor(ctx.classInternalName());
                String staticOuter = enclosingOuter == null ? ctx.enclosingStaticOuter() : null;
                String outerForField = enclosingOuter != null ? enclosingOuter : staticOuter;
                if (outerForField != null) {
                    MethodResolver.ResolvedField outerField = ctx.methodResolver().resolveField(outerForField, name.name());
                    if (outerField != null) {
                        if (outerField.isStatic()) {
                            if (constantInliner.tryInline(outerField.owner(), outerField.name())) return;
                            ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, outerField.owner(), outerField.name(), outerField.descriptor());
                        } else if (enclosingOuter != null) {
                            ctx.mv().visitVarInsn(Opcodes.ALOAD, 0);
                            ctx.mv().visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), "this$0", "L" + enclosingOuter + ";");
                            ctx.mv().visitFieldInsn(Opcodes.GETFIELD, outerField.owner(), outerField.name(), outerField.descriptor());
                        } else {
                            return;
                        }
                        return;
                    }
                    if (ctx.nestedClassFields() != null) {
                        Map<String, ResolvedType> outerFields = ctx.nestedClassFields().get(enclosingOuter);
                        if (outerFields != null && outerFields.containsKey(name.name())) {
                            ResolvedType ot = outerFields.get(name.name());
                            ctx.mv().visitVarInsn(Opcodes.ALOAD, 0);
                            ctx.mv().visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), "this$0", "L" + enclosingOuter + ";");
                            ctx.mv().visitFieldInsn(Opcodes.GETFIELD, enclosingOuter, name.name(), ot.descriptor());
                            return;
                        }
                    }
                }
            }
            boolean resolvedStatic = resolved != null && resolved.isStatic();
            boolean hasImplicitThis = !ctx.isStatic() || ctx.scope().resolve("this") != null;
            boolean isSelfConstant = resolved == null && ctx.nestedClassConstants() != null
                    && ctx.nestedClassConstants().getOrDefault(ctx.classInternalName(), Map.of()).containsKey(name.name());
            String fieldOwner = resolved != null ? resolved.owner() : ctx.classInternalName();
            if (resolvedStatic || (!hasImplicitThis) || isSelfConstant) {
                if (resolvedStatic && constantInliner.tryInline(resolved.owner(), resolved.name())) return;
                if (isSelfConstant && constantInliner.tryInline(ctx.classInternalName(), name.name())) return;
                ctx.mv().visitFieldInsn(Opcodes.GETSTATIC, fieldOwner, name.name(), descriptor);
            } else {
                ctx.mv().visitVarInsn(Opcodes.ALOAD, 0);
                ctx.mv().visitFieldInsn(Opcodes.GETFIELD, fieldOwner, name.name(), descriptor);
            }
        }
    }

    public boolean isReferenceType(@NotNull Expression expr) {
        if (expr instanceof LiteralExpression lit) {
            TokenType lt = lit.literalType();
            return lt == TokenType.STRING_LITERAL || lt == TokenType.TEXT_BLOCK || lt == TokenType.NULL;
        }
        ResolvedType type = ctx.typeInferrer().infer(expr);
        if (type == null) return true;
        return !type.isPrimitive();
    }

    public boolean isStringConcat(@NotNull BinaryExpression binary) {
        ResolvedType left = ctx.typeInferrer().infer(binary.left());
        if (left != null && "java/lang/String".equals(left.internalName())) return true;
        ResolvedType right = ctx.typeInferrer().infer(binary.right());
        return right != null && "java/lang/String".equals(right.internalName());
    }

    public void flattenConcat(@NotNull Expression expr, @NotNull List<Expression> parts) {
        if (expr instanceof BinaryExpression bin && "+".equals(bin.operator()) && isStringConcat(bin)) {
            flattenConcat(bin.left(), parts);
            flattenConcat(bin.right(), parts);
        } else {
            parts.add(expr);
        }
    }

    public void emitGenericReturnCheckcast(@NotNull MethodCallExpression call, @NotNull MethodResolver.ResolvedMethod resolved) {
        String ret = resolved.descriptor().substring(resolved.descriptor().indexOf(')') + 1);
        if (discardDepth > 0) return;
        if (suppressGenericReturnCheckcast) return;
        ResolvedType inferred = ctx.typeInferrer().infer(call);
        if (inferred == null || inferred.isPrimitive()) return;
        String inferredDesc = inferred.descriptor();
        if (inferredDesc.indexOf('?') >= 0) return;
        if (inferredDesc.startsWith("[")) {
            if (!"Ljava/lang/Object;".equals(ret) && !"[Ljava/lang/Object;".equals(ret)) return;
            if (inferredDesc.equals(ret)) return;
            ctx.mv().visitTypeInsn(Opcodes.CHECKCAST, inferredDesc);
            lastCheckcastType = inferredDesc;
            return;
        }
        if (!ret.startsWith("L") || !ret.endsWith(";")) return;
        if (inferred.internalName() == null) return;
        if (inferred.internalName().indexOf('/') < 0) return;
        String rawInternal = ret.substring(1, ret.length() - 1);
        if (rawInternal.equals(inferred.internalName())) return;
        if ("java/lang/Object".equals(inferred.internalName())) return;
        if (!genericRetWidens(rawInternal, inferred.internalName())) return;
        ctx.mv().visitTypeInsn(Opcodes.CHECKCAST, inferred.internalName());
        lastCheckcastType = inferred.internalName();
    }

    /**
     * True when the inferred type is a strict subtype of the raw declared
     * return type, i.e. the checkcast is needed because the generic type
     * parameter narrowed the erasure. Avoids emitting bogus downcasts from
     * {@code Object} to some unrelated class when inference got it wrong.
     */
    public boolean genericRetWidens(@NotNull String rawInternal, @NotNull String inferredInternal) {
        if ("java/lang/Object".equals(rawInternal)) return true;
        Class<?> raw = ctx.methodResolver().classpathManager().loadClass(rawInternal);
        Class<?> inf = ctx.methodResolver().classpathManager().loadClass(inferredInternal);
        if (raw == null || inf == null) return false;
        try {
            return raw.isAssignableFrom(inf) && raw != inf;
        } catch (LinkageError e) {
            return false;
        }
    }

    public boolean lastEmittedCheckcast(@NotNull String wrapperInternal) {
        boolean match = wrapperInternal.equals(lastCheckcastType);
        lastCheckcastType = null;
        return match;
    }

    /**
     * Unwraps redundant parenthesization around an expression so downstream logic can
     * pattern-match on the inner expression without tripping on parens.
     *
     * @param expr the expression to unwrap
     * @return the inner expression, or {@code expr} itself if not parenthesized
     */
    public @NotNull Expression unwrapParens(@NotNull Expression expr) {
        Expression cur = expr;
        while (cur instanceof ParenExpression paren) cur = paren.expression();
        return cur;
    }

    public boolean litHandledExpectedType(@NotNull LiteralExpression lit, @NotNull String paramDesc) {
        boolean b = "J".equals(paramDesc) || "F".equals(paramDesc) || "D".equals(paramDesc);
        if (lit.literalType() == TokenType.INT_LITERAL)
            return b;
        return lit.literalType() == TokenType.LONG_LITERAL && b;
    }

    public @NotNull String resolveFieldDescriptor(@NotNull String fieldName) {
        ResolvedType fieldType = ctx.typeInferrer().inferField(fieldName);
        return fieldType != null ? fieldType.descriptor() : "I";
    }

    public @Nullable String resolveTypePrefix(@NotNull Expression expr) {
        if (expr instanceof NameExpression ne) {
            if (ctx.scope().resolve(ne.name()) != null) return null;
            if (ctx.typeInferrer().inferField(ne.name()) != null) return null;
            if (!Character.isUpperCase(ne.name().charAt(0))) return null;
            String internal = ctx.typeResolver().resolveInternalName(new TypeNode(ne.name(), null, 0, ne.line()));
            if (ctx.methodResolver().classpathManager().exists(internal)) return internal;
            return null;
        }
        if (expr instanceof FieldAccessExpression fa) {
            String fqn = flattenTypeName(fa);
            if (fqn != null && fqn.indexOf('.') > 0) {
                String fqnInternal = fqn.replace('.', '/');
                if (ctx.methodResolver().classpathManager().exists(fqnInternal)) return fqnInternal;
                String dotted = ctx.typeResolver().resolveDottedFqn(fqn);
                if (dotted != null) return dotted;
            }
            String inner = resolveTypePrefix(fa.target());
            if (inner == null) return null;
            String candidate = inner + "$" + fa.fieldName();
            if (ctx.methodResolver().classpathManager().exists(candidate)) return candidate;
            return null;
        }
        return null;
    }

    /**
     * Resolves a {@link FieldAccessExpression} target as a static-field
     * reference when the left-hand side is a type name (or nested type
     * accessor) rather than an instance. Used so {@code Foo.BAR = value}
     * assignments emit {@code PUTSTATIC} instead of mis-emitting a
     * {@code PUTFIELD} against the type name loaded as an instance.
     */
    public @Nullable MethodResolver.ResolvedField resolveStaticFieldAccess(@NotNull FieldAccessExpression fa) {
        String typePrefix = resolveTypePrefix(fa.target());
        if (typePrefix != null) {
            MethodResolver.ResolvedField f = ctx.methodResolver().resolveField(typePrefix, fa.fieldName());
            if (f != null && f.isStatic()) return f;
        }
        if (fa.target() instanceof NameExpression ne
                && ctx.scope().resolve(ne.name()) == null
                && ctx.typeInferrer().inferField(ne.name()) == null
                && Character.isUpperCase(ne.name().charAt(0))) {
            String resolved = ctx.typeResolver().resolveInternalName(new TypeNode(ne.name(), null, 0, fa.line()));
            if (!"I".equals(resolved)) {
                MethodResolver.ResolvedField f = ctx.methodResolver().resolveField(resolved, fa.fieldName());
                if (f != null && f.isStatic()) return f;
            }
        }
        return null;
    }

    /**
     * Returns the top-level-ish host class internal name under which anonymous
     * classes should be numbered. Javac flattens anon class names so
     * {@code Outer$1$1} becomes {@code Outer$2}. This walks outward through any
     * enclosing anon classes (class names ending in {@code $<digits>}) so nested
     * anon classes get their name prefix from the real enclosing class.
     */
    public @NotNull String anonHostInternalName() {
        String current = ctx.classInternalName();
        while (LambdaEmitter.endsWithAnonSuffix(current)) {
            int dollar = current.lastIndexOf('$');
            if (dollar <= 0) break;
            current = current.substring(0, dollar);
        }
        return current;
    }

    /**
     * Generates bytecode for a "this" expression (loads this reference).
     */
    private void generateThis() {
        ctx.mv().visitVarInsn(Opcodes.ALOAD, 0);
    }

    /**
     * Generates bytecode for a "super" expression (loads this reference).
     */
    private void generateSuper() {
        ctx.mv().visitVarInsn(Opcodes.ALOAD, 0);
    }
}
