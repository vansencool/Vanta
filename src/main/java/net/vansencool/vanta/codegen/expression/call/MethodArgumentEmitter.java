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
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.GenericArrayType;
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
        generateArgs(args, methodDescriptor, null, false);
    }

    /**
     * @param args             call arguments
     * @param methodDescriptor resolved method descriptor
     * @param isVarargs        true when the resolved method's last parameter is a varargs slot
     */
    public void generateArgs(@NotNull List<Expression> args, @NotNull String methodDescriptor, boolean isVarargs) {
        generateArgs(args, methodDescriptor, null, isVarargs);
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
        generateArgs(args, methodDescriptor, reflective, reflective != null && reflective.isVarArgs());
    }

    /**
     * Generates argument values with both a reflective handle for generics and
     * an explicit varargs flag for source resolved methods.
     */
    public void generateArgs(@NotNull List<Expression> args, @NotNull String methodDescriptor, @Nullable Method reflective, boolean isVarargsMethod) {
        generateArgs(args, methodDescriptor, reflective, isVarargsMethod, null);
    }

    /**
     * Variant that also threads the resolved method's symbol so source
     * resolved generics (e.g. a {@code Predicate<Locale>} parameter) reach
     * lambda and method reference arguments.
     */
    public void generateArgs(@NotNull List<Expression> args, @NotNull String methodDescriptor, @Nullable Method reflective, boolean isVarargsMethod, @Nullable MethodSymbol symbol) {
        MethodContext ctx = exprGen.ctx();
        Type[] paramTypes = ctx.methodResolver().classpathManager().argumentTypes(methodDescriptor);
        java.lang.reflect.Type[] genericParams = reflective != null ? reflective.getGenericParameterTypes() : null;
        List<TypeRef> symbolParams = symbol != null ? symbol.parameterTypes() : null;
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
        Map<String, ResolvedType> tvSubst = projectedReceiverMap(reflective);
        if (!needsVarargPack) {
            for (int i = 0; i < args.size(); i++) {
                Type pt = i < paramTypes.length ? paramTypes[i] : null;
                java.lang.reflect.Type gpt = (genericParams != null && i < genericParams.length) ? genericParams[i] : null;
                TypeRef spt = (symbolParams != null && i < symbolParams.size()) ? symbolParams.get(i) : null;
                emitArg(args.get(i), pt, gpt, spt, tvSubst);
            }
            return;
        }
        for (int i = 0; i < varargStart; i++) {
            java.lang.reflect.Type gpt = (genericParams != null && i < genericParams.length) ? genericParams[i] : null;
            TypeRef spt = (symbolParams != null && i < symbolParams.size()) ? symbolParams.get(i) : null;
            emitArg(args.get(i), paramTypes[i], gpt, spt, tvSubst);
        }
        Type arrayType = paramTypes[varargStart];
        Type elemType = arrayType.getElementType();
        String elemInternalFromGeneric = null;
        if (genericParams != null && varargStart < genericParams.length) {
            java.lang.reflect.Type gp = genericParams[varargStart];
            if (gp instanceof Class<?> cl && cl.isArray() && !cl.getComponentType().isPrimitive()) {
                elemInternalFromGeneric = cl.getComponentType().getName().replace('.', '/');
            } else if (gp instanceof GenericArrayType gat) {
                java.lang.reflect.Type comp = gat.getGenericComponentType();
                Map<String, ResolvedType> tvs = buildReceiverTypeVarMap();
                ResolvedType resolved = GenericTypeResolver.resolve(comp, tvs);
                if (resolved.internalName() != null && !"java/lang/Object".equals(resolved.internalName())) {
                    elemInternalFromGeneric = resolved.internalName();
                } else if (comp instanceof TypeVariable<?>) {
                    elemInternalFromGeneric = inferVarargElementFromArgs(args, varargStart);
                }
            }
        }
        int varargCount = args.size() - varargStart;
        OpcodeUtils.pushInt(ctx.mv(), varargCount);
        if (elemType.getSort() == Type.OBJECT) {
            String internal = elemInternalFromGeneric != null ? elemInternalFromGeneric : elemType.getInternalName();
            ctx.mv().visitTypeInsn(Opcodes.ANEWARRAY, internal);
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
        emitArg(arg, paramType, genericParam, null, buildReceiverTypeVarMap());
    }

    /**
     * @param symbolParam declared parameter ref of a source resolved method, carries type arguments reflective generics cannot
     * @param tvSubst     type variable bindings projected to the declaring class of the resolved method
     */
    private void emitArg(@NotNull Expression arg, @Nullable Type paramType, @Nullable java.lang.reflect.Type genericParam, @Nullable TypeRef symbolParam, @NotNull Map<String, ResolvedType> tvSubst) {
        MethodContext ctx = exprGen.ctx();
        ResolvedType argExpected;
        if ((arg instanceof LambdaExpression || arg instanceof MethodReferenceExpression) && paramType != null && paramType.getSort() == Type.OBJECT) {
            argExpected = ResolvedType.ofObject(paramType.getInternalName());
            if (genericParam instanceof ParameterizedType pt) {
                List<ResolvedType> typeArgs = new ArrayList<>();
                for (java.lang.reflect.Type ta : pt.getActualTypeArguments()) {
                    typeArgs.add(GenericTypeResolver.resolve(ta, tvSubst));
                }
                argExpected = argExpected.withTypeArguments(typeArgs);
            } else if (genericParam instanceof TypeVariable<?> tv) {
                ResolvedType substituted = tvSubst.get(tv.getName());
                if (substituted != null) argExpected = substituted;
            } else if (symbolParam != null && !symbolParam.typeArguments().isEmpty()) {
                ResolvedType fromSymbol = refToResolved(symbolParam);
                if (fromSymbol != null) argExpected = fromSymbol;
            }
        } else if (paramType != null) {
            argExpected = ResolvedType.fromDescriptor(paramType.getDescriptor());
            if (genericParam instanceof TypeVariable<?> tv) {
                ResolvedType narrowed = tvSubst.get(tv.getName());
                if (narrowed != null && narrowed.internalName() != null) argExpected = narrowed;
            }
        } else {
            argExpected = null;
        }
        boolean argIsParameterized = genericParam instanceof TypeVariable<?> || genericParam instanceof ParameterizedType;
        if (argIsParameterized) {
            exprGen.generate(arg, argExpected);
        } else {
            exprGen.generateForAssign(arg, argExpected);
        }
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
                    if (!exprGen.consumeInlinePushedWidened()) {
                        PrimitiveConversionEmitter.emitPrimitiveWidening(ctx.mv(), argDesc, paramDesc);
                    }
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
     * @return resolved type mirroring {@code ref} including its type arguments, or null for primitives and arrays
     */
    private static @Nullable ResolvedType refToResolved(@NotNull TypeRef ref) {
        String internal = ref.internalName();
        if (internal == null || ref.descriptor().length() == 1 || ref.descriptor().startsWith("[")) return null;
        if (ref.typeArguments().isEmpty()) return ResolvedType.ofObject(internal);
        List<ResolvedType> args = new ArrayList<>(ref.typeArguments().size());
        for (TypeRef ta : ref.typeArguments()) {
            ResolvedType r = refToResolved(ta);
            args.add(r != null ? r : ResolvedType.ofObject("java/lang/Object"));
        }
        return ResolvedType.ofObject(internal).withTypeArguments(args);
    }

    /**
     * @return common internal name of every vararg arg inferred type, or null when types disagree
     */
    private @Nullable String inferVarargElementFromArgs(@NotNull List<Expression> args, int varargStart) {
        MethodContext ctx = exprGen.ctx();
        String common = null;
        for (int i = varargStart; i < args.size(); i++) {
            ResolvedType t = ctx.typeInferrer().infer(args.get(i));
            if (t == null || t.internalName() == null || t.isPrimitive()) return null;
            if (common == null) common = t.internalName();
            else if (!common.equals(t.internalName())) return null;
        }
        return common;
    }

    /**
     * Receiver type variable bindings projected to the declaring class of {@code reflective}.
     */
    private @NotNull Map<String, ResolvedType> projectedReceiverMap(@Nullable Method reflective) {
        Map<String, ResolvedType> base = buildReceiverTypeVarMap();
        if (reflective == null || base.isEmpty()) return base;
        ResolvedType recv = exprGen.currentReceiverType();
        if (recv == null || recv.internalName() == null) return base;
        Class<?> receiverRaw = exprGen.ctx().methodResolver().classpathManager().loadClass(recv.internalName());
        Class<?> declaring = reflective.getDeclaringClass();
        if (receiverRaw == null || declaring.equals(receiverRaw)) return base;
        return GenericTypeResolver.projectTypeVars(receiverRaw, base, declaring);
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
        TypeSymbol sym = ctx.methodResolver().classpathManager().typeRegistry().lookup(recv.internalName());
        if (sym == null) return Map.of();
        List<TypeParameterSymbol> tps = sym.typeParameters();
        List<ResolvedType> args = recv.typeArguments();
        Map<String, ResolvedType> map = new HashMap<>();
        for (int i = 0; i < tps.size() && i < args.size(); i++) map.put(tps.get(i).name(), args.get(i));
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
                generateArgs(call.arguments(), desc, outerSelf.isVarargs());
                String staticOwner = outerSelf.owner().equals(ctx.superInternalName()) ? ctx.classInternalName() : outerSelf.owner();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, staticOwner, outerSelf.name(), desc, false);
            } else if (!staticOnly) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), "this$0", "L" + outerInternal + ";");
                generateArgs(call.arguments(), desc, outerSelf.isVarargs());
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
        if (resolved != null) exprGen.emitGenericReturnCheckcast(call, resolved);
        return desc.endsWith(")V") ? 0 : 1;
    }
}
