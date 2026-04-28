package net.vansencool.vanta.codegen;

import net.vansencool.vanta.VantaCompiler;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.CompilationUnit;
import net.vansencool.vanta.parser.ast.declaration.EnumConstant;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.declaration.ImportDeclaration;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.declaration.RecordComponent;
import net.vansencool.vanta.parser.ast.declaration.TypeKind;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.ParenExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.parser.ast.type.TypeParameter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits signature-only {@code .class} files for every type declared in a
 * {@link CompilationUnit}. Used by the two-pass {@link VantaCompiler#compileAll}
 * so cross-file references resolve against real classpath entries instead of
 * silently falling back to {@code Object}. Method bodies throw
 * {@link AbstractMethodError} because these classes are never meant to run.
 */
public final class SkeletonGenerator {

    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double", "void");
    private static final Map<String, String> BUILTIN_FQN = Map.ofEntries(
            Map.entry("String", "java/lang/String"),
            Map.entry("Object", "java/lang/Object"),
            Map.entry("Integer", "java/lang/Integer"),
            Map.entry("Long", "java/lang/Long"),
            Map.entry("Short", "java/lang/Short"),
            Map.entry("Byte", "java/lang/Byte"),
            Map.entry("Float", "java/lang/Float"),
            Map.entry("Double", "java/lang/Double"),
            Map.entry("Boolean", "java/lang/Boolean"),
            Map.entry("Character", "java/lang/Character"),
            Map.entry("Number", "java/lang/Number"),
            Map.entry("CharSequence", "java/lang/CharSequence"),
            Map.entry("Throwable", "java/lang/Throwable"),
            Map.entry("Exception", "java/lang/Exception"),
            Map.entry("RuntimeException", "java/lang/RuntimeException"),
            Map.entry("Class", "java/lang/Class"),
            Map.entry("Void", "java/lang/Void"),
            Map.entry("Iterable", "java/lang/Iterable"),
            Map.entry("Comparable", "java/lang/Comparable"),
            Map.entry("Record", "java/lang/Record"),
            Map.entry("Enum", "java/lang/Enum")
    );
    private final @NotNull Map<String, String> batchTypes = new HashMap<>();

    private static boolean isConstTypeName(@NotNull String n) {
        return switch (n) {
            case "int", "long", "float", "double", "boolean", "byte", "short", "char", "String" -> true;
            default -> false;
        };
    }

    /**
     * Emits skeleton bytecode for every top-level and nested class declared
     * in {@code cu}. Returns a map from each class's internal name to its
     * {@code .class} bytes.
     */
    public @NotNull Map<String, byte[]> emit(@NotNull CompilationUnit cu) {
        Context ctx = new Context(cu);
        Map<String, byte[]> out = new HashMap<>();
        for (AstNode typeDecl : cu.typeDeclarations()) {
            if (typeDecl instanceof ClassDeclaration cd) emitClass(out, ctx, cd, null);
        }
        return out;
    }

    /**
     * Records every type declared in {@code cu} against the shared batch
     * registry so cross-file imports can resolve nested-class references
     * correctly without having to load the class on the system classpath.
     * Callers invoke this on every source in a batch before calling
     * {@link #emit} so skeleton emission sees sibling files' types.
     */
    public void registerBatchTypes(@NotNull CompilationUnit cu) {
        String pkg = cu.packageName() == null ? "" : cu.packageName().replace('.', '/');
        for (AstNode typeDecl : cu.typeDeclarations()) {
            if (typeDecl instanceof ClassDeclaration cd) {
                String internalName = pkg.isEmpty() ? cd.name() : pkg + "/" + cd.name();
                registerBatchType(cd, internalName);
            }
        }
    }

    /**
     * Walks a declared class and registers every nested class under its correct
     * {@code Outer$Inner} internal name against the shared batch registry.
     */
    private void registerBatchType(@NotNull ClassDeclaration cd, @NotNull String internalName) {
        batchTypes.put(cd.name(), internalName);
        batchTypes.put(internalName.replace('/', '.'), internalName);
        for (AstNode m : cd.members()) {
            if (m instanceof ClassDeclaration nested) {
                registerBatchType(nested, internalName + "$" + nested.name());
            }
        }
    }

    /**
     * Emits {@code cd} and any nested types as standalone {@code .class}
     * entries into {@code out}. Nested classes are encoded as {@code Outer$Inner}.
     */
    private void emitClass(@NotNull Map<String, byte[]> out, @NotNull Context ctx, @NotNull ClassDeclaration cd, @Nullable String outerInternal) {
        String internalName = outerInternal == null
                ? (ctx.packageInternal.isEmpty() ? cd.name() : ctx.packageInternal + "/" + cd.name())
                : outerInternal + "$" + cd.name();
        ctx.registerType(cd.name(), internalName);
        if (cd.typeParameters() != null) {
            for (TypeParameter tp : cd.typeParameters()) {
                ctx.typeParams.add(tp.name());
            }
        }
        for (AstNode m : cd.members()) {
            if (m instanceof ClassDeclaration inner) {
                ctx.registerType(inner.name(), internalName + "$" + inner.name());
            }
        }

        int access = cd.modifiers();
        boolean isInterface = cd.kind() == TypeKind.INTERFACE || cd.kind() == TypeKind.ANNOTATION;
        boolean isEnum = cd.kind() == TypeKind.ENUM;
        boolean isRecord = cd.kind() == TypeKind.RECORD;
        if (isInterface) access |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        if (isEnum) access |= Opcodes.ACC_ENUM | Opcodes.ACC_FINAL;
        if (isRecord) access |= Opcodes.ACC_FINAL;
        if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0) {
            access |= Opcodes.ACC_PUBLIC;
        }
        if (!isInterface) access |= Opcodes.ACC_SUPER;

        String superInternal = resolveSuper(ctx, cd, isEnum, isRecord, isInterface);
        String[] interfaces = resolveInterfaces(ctx, cd);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String classSignature = classSignature(ctx, cd);
        cw.visit(Opcodes.V17, access, internalName, classSignature, superInternal, interfaces);

        if (isEnum && cd.enumConstants() != null) {
            for (EnumConstant ec : cd.enumConstants()) {
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                        ec.name(), "L" + internalName + ";", null, null).visitEnd();
            }
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                    "$VALUES", "[L" + internalName + ";", null, null).visitEnd();
        }

        if (isRecord && cd.recordComponents() != null) {
            for (RecordComponent rc : cd.recordComponents()) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        rc.name(), typeDescriptor(ctx, rc.type()), null, null).visitEnd();
            }
        }

        Map<String, Object> resolvedConsts = resolveAllStaticFinalConstants(cd, isInterface);
        boolean sawConstructor = false;
        for (AstNode m : cd.members()) {
            if (m instanceof FieldDeclaration fd) {
                int fAccess = fd.modifiers();
                if (isInterface) fAccess |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
                for (FieldDeclarator fdr : fd.declarators()) {
                    TypeNode fieldType = fd.type();
                    if (fdr.extraArrayDimensions() > 0)
                        fieldType = fieldType.withExtraDimensions(fdr.extraArrayDimensions());
                    String fSig = typeHasGenerics(fieldType) ? typeSignature(ctx, fieldType) : null;
                    Object constVal = null;
                    if ((fAccess & Opcodes.ACC_STATIC) != 0 && (fAccess & Opcodes.ACC_FINAL) != 0) {
                        constVal = resolvedConsts.get(fdr.name());
                        if (constVal != null) constVal = coerceForField(constVal, fieldType.name());
                    }
                    cw.visitField(fAccess, fdr.name(), typeDescriptor(ctx, fieldType), fSig, constVal).visitEnd();
                }
            } else if (m instanceof MethodDeclaration md) {
                if ("<iinit>".equals(md.name()) || "<clinit>".equals(md.name())) continue;
                String desc = methodDescriptor(ctx, md);
                int mAccess = md.modifiers();
                if (isInterface && (mAccess & Opcodes.ACC_STATIC) == 0 && md.body() == null) {
                    mAccess |= Opcodes.ACC_ABSTRACT;
                }
                if (isInterface && (mAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE)) == 0) {
                    mAccess |= Opcodes.ACC_PUBLIC;
                }
                if (md.isVarargs()) mAccess |= Opcodes.ACC_VARARGS;
                String mSig = methodSignature(ctx, md);
                MethodVisitor mv = cw.visitMethod(mAccess, md.name(), desc, mSig, null);
                if (!"<init>".equals(md.name())) {
                    if ((mAccess & Opcodes.ACC_ABSTRACT) == 0 && (mAccess & Opcodes.ACC_NATIVE) == 0) {
                        emitStubBody(mv);
                    } else {
                        mv.visitEnd();
                    }
                } else {
                    sawConstructor = true;
                    emitCtorStub(mv, superInternal);
                }
            }
        }
        if (!sawConstructor && !isInterface) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            emitCtorStub(mv, superInternal);
        }
        if (isEnum) {
            MethodVisitor values = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "values", "()[L" + internalName + ";", null, null);
            emitStubBody(values);
            MethodVisitor valueOf = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "valueOf", "(Ljava/lang/String;)L" + internalName + ";", null, null);
            emitStubBody(valueOf);
        }

        cw.visitEnd();
        out.put(internalName, cw.toByteArray());

        for (AstNode m : cd.members()) {
            if (m instanceof ClassDeclaration inner) emitClass(out, ctx, inner, internalName);
        }
    }

    /**
     * Writes a throw-AbstractMethodError stub body so the verifier accepts
     * the method but any accidental invocation fails loudly rather than
     * silently returning null.
     */
    private void emitStubBody(@NotNull MethodVisitor mv) {
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/AbstractMethodError");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AbstractMethodError", "<init>", "()V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
    }

    /**
     * Writes a minimal constructor body that super-calls the parent class's
     * no-arg ctor. Matches javac's default-constructor shape so the skeleton
     * verifies even when the parent only exposes a default constructor.
     */
    private void emitCtorStub(@NotNull MethodVisitor mv, @NotNull String superInternal) {
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    /**
     * Returns the internal name of {@code cd}'s super class, defaulting to
     * the appropriate JDK base ({@code Object}, {@code Enum}, {@code Record})
     * when the source omits an explicit {@code extends} clause.
     */
    private @NotNull String resolveSuper(@NotNull Context ctx, @NotNull ClassDeclaration cd, boolean isEnum, boolean isRecord, boolean isInterface) {
        if (isEnum) return "java/lang/Enum";
        if (isRecord) return "java/lang/Record";
        if (isInterface) return "java/lang/Object";
        return cd.superClass() != null ? typeInternal(ctx, cd.superClass()) : "java/lang/Object";
    }

    private String @NotNull [] resolveInterfaces(@NotNull Context ctx, @NotNull ClassDeclaration cd) {
        if (cd.interfaces().isEmpty()) return new String[0];
        List<String> valid = new ArrayList<>(cd.interfaces().size());
        for (TypeNode iface : cd.interfaces()) {
            String resolved = typeInternal(ctx, iface);
            if ("java/lang/Object".equals(resolved)) continue;
            valid.add(resolved);
        }
        return valid.toArray(new String[0]);
    }

    /**
     * Builds the JVM method descriptor for {@code md} by converting each
     * parameter and the return type via {@link #typeDescriptor}. Resolver
     * lookups run against the batch-local type map populated as skeletons
     * are emitted, with JDK built-ins (String, Integer, ...) as a fallback.
     * Method-level type parameters are pushed onto the erasure scope for the
     * descriptor build and popped back out afterwards so a method-local
     * {@code <T>} does not leak into sibling method descriptors.
     */
    private @NotNull String methodDescriptor(@NotNull Context ctx, @NotNull MethodDeclaration md) {
        List<String> added = new ArrayList<>();
        if (md.typeParameters() != null) {
            for (TypeParameter tp : md.typeParameters()) {
                if (ctx.typeParams.add(tp.name())) added.add(tp.name());
            }
        }
        try {
            StringBuilder sb = new StringBuilder("(");
            for (Parameter p : md.parameters()) sb.append(typeDescriptor(ctx, p.type()));
            sb.append(')');
            if ("<init>".equals(md.name())) sb.append('V');
            else sb.append(typeDescriptor(ctx, md.returnType()));
            return sb.toString();
        } finally {
            for (String name : added) ctx.typeParams.remove(name);
        }
    }

    /**
     * Converts {@code t} into a JVM descriptor string, handling array
     * dimensions, primitives, and resolving simple type names via the
     * batch-local type registry.
     */
    private @NotNull String typeDescriptor(@NotNull Context ctx, @NotNull TypeNode t) {
        StringBuilder sb = new StringBuilder();
        sb.append("[".repeat(Math.max(0, t.arrayDimensions())));
        String name = t.name();
        switch (name) {
            case "boolean" -> sb.append('Z');
            case "byte" -> sb.append('B');
            case "short" -> sb.append('S');
            case "char" -> sb.append('C');
            case "int" -> sb.append('I');
            case "long" -> sb.append('J');
            case "float" -> sb.append('F');
            case "double" -> sb.append('D');
            case "void" -> sb.append('V');
            default -> sb.append('L').append(resolveReferenceInternal(ctx, name)).append(';');
        }
        return sb.toString();
    }

    /**
     * Returns true when a type node carries generic type arguments anywhere in
     * its spine, signalling that the caller should emit a {@code Signature}
     * attribute to preserve the generic information for downstream reflection.
     */
    private boolean typeHasGenerics(@NotNull TypeNode t) {
        if (t.typeArguments() != null && !t.typeArguments().isEmpty()) return true;
        if (t.typeArguments() != null) for (TypeNode arg : t.typeArguments()) if (typeHasGenerics(arg)) return true;
        return false;
    }

    /**
     * Builds a JVMS §4.7.9.1 field-type signature for {@code t}, preserving
     * generic type arguments. Type variables emit as {@code TName;} so
     * reflection can recover the original {@code T} name for downstream
     * method return-type inference.
     */
    private @NotNull String typeSignature(@NotNull Context ctx, @NotNull TypeNode t) {
        StringBuilder sb = new StringBuilder();
        sb.append("[".repeat(Math.max(0, t.arrayDimensions())));
        appendTypeSig(sb, ctx, t);
        return sb.toString();
    }

    /**
     * Helper for {@link #typeSignature} that walks a type and its arguments
     * recursively onto the shared {@link StringBuilder}. Primitives, type
     * variables, and parameterised reference types are handled inline; array
     * dimensions are expected to be emitted by the caller.
     */
    private void appendTypeSig(@NotNull StringBuilder sb, @NotNull Context ctx, @NotNull TypeNode t) {
        String name = t.name();
        switch (name) {
            case "boolean" -> {
                sb.append('Z');
                return;
            }
            case "byte" -> {
                sb.append('B');
                return;
            }
            case "short" -> {
                sb.append('S');
                return;
            }
            case "char" -> {
                sb.append('C');
                return;
            }
            case "int" -> {
                sb.append('I');
                return;
            }
            case "long" -> {
                sb.append('J');
                return;
            }
            case "float" -> {
                sb.append('F');
                return;
            }
            case "double" -> {
                sb.append('D');
                return;
            }
            case "void" -> {
                sb.append('V');
                return;
            }
        }
        if (ctx.typeParams.contains(name)) {
            sb.append('T').append(name).append(';');
            return;
        }
        String resolved = resolveReferenceInternal(ctx, name);
        sb.append('L').append(resolved);
        if (!"java/lang/Object".equals(resolved) && t.typeArguments() != null && !t.typeArguments().isEmpty()) {
            sb.append('<');
            for (TypeNode arg : t.typeArguments()) {
                StringBuilder inner = new StringBuilder();
                inner.append("[".repeat(Math.max(0, arg.arrayDimensions())));
                appendTypeSig(inner, ctx, arg);
                sb.append(inner);
            }
            sb.append('>');
        }
        sb.append(';');
    }

    /**
     * Emits the JVMS §4.7.9.1 class signature when the type parameters or
     * supertype list carry generics. Returns {@code null} for plain
     * non-generic classes so {@link ClassWriter#visit} can skip the
     * {@code Signature} attribute entirely.
     */
    private @Nullable String classSignature(@NotNull Context ctx, @NotNull ClassDeclaration cd) {
        boolean hasGenerics = cd.typeParameters() != null && !cd.typeParameters().isEmpty();
        if (!hasGenerics && cd.superClass() != null && typeHasGenerics(cd.superClass())) hasGenerics = true;
        if (!hasGenerics) for (TypeNode iface : cd.interfaces())
            if (typeHasGenerics(iface)) {
                hasGenerics = true;
                break;
            }
        if (!hasGenerics) return null;
        StringBuilder sb = new StringBuilder();
        if (cd.typeParameters() != null && !cd.typeParameters().isEmpty()) {
            sb.append('<');
            for (TypeParameter tp : cd.typeParameters()) {
                sb.append(tp.name()).append(':');
                if (tp.bounds() != null && !tp.bounds().isEmpty()) {
                    appendTypeSig(sb, ctx, tp.bounds().get(0));
                    for (int i = 1; i < tp.bounds().size(); i++) {
                        sb.append(':');
                        appendTypeSig(sb, ctx, tp.bounds().get(i));
                    }
                } else {
                    sb.append("Ljava/lang/Object;");
                }
            }
            sb.append('>');
        }
        if (cd.superClass() != null) appendTypeSig(sb, ctx, cd.superClass());
        else sb.append("Ljava/lang/Object;");
        for (TypeNode iface : cd.interfaces()) appendTypeSig(sb, ctx, iface);
        return sb.toString();
    }

    /**
     * Emits the JVMS §4.7.9.1 method signature when the method parameters or
     * return type carry generics. Returns {@code null} when none do.
     */
    private @Nullable String methodSignature(@NotNull Context ctx, @NotNull MethodDeclaration md) {
        boolean hasGenerics = md.typeParameters() != null && !md.typeParameters().isEmpty();
        if (!hasGenerics) {
            for (Parameter p : md.parameters())
                if (typeHasGenerics(p.type()) || ctx.typeParams.contains(p.type().name())) {
                    hasGenerics = true;
                    break;
                }
        }
        if (!hasGenerics && (typeHasGenerics(md.returnType()) || ctx.typeParams.contains(md.returnType().name())))
            hasGenerics = true;
        if (!hasGenerics) return null;
        List<String> added = new ArrayList<>();
        if (md.typeParameters() != null) {
            for (TypeParameter tp : md.typeParameters()) {
                if (ctx.typeParams.add(tp.name())) added.add(tp.name());
            }
        }
        try {
            StringBuilder sb = new StringBuilder();
            if (md.typeParameters() != null && !md.typeParameters().isEmpty()) {
                sb.append('<');
                for (TypeParameter tp : md.typeParameters()) {
                    sb.append(tp.name()).append(':');
                    if (tp.bounds() != null && !tp.bounds().isEmpty()) {
                        appendTypeSig(sb, ctx, tp.bounds().get(0));
                        for (int i = 1; i < tp.bounds().size(); i++) {
                            sb.append(':');
                            appendTypeSig(sb, ctx, tp.bounds().get(i));
                        }
                    } else sb.append("Ljava/lang/Object;");
                }
                sb.append('>');
            }
            sb.append('(');
            for (Parameter p : md.parameters()) {
                sb.append("[".repeat(Math.max(0, p.type().arrayDimensions())));
                appendTypeSig(sb, ctx, p.type());
            }
            sb.append(')');
            if ("<init>".equals(md.name())) sb.append('V');
            else {
                sb.append("[".repeat(Math.max(0, md.returnType().arrayDimensions())));
                appendTypeSig(sb, ctx, md.returnType());
            }
            return sb.toString();
        } finally {
            for (String name : added) ctx.typeParams.remove(name);
        }
    }

    /**
     * Same as {@link #typeDescriptor} but forces a reference-type internal
     * name: used for super-class and interface references where primitives
     * are not legal.
     */
    private @NotNull String typeInternal(@NotNull Context ctx, @NotNull TypeNode t) {
        if (PRIMITIVES.contains(t.name())) return "java/lang/Object";
        if (t.arrayDimensions() > 0) return "java/lang/Object";
        return resolveReferenceInternal(ctx, t.name());
    }

    /**
     * Resolves an unqualified type name into an internal name. Tries, in
     * order: already-emitted skeleton types in this batch; dot-prefixed
     * fully-qualified names in the source; single-type imports; star imports;
     * JDK built-ins; same-package fallback. The final fallback keeps the
     * skeleton compilable even when we cannot prove the type exists, which
     * is acceptable because these stubs are never executed.
     */
    private @NotNull String resolveReferenceInternal(@NotNull Context ctx, @NotNull String name) {
        if (ctx.typeParams.contains(name)) return "java/lang/Object";
        if (name.contains(".")) {
            int dot = name.indexOf('.');
            String head = name.substring(0, dot);
            String tail = name.substring(dot + 1);
            if (!ctx.typeParams.contains(head)) {
                String headLocal = ctx.types.get(head);
                if (headLocal != null) return headLocal + "$" + tail.replace('.', '$');
                String headImp = ctx.singleTypeImports.get(head);
                if (headImp != null) return headImp + "$" + tail.replace('.', '$');
                String headBuiltin = BUILTIN_FQN.get(head);
                if (headBuiltin != null) return headBuiltin + "$" + tail.replace('.', '$');
                String headBatch = batchTypes.get(head);
                if (headBatch != null) return headBatch + "$" + tail.replace('.', '$');
                if (!ctx.packageInternal.isEmpty()) {
                    String samePkg = ctx.packageInternal + "/" + head;
                    if (batchTypes.containsValue(samePkg)) return samePkg + "$" + tail.replace('.', '$');
                }
            }
            return name.replace('.', '/');
        }
        String local = ctx.types.get(name);
        if (local != null) return local;
        String imp = ctx.singleTypeImports.get(name);
        if (imp != null) return imp;
        for (String star : ctx.starImports) {
            if (star.equals("java/lang/")) {
                String builtin = BUILTIN_FQN.get(name);
                if (builtin != null) return builtin;
            }
        }
        String builtin = BUILTIN_FQN.get(name);
        if (builtin != null) return builtin;
        try {
            Class.forName("java.lang." + name, false, ClassLoader.getSystemClassLoader());
            return "java/lang/" + name;
        } catch (ClassNotFoundException | LinkageError ignored) {
        }
        for (String star : ctx.starImports) {
            String candidate = star + name;
            try {
                Class.forName(candidate.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
                return candidate;
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
        if (!ctx.packageInternal.isEmpty()) {
            String pkgCandidate = ctx.packageInternal + "/" + name;
            if (batchTypes.containsValue(pkgCandidate)) return pkgCandidate;
            try {
                Class.forName(pkgCandidate.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
                return pkgCandidate;
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
        return "java/lang/Object";
    }

    /**
     * Runs a fixed-point evaluation over all {@code static final} field
     * initializers in the class, so references like
     * {@code DIM_MASK = ((1 << DIM_SIZE) - 1) << DIM_SHIFT} resolve once
     * {@code DIM_SIZE} / {@code DIM_SHIFT} are known. Returns the map
     * {@code fieldName -> boxed constant}.
     */
    private @NotNull Map<String, Object> resolveAllStaticFinalConstants(@NotNull ClassDeclaration cd, boolean isInterface) {
        Map<String, Object> out = new HashMap<>();
        Map<String, FieldDeclarator> decls = new HashMap<>();
        Map<String, FieldDeclaration> enclosing = new HashMap<>();
        for (AstNode m : cd.members()) {
            if (!(m instanceof FieldDeclaration fd)) continue;
            int mods = fd.modifiers();
            boolean isSF = (mods & Opcodes.ACC_STATIC) != 0 && (mods & Opcodes.ACC_FINAL) != 0;
            if (isInterface) isSF = true;
            if (!isSF) continue;
            for (FieldDeclarator fdr : fd.declarators()) {
                if (fdr.initializer() != null) {
                    decls.put(fdr.name(), fdr);
                    enclosing.put(fdr.name(), fd);
                }
            }
        }
        int prev = -1;
        while (out.size() != prev) {
            prev = out.size();
            for (Map.Entry<String, FieldDeclarator> e : decls.entrySet()) {
                if (out.containsKey(e.getKey())) continue;
                FieldDeclaration fd = enclosing.get(e.getKey());
                if (fd.type().arrayDimensions() > 0) continue;
                if (!isConstTypeName(fd.type().name())) continue;
                Object v = foldExprWithScope(e.getValue().initializer(), out);
                if (v != null) out.put(e.getKey(), v);
            }
        }
        return out;
    }

    private @Nullable Object foldExprWithScope(@Nullable Expression expr, @NotNull Map<String, Object> scope) {
        if (expr == null) return null;
        while (expr instanceof ParenExpression p) expr = p.expression();
        if (expr instanceof LiteralExpression lit) return foldLiteral(lit);
        if (expr instanceof UnaryExpression u) {
            Object inner = foldExprWithScope(u.operand(), scope);
            if (inner == null) return null;
            return applyUnary(u.operator(), inner);
        }
        if (expr instanceof BinaryExpression b) {
            Object l = foldExprWithScope(b.left(), scope);
            if (l == null) return null;
            Object r = foldExprWithScope(b.right(), scope);
            if (r == null) return null;
            return applyBinary(b.operator(), l, r);
        }
        if (expr instanceof NameExpression ne) {
            return scope.get(ne.name());
        }
        return null;
    }

    private @Nullable Object foldLiteral(@NotNull LiteralExpression lit) {
        try {
            TokenType lt = lit.literalType();
            if (lt == TokenType.INT_LITERAL) {
                String s = lit.value().replace("_", "");
                return (int) Long.decode(s).longValue();
            }
            if (lt == TokenType.LONG_LITERAL) {
                String s = lit.value().replace("_", "").replace("l", "").replace("L", "");
                return Long.decode(s);
            }
            if (lt == TokenType.FLOAT_LITERAL) {
                return Float.parseFloat(lit.value().replace("_", "").replace("f", "").replace("F", ""));
            }
            if (lt == TokenType.DOUBLE_LITERAL) {
                return Double.parseDouble(lit.value().replace("_", "").replace("d", "").replace("D", ""));
            }
            if (lt == TokenType.STRING_LITERAL) {
                String v = lit.value();
                if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                return v;
            }
            if (lt == TokenType.CHAR_LITERAL) {
                String v = lit.value();
                if (v.length() < 3) return null;
                return (int) v.charAt(1);
            }
            if (lt == TokenType.TRUE) return 1;
            if (lt == TokenType.FALSE) return 0;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private @Nullable Object applyUnary(@NotNull String op, @NotNull Object v) {
        switch (op) {
            case "+" -> {
                return v;
            }
            case "-" -> {
                if (v instanceof Integer i) return -i;
                if (v instanceof Long l) return -l;
                if (v instanceof Float f) return -f;
                if (v instanceof Double d) return -d;
                return null;
            }
            case "~" -> {
                if (v instanceof Integer i) return ~i;
                if (v instanceof Long l) return ~l;
                return null;
            }
        }
        if ("!".equals(op) && v instanceof Integer i) return i == 0 ? 1 : 0;
        return null;
    }

    private @Nullable Object applyBinary(@NotNull String op, @NotNull Object l, @NotNull Object r) {
        if (l instanceof String || r instanceof String) {
            if ("+".equals(op)) return String.valueOf(l) + r;
            return null;
        }
        if (!(l instanceof Number) || !(r instanceof Number)) return null;
        if (l instanceof Double || r instanceof Double) {
            double a = ((Number) l).doubleValue();
            double b = ((Number) r).doubleValue();
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0 ? null : a / b;
                case "%" -> b == 0 ? null : a % b;
                default -> null;
            };
        }
        if (l instanceof Float || r instanceof Float) {
            float a = ((Number) l).floatValue();
            float b = ((Number) r).floatValue();
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0f ? null : a / b;
                case "%" -> b == 0f ? null : a % b;
                default -> null;
            };
        }
        if (l instanceof Long || r instanceof Long) {
            long a = ((Number) l).longValue();
            long b = ((Number) r).longValue();
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0L ? null : a / b;
                case "%" -> b == 0L ? null : a % b;
                case "&" -> a & b;
                case "|" -> a | b;
                case "^" -> a ^ b;
                case "<<" -> a << b;
                case ">>" -> a >> b;
                case ">>>" -> a >>> b;
                default -> null;
            };
        }
        int a = ((Number) l).intValue();
        int b = ((Number) r).intValue();
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> b == 0 ? null : a / b;
            case "%" -> b == 0 ? null : a % b;
            case "&" -> a & b;
            case "|" -> a | b;
            case "^" -> a ^ b;
            case "<<" -> a << b;
            case ">>" -> a >> b;
            case ">>>" -> a >>> b;
            default -> null;
        };
    }

    private @Nullable Object coerceForField(@NotNull Object v, @NotNull String typeName) {
        return switch (typeName) {
            case "byte" -> v instanceof Number n ? (int) (byte) n.intValue() : null;
            case "short" -> v instanceof Number n ? (int) (short) n.intValue() : null;
            case "char" -> v instanceof Number n ? (n.intValue() & 0xFFFF) : null;
            case "int", "boolean" -> v instanceof Number n ? n.intValue() : v;
            case "long" -> v instanceof Number n ? n.longValue() : null;
            case "float" -> v instanceof Number n ? n.floatValue() : null;
            case "double" -> v instanceof Number n ? n.doubleValue() : null;
            case "String" -> v instanceof String ? v : null;
            default -> v;
        };
    }

    private @NotNull String dottedFqnToInternal(@NotNull String dotted) {
        String[] parts = dotted.split("\\.");
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        for (int split = parts.length - 1; split >= 1; split--) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < split; i++) {
                if (i > 0) sb.append('/');
                sb.append(parts[i]);
            }
            sb.append('/').append(parts[split]);
            for (int i = split + 1; i < parts.length; i++) sb.append('$').append(parts[i]);
            String candidate = sb.toString();
            if (batchTypes.containsValue(candidate)) return candidate;
            try {
                Class.forName(candidate.replace('/', '.'), false, loader);
                return candidate;
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
        return dotted.replace('.', '/');
    }

    /**
     * Pre-resolved import and package info for the current compilation unit.
     * Types declared inside the unit register themselves here as they are
     * emitted so nested and sibling types resolve before their {@code .class}
     * is written.
     */
    private final class Context {
        final @NotNull String packageInternal;
        final @NotNull Map<String, String> singleTypeImports = new HashMap<>();
        final @NotNull Set<String> starImports = new HashSet<>();
        final @NotNull Map<String, String> types = new HashMap<>();
        final @NotNull Set<String> typeParams = new HashSet<>();

        Context(@NotNull CompilationUnit cu) {
            this.packageInternal = cu.packageName() == null ? "" : cu.packageName().replace('.', '/');
            starImports.add("java/lang/");
            for (ImportDeclaration im : cu.imports()) {
                if (im.isStatic()) continue;
                String fqn = im.name();
                if (im.isWildcard()) {
                    starImports.add(fqn.replace('.', '/') + "/");
                } else {
                    int dot = fqn.lastIndexOf('.');
                    String simple = dot < 0 ? fqn : fqn.substring(dot + 1);
                    singleTypeImports.put(simple, dottedFqnToInternal(fqn));
                }
            }
        }

        void registerType(@NotNull String simple, @NotNull String internal) {
            types.put(simple, internal);
        }
    }
}
