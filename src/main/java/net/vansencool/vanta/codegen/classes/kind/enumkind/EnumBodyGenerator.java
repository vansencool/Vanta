package net.vansencool.vanta.codegen.classes.kind.enumkind;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.EnumConstant;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.declaration.TypeKind;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.MethodResolver;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Emits bytecode for a Java enum declaration. Handles the header, constant
 * fields, synthetic {@code $VALUES} backing array, {@code values()} /
 * {@code valueOf(String)} synthetic methods, the private
 * {@code <init>(String, int, ...)} constructor, and the {@code <clinit>}
 * that instantiates every constant (including synthetic {@code Foo$N}
 * subclasses for constants with anonymous bodies).
 */
public final class EnumBodyGenerator {

    private final @NotNull ClassGenerator owner;

    /**
     * @param owner class generator this helper cooperates with
     */
    public EnumBodyGenerator(@NotNull ClassGenerator owner) {
        this.owner = owner;
    }

    /**
     * Emits bytecode for {@code classDecl} as an enum.
     *
     * @param classDecl         enum declaration being emitted
     * @param packageName       enclosing package for top-level enums, else null
     * @param outerInternalName enclosing-class internal name when nested, else null
     * @return class file bytes
     */
    public byte @NotNull [] generate(@NotNull ClassDeclaration classDecl, @Nullable String packageName, @Nullable String outerInternalName) {
        ClassWriter cw = owner.createClassWriter();
        String internalName = outerInternalName != null ? outerInternalName + "$" + classDecl.name() : owner.toInternalName(classDecl.name(), packageName);
        String[] interfaces = owner.resolveInterfaces(classDecl.interfaces());
        boolean hasAnonConstants = false;
        if (classDecl.enumConstants() != null) {
            for (EnumConstant ec : classDecl.enumConstants()) {
                if (ec.classBody() != null) {
                    hasAnonConstants = true;
                    break;
                }
            }
        }
        int access = enumAccess(classDecl, outerInternalName, hasAnonConstants);
        String superInternal = "java/lang/Enum";
        String enumSig = "Ljava/lang/Enum<L" + internalName + ";>;";

        owner.classHeaderEmitter().emit(cw, classDecl, internalName, access, enumSig, superInternal, interfaces, outerInternalName, false, owner.nestHostInternal(), owner.nestMemberInternals());

        List<EnumConstant> constants = classDecl.enumConstants() != null ? classDecl.enumConstants() : List.of();
        String enumDesc = "L" + internalName + ";";

        for (EnumConstant constant : constants) {
            FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM, constant.name(), enumDesc, null, null);
            fv.visitEnd();
        }

        FieldVisitor valuesField = cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "$VALUES", "[" + enumDesc, null, null);
        valuesField.visitEnd();

        Map<String, ResolvedType> fieldTypes = new HashMap<>();
        Map<String, SelfMethodInfo> selfMethods = new HashMap<>();
        ResolvedType enumType = ResolvedType.ofObject(internalName);
        for (EnumConstant constant : constants) {
            fieldTypes.put(constant.name(), enumType);
        }
        owner.collectFieldsAndMethods(cw, classDecl, internalName, fieldTypes, selfMethods);
        owner.methodEmitter().emitMemberMethods(cw, classDecl, internalName, "java/lang/Enum", fieldTypes, selfMethods);

        emitValues(cw, internalName, enumDesc);
        emitValueOf(cw, internalName, enumDesc);
        emitConstructor(cw, hasAnonConstants);
        emitClinit(cw, internalName, enumDesc, constants);
        emitDollarValues(cw, internalName, enumDesc, constants);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Computes the JVM access flags for an enum class declaration: enum bit,
     * abstract vs final based on whether any constant has a class body, plus
     * the nesting + interface-member adjustments javac applies.
     */
    private int enumAccess(@NotNull ClassDeclaration classDecl, @Nullable String outerInternalName, boolean hasAnonConstants) {
        int access = classDecl.modifiers() | Opcodes.ACC_SUPER | Opcodes.ACC_ENUM;
        access |= hasAnonConstants ? Opcodes.ACC_ABSTRACT : Opcodes.ACC_FINAL;
        if (outerInternalName != null) access |= Opcodes.ACC_STATIC;
        if (owner.outerIsInterfaceLike() && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0) {
            access |= Opcodes.ACC_PUBLIC;
        }
        if (outerInternalName != null) access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);
        return access;
    }

    /**
     * Emits the synthetic {@code values()} method that clones the backing
     * {@code $VALUES} array so callers can't mutate the enum's internal state.
     */
    private void emitValues(@NotNull ClassWriter cw, @NotNull String internalName, @NotNull String enumDesc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values", "()[" + enumDesc, null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "$VALUES", "[" + enumDesc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[" + enumDesc, "clone", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[" + enumDesc);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emits the synthetic {@code valueOf(String)} method that delegates to
     * {@code Enum.valueOf(Class, String)} and casts the result.
     */
    private void emitValueOf(@NotNull ClassWriter cw, @NotNull String internalName, @NotNull String enumDesc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "valueOf", "(Ljava/lang/String;)" + enumDesc, null, null);
        mv.visitCode();
        mv.visitLdcInsn(Type.getObjectType(internalName));
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, internalName);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emits the private enum constructor that forwards to
     * {@code Enum(String, int)}. Drops the private flag when any constant
     * carries an anonymous body so the synthetic {@code $N} subclasses can
     * call it from their own constructors.
     */
    private void emitConstructor(@NotNull ClassWriter cw, boolean hasAnonConstants) {
        int ctorAccess = hasAnonConstants ? 0 : Opcodes.ACC_PRIVATE;
        MethodVisitor mv = cw.visitMethod(ctorAccess, "<init>", "(Ljava/lang/String;I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emits the {@code <clinit>} that instantiates every enum constant (via
     * synthetic {@code Foo$N} subclasses when a constant carries an anonymous
     * body) and then populates {@code $VALUES}.
     */
    private void emitClinit(@NotNull ClassWriter cw, @NotNull String internalName, @NotNull String enumDesc, @NotNull List<EnumConstant> constants) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        int anonIdx = 0;
        for (int i = 0; i < constants.size(); i++) {
            EnumConstant constant = constants.get(i);
            String ctorOwner = internalName;
            String ctorDesc = "(Ljava/lang/String;I)V";
            if (constant.classBody() != null) {
                anonIdx++;
                ctorOwner = internalName + "$" + anonIdx;
                ctorDesc = emitConstantSubclass(cw, internalName, ctorOwner, constant);
            }
            mv.visitTypeInsn(Opcodes.NEW, ctorOwner);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(constant.name());
            OpcodeUtils.pushInt(mv, i);
            for (Expression arg : constant.arguments()) {
                MethodContext clinitCtx = new MethodContext(mv, new Scope(0), owner.typeResolver(), new MethodResolver(owner.classpathManager()), internalName, "java/lang/Enum", true, new HashMap<>());
                clinitCtx.nestedClassFields(owner.nestedClassFields());
                clinitCtx.nestedClassMethods(owner.nestedClassMethods());
                clinitCtx.nestedClassConstants(owner.nestedClassConstants());
                clinitCtx.setupLambdaSupport(cw, owner.lambdaCounter(), "<clinit>");
                clinitCtx.setupAnonClassSupport(owner, cw, owner.anonClassCounter(), "<clinit>", owner.anonClassBytecodes(), owner.anonClassNames());
                ExpressionGenerator clinitExpr = new ExpressionGenerator(clinitCtx);
                clinitExpr.generate(arg);
            }
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ctorOwner, "<init>", ctorDesc, false);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, constant.name(), enumDesc);
        }

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, internalName, "$values", "()[" + enumDesc, false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "$VALUES", "[" + enumDesc);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emits a synthetic {@code Foo$N} subclass for an enum constant that
     * overrides methods via its anonymous body, and returns the constructor
     * descriptor the enclosing clinit should use to instantiate it.
     */
    private @NotNull String emitConstantSubclass(@NotNull ClassWriter outerCw, @NotNull String enumInternal, @NotNull String subclassInternal, @NotNull EnumConstant constant) {
        ClassWriter subCw = owner.createClassWriter();
        StringBuilder argsDescBuilder = new StringBuilder();
        List<String> argDescriptors = new ArrayList<>();
        for (Expression arg : constant.arguments()) {
            ResolvedType argType = inferArgType(arg);
            String d = argType != null ? argType.descriptor() : "Ljava/lang/Object;";
            argDescriptors.add(d);
            argsDescBuilder.append(d);
        }
        String ctorDesc = "(Ljava/lang/String;I" + argsDescBuilder + ")V";

        int subAccess = Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_ENUM;
        subCw.visit(Opcodes.V17, subAccess, subclassInternal, null, enumInternal, null);
        if (owner.sourceFile() != null) subCw.visitSource(owner.sourceFile(), null);
        outerCw.visitInnerClass(subclassInternal, null, null, Opcodes.ACC_ENUM | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
        subCw.visitInnerClass(subclassInternal, null, null, Opcodes.ACC_ENUM | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);

        MethodVisitor ctorMv = subCw.visitMethod(0, "<init>", ctorDesc, null, null);
        ctorMv.visitCode();
        ctorMv.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        ctorMv.visitVarInsn(Opcodes.ALOAD, slot++);
        ctorMv.visitVarInsn(Opcodes.ILOAD, slot++);
        for (String d : argDescriptors) {
            ctorMv.visitVarInsn(OpcodeUtils.loadOpcodeForDescriptor(d), slot);
            slot += OpcodeUtils.descriptorStackSize(d);
        }
        ctorMv.visitMethodInsn(Opcodes.INVOKESPECIAL, enumInternal, "<init>", ctorDesc, false);
        ctorMv.visitInsn(Opcodes.RETURN);
        ctorMv.visitMaxs(0, 0);
        ctorMv.visitEnd();

        Map<String, ResolvedType> fieldTypes = new HashMap<>();
        Map<String, SelfMethodInfo> selfMethods = new HashMap<>();
        List<AstNode> classBody = Objects.requireNonNull(constant.classBody());
        for (AstNode m : classBody) {
            if (m instanceof MethodDeclaration md) {
                List<TypeNode> paramTypes = new ArrayList<>();
                for (Parameter p : md.parameters()) paramTypes.add(p.type());
                String desc = owner.typeResolver().methodDescriptor(paramTypes, md.returnType());
                selfMethods.put(md.name() + ":" + md.parameters().size(), new SelfMethodInfo(subclassInternal, md.name(), desc, false));
            }
        }
        String[] savedOuter = owner.swapOuter(owner.currentEnclosingOuter(), enumInternal);
        for (AstNode m : classBody) {
            if (m instanceof MethodDeclaration md) {
                ClassDeclaration syntheticDecl = new ClassDeclaration(subclassInternal, 0, null, null, new ArrayList<>(), classBody, new ArrayList<>(), TypeKind.CLASS, null, null, constant.line());
                owner.methodEmitter().emitMethod(subCw, md, subclassInternal, enumInternal, fieldTypes, selfMethods, syntheticDecl);
            }
        }
        owner.restoreOuter(savedOuter[0], savedOuter[1]);
        owner.bridgeMethodEmitter().emitForMembers(subCw, classBody, subclassInternal, List.of(enumInternal));
        subCw.visitEnd();
        owner.anonClassBytecodes().add(subCw.toByteArray());
        owner.anonClassNames().add(subclassInternal);
        owner.classpathManager().registerInMemoryClass(subclassInternal, subCw.toByteArray());
        return ctorDesc;
    }

    /**
     * Shallow type inference used only while computing the synthetic
     * {@code Foo$N} constructor descriptor: good enough to pick the right
     * primitive/string shape for each literal arg.
     */
    private @Nullable ResolvedType inferArgType(@NotNull Expression arg) {
        if (arg instanceof LiteralExpression lit) {
            return switch (lit.literalType()) {
                case INT_LITERAL -> ResolvedType.INT;
                case LONG_LITERAL -> ResolvedType.LONG;
                case FLOAT_LITERAL -> ResolvedType.FLOAT;
                case DOUBLE_LITERAL -> ResolvedType.DOUBLE;
                case STRING_LITERAL, TEXT_BLOCK -> ResolvedType.ofObject("java/lang/String");
                case CHAR_LITERAL -> ResolvedType.CHAR;
                case TRUE, FALSE -> ResolvedType.BOOLEAN;
                default -> null;
            };
        }
        return null;
    }

    /**
     * Emits the synthetic {@code $values()} method that creates the backing
     * array populated by {@code <clinit>}.
     */
    private void emitDollarValues(@NotNull ClassWriter cw, @NotNull String internalName, @NotNull String enumDesc, @NotNull List<EnumConstant> constants) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "$values", "()[" + enumDesc, null, null);
        mv.visitCode();
        OpcodeUtils.pushInt(mv, constants.size());
        mv.visitTypeInsn(Opcodes.ANEWARRAY, internalName);
        for (int i = 0; i < constants.size(); i++) {
            mv.visitInsn(Opcodes.DUP);
            OpcodeUtils.pushInt(mv, i);
            mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, constants.get(i).name(), enumDesc);
            mv.visitInsn(Opcodes.AASTORE);
        }
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
