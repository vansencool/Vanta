package net.vansencool.vanta.codegen.expression.lambda;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.MethodGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.classes.visitor.RecordingMethodVisitor;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.lambda.BoundMethodReferenceDiagnostic;
import net.vansencool.vanta.codegen.diagnostic.lambda.ConstructorReferenceDiagnostic;
import net.vansencool.vanta.codegen.diagnostic.lambda.LambdaNoSamDiagnostic;
import net.vansencool.vanta.codegen.diagnostic.lambda.LambdaTargetDiagnostic;
import net.vansencool.vanta.codegen.diagnostic.lambda.MethodReferenceDiagnostic;
import net.vansencool.vanta.codegen.diagnostic.lambda.MethodReferenceNoSamDiagnostic;
import net.vansencool.vanta.codegen.diagnostic.lambda.MethodReferenceReceiverDiagnostic;
import net.vansencool.vanta.codegen.diagnostic.lambda.MethodReferenceReceiverNotLoadableDiagnostic;
import net.vansencool.vanta.codegen.diagnostic.lambda.MethodReferenceTargetDiagnostic;
import net.vansencool.vanta.codegen.diagnostic.lambda.MissingFunctionalInterfaceDiagnostic;
import net.vansencool.vanta.codegen.diagnostic.lambda.capture.NonEffectivelyFinalCaptureDiagnostic;
import net.vansencool.vanta.codegen.expression.cast.PrimitiveConversionEmitter;
import net.vansencool.vanta.exception.CompilationException;
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
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

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
 * so this class concentrates: SAM method discovery and generic param
 * resolution, capture analysis over the lambda body, emission of the
 * synthetic {@code lambda$<enclosing>$N} backing method, and assembly of
 * the final {@code invokedynamic} instruction.
 */
public final class LambdaEmitter {

    private static final Set<String> OBJECT_METHODS_BY_ARITY = Set.of(
            "equals:1", "hashCode:0", "toString:0", "getClass:0",
            "wait:0", "wait:1", "wait:2", "notify:0", "notifyAll:0", "clone:0", "finalize:0"
    );

    private final @NotNull ExpressionGenerator exprGen;

    public LambdaEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

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

    public static boolean endsWithAnonSuffix(@NotNull String internal) {
        int dollar = internal.lastIndexOf('$');
        if (dollar < 0 || dollar == internal.length() - 1) return false;
        for (int i = dollar + 1; i < internal.length(); i++) {
            if (!Character.isDigit(internal.charAt(i))) return false;
        }
        return true;
    }

    public void emitMethodReference(@NotNull MethodReferenceExpression ref, @Nullable ResolvedType targetType) {
        MethodContext ctx = exprGen.ctx();
        if (targetType == null || targetType.internalName() == null) {
            throw new CompilationException(MethodReferenceTargetDiagnostic.build(ctx, ref));
        }
        ClassWriter cw = ctx.classWriter();
        if (cw == null) throw new IllegalStateException("internal compiler error: method reference emission requires a class writer on the method context, none present at line " + ref.line());
        TypeSymbol ifaceSym = registry().lookup(targetType.internalName());
        if (ifaceSym == null)
            throw new CompilationException(MissingFunctionalInterfaceDiagnostic.build(ctx, ref, targetType.internalName(), "method reference"));
        MethodSymbol samSym = findSam(ifaceSym);
        if (samSym == null)
            throw new CompilationException(MethodReferenceNoSamDiagnostic.build(ctx, ref, ifaceSym));
        String samName = samSym.name();
        String samDescriptor = samSym.descriptor();
        int samArity = samSym.parameterTypes().size();

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
                && !"I".equals(refTargetType.descriptor())
                && ctx.methodResolver().classpathManager().exists(refTargetType.internalName());
        TypeSymbol refTargetSym = refTargetIsType ? registry().lookup(refTargetType.internalName()) : null;

        MethodSymbol targetSym = null;
        boolean targetIsCtor = false;
        boolean isStaticTarget = false;
        boolean isUnboundInstanceRef = false;

        String fallbackOwnerInternal = null;
        String fallbackDescriptor = null;
        boolean fallbackIsStatic = false;
        boolean fallbackIsCtor = false;

        if (isCtorRef && refTargetSym != null) {
            for (MethodSymbol m : refTargetSym.methods()) {
                if (!"<init>".equals(m.name())) continue;
                if (m.parameterTypes().size() != samArity) continue;
                targetSym = m;
                targetIsCtor = true;
                break;
            }
            if (targetSym == null)
                throw new CompilationException(ConstructorReferenceDiagnostic.build(ctx, ref, refTargetSym, samArity));
        } else if (isCtorRef && refTargetIsType) {
            fallbackIsCtor = true;
            fallbackOwnerInternal = refTargetType.internalName();
            StringBuilder sb = new StringBuilder("(");
            for (TypeRef p : samSym.parameterTypes()) sb.append(p.descriptor());
            sb.append(")V");
            fallbackDescriptor = sb.toString();
        } else if (refTargetSym != null) {
            MethodSymbol staticMatch = null;
            MethodSymbol instMatch = null;
            for (MethodSymbol m : allMethodsIncludingSupers(refTargetSym)) {
                if (!m.name().equals(ref.methodName())) continue;
                int arity = m.parameterTypes().size();
                if (m.isStatic() && arity == samArity && staticMatch == null) staticMatch = m;
                else if (!m.isStatic() && arity == samArity - 1 && instMatch == null) instMatch = m;
            }
            if (staticMatch != null) {
                targetSym = staticMatch;
                isStaticTarget = true;
            } else if (instMatch != null) {
                targetSym = instMatch;
                isUnboundInstanceRef = true;
            } else throw new CompilationException(MethodReferenceDiagnostic.build(ctx, ref, refTargetSym, samArity));
        } else if (refTargetIsType) {
            fallbackOwnerInternal = refTargetType.internalName();
            fallbackIsStatic = true;
            StringBuilder sb = new StringBuilder("(");
            for (TypeRef p : samSym.parameterTypes()) sb.append(p.descriptor());
            sb.append(")").append(samSym.returnType().descriptor());
            fallbackDescriptor = sb.toString();
            if (refTargetType.internalName().equals(ctx.classInternalName())) {
                SelfMethodInfo info = ctx.selfMethods().get(ref.methodName() + ":" + samArity);
                if (info != null) {
                    fallbackDescriptor = info.descriptor();
                    fallbackIsStatic = info.isStatic();
                }
            }
        } else {
            ResolvedType recvType = ctx.typeInferrer().infer(ref.target());
            if (recvType == null || recvType.internalName() == null)
                throw new CompilationException(MethodReferenceReceiverDiagnostic.build(ctx, ref));
            TypeSymbol recvSym = registry().lookup(recvType.internalName());
            if (recvSym == null) throw new CompilationException(MethodReferenceReceiverNotLoadableDiagnostic.build(ctx, ref, recvType.internalName()));
            for (MethodSymbol m : allMethodsIncludingSupers(recvSym)) {
                if (m.name().equals(ref.methodName()) && m.parameterTypes().size() == samArity) {
                    targetSym = m;
                    break;
                }
            }
            if (targetSym == null) throw new CompilationException(BoundMethodReferenceDiagnostic.build(ctx, ref, recvSym, samArity));
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
        } else if (targetIsCtor) {
            String ctorDescriptor = targetSym.descriptor();
            implHandle = new Handle(Opcodes.H_NEWINVOKESPECIAL, refTargetType.internalName(), "<init>", ctorDescriptor, false);
            lambdaDescriptor = ctorDescriptor.substring(0, ctorDescriptor.indexOf(')') + 1) + "L" + refTargetType.internalName() + ";";
        } else {
            String ownerInternal = targetSym.owner().internalName();
            boolean isInterface = targetSym.owner().isInterface();
            int handleTag;
            String implDesc = targetSym.descriptor();
            if (isStaticTarget) {
                handleTag = Opcodes.H_INVOKESTATIC;
                lambdaDescriptor = implDesc;
            } else if (isUnboundInstanceRef) {
                handleTag = isInterface ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL;
                StringBuilder sb = new StringBuilder("(L").append(ownerInternal).append(";");
                for (TypeRef p : targetSym.parameterTypes()) sb.append(p.descriptor());
                sb.append(")").append(targetSym.returnType().descriptor());
                lambdaDescriptor = sb.toString();
            } else {
                handleTag = isInterface ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL;
                lambdaDescriptor = implDesc;
            }
            implHandle = new Handle(handleTag, ownerInternal, targetSym.name(), implDesc, isInterface);
        }

        StringBuilder indyDesc = new StringBuilder("(");
        MethodVisitor mv = ctx.mv();
        boolean bindReceiver = !isStaticTarget && !isUnboundInstanceRef && !targetIsCtor && !fallbackIsCtor && !refTargetIsType;
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

    public void emitLambda(@NotNull LambdaExpression lambda, @Nullable ResolvedType targetType) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        ClassWriter cw = ctx.classWriter();
        if (cw == null) throw new IllegalStateException("internal compiler error: lambda emission requires a class writer on the method context, none present at line " + lambda.line());

        if (targetType == null || targetType.internalName() == null)
            throw new CompilationException(LambdaTargetDiagnostic.build(ctx, lambda));

        String interfaceInternal = targetType.internalName();
        TypeSymbol ifaceSym = registry().lookup(interfaceInternal);
        if (ifaceSym == null)
            throw new CompilationException(MissingFunctionalInterfaceDiagnostic.build(ctx, lambda, interfaceInternal, "lambda"));

        MethodSymbol samSym = findSam(ifaceSym);
        if (samSym == null)
            throw new CompilationException(LambdaNoSamDiagnostic.build(ctx, lambda, ifaceSym));

        String samName = samSym.name();
        String samDescriptor = samSym.descriptor();
        List<TypeRef> samParamRefs = samSym.parameterTypes();
        TypeRef samReturnRef = samSym.returnType();

        Map<String, ResolvedType> tvMap = buildTypeVarMap(ifaceSym, targetType);
        ResolvedType[] resolvedSamParams = resolveSamParams(samParamRefs, tvMap);
        ResolvedType instantiatedReturn = resolveSamReturn(samReturnRef, tvMap);
        boolean returnsVoid = "V".equals(samReturnRef.descriptor());

        Set<String> lambdaParamNames = new HashSet<>();
        for (Parameter p : lambda.parameters()) lambdaParamNames.add(p.name());

        LinkedHashMap<String, LocalVariable> captures = new LinkedHashMap<>();
        boolean hasImplicitThis = !ctx.isStatic() || ctx.scope().resolve("this") != null;
        boolean needsThis = hasImplicitThis && lambdaReferencesThis(lambda, lambdaParamNames);
        collectLambdaCaptures(lambda, lambdaParamNames, captures);
        for (LocalVariable cap : captures.values()) {
            if (!ctx.isEffectivelyFinal(cap)) {
                throw new CompilationException(NonEffectivelyFinalCaptureDiagnostic.build(ctx, lambda, cap, "lambda"));
            }
        }

        List<ResolvedType> lambdaParamTypes = new ArrayList<>();
        for (int i = 0; i < lambda.parameters().size(); i++) {
            Parameter param = lambda.parameters().get(i);
            if ("var".equals(param.type().name())) {
                if (i < resolvedSamParams.length) lambdaParamTypes.add(resolvedSamParams[i]);
                else if (i < samParamRefs.size())
                    lambdaParamTypes.add(refToResolvedType(samParamRefs.get(i)));
                else lambdaParamTypes.add(ResolvedType.ofObject("java/lang/Object"));
            } else {
                lambdaParamTypes.add(ctx.typeResolver().resolve(param.type()));
            }
        }

        StringBuilder lambdaDescBuilder = new StringBuilder("(");
        for (LocalVariable cap : captures.values()) lambdaDescBuilder.append(cap.type().descriptor());
        for (ResolvedType t : lambdaParamTypes) lambdaDescBuilder.append(t.descriptor());
        lambdaDescBuilder.append(")").append(returnsVoid ? "V" : instantiatedReturn.descriptor());
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
            if (returnsVoid) {
                if (exprType != null && !exprType.isVoid())
                    lambdaBuffer.visitInsn(exprType.stackSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                lambdaBuffer.visitInsn(Opcodes.RETURN);
            } else {
                if (exprType != null && exprType.isPrimitive() && !instantiatedReturn.isPrimitive()) {
                    PrimitiveConversionEmitter.emitBoxing(lambdaBuffer, exprType);
                }
                lambdaBuffer.visitInsn(returnInsnFor(instantiatedReturn));
            }
        } else if (lambda.body() instanceof BlockStatement block) {
            MethodGenerator lambdaGen = new MethodGenerator(lambdaCtx);
            lambdaGen.generateBody(block);
            if (returnsVoid) lambdaBuffer.visitInsn(Opcodes.RETURN);
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
        instantiatedDescBuilder.append(")").append(returnsVoid ? "V" : instantiatedReturn.descriptor());
        Type samType = Type.getMethodType(samDescriptor);
        Type instantiatedType = Type.getMethodType(instantiatedDescBuilder.toString());
        mv.visitInvokeDynamicInsn(samName, indyDescBuilder.toString(), bsmHandle, samType, implHandle, instantiatedType);
    }

    /**
     * Walks {@code owner} and its supers picking the first abstract instance
     * method that isn't an Object override (equals/hashCode/etc.). Functional
     * interfaces have exactly one such method by definition.
     */
    private @Nullable MethodSymbol findSam(@NotNull TypeSymbol owner) {
        Set<String> visited = new HashSet<>();
        return findSamWalk(owner, visited);
    }

    private @Nullable MethodSymbol findSamWalk(@NotNull TypeSymbol owner, @NotNull Set<String> visited) {
        if (!visited.add(owner.internalName())) return null;
        for (MethodSymbol m : owner.methods()) {
            if (!m.isAbstract()) continue;
            if (m.isStatic()) continue;
            if (isOverrideOfObjectMethod(m)) continue;
            return m;
        }
        for (TypeSymbol iface : owner.interfaces()) {
            MethodSymbol found = findSamWalk(iface, visited);
            if (found != null) return found;
        }
        TypeSymbol sup = owner.superclass();
        if (sup != null) return findSamWalk(sup, visited);
        return null;
    }

    private @NotNull List<MethodSymbol> allMethodsIncludingSupers(@NotNull TypeSymbol owner) {
        List<MethodSymbol> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        collectMethods(owner, out, visited);
        return out;
    }

    private void collectMethods(@NotNull TypeSymbol owner, @NotNull List<MethodSymbol> out, @NotNull Set<String> visited) {
        if (!visited.add(owner.internalName())) return;
        out.addAll(owner.methods());
        TypeSymbol sup = owner.superclass();
        if (sup != null) collectMethods(sup, out, visited);
        for (TypeSymbol iface : owner.interfaces()) collectMethods(iface, out, visited);
    }

    /**
     * True when {@code m}'s name + arity match a method on {@link Object}.
     * Lets SAM discovery skip equals/hashCode/toString that every functional
     * interface technically re declares but which aren't the SAM.
     */
    private boolean isOverrideOfObjectMethod(@NotNull MethodSymbol m) {
        return OBJECT_METHODS_BY_ARITY.contains(m.name() + ":" + m.parameterTypes().size());
    }

    private @NotNull ResolvedType[] resolveSamParams(@NotNull List<TypeRef> samParams, @NotNull Map<String, ResolvedType> tvMap) {
        ResolvedType[] out = new ResolvedType[samParams.size()];
        for (int i = 0; i < samParams.size(); i++) {
            ResolvedType resolved = resolveRefWithMap(samParams.get(i), tvMap);
            out[i] = resolved != null ? resolved : refToResolvedType(samParams.get(i));
        }
        return out;
    }

    private @NotNull ResolvedType resolveSamReturn(@NotNull TypeRef samReturn, @NotNull Map<String, ResolvedType> tvMap) {
        ResolvedType resolved = resolveRefWithMap(samReturn, tvMap);
        return resolved != null ? resolved : refToResolvedType(samReturn);
    }

    /**
     * Builds a map from the functional interface's type parameter names to
     * the concrete arguments supplied by the call site, read off
     * {@link ResolvedType#typeArguments()}.
     */
    private @NotNull Map<String, ResolvedType> buildTypeVarMap(@NotNull TypeSymbol owner, @Nullable ResolvedType expected) {
        Map<String, ResolvedType> map = new HashMap<>();
        if (expected == null || expected.typeArguments() == null) return map;
        List<TypeParameterSymbol> tvs = owner.typeParameters();
        List<ResolvedType> args = expected.typeArguments();
        for (int i = 0; i < tvs.size() && i < args.size(); i++) {
            map.put(tvs.get(i).name(), args.get(i));
        }
        return map;
    }

    private @Nullable ResolvedType resolveRefWithMap(@NotNull TypeRef t, @NotNull Map<String, ResolvedType> tvMap) {
        if (t.isPrimitive()) return refToResolvedType(t);
        if (t.isTypeVariable()) {
            ResolvedType mapped = tvMap.get(t.typeVariableName());
            if (mapped != null) return mapped;
            return ResolvedType.ofObject("java/lang/Object");
        }
        return refToResolvedType(t);
    }

    private @NotNull ResolvedType refToResolvedType(@NotNull TypeRef ref) {
        return ResolvedType.fromDescriptor(ref.descriptor());
    }

    private int returnInsnFor(@NotNull ResolvedType type) {
        String desc = type.descriptor();
        return switch (desc) {
            case "V" -> Opcodes.RETURN;
            case "I", "Z", "B", "S", "C" -> Opcodes.IRETURN;
            case "J" -> Opcodes.LRETURN;
            case "F" -> Opcodes.FRETURN;
            case "D" -> Opcodes.DRETURN;
            default -> Opcodes.ARETURN;
        };
    }

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

    private @NotNull TypeRegistry registry() {
        return exprGen.ctx().methodResolver().classpathManager().typeRegistry();
    }
}
