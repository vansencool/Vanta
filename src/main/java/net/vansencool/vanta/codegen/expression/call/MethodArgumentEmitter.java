package net.vansencool.vanta.codegen.expression.call;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.expression.cast.PrimitiveConversionEmitter;
import net.vansencool.vanta.codegen.expression.util.desc.DescriptorUtils;
import net.vansencool.vanta.codegen.expression.util.generic.GenericTypeResolver;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.LambdaExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.expression.MethodReferenceExpression;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits method call arguments coerced to the resolved parameter types,
 * including varargs packing, generic threading for lambda SAM targets, and
 * boxing or narrowing as required. Also handles dispatch to outer class
 * methods discovered via the enclosing instance chain.
 */
public final class MethodArgumentEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator
     */
    public MethodArgumentEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * @param args             call arguments
     * @param methodDescriptor resolved method descriptor
     */
    public void generateArgs(@NotNull List<Expression> args, @NotNull String methodDescriptor) {
        generateArgs(args, methodDescriptor, null);
    }

    /**
     * Generates argument values for a method call, threading expected types
     * from the resolved method's parameter list. When {@code reflective} is
     * non null, generic parameter types (for example {@code Consumer<Runnable>})
     * are used to give lambda arguments the correct SAM target type instead
     * of the raw erasure.
     *
     * @param args             call arguments
     * @param methodDescriptor resolved method descriptor
     * @param reflective       reflective method when the call resolves to a
     *                         classpath method, used to recover generic
     *                         parameter types and varargs flag
     */
    public void generateArgs(@NotNull List<Expression> args, @NotNull String methodDescriptor, @Nullable Method reflective) {
        MethodContext ctx = exprGen.ctx();
        Type[] paramTypes = ctx.methodResolver().classpathManager().argumentTypes(methodDescriptor);
        java.lang.reflect.Type[] genericParams = reflective != null ? reflective.getGenericParameterTypes() : null;
        boolean isVarargsMethod = reflective != null && reflective.isVarArgs();
        boolean needsVarargPack = false;
        int varargStart = -1;
        if (isVarargsMethod && paramTypes.length > 0 && paramTypes[paramTypes.length - 1].getSort() == Type.ARRAY) {
            varargStart = paramTypes.length - 1;
            if (args.size() > paramTypes.length || args.size() == paramTypes.length - 1) {
                needsVarargPack = true;
            } else if (args.size() == paramTypes.length) {
                Expression last = args.get(args.size() - 1);
                ResolvedType lastType = ctx.typeInferrer().infer(last);
                String arrayDesc = paramTypes[varargStart].getDescriptor();
                if (lastType != null && !arrayDesc.equals(lastType.descriptor()) && lastType != ResolvedType.NULL
                        && !MethodResolver.isDescriptorAssignable(lastType.descriptor(), arrayDesc)) {
                    needsVarargPack = true;
                }
            }
        }
        if (!needsVarargPack) {
            for (int i = 0; i < args.size(); i++) {
                Type pt = i < paramTypes.length ? paramTypes[i] : null;
                java.lang.reflect.Type gpt = (genericParams != null && i < genericParams.length) ? genericParams[i] : null;
                emitArg(args.get(i), pt, gpt);
            }
            return;
        }
        for (int i = 0; i < varargStart; i++) {
            java.lang.reflect.Type gpt = i < genericParams.length ? genericParams[i] : null;
            emitArg(args.get(i), paramTypes[i], gpt);
        }
        Type arrayType = paramTypes[varargStart];
        Type elemType = arrayType.getElementType();
        int varargCount = args.size() - varargStart;
        OpcodeUtils.pushInt(ctx.mv(), varargCount);
        if (elemType.getSort() == Type.OBJECT) {
            ctx.mv().visitTypeInsn(Opcodes.ANEWARRAY, elemType.getInternalName());
        } else if (elemType.getSort() == Type.ARRAY) {
            ctx.mv().visitTypeInsn(Opcodes.ANEWARRAY, elemType.getDescriptor());
        } else {
            int atype = switch (elemType.getSort()) {
                case Type.BOOLEAN -> Opcodes.T_BOOLEAN;
                case Type.CHAR -> Opcodes.T_CHAR;
                case Type.BYTE -> Opcodes.T_BYTE;
                case Type.SHORT -> Opcodes.T_SHORT;
                case Type.LONG -> Opcodes.T_LONG;
                case Type.FLOAT -> Opcodes.T_FLOAT;
                case Type.DOUBLE -> Opcodes.T_DOUBLE;
                default -> Opcodes.T_INT;
            };
            ctx.mv().visitIntInsn(Opcodes.NEWARRAY, atype);
        }
        for (int i = varargStart, j = 0; i < args.size(); i++, j++) {
            ctx.mv().visitInsn(Opcodes.DUP);
            OpcodeUtils.pushInt(ctx.mv(), j);
            emitArg(args.get(i), elemType, null);
            ctx.mv().visitInsn(arrayStoreOpcodeForType(elemType));
        }
    }

    /**
     * Emits a single argument value with an expected target type. When
     * {@code genericParam} is a {@code ParameterizedType} and the argument
     * is a lambda or method reference, the generic info is recorded on the
     * lambda's expected type so the synthetic implementation method gets
     * the correct SAM parameter types.
     *
     * @param arg          argument expression
     * @param paramType    target parameter type, or null when unknown
     * @param genericParam generic parameter type for SAM threading, or null
     */
    private void emitArg(@NotNull Expression arg, @Nullable Type paramType, @Nullable java.lang.reflect.Type genericParam) {
        MethodContext ctx = exprGen.ctx();
        ResolvedType argExpected;
        if ((arg instanceof LambdaExpression || arg instanceof MethodReferenceExpression) && paramType != null && paramType.getSort() == Type.OBJECT) {
            argExpected = ResolvedType.ofObject(paramType.getInternalName());
            if (genericParam instanceof ParameterizedType pt) {
                Map<String, ResolvedType> tvSubst = buildReceiverTypeVarMap();
                List<ResolvedType> typeArgs = new ArrayList<>();
                for (java.lang.reflect.Type ta : pt.getActualTypeArguments()) {
                    typeArgs.add(GenericTypeResolver.resolve(ta, tvSubst));
                }
                argExpected = argExpected.withTypeArguments(typeArgs);
            } else if (genericParam instanceof TypeVariable<?> tv) {
                ResolvedType substituted = buildReceiverTypeVarMap().get(tv.getName());
                if (substituted != null) argExpected = substituted;
            }
        } else if (paramType != null) {
            argExpected = ResolvedType.fromDescriptor(paramType.getDescriptor());
        } else {
            argExpected = null;
        }
        exprGen.generate(arg, argExpected);
        if (paramType != null) {
            ResolvedType argType = ctx.typeInferrer().infer(arg);
            String paramDesc = paramType.getDescriptor();
            boolean literalCoerced = exprGen.unwrapParens(arg) instanceof LiteralExpression lit && exprGen.litHandledExpectedType(lit, paramDesc);
            if (argType != null && argType != ResolvedType.NULL) {
                String argDesc = literalCoerced ? paramDesc : argType.descriptor();
                if (DescriptorUtils.needsBoxing(argDesc, paramDesc))
                    PrimitiveConversionEmitter.emitBoxing(ctx.mv(), argType);
                else if (DescriptorUtils.needsUnboxing(argDesc, paramDesc))
                    exprGen.unboxingEmitter().emit(ctx.mv(), paramDesc, argType.internalName());
                else if (DescriptorUtils.isPrimitive(argDesc) && DescriptorUtils.isPrimitive(paramDesc) && !argDesc.equals(paramDesc)) {
                    PrimitiveConversionEmitter.emitPrimitiveWidening(ctx.mv(), argDesc, paramDesc);
                } else if ("Ljava/lang/Object;".equals(argDesc) && paramType.getSort() == Type.OBJECT && !"java/lang/Object".equals(paramType.getInternalName())) {
                    ctx.mv().visitTypeInsn(Opcodes.CHECKCAST, paramType.getInternalName());
                } else if (paramType.getSort() == Type.OBJECT && !"java/lang/Object".equals(paramType.getInternalName())
                        && argDesc.startsWith("L") && !argDesc.equals(paramDesc)
                        && MethodResolver.isDescriptorAssignable(paramDesc, argDesc)
                        && !MethodResolver.isDescriptorAssignable(argDesc, paramDesc)) {
                    ctx.mv().visitTypeInsn(Opcodes.CHECKCAST, paramType.getInternalName());
                }
            } else if (argType == null && paramType.getSort() == Type.OBJECT && !"java/lang/Object".equals(paramType.getInternalName())
                    && !(arg instanceof LambdaExpression) && !(arg instanceof MethodReferenceExpression)) {
                ctx.mv().visitTypeInsn(Opcodes.CHECKCAST, paramType.getInternalName());
            }
        }
    }

    /**
     * Builds a substitution map from type variable name to concrete
     * {@code ResolvedType} based on the receiver's runtime type arguments.
     * Used when threading generics into lambda SAM targets.
     *
     * @return type variable substitution map for the current receiver
     */
    private @NotNull Map<String, ResolvedType> buildReceiverTypeVarMap() {
        MethodContext ctx = exprGen.ctx();
        ResolvedType recv = exprGen.currentReceiverType();
        if (recv == null || recv.typeArguments() == null || recv.internalName() == null) return Map.of();
        Class<?> cls = ctx.methodResolver().classpathManager().loadClass(recv.internalName());
        if (cls == null) return Map.of();
        TypeVariable<?>[] tvs = cls.getTypeParameters();
        List<ResolvedType> args = recv.typeArguments();
        Map<String, ResolvedType> map = new HashMap<>();
        for (int i = 0; i < tvs.length && i < args.size(); i++) map.put(tvs[i].getName(), args.get(i));
        return map;
    }

    /**
     * @param elemType array element type
     * @return matching {@code *ASTORE} opcode
     */
    private int arrayStoreOpcodeForType(@NotNull Type elemType) {
        return switch (elemType.getSort()) {
            case Type.BOOLEAN, Type.BYTE -> Opcodes.BASTORE;
            case Type.CHAR -> Opcodes.CASTORE;
            case Type.SHORT -> Opcodes.SASTORE;
            case Type.INT -> Opcodes.IASTORE;
            case Type.LONG -> Opcodes.LASTORE;
            case Type.FLOAT -> Opcodes.FASTORE;
            case Type.DOUBLE -> Opcodes.DASTORE;
            default -> Opcodes.AASTORE;
        };
    }

    /**
     * Tries to dispatch a name only call to a method on the enclosing outer
     * class, threading {@code this$0} through the enclosing instance chain.
     * Used so an inner class can call outer methods without an explicit
     * {@code OuterClass.this} prefix.
     *
     * @param call call site
     * @return 0 when the dispatched method returns void, 1 when it returns a
     * value left on the stack, or null when no outer dispatch matches
     */
    public @Nullable Integer tryOuterCallResult(@NotNull MethodCallExpression call) {
        MethodContext ctx = exprGen.ctx();
        String outerInternal = ctx.enclosingOuterInternal();
        boolean staticOnly = outerInternal == null;
        if (staticOnly) outerInternal = ctx.enclosingStaticOuter();
        if (outerInternal == null) return null;
        MethodResolver.ResolvedMethod selfResolved = ctx.methodResolver().resolveMethod(ctx.classInternalName(), call.methodName(), call.arguments().size());
        if (selfResolved != null) return null;
        SelfMethodInfo outerSelf = null;
        if (ctx.nestedClassMethods() != null) {
            Map<String, SelfMethodInfo> outerMethods = ctx.nestedClassMethods().get(outerInternal);
            if (outerMethods != null) outerSelf = outerMethods.get(call.methodName() + ":" + call.arguments().size());
        }
        MethodResolver.ResolvedMethod resolved = exprGen.methodResolutionHelper().resolveMethodWithArgTypes(outerInternal, call.methodName(), call.arguments());
        MethodVisitor mv = ctx.mv();
        String desc;
        if (outerSelf != null && resolved == null) {
            desc = outerSelf.descriptor();
            if (outerSelf.isStatic()) {
                generateArgs(call.arguments(), desc);
                String staticOwner = outerSelf.owner().equals(ctx.superInternalName()) ? ctx.classInternalName() : outerSelf.owner();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, staticOwner, outerSelf.name(), desc, false);
            } else if (!staticOnly) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), "this$0", "L" + outerInternal + ";");
                generateArgs(call.arguments(), desc);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, outerSelf.owner(), outerSelf.name(), desc, false);
            } else {
                return null;
            }
        } else if (resolved != null) {
            desc = resolved.descriptor();
            String resolvedOwner = resolved.owner();
            if (resolved.opcode() == Opcodes.INVOKESTATIC) {
                generateArgs(call.arguments(), desc, resolved.reflective());
                if (resolvedOwner.equals(ctx.superInternalName())) resolvedOwner = ctx.classInternalName();
            } else if (!staticOnly) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), "this$0", "L" + outerInternal + ";");
                generateArgs(call.arguments(), desc, resolved.reflective());
            } else {
                return null;
            }
            mv.visitMethodInsn(resolved.opcode(), resolvedOwner, resolved.name(), desc, resolved.isInterface());
        } else {
            return null;
        }
        return desc.endsWith(")V") ? 0 : 1;
    }
}
