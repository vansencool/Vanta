package net.vansencool.vanta.codegen.expression.newobj;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.classpath.AsmClassInfo.FieldInfo;
import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.MethodGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.expression.NewExpression;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.ExpressionStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.scope.Scope;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Emits bytecode for {@code new} expressions, handling both plain
 * constructor calls and anonymous-class instantiation. For anon classes
 * the emitter walks the body for captured locals, synthesises a dedicated
 * {@link ClassWriter} with a common-super cache matching the main class
 * writer, emits the nested class bytes, then emits the enclosing
 * {@code NEW}/{@code DUP}/{@code INVOKESPECIAL} that builds one.
 */
public final class ObjectCreationEmitter {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used for nested sub-expressions,
     *                argument adaptation, and shared helpers
     */
    public ObjectCreationEmitter(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * @param methodDecl method declaration
     * @return true when the method's first statement is an explicit
     * {@code this(...)} or {@code super(...)} call
     */
    private static boolean startsWithConstructorCall(@NotNull MethodDeclaration methodDecl) {
        if (methodDecl.body() != null) {
            BlockStatement block = methodDecl.body();
            if (!block.statements().isEmpty()) {
                Statement first = block.statements().get(0);
                if (first instanceof ExpressionStatement exprStmt) {
                    if (exprStmt.expression() instanceof MethodCallExpression call) {
                        return "this".equals(call.methodName()) || "super".equals(call.methodName());
                    }
                }
            }
        }
        return false;
    }

    /**
     * Emits a {@code new} expression. Routes to anonymous-class synthesis
     * when an anonymous class body is present, otherwise emits a plain
     * {@code NEW}/{@code DUP}/{@code INVOKESPECIAL} sequence against the
     * resolved constructor descriptor.
     *
     * @param newExpr {@code new} expression AST node
     */
    public void emitNew(@NotNull NewExpression newExpr) {
        MethodContext ctx = exprGen.ctx();
        MethodVisitor mv = ctx.mv();
        String declaredType = ctx.typeResolver().resolveInternalName(newExpr.type());
        boolean targetIsInterface = isTargetInterface(declaredType);
        String superInternal = targetIsInterface ? "java/lang/Object" : declaredType;
        String[] interfaces = targetIsInterface ? new String[]{declaredType} : null;

        if (newExpr.anonymousClassBody() != null) {
            ClassGenerator cg = ctx.classGenerator();
            int anonIndex = cg != null ? cg.anonClassIndexFor(newExpr) : ctx.nextAnonClassIndex();
            String anonHost = exprGen.anonHostInternalName();
            String anonInternalName = anonHost + "$" + anonIndex;
            boolean needsOuter = !ctx.isStatic();
            String outerDesc = "L" + ctx.classInternalName() + ";";

            LinkedHashMap<String, LocalVariable> anonCaptures = new LinkedHashMap<>();
            exprGen.expressionWalker().collectAnonCaptures(newExpr.anonymousClassBody(), anonCaptures);

            ClasspathManager cp = ctx.methodResolver().classpathManager();
            ClassWriter anonCw = createAnonClassWriter(cp);
            anonCw.visit(Opcodes.V17, Opcodes.ACC_SUPER, anonInternalName, null, superInternal, interfaces);
            ClassGenerator cgForNest = ctx.classGenerator();
            if (cgForNest != null) {
                String nestHost = cgForNest.nestHostInternal();
                if (nestHost != null) {
                    anonCw.visitNestHost(nestHost);
                    cgForNest.addNestMember(anonInternalName);
                }
            }

            if (needsOuter) {
                FieldVisitor fv = anonCw.visitField(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "this$0", outerDesc, null, null);
                fv.visitEnd();
            }
            for (LocalVariable cap : anonCaptures.values()) {
                FieldVisitor fv = anonCw.visitField(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "val$" + cap.name(), cap.type().descriptor(), null, null);
                fv.visitEnd();
            }

            Map<String, ResolvedType> capturedTypes = new LinkedHashMap<>();
            for (LocalVariable cap : anonCaptures.values()) capturedTypes.put(cap.name(), cap.type());

            Map<String, ResolvedType> fieldTypes = new HashMap<>();
            Set<String> staticFieldNames = new HashSet<>();
            Map<String, SelfMethodInfo> selfMethods = new HashMap<>();
            collectAnonFieldsAndMethods(anonCw, newExpr.anonymousClassBody(), anonInternalName, fieldTypes, staticFieldNames, selfMethods);
            List<String> anonSupers = new ArrayList<>();
            anonSupers.add(superInternal);
            if (interfaces != null) Collections.addAll(anonSupers, interfaces);
            emitAnonClassBody(anonCw, newExpr.anonymousClassBody(), anonInternalName, superInternal, fieldTypes, staticFieldNames, selfMethods, needsOuter, outerDesc, ctx.classInternalName(), capturedTypes, newExpr.arguments(), anonSupers);

            anonCw.visitEnd();
            byte[] anonBytecode = anonCw.toByteArray();
            ctx.addAnonClass(anonBytecode, anonInternalName);

            if (ctx.classWriter() != null) {
                int anonAccess = needsOuter ? 0 : Opcodes.ACC_STATIC;
                ClassWriter sharedCw = ctx.classWriter();
                ClassGenerator cgForInner = ctx.classGenerator();
                if (cgForInner == null || !cgForInner.methodEmitter().tryDeferInnerClass(sharedCw, anonInternalName, null, null, anonAccess)) {
                    synchronized (sharedCw) {
                        sharedCw.visitInnerClass(anonInternalName, null, null, anonAccess);
                    }
                }
            }

            mv.visitTypeInsn(Opcodes.NEW, anonInternalName);
            mv.visitInsn(Opcodes.DUP);

            String superCtorDesc = targetIsInterface ? "()V" : exprGen.methodResolutionHelper().resolveConstructorDescriptor(superInternal, newExpr.arguments(), null);
            StringBuilder ctorDescBuilder = new StringBuilder("(");
            if (needsOuter) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                ctorDescBuilder.append(outerDesc);
            }
            exprGen.methodArgumentEmitter().generateArgs(newExpr.arguments(), superCtorDesc);
            Type[] superParams = Type.getArgumentTypes(superCtorDesc);
            for (Type pt : superParams) ctorDescBuilder.append(pt.getDescriptor());
            for (LocalVariable cap : anonCaptures.values()) {
                if (cap.index() < 0) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(Opcodes.GETFIELD, ctx.classInternalName(), "val$" + cap.name(), cap.type().descriptor());
                } else {
                    mv.visitVarInsn(OpcodeUtils.loadOpcode(cap.type()), cap.index());
                }
                ctorDescBuilder.append(cap.type().descriptor());
            }
            ctorDescBuilder.append(")V");
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, anonInternalName, "<init>", ctorDescBuilder.toString(), false);
            exprGen.lastEmittedAnonInternal(anonInternalName);
        } else {
            mv.visitTypeInsn(Opcodes.NEW, declaredType);
            mv.visitInsn(Opcodes.DUP);

            String enclosingOuter = enclosingOuterFor(declaredType);
            if (enclosingOuter != null) mv.visitVarInsn(Opcodes.ALOAD, 0);
            String desc = exprGen.methodResolutionHelper().resolveConstructorDescriptor(declaredType, newExpr.arguments(), enclosingOuter);
            exprGen.methodArgumentEmitter().generateArgs(newExpr.arguments(), desc);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, declaredType, "<init>", desc, false);
        }
    }

    /**
     * @param internalName class internal name
     * @return true when {@code internalName} resolves to an interface (via
     * reflection or ASM fallback), so {@code new X(){}} should treat
     * it as the interface side of an anonymous-class declaration
     */
    public boolean isTargetInterface(@NotNull String internalName) {
        MethodContext ctx = exprGen.ctx();
        Class<?> c = ctx.methodResolver().classpathManager().loadClass(internalName);
        if (c != null) return c.isInterface();
        AsmClassInfo info = ctx.methodResolver().classpathManager().asmClassInfo(internalName);
        return info != null && info.isInterface();
    }

    /**
     * Returns the enclosing-class internal name that {@code targetInternal}
     * needs as an implicit first constructor arg when it's a non-static
     * inner class, or null when no outer-this is needed.
     *
     * @param targetInternal class being instantiated
     * @return enclosing outer internal name, or null for static/top-level types
     */
    public @Nullable String enclosingOuterFor(@NotNull String targetInternal) {
        MethodContext ctx = exprGen.ctx();
        if (targetInternal.equals(ctx.classInternalName()) && ctx.enclosingOuterInternal() != null)
            return ctx.enclosingOuterInternal();
        AsmClassInfo info = ctx.methodResolver().classpathManager().asmClassInfo(targetInternal);
        if (info == null) return null;
        if ((info.access() & Opcodes.ACC_STATIC) != 0) return null;
        for (FieldInfo f : info.fields()) {
            if ("this$0".equals(f.name()) && f.descriptor().startsWith("L") && f.descriptor().endsWith(";")) {
                return f.descriptor().substring(1, f.descriptor().length() - 1);
            }
        }
        return null;
    }

    /**
     * Declares the fields and indexes the methods of an anonymous-class
     * body so its body emission can reference them by name. Runs before
     * {@link #emitAnonClassBody} so self-method resolution has a populated
     * {@code selfMethods} map.
     *
     * @param cw           anon-class writer
     * @param members      anon-class members
     * @param internalName anon-class internal name
     * @param fieldTypes   output map populated with field types
     * @param selfMethods  output map populated with self-method info
     */
    private void collectAnonFieldsAndMethods(@NotNull ClassWriter cw, @NotNull List<AstNode> members, @NotNull String internalName, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Set<String> staticFieldNames, @NotNull Map<String, SelfMethodInfo> selfMethods) {
        MethodContext ctx = exprGen.ctx();
        for (AstNode member : members) {
            if (member instanceof FieldDeclaration fieldDecl) {
                boolean fieldIsStatic = (fieldDecl.modifiers() & Opcodes.ACC_STATIC) != 0;
                for (FieldDeclarator declarator : fieldDecl.declarators()) {
                    TypeNode effectiveType = fieldDecl.type();
                    if (declarator.extraArrayDimensions() > 0) {
                        effectiveType = effectiveType.withExtraDimensions(declarator.extraArrayDimensions());
                    }
                    String descriptor = ctx.typeResolver().resolveDescriptor(effectiveType);
                    FieldVisitor fv = cw.visitField(fieldDecl.modifiers(), declarator.name(), descriptor, null, null);
                    fv.visitEnd();
                    fieldTypes.put(declarator.name(), ctx.typeResolver().resolve(effectiveType));
                    if (fieldIsStatic) staticFieldNames.add(declarator.name());
                }
            } else if (member instanceof MethodDeclaration methodDecl) {
                List<TypeNode> paramTypes = new ArrayList<>();
                for (Parameter p : methodDecl.parameters()) paramTypes.add(p.type());
                String desc = ctx.typeResolver().methodDescriptor(paramTypes, methodDecl.returnType());
                boolean isStatic = (methodDecl.modifiers() & Opcodes.ACC_STATIC) != 0;
                SelfMethodInfo info = new SelfMethodInfo(internalName, methodDecl.name(), desc, isStatic);
                String baseKey = methodDecl.name() + ":" + methodDecl.parameters().size();
                if (!selfMethods.containsKey(baseKey)) selfMethods.put(baseKey, info);
                else selfMethods.put(baseKey + "#" + desc, info);
            }
        }
    }

    /**
     * Emits every member of an anonymous-class body plus a synthetic
     * constructor when none was declared, then triggers bridge-method
     * synthesis against the recorded super chain.
     *
     * @param cw             anon-class writer
     * @param members        anon-class member nodes
     * @param internalName   anon-class internal name
     * @param superInternal  super class internal name (or {@code Object} when anon targets an interface)
     * @param fieldTypes     declared field types
     * @param selfMethods    self-method info
     * @param needsOuter     true when the anon captures a non-static outer
     * @param outerDesc      descriptor of the {@code this$0} field ({@code "LOuter;"})
     * @param outerInternal  outer internal name
     * @param capturedFields captured locals promoted to synthetic {@code val$X} fields
     * @param superArgs      constructor arguments passed to the super call
     * @param bridgeSupers   super + interfaces list consulted for bridge synthesis
     */
    private void emitAnonClassBody(@NotNull ClassWriter cw, @NotNull List<AstNode> members, @NotNull String internalName, @NotNull String superInternal, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Set<String> staticFieldNames, @NotNull Map<String, SelfMethodInfo> selfMethods, boolean needsOuter, @NotNull String outerDesc, @NotNull String outerInternal, @NotNull Map<String, ResolvedType> capturedFields, @NotNull List<Expression> superArgs, @NotNull List<String> bridgeSupers) {
        MethodContext ctx = exprGen.ctx();
        boolean hasConstructor = members.stream().anyMatch(m -> m instanceof MethodDeclaration md && md.name().equals("<init>"));
        if (!hasConstructor) {
            List<String> rawArgDescs = new ArrayList<>();
            for (Expression a : superArgs) {
                ResolvedType t = ctx.typeInferrer().infer(a);
                rawArgDescs.add(t != null ? t.descriptor() : "Ljava/lang/Object;");
            }
            String[] resolvedSuperParams = resolveSuperCtorDescs(superInternal, rawArgDescs);
            List<String> superArgDescs = new ArrayList<>();
            if (resolvedSuperParams != null) {
                Collections.addAll(superArgDescs, resolvedSuperParams);
            } else {
                superArgDescs.addAll(rawArgDescs);
            }
            StringBuilder ctorDescBuilder = new StringBuilder("(");
            if (needsOuter) ctorDescBuilder.append(outerDesc);
            for (String d : superArgDescs) ctorDescBuilder.append(d);
            for (ResolvedType capType : capturedFields.values()) ctorDescBuilder.append(capType.descriptor());
            ctorDescBuilder.append(")V");
            String ctorDesc = ctorDescBuilder.toString();
            MethodVisitor initMv = cw.visitMethod(0, "<init>", ctorDesc, null, null);
            initMv.visitCode();
            int slot = 1;
            if (needsOuter) {
                initMv.visitVarInsn(Opcodes.ALOAD, 0);
                initMv.visitVarInsn(Opcodes.ALOAD, slot);
                initMv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "this$0", outerDesc);
                slot++;
            }
            int superArgStart = slot;
            for (String d : superArgDescs) slot += ResolvedType.fromDescriptor(d).stackSize();
            for (Map.Entry<String, ResolvedType> e : capturedFields.entrySet()) {
                initMv.visitVarInsn(Opcodes.ALOAD, 0);
                initMv.visitVarInsn(OpcodeUtils.loadOpcode(e.getValue()), slot);
                initMv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "val$" + e.getKey(), e.getValue().descriptor());
                slot += e.getValue().stackSize();
            }
            initMv.visitVarInsn(Opcodes.ALOAD, 0);
            int argSlot = superArgStart;
            String[] superParamDescs = resolveSuperCtorDescs(superInternal, superArgDescs);
            Iterable<String> ctorDescParams = superParamDescs != null ? Arrays.asList(superParamDescs) : superArgDescs;
            StringBuilder sb = new StringBuilder("(");
            for (String d : ctorDescParams) sb.append(d);
            sb.append(")V");
            String superCtorDesc = sb.toString();
            for (String d : superArgDescs) {
                ResolvedType t = ResolvedType.fromDescriptor(d);
                initMv.visitVarInsn(OpcodeUtils.loadOpcode(t), argSlot);
                argSlot += t.stackSize();
            }
            initMv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", superCtorDesc, false);
            initMv.visitInsn(Opcodes.RETURN);
            initMv.visitMaxs(0, 0);
            initMv.visitEnd();
        }

        for (AstNode member : members) {
            if (member instanceof MethodDeclaration methodDecl) {
                if ("<clinit>".equals(methodDecl.name()) || "<init>".equals(methodDecl.name())) continue;
                emitMethodForAnon(cw, methodDecl, internalName, superInternal, fieldTypes, staticFieldNames, selfMethods, outerInternal, capturedFields);
            }
        }
        ClassGenerator cg = ctx.classGenerator();
        if (cg != null) {
            cg.bridgeMethodEmitter().emitForMembers(cw, members, internalName, bridgeSupers);
        }
    }

    /**
     * @param superInternal super class internal name
     * @param argDescs      argument descriptors at the call site
     * @return resolved parameter descriptors of the matching super
     * constructor, or null when no match was found
     */
    private @Nullable String[] resolveSuperCtorDescs(@NotNull String superInternal, @NotNull List<String> argDescs) {
        MethodContext ctx = exprGen.ctx();
        String desc = ctx.methodResolver().resolveConstructor(superInternal, argDescs);
        if (desc == null) return null;
        Type[] params = Type.getArgumentTypes(desc);
        String[] out = new String[params.length];
        for (int i = 0; i < params.length; i++) out[i] = params[i].getDescriptor();
        return out;
    }

    /**
     * Emits a single method declared on an anonymous-class body, wiring up
     * the captured-field map so references to outer locals inside the method
     * resolve to the synthetic {@code val$X} fields.
     *
     * @param cw             anon-class writer
     * @param methodDecl     method declaration
     * @param classInternal  anon-class internal name
     * @param superInternal  super class internal name
     * @param fieldTypes     declared field types
     * @param selfMethods    self-method info
     * @param outerInternal  outer class internal name
     * @param capturedFields captured outer locals promoted to anon fields
     */
    private void emitMethodForAnon(@NotNull ClassWriter cw, @NotNull MethodDeclaration methodDecl, @NotNull String classInternal, @NotNull String superInternal, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Set<String> staticFieldNames, @NotNull Map<String, SelfMethodInfo> selfMethods, @NotNull String outerInternal, @NotNull Map<String, ResolvedType> capturedFields) {
        MethodContext outerCtx = exprGen.ctx();
        List<TypeNode> paramTypeNodes = new ArrayList<>();
        for (Parameter p : methodDecl.parameters()) {
            paramTypeNodes.add(p.type());
        }
        String descriptor = outerCtx.typeResolver().methodDescriptor(paramTypeNodes, methodDecl.returnType());
        int access = methodDecl.modifiers();

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
                ResolvedType paramType = outerCtx.typeResolver().resolve(param.type());
                scope.declare(param.name(), paramType);
                nextSlot += paramType.stackSize();
            }
            scope.syncNextLocalIndex(nextSlot);

            MethodContext inner = new MethodContext(mv, scope, outerCtx.typeResolver(), new MethodResolver(outerCtx.methodResolver().classpathManager()), classInternal, superInternal, isStaticMethod, selfMethods);
            inner.enclosingOuterInternal(outerInternal);
            if (outerCtx.nestedClassFields() != null) inner.nestedClassFields(outerCtx.nestedClassFields());
            if (outerCtx.nestedClassMethods() != null) inner.nestedClassMethods(outerCtx.nestedClassMethods());
            if (outerCtx.nestedClassConstants() != null) inner.nestedClassConstants(outerCtx.nestedClassConstants());
            if (!capturedFields.isEmpty()) inner.capturedFields(capturedFields);
            inner.setupLambdaSupport(cw, outerCtx.lambdaCounter() != null ? outerCtx.lambdaCounter() : new AtomicInteger(), methodDecl.name());
            inner.setupAnonClassSupport(outerCtx.classGenerator(), cw, outerCtx.anonClassCounter() != null ? outerCtx.anonClassCounter() : new AtomicInteger(), methodDecl.name(), outerCtx.anonClassBytecodes(), outerCtx.anonClassNames());
            inner.returnType(inner.typeResolver().resolve(methodDecl.returnType()));
            inner.typeInferrer().registerSelfMethods(selfMethods);
            for (Map.Entry<String, ResolvedType> entry : fieldTypes.entrySet()) {
                inner.typeInferrer().registerField(entry.getKey(), entry.getValue(), staticFieldNames.contains(entry.getKey()));
            }

            MethodGenerator gen = new MethodGenerator(inner);
            if ("<init>".equals(methodDecl.name()) && !startsWithConstructorCall(methodDecl)) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false);
            }
            gen.generateBody(methodDecl.body());
            ResolvedType returnType = inner.typeResolver().resolve(methodDecl.returnType());
            if (returnType.isVoid()) {
                mv.visitInsn(Opcodes.RETURN);
            }
            mv.visitMaxs(0, 0);
        }
        mv.visitEnd();
    }

    /**
     * Creates an anonymous-class {@link ClassWriter} whose
     * {@code getCommonSuperClass} resolves through the supplied
     * {@link ClasspathManager} so frame computation works for classes
     * referenced only via the in-flight compilation classpath.
     */
    private @NotNull ClassWriter createAnonClassWriter(@NotNull ClasspathManager cp) {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String t1, String t2) {
                if (t1.equals(t2)) return t1;
                try {
                    Class<?> c1 = cp.loadClass(t1);
                    Class<?> c2 = cp.loadClass(t2);
                    if (c1 == null || c2 == null) return "java/lang/Object";
                    if (c1.isAssignableFrom(c2)) return t1;
                    if (c2.isAssignableFrom(c1)) return t2;
                    if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                    Class<?> c = c1;
                    do {
                        c = c.getSuperclass();
                    } while (c != null && !c.isAssignableFrom(c2));
                    return c == null ? "java/lang/Object" : c.getName().replace('.', '/');
                } catch (Throwable t) {
                    return "java/lang/Object";
                }
            }
        };
    }
}
