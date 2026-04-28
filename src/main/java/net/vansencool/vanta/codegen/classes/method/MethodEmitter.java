package net.vansencool.vanta.codegen.classes.method;

import net.vansencool.vanta.MethodParallelism;
import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.MethodGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.StatementGenerator;
import net.vansencool.vanta.codegen.classes.visitor.RecordingMethodVisitor;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.declaration.TypeKind;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.ExpressionStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.scope.Scope;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates emission of instance and static methods for a class body.
 * Handles the full lowering pipeline: constructor super-call prefix,
 * instance-field initializer weaving, body generation through
 * {@link MethodGenerator}, local-variable table emission, parallel worker
 * batching, and deferred flushing so parallel-generated methods reach the
 * {@link ClassWriter} in source order.
 */
public final class MethodEmitter {

    private final @NotNull ClassGenerator owner;
    private final @NotNull Object cwLock = new Object();

    private final @NotNull ThreadLocal<List<DeferredAction>> pendingMethods = new ThreadLocal<>();
    private final @NotNull ThreadLocal<ClassWriter> pendingCw = new ThreadLocal<>();
    private final @NotNull ConcurrentHashMap<Integer, List<DeferredAction>> orderedDeferred = new ConcurrentHashMap<>();

    /**
     * Binds the emitter to the owning generator so it can read the shared
     * resolver, classpath, counters, and nested-class caches.
     *
     * @param owner generator this emitter cooperates with
     */
    public MethodEmitter(@NotNull ClassGenerator owner) {
        this.owner = owner;
    }

    /**
     * True when a user-authored constructor begins with an explicit
     * {@code super(...)} or {@code this(...)} call.
     */
    private static boolean startsWithConstructorCall(@NotNull MethodDeclaration methodDecl) {
        if (methodDecl.body() == null || methodDecl.body().statements().isEmpty()) return false;
        Statement first = methodDecl.body().statements().get(0);
        if (first instanceof ExpressionStatement exprStmt && exprStmt.expression() instanceof MethodCallExpression call) {
            return "super".equals(call.methodName()) || "this".equals(call.methodName());
        }
        return false;
    }

    /**
     * True when a user-authored constructor begins with an explicit
     * {@code super(...)} call specifically.
     */
    private static boolean startsWithSuperCall(@NotNull MethodDeclaration methodDecl) {
        if (methodDecl.body() == null || methodDecl.body().statements().isEmpty()) return false;
        Statement first = methodDecl.body().statements().get(0);
        if (first instanceof ExpressionStatement exprStmt && exprStmt.expression() instanceof MethodCallExpression call) {
            return "super".equals(call.methodName());
        }
        return false;
    }

    /**
     * Emits every non-synthetic method on {@code classDecl} and the trailing
     * bridge methods covering covariant/erased overrides.
     *
     * @param cw            class writer receiving method emissions
     * @param classDecl     declaration whose members are scanned
     * @param internalName  internal name of the owning class
     * @param superInternal internal name of the super class
     * @param fieldTypes    resolved types of declared fields
     * @param selfMethods   self-method table for same-class calls
     */
    public void emitMemberMethods(@NotNull ClassWriter cw, @NotNull ClassDeclaration classDecl, @NotNull String internalName, @NotNull String superInternal, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Map<String, SelfMethodInfo> selfMethods) {
        boolean isRecord = classDecl.kind() == TypeKind.RECORD;
        int recordComponentCount = isRecord && classDecl.recordComponents() != null ? classDecl.recordComponents().size() : 0;
        for (AstNode member : classDecl.members()) {
            if (member instanceof MethodDeclaration methodDecl) {
                if ("<clinit>".equals(methodDecl.name())) continue;
                if ("<iinit>".equals(methodDecl.name())) continue;
                if (isRecord && "<init>".equals(methodDecl.name()) && methodDecl.parameters().size() == recordComponentCount)
                    continue;
                emitMethod(cw, methodDecl, internalName, superInternal, fieldTypes, selfMethods, classDecl);
            }
        }
        owner.bridgeMethodEmitter().emit(cw, classDecl, internalName);
    }

    /**
     * Walks the class members in source order so static-field initializers,
     * static blocks, and methods are emitted in the exact order javac would:
     * anonymous-class and lambda indices tick in source order, and the
     * {@code <clinit>} body weaves field initializers between authored static
     * blocks.
     *
     * @param cw            class writer receiving emissions
     * @param classDecl     declaration being emitted
     * @param internalName  internal name of the owning class
     * @param superInternal internal name of the super class
     * @param fieldTypes    resolved types of declared fields
     * @param selfMethods   self-method table for same-class calls
     */
    public void emitInSourceOrder(@NotNull ClassWriter cw, @NotNull ClassDeclaration classDecl, @NotNull String internalName, @NotNull String superInternal, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Map<String, SelfMethodInfo> selfMethods) {
        boolean hasStaticInit = owner.hasStaticFieldInitOrBlock(classDecl);

        RecordingMethodVisitor clinitBuffer = null;
        ExpressionGenerator clinitExpr = null;
        StatementGenerator clinitStmt = null;
        if (hasStaticInit) {
            clinitBuffer = new RecordingMethodVisitor();
            clinitBuffer.visitCode();
            Scope cinitScope = new Scope(0);
            MethodContext clinitCtx = new MethodContext(clinitBuffer, cinitScope, owner.typeResolver(), new MethodResolver(owner.classpathManager()), internalName, superInternal, true, selfMethods);
            clinitCtx.enclosingOuterInternal(owner.currentEnclosingOuter());
            clinitCtx.enclosingStaticOuter(owner.currentStaticOuter());
            clinitCtx.nestedClassFields(owner.nestedClassFields());
            clinitCtx.nestedClassMethods(owner.nestedClassMethods());
            clinitCtx.nestedClassConstants(owner.nestedClassConstants());
            clinitCtx.setupLambdaSupport(cw, owner.lambdaCounter(), "<clinit>");
            clinitCtx.setupAnonClassSupport(owner, cw, owner.anonClassCounter(), "<clinit>", owner.anonClassBytecodes(), owner.anonClassNames());
            for (Map.Entry<String, ResolvedType> e : fieldTypes.entrySet()) {
                clinitCtx.typeInferrer().registerField(e.getKey(), e.getValue());
            }
            clinitExpr = new ExpressionGenerator(clinitCtx);
            clinitStmt = new StatementGenerator(clinitCtx, clinitExpr);
            if (owner.assertUsed()) {
                cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "$assertionsDisabled", "Z", null, null).visitEnd();
                String topInternal = owner.topEnclosingInternal(internalName);
                clinitBuffer.visitLdcInsn(Type.getObjectType(topInternal));
                clinitBuffer.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "desiredAssertionStatus", "()Z", false);
                Label assertTrue = new Label();
                Label assertEnd = new Label();
                clinitBuffer.visitJumpInsn(Opcodes.IFNE, assertTrue);
                clinitBuffer.visitInsn(Opcodes.ICONST_1);
                clinitBuffer.visitJumpInsn(Opcodes.GOTO, assertEnd);
                clinitBuffer.visitLabel(assertTrue);
                clinitBuffer.visitInsn(Opcodes.ICONST_0);
                clinitBuffer.visitLabel(assertEnd);
                clinitBuffer.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "$assertionsDisabled", "Z");
            }
        }

        boolean isRecord = classDecl.kind() == TypeKind.RECORD;
        int recordComponentCount = isRecord && classDecl.recordComponents() != null ? classDecl.recordComponents().size() : 0;

        List<MethodDeclaration> deferredMethods = new ArrayList<>();
        for (AstNode member : classDecl.members()) {
            if (member instanceof FieldDeclaration fd && (fd.modifiers() & Opcodes.ACC_STATIC) != 0 && clinitExpr != null) {
                for (FieldDeclarator declarator : fd.declarators()) {
                    if (declarator.initializer() == null || owner.constantFolder().resolveConstantValue(fd, declarator) != null)
                        continue;
                    TypeNode effectiveType = fd.type();
                    if (declarator.extraArrayDimensions() > 0) {
                        effectiveType = effectiveType.withExtraDimensions(declarator.extraArrayDimensions());
                    }
                    ResolvedType resolved = owner.typeResolver().resolve(effectiveType);
                    clinitExpr.generate(declarator.initializer(), resolved);
                    clinitExpr.numericCoercion().adaptForStore(resolved, declarator.initializer());
                    clinitBuffer.visitFieldInsn(Opcodes.PUTSTATIC, internalName, declarator.name(), resolved.descriptor());
                }
            } else if (member instanceof MethodDeclaration md) {
                if ("<clinit>".equals(md.name()) && md.body() != null && clinitStmt != null) {
                    for (Statement stmt : md.body().statements()) clinitStmt.generate(stmt);
                    continue;
                }
                if ("<iinit>".equals(md.name())) continue;
                if (isRecord && "<init>".equals(md.name()) && md.parameters().size() == recordComponentCount) continue;
                deferredMethods.add(md);
            }
        }
        runMethodBatch(cw, deferredMethods, internalName, superInternal, fieldTypes, selfMethods, classDecl);

        if (clinitBuffer != null) {
            clinitBuffer.visitInsn(Opcodes.RETURN);
            clinitBuffer.visitMaxs(0, 0);
            clinitBuffer.visitEnd();
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinitBuffer.replayInto(mv);
        }

        owner.bridgeMethodEmitter().emit(cw, classDecl, internalName);
    }

    /**
     * Emits {@code methods} against {@code cw}. Dispatches across worker
     * threads when {@link MethodParallelism#workers()} reports more than one
     * worker, otherwise keeps the serial emission order. Shared write points
     * inside {@link #emitMethod} are protected by an internal lock so parallel
     * execution remains byte-identical to serial.
     */
    private void runMethodBatch(@NotNull ClassWriter cw, @NotNull List<MethodDeclaration> methods, @NotNull String internalName, @NotNull String superInternal, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Map<String, SelfMethodInfo> selfMethods, @NotNull ClassDeclaration classDecl) {
        int workers = MethodParallelism.workers();
        if (workers <= 1 || methods.size() <= 1) {
            for (MethodDeclaration md : methods)
                emitMethod(cw, md, internalName, superInternal, fieldTypes, selfMethods, classDecl);
            return;
        }
        Object scopeSnapshot = owner.typeResolver().captureScope();
        ExecutorService pool = owner.classpathManager().sharedMethodPool(workers);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        int batchSize = Math.max(1, (int) Math.ceil((double) methods.size() / workers));
        List<Future<?>> futures = new ArrayList<>(workers);
        try {
            for (int start = 0; start < methods.size(); start += batchSize) {
                int from = start;
                int to = Math.min(start + batchSize, methods.size());
                futures.add(pool.submit(() -> {
                    if (failure.get() != null) return;
                    owner.typeResolver().adoptScope(scopeSnapshot);
                    pendingMethods.set(new ArrayList<>());
                    pendingCw.set(cw);
                    try {
                        for (int i = from; i < to; i++) {
                            if (failure.get() != null) return;
                            emitMethod(cw, methods.get(i), internalName, superInternal, fieldTypes, selfMethods, classDecl);
                            orderedDeferred.put(i, pendingMethods.get());
                            pendingMethods.set(new ArrayList<>());
                        }
                    } catch (RuntimeException e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        owner.typeResolver().clearScope();
                        pendingMethods.remove();
                        pendingCw.remove();
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
        } finally {
            pendingMethods.remove();
            pendingCw.remove();
        }
        if (failure.get() != null) throw failure.get();
        for (int i = 0; i < methods.size(); i++) {
            List<DeferredAction> list = orderedDeferred.remove(i);
            if (list == null) continue;
            for (DeferredAction action : list) action.apply(cw);
        }
    }

    /**
     * Emits a full method body: descriptor assembly, scope + local-var setup,
     * constructor super-call prefix, instance-field initializer weaving, body
     * generation, auto-emitted void return, local-variable table, and
     * deferred flushing (under {@link #cwLock} or into the parallel batch's
     * pending list).
     */
    public void emitMethod(@NotNull ClassWriter cw, @NotNull MethodDeclaration methodDecl, @NotNull String classInternal, @NotNull String superInternal, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Map<String, SelfMethodInfo> selfMethods, @NotNull ClassDeclaration classDecl) {
        if (methodDecl.typeParameters() != null && !methodDecl.typeParameters().isEmpty())
            owner.typeResolver().registerTypeParameters(methodDecl.typeParameters());
        int paramCount = methodDecl.parameters().size();
        ResolvedType[] resolvedParamTypes = new ResolvedType[paramCount];
        for (int i = 0; i < paramCount; i++) {
            resolvedParamTypes[i] = owner.typeResolver().resolve(methodDecl.parameters().get(i).type());
        }
        ResolvedType resolvedReturnType = owner.typeResolver().resolve(methodDecl.returnType());
        boolean isCtor = "<init>".equals(methodDecl.name());
        boolean prependOuterThis = isCtor && owner.currentEnclosingOuter() != null;
        String outerDesc = prependOuterThis ? "L" + owner.currentEnclosingOuter() + ";" : null;
        StringBuilder descBuilder = new StringBuilder(32);
        descBuilder.append('(');
        if (prependOuterThis) descBuilder.append(outerDesc);
        for (ResolvedType pt : resolvedParamTypes) descBuilder.append(pt.descriptor());
        descBuilder.append(')').append(resolvedReturnType.descriptor());
        String descriptor = descBuilder.toString();
        int access = methodDecl.modifiers();
        if (!methodDecl.parameters().isEmpty() && methodDecl.parameters().get(methodDecl.parameters().size() - 1).isVarargs()) {
            access |= Opcodes.ACC_VARARGS;
        }

        RecordingMethodVisitor mv = new RecordingMethodVisitor();
        for (Parameter mp : methodDecl.parameters()) {
            mv.visitParameter(mp.name(), mp.modifiers());
        }
        owner.annotationEmitter().emitMethodAnnotations(mv, methodDecl.annotations());
        mv.visitCode();

        if (methodDecl.body() != null) {
            boolean isStaticMethod = (access & Opcodes.ACC_STATIC) != 0;
            Scope scope = new Scope(0);
            int nextSlot = 0;

            if (!isStaticMethod) {
                scope.declare("this", ResolvedType.ofObject(classInternal));
                nextSlot = 1;
            }
            if (prependOuterThis) {
                scope.declare("this$0", ResolvedType.ofObject(owner.currentEnclosingOuter()));
                nextSlot++;
            }

            for (int i = 0; i < paramCount; i++) {
                ResolvedType paramType = resolvedParamTypes[i];
                scope.declare(methodDecl.parameters().get(i).name(), paramType);
                nextSlot += paramType.stackSize();
            }
            scope.syncNextLocalIndex(nextSlot);

            MethodContext ctx = new MethodContext(mv, scope, owner.typeResolver(), new MethodResolver(owner.classpathManager()), classInternal, superInternal, isStaticMethod, selfMethods);
            ctx.enclosingOuterInternal(owner.currentEnclosingOuter());
            ctx.enclosingStaticOuter(owner.currentStaticOuter());
            ctx.nestedClassFields(owner.nestedClassFields());
            ctx.nestedClassMethods(owner.nestedClassMethods());
            ctx.nestedClassConstants(owner.nestedClassConstants());
            ctx.setupLambdaSupport(cw, owner.lambdaCounter(), methodDecl.name());
            ctx.setupAnonClassSupport(owner, cw, owner.anonClassCounter(), methodDecl.name(), owner.anonClassBytecodes(), owner.anonClassNames());
            ctx.returnType(resolvedReturnType);
            ctx.typeInferrer().registerSelfMethods(selfMethods);
            for (Map.Entry<String, ResolvedType> entry : fieldTypes.entrySet()) {
                ctx.typeInferrer().registerField(entry.getKey(), entry.getValue());
            }

            Label methodStart = new Label();
            mv.visitLabel(methodStart);
            ctx.setMethodStartLabel(methodStart);
            if (!isStaticMethod) {
                ctx.openLocal("this", "L" + classInternal + ";", null, methodStart, 0);
            }
            int paramSlot = isStaticMethod ? 0 : 1;
            if (prependOuterThis) paramSlot++;
            for (int i = 0; i < paramCount; i++) {
                ResolvedType paramType = resolvedParamTypes[i];
                ctx.openLocal(methodDecl.parameters().get(i).name(), paramType.descriptor(), null, methodStart, paramSlot);
                paramSlot += paramType.stackSize();
            }

            MethodGenerator gen = new MethodGenerator(ctx);
            if ("<init>".equals(methodDecl.name())) {
                if (prependOuterThis) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, classInternal, "this$0", outerDesc);
                }
                if (!startsWithConstructorCall(methodDecl)) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false);
                    emitInstanceFieldInitializers(classDecl, classInternal, new ExpressionGenerator(ctx), mv);
                    gen.generateBody(methodDecl.body());
                } else if (startsWithSuperCall(methodDecl)) {
                    BlockStatement body = methodDecl.body();
                    if (!body.statements().isEmpty()) {
                        gen.generate(body.statements().get(0));
                        emitInstanceFieldInitializers(classDecl, classInternal, new ExpressionGenerator(ctx), mv);
                        for (int i = 1; i < body.statements().size(); i++) gen.generate(body.statements().get(i));
                    }
                } else {
                    gen.generateBody(methodDecl.body());
                }
            } else {
                gen.generateBody(methodDecl.body());
            }

            if (resolvedReturnType.isVoid() && ctx.isReachable()) {
                int closingLine = methodEndLine(methodDecl.body());
                ctx.emitLine(closingLine);
                mv.visitInsn(Opcodes.RETURN);
            }

            Label endLabel = new Label();
            mv.visitLabel(endLabel);
            ctx.closeLocalsFrom(0, endLabel);
            List<MethodContext.LocalVarEntry> entries = ctx.localVarEntries();
            int entrySize = entries.size();
            for (int i = 0; i < entrySize; i++) {
                MethodContext.LocalVarEntry e = entries.get(i);
                boolean duplicate = false;
                for (int j = 0; j < i; j++) {
                    MethodContext.LocalVarEntry prior = entries.get(j);
                    if (prior.index() == e.index() && prior.name().equals(e.name())
                            && prior.start() == e.start() && prior.end() == e.end()) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;
                mv.visitLocalVariable(e.name(), e.descriptor(), e.signature(), e.start(), e.end(), e.index());
            }

            mv.visitMaxs(0, 0);
        } else {
            mv.visitMaxs(0, 0);
        }
        mv.visitEnd();
        List<DeferredAction> deferred = pendingMethods.get();
        if (deferred != null && pendingCw.get() == cw) {
            deferred.add(new DeferredMethod(access, methodDecl.name(), descriptor, mv));
        } else {
            synchronized (cwLock) {
                MethodVisitor mvOrig = cw.visitMethod(access, methodDecl.name(), descriptor, null, null);
                mv.replayInto(mvOrig);
            }
        }
    }

    /**
     * Emits a method body with a caller-chosen access mask. Used for
     * interface members where source-declared flags need combining with
     * {@code ACC_PUBLIC}/{@code ACC_ABSTRACT}.
     */
    public void emitMethodWithAccess(@NotNull ClassWriter cw, @NotNull MethodDeclaration methodDecl, @NotNull String classInternal, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Map<String, SelfMethodInfo> selfMethods, int access) {
        if (methodDecl.typeParameters() != null && !methodDecl.typeParameters().isEmpty())
            owner.typeResolver().registerTypeParameters(methodDecl.typeParameters());
        List<TypeNode> paramTypeNodes = new ArrayList<>();
        for (Parameter p : methodDecl.parameters()) paramTypeNodes.add(p.type());
        String descriptor = owner.typeResolver().methodDescriptor(paramTypeNodes, methodDecl.returnType());

        MethodVisitor mv = cw.visitMethod(access, methodDecl.name(), descriptor, null, null);

        if (methodDecl.body() != null) {
            mv.visitCode();
            boolean isStaticMethod = (access & Opcodes.ACC_STATIC) != 0;
            Scope scope = new Scope(0);
            int nextSlot = 0;
            if (!isStaticMethod) {
                scope.declare("this", ResolvedType.ofObject(classInternal));
                nextSlot = 1;
            }
            for (Parameter param : methodDecl.parameters()) {
                ResolvedType paramType = owner.typeResolver().resolve(param.type());
                scope.declare(param.name(), paramType);
                nextSlot += paramType.stackSize();
            }
            scope.syncNextLocalIndex(nextSlot);
            MethodContext ctx = new MethodContext(mv, scope, owner.typeResolver(), new MethodResolver(owner.classpathManager()), classInternal, "java/lang/Object", isStaticMethod, selfMethods);
            ctx.nestedClassFields(owner.nestedClassFields());
            ctx.nestedClassConstants(owner.nestedClassConstants());
            ctx.nestedClassMethods(owner.nestedClassMethods());
            ctx.setupLambdaSupport(cw, owner.lambdaCounter(), methodDecl.name());
            ctx.setupAnonClassSupport(owner, cw, owner.anonClassCounter(), methodDecl.name(), owner.anonClassBytecodes(), owner.anonClassNames());
            ctx.returnType(owner.typeResolver().resolve(methodDecl.returnType()));
            for (Map.Entry<String, ResolvedType> entry : fieldTypes.entrySet()) {
                ctx.typeInferrer().registerField(entry.getKey(), entry.getValue());
            }
            MethodGenerator gen = new MethodGenerator(ctx);
            if ("<init>".equals(methodDecl.name()) && !startsWithConstructorCall(methodDecl)) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            }
            gen.generateBody(methodDecl.body());
            ResolvedType returnType = owner.typeResolver().resolve(methodDecl.returnType());
            if (returnType.isVoid()) mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
        }

        mv.visitEnd();
    }

    /**
     * Emits a default no-arg constructor, handling the {@code this$0}
     * initialiser and non-constant instance-field initializers for classes
     * that don't author their own constructor.
     */
    public void emitDefaultConstructor(@NotNull ClassWriter cw, @NotNull String classInternal, @NotNull String superInternal, @NotNull ClassDeclaration classDecl, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Map<String, SelfMethodInfo> selfMethods, @Nullable String enclosingOuterInternal) {
        String desc = enclosingOuterInternal != null ? "(L" + enclosingOuterInternal + ";)V" : "()V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        Label startLabel = new Label();
        mv.visitLabel(startLabel);
        mv.visitLineNumber(classDecl.line(), startLabel);
        if (enclosingOuterInternal != null) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.PUTFIELD, classInternal, "this$0", "L" + enclosingOuterInternal + ";");
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false);
        Scope scope = new Scope(0);
        scope.declare("this", ResolvedType.ofObject(classInternal));
        int nextSlot = 1;
        if (enclosingOuterInternal != null) {
            scope.declare("$outer", ResolvedType.ofObject(enclosingOuterInternal));
            nextSlot = 2;
        }
        scope.syncNextLocalIndex(nextSlot);
        MethodContext ctx = new MethodContext(mv, scope, owner.typeResolver(), new MethodResolver(owner.classpathManager()), classInternal, superInternal, false, selfMethods);
        ctx.enclosingOuterInternal(owner.currentEnclosingOuter());
        ctx.enclosingStaticOuter(owner.currentStaticOuter());
        ctx.nestedClassFields(owner.nestedClassFields());
        ctx.classGenerator(owner);
        ctx.setupLambdaSupport(cw, owner.lambdaCounter(), "<init>");
        for (Map.Entry<String, ResolvedType> entry : fieldTypes.entrySet())
            ctx.typeInferrer().registerField(entry.getKey(), entry.getValue());
        emitInstanceFieldInitializers(classDecl, classInternal, new ExpressionGenerator(ctx), mv);
        mv.visitInsn(Opcodes.RETURN);
        Label endLabel = new Label();
        mv.visitLabel(endLabel);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Weaves each non-constant instance-field initializer onto {@code mv}
     * after the super constructor call, in source order.
     */
    private void emitInstanceFieldInitializers(@NotNull ClassDeclaration classDecl, @NotNull String internalName, @NotNull ExpressionGenerator exprGen, @NotNull MethodVisitor mv) {
        for (AstNode member : classDecl.members()) {
            if (member instanceof FieldDeclaration fieldDecl && (fieldDecl.modifiers() & Opcodes.ACC_STATIC) == 0) {
                for (FieldDeclarator declarator : fieldDecl.declarators()) {
                    if (declarator.initializer() != null && owner.constantFolder().resolveConstantValue(fieldDecl, declarator) == null) {
                        TypeNode effectiveType = fieldDecl.type();
                        if (declarator.extraArrayDimensions() > 0)
                            effectiveType = effectiveType.withExtraDimensions(declarator.extraArrayDimensions());
                        ResolvedType fieldResolved = owner.typeResolver().resolve(effectiveType);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        exprGen.generate(declarator.initializer(), fieldResolved);
                        exprGen.numericCoercion().adaptForStore(fieldResolved, declarator.initializer());
                        mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, declarator.name(), fieldResolved.descriptor());
                    }
                }
            }
        }
    }

    /**
     * Estimates the source line of the closing brace for a method body so
     * the auto-emitted final {@code RETURN} carries a line number matching
     * javac's debug info.
     */
    private int methodEndLine(@NotNull BlockStatement body) {
        if (body.statements().isEmpty()) return body.line();
        Statement last = body.statements().get(body.statements().size() - 1);
        return last.line() + 1;
    }

    /**
     * Adds a synthesised method (e.g. a lambda body) to the current
     * thread's deferred list. Only buffers when {@code target} matches the
     * {@link ClassWriter} whose method-batch is in flight; writes that target
     * a different writer (e.g. an anon-class body's cw) run inline.
     */
    public boolean tryDeferMethod(@NotNull ClassWriter target, int access, @NotNull String name, @NotNull String descriptor, @NotNull RecordingMethodVisitor node) {
        if (target != pendingCw.get()) return false;
        List<DeferredAction> list = pendingMethods.get();
        if (list == null) return false;
        list.add(new DeferredMethod(access, name, descriptor, node));
        return true;
    }

    /**
     * Buffers an {@code InnerClasses} attribute entry for deferred emission
     * only when the caller is writing to the same cw the current thread's
     * method batch targets.
     */
    public boolean tryDeferInnerClass(@NotNull ClassWriter target, @NotNull String name, @Nullable String outerName, @Nullable String innerName, int access) {
        if (target != pendingCw.get()) return false;
        List<DeferredAction> list = pendingMethods.get();
        if (list == null) return false;
        list.add(new DeferredInnerClass(name, outerName, innerName, access));
        return true;
    }

    /**
     * Superinterface for anything a parallel method worker records for the
     * orchestrator to commit to {@code cw} in source order.
     */
    private interface DeferredAction {
        void apply(@NotNull ClassWriter cw);
    }

    /**
     * Buffered pair of {@code cw.visitMethod} + replay of the recorded body.
     */
    private record DeferredMethod(int access, @NotNull String name, @NotNull String descriptor,
                                  @NotNull RecordingMethodVisitor node) implements DeferredAction {
        @Override
        public void apply(@NotNull ClassWriter cw) {
            MethodVisitor mv = cw.visitMethod(access, name, descriptor, null, null);
            node.replayInto(mv);
        }
    }

    /**
     * Buffered {@code InnerClasses} attribute entry.
     */
    private record DeferredInnerClass(@NotNull String name, @Nullable String outerName, @Nullable String innerName,
                                      int access) implements DeferredAction {
        @Override
        public void apply(@NotNull ClassWriter cw) {
            cw.visitInnerClass(name, outerName, innerName, access);
        }
    }
}
