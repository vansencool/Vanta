package net.vansencool.vanta.codegen.expression.call;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.exception.CodeGenException;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.NewExpression;
import net.vansencool.vanta.parser.ast.expression.SuperExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits bytecode for a method-call expression. Routes {@code super(...)} /
 * {@code this(...)} constructor calls, receiver-bound virtual / interface /
 * static invocations, same-class self calls, static helper lookups through
 * type-prefix or qualified field-access targets, and the
 * {@code array.clone()} shortcut. Resolution precedence matches javac's so
 * the emitted call picks the same method even when multiple overloads would
 * satisfy the call signature.
 */
public final class MethodCallEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for argument emission
     *                and shared resolver/coercion helpers
     */
    public MethodCallEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Emits a method call.
     *
     * @param call call expression
     * @return true when the call produced a value on the operand stack
     */
    public boolean emit(@NotNull MethodCallExpression call) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();

        if ("super".equals(call.methodName()) || "this".equals(call.methodName())) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            String target = "super".equals(call.methodName()) ? ctx.superInternalName() : ctx.classInternalName();
            String desc = exprGen.methodResolutionHelper().resolveConstructorDescriptor(target, call.arguments());
            exprGen.methodArgumentEmitter().generateArgs(call.arguments(), desc);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, target, "<init>", desc, false);
            return false;
        }

        if (call.target() != null) {
            boolean isSuperCall = call.target() instanceof SuperExpression;
            boolean targetIsAnonNew = call.target() instanceof NewExpression ne && ne.anonymousClassBody() != null;
            ResolvedType targetType = ctx.typeInferrer().infer(call.target());
            if (targetType != null && targetType.internalName() != null) {
                MethodResolver.ResolvedMethod resolved = exprGen.methodResolutionHelper().resolveMethodWithArgTypes(targetType.internalName(), call.methodName(), call.arguments());
                if (resolved == null && isSuperCall) {
                    resolved = ctx.methodResolver().resolveDeclaredProtected(targetType.internalName(), call.methodName(), call.arguments().size());
                }
                if (resolved == null) {
                    for (ResolvedType b : ctx.typeResolver().additionalBoundsForErasure(targetType.internalName())) {
                        if (b.internalName() == null) continue;
                        resolved = exprGen.methodResolutionHelper().resolveMethodWithArgTypes(b.internalName(), call.methodName(), call.arguments());
                        if (resolved != null) break;
                    }
                }
                if (resolved != null) {
                    int savedDiscard = exprGen.discardDepth();
                    exprGen.discardDepth(0);
                    ResolvedType prevReceiver = exprGen.currentReceiverType();
                    exprGen.currentReceiverType(targetType);
                    try {
                        String anonOwner = null;
                        if (resolved.opcode() == Opcodes.INVOKESTATIC) {
                            exprGen.methodArgumentEmitter().generateArgs(call.arguments(), resolved.descriptor(), resolved.reflective());
                        } else {
                            exprGen.lastEmittedAnonInternal(null);
                            exprGen.generate(call.target());
                            if (targetIsAnonNew) anonOwner = exprGen.lastEmittedAnonInternal();
                            exprGen.methodArgumentEmitter().generateArgs(call.arguments(), resolved.descriptor(), resolved.reflective());
                        }
                        exprGen.discardDepth(savedDiscard);
                        int opcode = isSuperCall && resolved.opcode() == Opcodes.INVOKEVIRTUAL ? Opcodes.INVOKESPECIAL : resolved.opcode();
                        String effOwner = anonOwner != null ? anonOwner : resolved.owner();
                        mv.visitMethodInsn(opcode, effOwner, resolved.name(), resolved.descriptor(), resolved.isInterface());
                        exprGen.emitGenericReturnCheckcast(call, resolved);
                        return (resolved.descriptor().endsWith(")V") ? 0 : 1) > 0;
                    } finally {
                        exprGen.currentReceiverType(prevReceiver);
                    }
                }
                if (targetType.internalName().equals(ctx.classInternalName())) {
                    SelfMethodInfo selfInfo = ctx.selfMethods().get(call.methodName() + ":" + call.arguments().size());
                    if (selfInfo != null) {
                        if (selfInfo.isStatic()) {
                            exprGen.methodArgumentEmitter().generateArgs(call.arguments(), selfInfo.descriptor());
                        } else {
                            exprGen.generate(call.target());
                            exprGen.methodArgumentEmitter().generateArgs(call.arguments(), selfInfo.descriptor());
                        }
                        mv.visitMethodInsn(selfInfo.isStatic() ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, selfInfo.owner(), selfInfo.name(), selfInfo.descriptor(), false);
                        return !selfInfo.descriptor().endsWith(")V");
                    }
                }
            }

            if (call.target() instanceof NameExpression nameTarget && ctx.scope().resolve(nameTarget.name()) == null
                    && ctx.typeInferrer().inferField(nameTarget.name()) == null
                    && Character.isUpperCase(nameTarget.name().charAt(0))) {
                String ownerInternal = ctx.typeResolver().resolveInternalName(new TypeNode(nameTarget.name(), null, 0, call.line()));
                MethodResolver.ResolvedMethod staticResolved = exprGen.methodResolutionHelper().resolveMethodWithArgTypes(ownerInternal, call.methodName(), call.arguments());
                if (staticResolved != null && staticResolved.opcode() == Opcodes.INVOKESTATIC) {
                    int savedDiscard = exprGen.discardDepth();
                    exprGen.discardDepth(0);
                    try {
                        exprGen.methodArgumentEmitter().generateArgs(call.arguments(), staticResolved.descriptor(), staticResolved.reflective());
                    } finally {
                        exprGen.discardDepth(savedDiscard);
                    }
                    mv.visitMethodInsn(staticResolved.opcode(), staticResolved.owner(), staticResolved.name(), staticResolved.descriptor(), staticResolved.isInterface());
                    exprGen.emitGenericReturnCheckcast(call, staticResolved);
                    int produced = staticResolved.descriptor().endsWith(")V") ? 0 : 1;
                    return produced > 0;
                }
            }

            if (call.target() instanceof FieldAccessExpression fa) {
                String fqn = exprGen.flattenTypeName(fa);
                if (fqn != null && fqn.indexOf('.') > 0) {
                    String ownerInternal = ctx.typeResolver().resolveInternalName(new TypeNode(fqn, null, 0, call.line()));
                    if (!fqn.equals(ownerInternal.replace('/', '.')) || ctx.methodResolver().classpathManager().loadClass(ownerInternal) != null
                            || ctx.methodResolver().classpathManager().asmClassInfo(ownerInternal) != null) {
                        MethodResolver.ResolvedMethod staticResolved = exprGen.methodResolutionHelper().resolveMethodWithArgTypes(ownerInternal, call.methodName(), call.arguments());
                        if (staticResolved != null && staticResolved.opcode() == Opcodes.INVOKESTATIC) {
                            int savedDiscard = exprGen.discardDepth();
                            exprGen.discardDepth(0);
                            try {
                                exprGen.methodArgumentEmitter().generateArgs(call.arguments(), staticResolved.descriptor(), staticResolved.reflective());
                            } finally {
                                exprGen.discardDepth(savedDiscard);
                            }
                            mv.visitMethodInsn(staticResolved.opcode(), staticResolved.owner(), staticResolved.name(), staticResolved.descriptor(), staticResolved.isInterface());
                            exprGen.emitGenericReturnCheckcast(call, staticResolved);
                            return !staticResolved.descriptor().endsWith(")V");
                        }
                    }
                }
            }

            ResolvedType targetTypeFb = ctx.typeInferrer().infer(call.target());
            if ("clone".equals(call.methodName()) && call.arguments().isEmpty()
                    && targetTypeFb != null && targetTypeFb.descriptor().startsWith("[")) {
                exprGen.generate(call.target());
                String arrayDesc = targetTypeFb.descriptor();
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, arrayDesc, "clone", "()Ljava/lang/Object;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, arrayDesc);
                return true;
            }
            String targetDesc = targetTypeFb == null ? "<unknown>" : targetTypeFb.descriptor();
            throw new CodeGenException("Cannot resolve method " + call.methodName() + "/" + call.arguments().size()
                    + " on receiver of type " + targetDesc + " (in " + ctx.classInternalName() + ")", call.line());
        }

        SelfMethodInfo selfInfo = exprGen.methodResolutionHelper().resolveSelfMethod(call);
        if (selfInfo == null) {
            String staticOwner = ctx.typeResolver().resolveStaticMethodOwner(call.methodName());
            if (staticOwner != null) {
                MethodResolver.ResolvedMethod staticResolved = exprGen.methodResolutionHelper().resolveMethodWithArgTypes(staticOwner, call.methodName(), call.arguments());
                if (staticResolved != null && staticResolved.opcode() == Opcodes.INVOKESTATIC) {
                    exprGen.methodArgumentEmitter().generateArgs(call.arguments(), staticResolved.descriptor(), staticResolved.reflective());
                    mv.visitMethodInsn(staticResolved.opcode(), staticResolved.owner(), staticResolved.name(), staticResolved.descriptor(), staticResolved.isInterface());
                    exprGen.emitGenericReturnCheckcast(call, staticResolved);
                    return !staticResolved.descriptor().endsWith(")V");
                }
            }
        }
        if (selfInfo != null) {
            if (!selfInfo.isStatic()) mv.visitVarInsn(Opcodes.ALOAD, 0);
            int savedDiscard = exprGen.discardDepth();
            exprGen.discardDepth(0);
            try {
                exprGen.methodArgumentEmitter().generateArgs(call.arguments(), selfInfo.descriptor());
            } finally {
                exprGen.discardDepth(savedDiscard);
            }
            int opcode = selfInfo.isStatic() ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
            mv.visitMethodInsn(opcode, selfInfo.owner(), selfInfo.name(), selfInfo.descriptor(), false);
            return !selfInfo.descriptor().endsWith(")V");
        }
        if (ctx.enclosingOuterInternal() != null || ctx.enclosingStaticOuter() != null) {
            Integer outerResult = exprGen.methodArgumentEmitter().tryOuterCallResult(call);
            if (outerResult != null) return outerResult > 0;
        }
        MethodResolver.ResolvedMethod selfResolved = exprGen.methodResolutionHelper().resolveMethodWithArgTypes(ctx.classInternalName(), call.methodName(), call.arguments());
        if (selfResolved == null) {
            selfResolved = exprGen.methodResolutionHelper().resolveMethodWithArgTypes(ctx.superInternalName(), call.methodName(), call.arguments());
        }
        if (selfResolved == null) {
            selfResolved = exprGen.methodResolutionHelper().resolveInheritedProtectedMethod(ctx.superInternalName(), call.methodName(), call.arguments().size());
        }
        if (selfResolved != null && selfResolved.opcode() == Opcodes.INVOKESTATIC) {
            exprGen.methodArgumentEmitter().generateArgs(call.arguments(), selfResolved.descriptor());
            String staticOwner = selfResolved.owner();
            if (staticOwner.equals(ctx.superInternalName())) staticOwner = ctx.classInternalName();
            mv.visitMethodInsn(selfResolved.opcode(), staticOwner, selfResolved.name(), selfResolved.descriptor(), selfResolved.isInterface());
            return !selfResolved.descriptor().endsWith(")V");
        }
        if (selfResolved != null) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            exprGen.methodArgumentEmitter().generateArgs(call.arguments(), selfResolved.descriptor());
            mv.visitMethodInsn(selfResolved.opcode(), selfResolved.owner(), selfResolved.name(), selfResolved.descriptor(), selfResolved.isInterface());
            return !selfResolved.descriptor().endsWith(")V");
        }
        throw new CodeGenException("Cannot resolve self method " + call.methodName() + "/" + call.arguments().size()
                + " on " + ctx.classInternalName(), call.line());
    }
}
