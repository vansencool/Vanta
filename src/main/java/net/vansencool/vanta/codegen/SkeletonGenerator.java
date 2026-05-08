package net.vansencool.vanta.codegen;

import net.vansencool.vanta.VantaCompiler;
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
 * Emits signature only {@code .class} files for every type declared in a
 * {@link CompilationUnit}. Used by the two pass {@link VantaCompiler#compileAll}
 * so cross file references resolve against real classpath entries instead of
 * silently falling back to {@code Object}.
 * <p>
 * Skeleton emission is deliberately shallow: no constant values, no generic
 * signatures, no method bodies. Real codegen recomputes anything heavier when
 * it actually needs it.
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

    public @NotNull Map<String, byte[]> emit(@NotNull CompilationUnit cu) {
        Context ctx = new Context(cu);
        Map<String, byte[]> out = new HashMap<>();
        for (AstNode typeDecl : cu.typeDeclarations()) {
            if (typeDecl instanceof ClassDeclaration cd) emitClass(out, ctx, cd, null);
        }
        return out;
    }

    /**
     * Records every type declared in {@code cu} so cross file imports can
     * resolve nested class references without round tripping through the
     * system classpath.
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

    private void registerBatchType(@NotNull ClassDeclaration cd, @NotNull String internalName) {
        batchTypes.put(cd.name(), internalName);
        batchTypes.put(internalName.replace('/', '.'), internalName);
        for (AstNode m : cd.members()) {
            if (m instanceof ClassDeclaration nested) {
                registerBatchType(nested, internalName + "$" + nested.name());
            }
        }
    }

    private void emitClass(@NotNull Map<String, byte[]> out, @NotNull Context ctx, @NotNull ClassDeclaration cd, @Nullable String outerInternal) {
        String internalName = outerInternal == null
                ? (ctx.packageInternal.isEmpty() ? cd.name() : ctx.packageInternal + "/" + cd.name())
                : outerInternal + "$" + cd.name();
        ctx.registerType(cd.name(), internalName);
        if (cd.typeParameters() != null) {
            for (TypeParameter tp : cd.typeParameters()) ctx.typeParams.add(tp.name());
        }
        for (AstNode m : cd.members()) {
            if (m instanceof ClassDeclaration inner) ctx.registerType(inner.name(), internalName + "$" + inner.name());
        }

        boolean isInterface = cd.kind() == TypeKind.INTERFACE || cd.kind() == TypeKind.ANNOTATION;
        boolean isEnum = cd.kind() == TypeKind.ENUM;
        boolean isRecord = cd.kind() == TypeKind.RECORD;
        int access = cd.modifiers();
        if (isInterface) access |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        if (isEnum) access |= Opcodes.ACC_ENUM | Opcodes.ACC_FINAL;
        if (isRecord) access |= Opcodes.ACC_FINAL;
        if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0) access |= Opcodes.ACC_PUBLIC;
        if (!isInterface) access |= Opcodes.ACC_SUPER;

        String superInternal = resolveSuper(ctx, cd, isEnum, isRecord, isInterface);
        String[] interfaces = resolveInterfaces(ctx, cd);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, access, internalName, classSignature(ctx, cd), superInternal, interfaces);

        if (isEnum && cd.enumConstants() != null) {
            for (EnumConstant ec : cd.enumConstants()) {
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM, ec.name(), "L" + internalName + ";", null, null).visitEnd();
            }
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "$VALUES", "[L" + internalName + ";", null, null).visitEnd();
        }

        if (isRecord && cd.recordComponents() != null) {
            for (RecordComponent rc : cd.recordComponents()) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, rc.name(), typeDescriptor(ctx, rc.type()), null, null).visitEnd();
            }
        }

        boolean sawConstructor = false;
        for (AstNode m : cd.members()) {
            if (m instanceof FieldDeclaration fd) {
                int fAccess = fd.modifiers();
                if (isInterface) fAccess |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
                for (FieldDeclarator fdr : fd.declarators()) {
                    TypeNode fieldType = fd.type();
                    if (fdr.extraArrayDimensions() > 0) fieldType = fieldType.withExtraDimensions(fdr.extraArrayDimensions());
                    String fSig = typeHasGenerics(fieldType) ? typeSignature(ctx, fieldType) : null;
                    cw.visitField(fAccess, fdr.name(), typeDescriptor(ctx, fieldType), fSig, null).visitEnd();
                }
            } else if (m instanceof MethodDeclaration md) {
                if ("<iinit>".equals(md.name()) || "<clinit>".equals(md.name())) continue;
                int mAccess = md.modifiers();
                if (isInterface && (mAccess & Opcodes.ACC_STATIC) == 0 && md.body() == null) mAccess |= Opcodes.ACC_ABSTRACT;
                if (isInterface && (mAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE)) == 0) mAccess |= Opcodes.ACC_PUBLIC;
                if (md.isVarargs()) mAccess |= Opcodes.ACC_VARARGS;
                MethodVisitor mv = cw.visitMethod(mAccess, md.name(), methodDescriptor(ctx, md), methodSignature(ctx, md), null);
                if ("<init>".equals(md.name())) {
                    sawConstructor = true;
                    emitCtorStub(mv, superInternal);
                } else if ((mAccess & Opcodes.ACC_ABSTRACT) == 0 && (mAccess & Opcodes.ACC_NATIVE) == 0) {
                    emitStubBody(mv);
                } else {
                    mv.visitEnd();
                }
            }
        }
        if (!sawConstructor && !isInterface) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            emitCtorStub(mv, superInternal);
        }
        if (isEnum) {
            emitStubBody(cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values", "()[L" + internalName + ";", null, null));
            emitStubBody(cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "valueOf", "(Ljava/lang/String;)L" + internalName + ";", null, null));
        }

        cw.visitEnd();
        out.put(internalName, cw.toByteArray());

        for (AstNode m : cd.members()) {
            if (m instanceof ClassDeclaration inner) emitClass(out, ctx, inner, internalName);
        }
    }

    private void emitStubBody(@NotNull MethodVisitor mv) {
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/AbstractMethodError");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AbstractMethodError", "<init>", "()V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
    }

    private void emitCtorStub(@NotNull MethodVisitor mv, @NotNull String superInternal) {
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

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

    private @NotNull String typeDescriptor(@NotNull Context ctx, @NotNull TypeNode t) {
        StringBuilder sb = new StringBuilder();
        sb.append("[".repeat(Math.max(0, t.arrayDimensions())));
        switch (t.name()) {
            case "boolean" -> sb.append('Z');
            case "byte" -> sb.append('B');
            case "short" -> sb.append('S');
            case "char" -> sb.append('C');
            case "int" -> sb.append('I');
            case "long" -> sb.append('J');
            case "float" -> sb.append('F');
            case "double" -> sb.append('D');
            case "void" -> sb.append('V');
            default -> sb.append('L').append(resolveReferenceInternal(ctx, t.name())).append(';');
        }
        return sb.toString();
    }

    private @NotNull String typeInternal(@NotNull Context ctx, @NotNull TypeNode t) {
        if (PRIMITIVES.contains(t.name())) return "java/lang/Object";
        if (t.arrayDimensions() > 0) return "java/lang/Object";
        return resolveReferenceInternal(ctx, t.name());
    }

    private boolean typeHasGenerics(@NotNull TypeNode t) {
        if (t.typeArguments() == null || t.typeArguments().isEmpty()) return false;
        for (TypeNode arg : t.typeArguments()) {
            if (typeHasGenerics(arg)) return true;
        }
        return true;
    }

    /**
     * JVMS §4.7.9.1 field type signature. Type variables emit as {@code TName;}
     * so reflection downstream can recover the original {@code T} name.
     */
    private @NotNull String typeSignature(@NotNull Context ctx, @NotNull TypeNode t) {
        StringBuilder sb = new StringBuilder();
        sb.append("[".repeat(Math.max(0, t.arrayDimensions())));
        appendTypeSig(sb, ctx, t);
        return sb.toString();
    }

    private void appendTypeSig(@NotNull StringBuilder sb, @NotNull Context ctx, @NotNull TypeNode t) {
        switch (t.name()) {
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
        if (ctx.typeParams.contains(t.name())) {
            sb.append('T').append(t.name()).append(';');
            return;
        }
        String resolved = resolveReferenceInternal(ctx, t.name());
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
     * Returns {@code null} for plain non generic classes so the
     * {@code Signature} attribute is skipped entirely.
     */
    private @Nullable String classSignature(@NotNull Context ctx, @NotNull ClassDeclaration cd) {
        boolean hasGenerics = cd.typeParameters() != null && !cd.typeParameters().isEmpty();
        if (!hasGenerics && cd.superClass() != null && typeHasGenerics(cd.superClass())) hasGenerics = true;
        if (!hasGenerics) {
            for (TypeNode iface : cd.interfaces()) {
                if (typeHasGenerics(iface)) {
                    hasGenerics = true;
                    break;
                }
            }
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
     * Returns {@code null} when neither the parameters nor the return type
     * carry generics so the {@code Signature} attribute is skipped.
     */
    private @Nullable String methodSignature(@NotNull Context ctx, @NotNull MethodDeclaration md) {
        boolean hasGenerics = md.typeParameters() != null && !md.typeParameters().isEmpty();
        if (!hasGenerics) {
            for (Parameter p : md.parameters()) {
                if (typeHasGenerics(p.type()) || ctx.typeParams.contains(p.type().name())) {
                    hasGenerics = true;
                    break;
                }
            }
        }
        if (!hasGenerics && (typeHasGenerics(md.returnType()) || ctx.typeParams.contains(md.returnType().name()))) {
            hasGenerics = true;
        }
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
                    } else {
                        sb.append("Ljava/lang/Object;");
                    }
                }
                sb.append('>');
            }
            sb.append('(');
            for (Parameter p : md.parameters()) {
                sb.append("[".repeat(Math.max(0, p.type().arrayDimensions())));
                appendTypeSig(sb, ctx, p.type());
            }
            sb.append(')');
            if ("<init>".equals(md.name())) {
                sb.append('V');
            } else {
                sb.append("[".repeat(Math.max(0, md.returnType().arrayDimensions())));
                appendTypeSig(sb, ctx, md.returnType());
            }
            return sb.toString();
        } finally {
            for (String name : added) ctx.typeParams.remove(name);
        }
    }

    /**
     * Tries, in order: already emitted skeleton types, dot prefixed FQNs in the
     * source, single type imports, JDK built ins, same package fallback. The
     * final fallback returns {@code java/lang/Object} so the skeleton stays
     * compilable when we cannot prove the type exists.
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
