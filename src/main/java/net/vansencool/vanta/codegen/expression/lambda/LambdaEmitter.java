package net.vansencool.vanta.codegen.expression.lambda;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.MethodGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.classes.visitor.RecordingMethodVisitor;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.exception.CodeGenException;
import net.vansencool.vanta.codegen.expression.cast.PrimitiveConversionEmitter;
import net.vansencool.vanta.codegen.expression.util.desc.DescriptorUtils;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.LambdaExpression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.expression.MethodReferenceExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.ThisExpression;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.scope.Scope;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Emits bytecode for lambda expressions and method references. Both lower to
 * an {@code invokedynamic} call through {@link java.lang.invoke.LambdaMetafactory},
 * so this class concentrates: SAM method discovery and generic-param
 * resolution, capture analysis over the lambda body, emission of the
 * synthetic {@code lambda$<enclosing>$N} backing method, and assembly of
 * the final {@code invokedynamic} instruction. Shared scanning helpers
 * (sub-expression walks, anon-capture collection) live on
 * {@link ExpressionGenerator} since they're reused by anonymous-class
 * emission.
 */
public final class LambdaEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen expression generator whose context, generators, and
     *                sub-expression collectors this emitter reuses
     */
    public LambdaEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Shallow JVM descriptor validator. Guards against splicing
     * malformed descriptors like {@code L?;} into a synthetic
     * {@code makeConcatWithConstants} bootstrap where they would break
     * downstream StackMapTable computation.
     *
     * @param desc candidate descriptor, possibly from type inference
     * @return true when {@code desc} is a well-formed field descriptor
     */
    public static boolean isValidDescriptor(@Nullable String desc) {
        if (desc == null || desc.isEmpty()) return false;
        int i = 0;
        while (i < desc.length() && desc.charAt(i) == '[') i++;
        if (i >= desc.length()) return false;
        char c = desc.charAt(i);
        if ("BSCIJFDZV".indexOf(c) >= 0) return i == desc.length() - 1;
        if (c != 'L') return false;
        int semi = desc.indexOf(';', i + 1);
        if (semi != desc.length() - 1) return false;
        for (int k = i + 1; k < semi; k++) {
            char ch = desc.charAt(k);
            if (!(Character.isLetterOrDigit(ch) || ch == '/' || ch == '_' || ch == '$')) return false;
        }
        return semi > i + 1;
    }

    /**
     * True when {@code internal} looks like an anon-class internal name
     * ({@code Outer$<digits>}). Used when flattening nested anon class
     * numbering back to the real enclosing class.
     *
     * @param internal class internal name
     * @return true when the tail after the last {@code $} is all digits
     */
    public static boolean endsWithAnonSuffix(@NotNull String internal) {
        int dollar = internal.lastIndexOf('$');
        if (dollar < 0 || dollar == internal.length() - 1) return false;
        for (int i = dollar + 1; i < internal.length(); i++) {
            if (!Character.isDigit(internal.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Emits bytecode for a method reference, resolving the target method
     * (or constructor) against the current classpath, picking the right
     * {@code Handle} tag (static/virtual/interface/newinvokespecial), and
     * issuing the matching {@code invokedynamic} against
     * {@code LambdaMetafactory#metafactory}.
     *
     * @param ref        method reference expression
     * @param targetType functional-interface type the reference flows into
     */
    public void emitMethodReference(@NotNull MethodReferenceExpression ref, @Nullable ResolvedType targetType) {
        MethodContext ctx = exprGen.ctx();
        if (targetType == null || targetType.internalName() == null) {
            throw new CodeGenException("Cannot determine functional interface for method reference", ref.line());
        }
        ClassWriter cw = ctx.classWriter();
        if (cw == null) throw new CodeGenException("Method references require class level context", ref.line());
        Class<?> ifaceClass = ctx.methodResolver().classpathManager().loadClass(targetType.internalName());
        if (ifaceClass == null)
            throw new CodeGenException("Cannot load functional interface: " + targetType.internalName(), ref.line());
        Method samMethod = null;
        for (Method m : ctx.methodResolver().classpathManager().cachedMethods(ifaceClass)) {
            if (!Modifier.isAbstract(m.getModifiers())) continue;
            if (isOverrideOfObjectMethod(m)) continue;
            samMethod = m;
            break;
        }
        if (samMethod == null)
            throw new CodeGenException("No abstract method in: " + targetType.internalName(), ref.line());
        String samName = samMethod.getName();
        String samDescriptor = Type.getMethodDescriptor(samMethod);

        boolean isCtorRef = "new".equals(ref.methodName());
        String refTargetName = null;
        ResolvedType refTargetType = null;
        if (ref.target() instanceof NameExpression ne) {
            refTargetName = ne.name();
            try {
                refTargetType = ctx.typeResolver().resolve(new TypeNode(ne.name(), null, 0, ref.line()));
            } catch (Exception ignored) {
            }
        }
        LocalVariable receiverLocal = refTargetName != null ? ctx.scope().resolve(refTargetName) : null;
        boolean refTargetIsType = receiverLocal == null && refTargetType != null && refTargetType.internalName() != null
                && !"I".equals(refTargetType.descriptor());
        boolean refTargetLoadable = refTargetIsType && ctx.methodResolver().classpathManager().loadClass(refTargetType.internalName()) != null;

        Method targetMethod = null;
        Constructor<?> targetCtor = null;
        boolean isStaticTarget = false;
        boolean isUnboundInstanceRef = false;

        String fallbackOwnerInternal = null;
        String fallbackDescriptor = null;
        boolean fallbackIsStatic = false;
        boolean fallbackIsCtor = false;
        if (isCtorRef && refTargetLoadable) {
            Class<?> cls = ctx.methodResolver().classpathManager().loadClass(refTargetType.internalName());
            if (cls == null)
                throw new CodeGenException("Cannot load class " + refTargetType.internalName(), ref.line());
            int want = samMethod.getParameterCount();
            for (Constructor<?> c : ctx.methodResolver().classpathManager().cachedDeclaredConstructors(cls)) {
                if (c.getParameterCount() == want) {
                    targetCtor = c;
                    break;
                }
            }
            if (targetCtor == null)
                throw new CodeGenException("No matching constructor for " + refTargetType.internalName(), ref.line());
        } else if (isCtorRef && refTargetIsType) {
            fallbackIsCtor = true;
            fallbackOwnerInternal = refTargetType.internalName();
            StringBuilder sb = new StringBuilder("(");
            for (Class<?> p : samMethod.getParameterTypes()) sb.append(Type.getDescriptor(p));
            sb.append(")V");
            fallbackDescriptor = sb.toString();
        } else if (refTargetLoadable) {
            Class<?> cls = ctx.methodResolver().classpathManager().loadClass(refTargetType.internalName());
            if (cls == null)
                throw new CodeGenException("Cannot load class " + refTargetType.internalName(), ref.line());
            int samArity = samMethod.getParameterCount();
            Method staticMatch = null, instMatch = null;
            for (Method m : ctx.methodResolver().classpathManager().cachedMethods(cls)) {
                if (!m.getName().equals(ref.methodName())) continue;
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == samArity && staticMatch == null)
                    staticMatch = m;
                else if (!Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == samArity - 1 && instMatch == null)
                    instMatch = m;
            }
            if (staticMatch != null) {
                targetMethod = staticMatch;
                isStaticTarget = true;
            } else if (instMatch != null) {
                targetMethod = instMatch;
                isUnboundInstanceRef = true;
            } else
                throw new CodeGenException("No matching method " + ref.methodName() + " in " + refTargetType.internalName(), ref.line());
        } else if (refTargetIsType) {
            fallbackOwnerInternal = refTargetType.internalName();
            fallbackIsStatic = true;
            StringBuilder sb = new StringBuilder("(");
            for (Class<?> p : samMethod.getParameterTypes()) sb.append(Type.getDescriptor(p));
            sb.append(")").append(Type.getDescriptor(samMethod.getReturnType()));
            fallbackDescriptor = sb.toString();
            if (refTargetType.internalName().equals(ctx.classInternalName())) {
                SelfMethodInfo info = ctx.selfMethods().get(ref.methodName() + ":" + samMethod.getParameterCount());
                if (info != null) {
                    fallbackDescriptor = info.descriptor();
                    fallbackIsStatic = info.isStatic();
                }
            }
        } else {
            ResolvedType recvType = ctx.typeInferrer().infer(ref.target());
            if (recvType == null || recvType.internalName() == null)
                throw new CodeGenException("Cannot resolve method reference receiver", ref.line());
            Class<?> cls = ctx.methodResolver().classpathManager().loadClass(recvType.internalName());
            if (cls == null) throw new CodeGenException("Cannot load: " + recvType.internalName(), ref.line());
            int samArity = samMethod.getParameterCount();
            for (Method m : ctx.methodResolver().classpathManager().cachedMethods(cls)) {
                if (m.getName().equals(ref.methodName()) && m.getParameterCount() == samArity) {
                    targetMethod = m;
                    break;
                }
            }
            if (targetMethod == null) throw new CodeGenException("No matching method " + ref.methodName(), ref.line());
        }

        Handle implHandle;
        String lambdaDescriptor;
        if (fallbackOwnerInternal != null) {
            if (fallbackIsCtor) {
                implHandle = new Handle(Opcodes.H_NEWINVOKESPECIAL, fallbackOwnerInternal, "<init>", fallbackDescriptor, false);
                lambdaDescriptor = fallbackDescriptor.substring(0, fallbackDescriptor.indexOf(')') + 1) + "L" + fallbackOwnerInternal + ";";
            } else {
                implHandle = new Handle(fallbackIsStatic ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKEVIRTUAL, fallbackOwnerInternal, ref.methodName(), fallbackDescriptor, false);
                lambdaDescriptor = fallbackDescriptor;
                isStaticTarget = fallbackIsStatic;
            }
        } else if (targetCtor != null) {
            implHandle = new Handle(Opcodes.H_NEWINVOKESPECIAL, refTargetType.internalName(), "<init>", Type.getConstructorDescriptor(targetCtor), false);
            lambdaDescriptor = "(" + descriptorOf(targetCtor.getParameterTypes()) + ")L" + refTargetType.internalName() + ";";
        } else {
            String ownerInternal = targetMethod.getDeclaringClass().getName().replace('.', '/');
            boolean isInterface = targetMethod.getDeclaringClass().isInterface();
            int handleTag;
            String implDesc = Type.getMethodDescriptor(targetMethod);
            if (isStaticTarget) {
                handleTag = Opcodes.H_INVOKESTATIC;
                lambdaDescriptor = implDesc;
            } else if (isUnboundInstanceRef) {
                handleTag = isInterface ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL;
                lambdaDescriptor = "(L" + ownerInternal + ";" + descriptorOf(targetMethod.getParameterTypes()) + ")" + Type.getDescriptor(targetMethod.getReturnType());
            } else {
                handleTag = isInterface ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL;
                lambdaDescriptor = implDesc;
            }
            implHandle = new Handle(handleTag, ownerInternal, targetMethod.getName(), implDesc, isInterface);
        }

        StringBuilder indyDesc = new StringBuilder("(");
        MethodVisitor mv = ctx.mv();
        boolean bindReceiver = !isStaticTarget && !isUnboundInstanceRef && targetCtor == null && !fallbackIsCtor && !refTargetIsType;
        if (bindReceiver) {
            exprGen.generate(ref.target());
            ResolvedType recvType = ctx.typeInferrer().infer(ref.target());
            if (recvType != null && recvType.internalName() != null)
                indyDesc.append("L").append(recvType.internalName()).append(";");
            else indyDesc.append("Ljava/lang/Object;");
        }
        indyDesc.append(")L").append(targetType.internalName()).append(";");

        Handle bsmHandle = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        Type samType = Type.getMethodType(samDescriptor);
        Type instantiatedType = Type.getMethodType(lambdaDescriptor);
        mv.visitInvokeDynamicInsn(samName, indyDesc.toString(), bsmHandle, samType, implHandle, instantiatedType);
    }

    /**
     * Emits bytecode for a lambda expression: resolves the SAM target,
     * analyses captures, writes a synthetic {@code lambda$<enclosing>$N}
     * method body carrying the lambda's logic, and issues the enclosing
     * {@code invokedynamic} that builds the functional-interface instance.
     *
     * @param lambda     lambda expression
     * @param targetType functional-interface type the lambda flows into
     */
    public void emitLambda(@NotNull LambdaExpression lambda, @Nullable ResolvedType targetType) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        ClassWriter cw = ctx.classWriter();
        if (cw == null) throw new CodeGenException("Lambda expressions require class level context", lambda.line());

        if (targetType == null || targetType.internalName() == null)
            throw new CodeGenException("Cannot determine target functional interface for lambda", lambda.line());

        String interfaceInternal = targetType.internalName();
        Class<?> ifaceClass = ctx.methodResolver().classpathManager().loadClass(interfaceInternal);
        if (ifaceClass == null)
            throw new CodeGenException("Cannot load functional interface: " + interfaceInternal, lambda.line());

        Method samMethod = null;
        for (Method m : ctx.methodResolver().classpathManager().cachedMethods(ifaceClass)) {
            if (!Modifier.isAbstract(m.getModifiers())) continue;
            if (isOverrideOfObjectMethod(m)) continue;
            samMethod = m;
            break;
        }
        if (samMethod == null)
            throw new CodeGenException("No abstract method found in: " + interfaceInternal, lambda.line());

        String samName = samMethod.getName();
        String samDescriptor = Type.getMethodDescriptor(samMethod);
        Class<?>[] samParamTypes = samMethod.getParameterTypes();
        Class<?> samReturnType = samMethod.getReturnType();

        ResolvedType[] resolvedSamParams = resolveSamParams(samMethod, ifaceClass, targetType);
        Class<?> instantiatedReturn = resolveSamReturn(samMethod, ifaceClass, targetType, samReturnType);

        Set<String> lambdaParamNames = new HashSet<>();
        for (Parameter p : lambda.parameters()) lambdaParamNames.add(p.name());

        LinkedHashMap<String, LocalVariable> captures = new LinkedHashMap<>();
        boolean hasImplicitThis = !ctx.isStatic() || ctx.scope().resolve("this") != null;
        boolean needsThis = hasImplicitThis && lambdaReferencesThis(lambda, lambdaParamNames);
        collectLambdaCaptures(lambda, lambdaParamNames, captures);

        List<ResolvedType> lambdaParamTypes = new ArrayList<>();
        for (int i = 0; i < lambda.parameters().size(); i++) {
            Parameter param = lambda.parameters().get(i);
            if ("var".equals(param.type().name())) {
                if (i < resolvedSamParams.length) lambdaParamTypes.add(resolvedSamParams[i]);
                else if (i < samParamTypes.length)
                    lambdaParamTypes.add(DescriptorUtils.resolvedTypeFromClass(samParamTypes[i]));
                else lambdaParamTypes.add(ResolvedType.ofObject("java/lang/Object"));
            } else {
                lambdaParamTypes.add(ctx.typeResolver().resolve(param.type()));
            }
        }

        StringBuilder lambdaDescBuilder = new StringBuilder("(");
        for (LocalVariable cap : captures.values()) lambdaDescBuilder.append(cap.type().descriptor());
        for (ResolvedType t : lambdaParamTypes) lambdaDescBuilder.append(t.descriptor());
        lambdaDescBuilder.append(")").append(Type.getDescriptor(instantiatedReturn));
        String lambdaDescriptor = lambdaDescBuilder.toString();

        String enclosing = ctx.enclosingMethodName() != null ? ctx.enclosingMethodName() : "new";
        if (enclosing.startsWith("lambda$")) {
            int secondDollar = enclosing.indexOf('$', "lambda$".length());
            if (secondDollar > 0) enclosing = enclosing.substring("lambda$".length(), secondDollar);
        }
        if ("<clinit>".equals(enclosing)) enclosing = "static";
        else if ("<init>".equals(enclosing)) enclosing = "new";
        int myIndex = ctx.classGenerator() != null
                ? ctx.classGenerator().lambdaIndexFor(lambda)
                : (ctx.lambdaCounter() != null ? ctx.lambdaCounter().getAndIncrement() : 0);
        String lambdaMethodName = "lambda$" + enclosing + "$" + myIndex;
        int lambdaAccess = needsThis ? (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC) : (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);

        RecordingMethodVisitor lambdaBuffer = new RecordingMethodVisitor();
        lambdaBuffer.visitCode();

        Scope lambdaScope = new Scope(0);
        int slot = 0;
        if (needsThis) {
            lambdaScope.declare("this", ResolvedType.ofObject(ctx.classInternalName()));
            slot = 1;
        }
        for (LocalVariable cap : captures.values()) {
            lambdaScope.declare(cap.name(), cap.type());
            slot += cap.type().stackSize();
        }
        for (int i = 0; i < lambda.parameters().size(); i++) {
            lambdaScope.declare(lambda.parameters().get(i).name(), lambdaParamTypes.get(i));
            slot += lambdaParamTypes.get(i).stackSize();
        }
        lambdaScope.syncNextLocalIndex(slot);

        MethodContext lambdaCtx = new MethodContext(lambdaBuffer, lambdaScope, ctx.typeResolver(), new MethodResolver(ctx.methodResolver().classpathManager()), ctx.classInternalName(), ctx.superInternalName(), !needsThis, ctx.selfMethods());
        lambdaCtx.classGenerator(ctx.classGenerator());
        lambdaCtx.setupLambdaSupport(cw, ctx.lambdaCounter() != null ? ctx.lambdaCounter() : new AtomicInteger(), lambdaMethodName);
        lambdaCtx.typeInferrer().registerSelfMethods(ctx.selfMethods());
        lambdaCtx.typeInferrer().copyFieldsFrom(ctx.typeInferrer());

        if (lambda.expressionBody() != null) {
            ExpressionGenerator lambdaExprGen = new ExpressionGenerator(lambdaCtx);
            lambdaExprGen.generate(lambda.expressionBody());
            ResolvedType exprType = lambdaCtx.typeInferrer().infer(lambda.expressionBody());
            if (instantiatedReturn == void.class) {
                if (exprType != null && !exprType.isVoid())
                    lambdaBuffer.visitInsn(exprType.stackSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                lambdaBuffer.visitInsn(Opcodes.RETURN);
            } else {
                if (exprType != null && exprType.isPrimitive() && !instantiatedReturn.isPrimitive()) {
                    PrimitiveConversionEmitter.emitBoxing(lambdaBuffer, exprType);
                }
                lambdaBuffer.visitInsn(DescriptorUtils.returnInsn(instantiatedReturn));
            }
        } else if (lambda.body() instanceof BlockStatement block) {
            MethodGenerator lambdaGen = new MethodGenerator(lambdaCtx);
            lambdaGen.generateBody(block);
            if (instantiatedReturn == void.class) lambdaBuffer.visitInsn(Opcodes.RETURN);
        }

        lambdaBuffer.visitMaxs(0, 0);
        lambdaBuffer.visitEnd();
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null || !cg.methodEmitter().tryDeferMethod(cw, lambdaAccess, lambdaMethodName, lambdaDescriptor, lambdaBuffer)) {
            synchronized (cw) {
                MethodVisitor lambdaDest = cw.visitMethod(lambdaAccess, lambdaMethodName, lambdaDescriptor, null, null);
                lambdaBuffer.replayInto(lambdaDest);
            }
        }

        Handle bsmHandle = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        Handle implHandle = needsThis
                ? new Handle(Opcodes.H_INVOKEVIRTUAL, ctx.classInternalName(), lambdaMethodName, lambdaDescriptor, false)
                : new Handle(Opcodes.H_INVOKESTATIC, ctx.classInternalName(), lambdaMethodName, lambdaDescriptor, false);

        StringBuilder indyDescBuilder = new StringBuilder("(");
        if (needsThis) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            indyDescBuilder.append("L").append(ctx.classInternalName()).append(";");
        }
        for (LocalVariable cap : captures.values()) {
            mv.visitVarInsn(OpcodeUtils.loadOpcode(cap.type()), cap.index());
            indyDescBuilder.append(cap.type().descriptor());
        }
        indyDescBuilder.append(")L").append(interfaceInternal).append(";");

        StringBuilder instantiatedDescBuilder = new StringBuilder("(");
        for (ResolvedType t : lambdaParamTypes) instantiatedDescBuilder.append(t.descriptor());
        instantiatedDescBuilder.append(")").append(Type.getDescriptor(instantiatedReturn));
        Type samType = Type.getMethodType(samDescriptor);
        Type instantiatedType = Type.getMethodType(instantiatedDescBuilder.toString());
        mv.visitInvokeDynamicInsn(samName, indyDescBuilder.toString(), bsmHandle, samType, implHandle, instantiatedType);
    }

    /**
     * True when {@code m}'s name and parameter types match a method on
     * {@link Object}. Lets SAM discovery skip Object overrides (equals,
     * hashCode, toString) that every functional interface technically
     * re-declares but which aren't the "single abstract method".
     *
     * @param m candidate abstract method
     * @return true when a same-signature method exists on {@link Object}
     */
    private boolean isOverrideOfObjectMethod(@NotNull Method m) {
        try {
            Object.class.getMethod(m.getName(), m.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Resolves the SAM method's parameter types with the call-site's generic
     * arguments applied. Each slot falls back to the raw type when the
     * generic resolution can't produce a {@link ResolvedType}.
     *
     * @param samMethod SAM method from the target functional interface
     * @param samOwner  owning interface class
     * @param expected  expected target type with any type arguments
     * @return per-parameter resolved types
     */
    private @NotNull ResolvedType[] resolveSamParams(@NotNull Method samMethod, @NotNull Class<?> samOwner, @Nullable ResolvedType expected) {
        java.lang.reflect.Type[] generic = samMethod.getGenericParameterTypes();
        Class<?>[] raw = samMethod.getParameterTypes();
        ResolvedType[] out = new ResolvedType[raw.length];
        Map<String, ResolvedType> tvMap = buildTypeVarMap(samOwner, expected);
        for (int i = 0; i < raw.length; i++) {
            ResolvedType resolved = resolveGenericTypeWithMap(generic[i], tvMap);
            out[i] = resolved != null ? resolved : DescriptorUtils.resolvedTypeFromClass(raw[i]);
        }
        return out;
    }

    /**
     * Resolves the SAM return type with generic info, mirroring
     * {@link #resolveSamParams(Method, Class, ResolvedType)}.
     *
     * @param samMethod SAM method
     * @param samOwner  owning functional-interface class
     * @param expected  expected target type carrying type arguments
     * @param rawReturn raw return type when generic resolution fails
     * @return resolved concrete class driving the lambda's return opcode
     */
    private @NotNull Class<?> resolveSamReturn(@NotNull Method samMethod, @NotNull Class<?> samOwner, @Nullable ResolvedType expected, @NotNull Class<?> rawReturn) {
        java.lang.reflect.Type genericReturn = samMethod.getGenericReturnType();
        Map<String, ResolvedType> tvMap = buildTypeVarMap(samOwner, expected);
        if (genericReturn instanceof TypeVariable<?> tv) {
            ResolvedType resolved = tvMap.get(tv.getName());
            if (resolved != null && resolved.internalName() != null) {
                Class<?> c = exprGen.ctx().methodResolver().classpathManager().loadClass(resolved.internalName());
                if (c != null) return c;
            }
        }
        return rawReturn;
    }

    /**
     * Builds a map from the functional-interface's type-parameter names to
     * the concrete arguments supplied by the call-site, read off
     * {@link ResolvedType#typeArguments()}.
     *
     * @param owner    functional-interface class
     * @param expected expected target type with type arguments, or null
     * @return map from type-variable name to resolved argument
     */
    private @NotNull Map<String, ResolvedType> buildTypeVarMap(@NotNull Class<?> owner, @Nullable ResolvedType expected) {
        Map<String, ResolvedType> map = new HashMap<>();
        if (expected == null || expected.typeArguments() == null) return map;
        TypeVariable<?>[] tvs = owner.getTypeParameters();
        List<ResolvedType> args = expected.typeArguments();
        for (int i = 0; i < tvs.length && i < args.size(); i++) {
            map.put(tvs[i].getName(), args.get(i));
        }
        return map;
    }

    /**
     * Resolves a reflective {@link java.lang.reflect.Type} against a
     * type-variable substitution map. Returns the matching
     * {@link ResolvedType} or null when no resolution is possible.
     *
     * @param t     reflective type to resolve
     * @param tvMap current substitution map
     * @return matching resolved type or null
     */
    private @Nullable ResolvedType resolveGenericTypeWithMap(@NotNull java.lang.reflect.Type t, @NotNull Map<String, ResolvedType> tvMap) {
        if (t instanceof Class<?> c) return DescriptorUtils.resolvedTypeFromClass(c);
        if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> rc) {
            return DescriptorUtils.resolvedTypeFromClass(rc);
        }
        if (t instanceof TypeVariable<?> tv) {
            ResolvedType mapped = tvMap.get(tv.getName());
            if (mapped != null) return mapped;
            java.lang.reflect.Type[] bounds = tv.getBounds();
            if (bounds.length > 0) return resolveGenericTypeWithMap(bounds[0], tvMap);
            return ResolvedType.ofObject("java/lang/Object");
        }
        if (t instanceof WildcardType wt) {
            java.lang.reflect.Type[] upper = wt.getUpperBounds();
            if (upper.length > 0) return resolveGenericTypeWithMap(upper[0], tvMap);
        }
        return null;
    }

    /**
     * True when the body of {@code lambda} references {@code this} (directly,
     * through a {@code this.foo} access, or through an unqualified call to a
     * method name that isn't shadowed by a lambda parameter). Drives the
     * choice between {@code INVOKESTATIC} and {@code INVOKEVIRTUAL} for the
     * synthetic backing method handle.
     *
     * @param lambda     lambda expression
     * @param paramNames names of the lambda's own parameters
     * @return true when the lambda body implicitly reads {@code this}
     */
    private boolean lambdaReferencesThis(@NotNull LambdaExpression lambda, @NotNull Set<String> paramNames) {
        MethodContext ctx = exprGen.ctx();
        List<Expression> exprs = new ArrayList<>();
        if (lambda.expressionBody() != null)
            exprGen.expressionWalker().collectSubExpressions(lambda.expressionBody(), exprs);
        if (lambda.body() instanceof BlockStatement block) exprGen.expressionWalker().collectFromBlock(block, exprs);
        for (Expression expr : exprs) {
            if (expr instanceof ThisExpression) return true;
            if (expr instanceof FieldAccessExpression fa && fa.target() instanceof ThisExpression) return true;
            if (expr instanceof MethodCallExpression mc && mc.target() == null && !paramNames.contains(mc.methodName()))
                return true;
            if (expr instanceof NameExpression name && !paramNames.contains(name.name()) && ctx.typeInferrer().inferField(name.name()) != null)
                return true;
        }
        return false;
    }

    /**
     * Walks the lambda body and records any outer-scope locals that the
     * lambda reads, so they can be passed as arguments to the synthetic
     * backing method.
     *
     * @param lambda     lambda expression
     * @param paramNames names of the lambda's own parameters
     * @param captures   accumulator populated with captured local variables
     */
    private void collectLambdaCaptures(@NotNull LambdaExpression lambda, @NotNull Set<String> paramNames, @NotNull LinkedHashMap<String, LocalVariable> captures) {
        MethodContext ctx = exprGen.ctx();
        List<Expression> exprs = new ArrayList<>();
        if (lambda.expressionBody() != null)
            exprGen.expressionWalker().collectSubExpressions(lambda.expressionBody(), exprs);
        if (lambda.body() instanceof BlockStatement block) exprGen.expressionWalker().collectFromBlock(block, exprs);
        for (Expression expr : exprs) {
            if (expr instanceof NameExpression name && !paramNames.contains(name.name()) && !captures.containsKey(name.name())) {
                LocalVariable local = ctx.scope().resolve(name.name());
                if (local != null) captures.put(name.name(), local);
            }
        }
    }

    /**
     * @param params reflective parameter types
     * @return concatenated JVM descriptors ({@code "II"} for {@code int, int}, etc.)
     */
    private @NotNull String descriptorOf(@NotNull Class<?>[] params) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : params) sb.append(Type.getDescriptor(p));
        return sb.toString();
    }
}
