package net.vansencool.vanta.codegen.classes.kind.clazz;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.classes.scan.AnonCounter;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.expression.LambdaExpression;
import net.vansencool.vanta.parser.ast.expression.NewExpression;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Emits bytecode for a standard Java class declaration. Handles the
 * full per-class setup ritual: resetting lambda/anon counters, running the
 * anon/lambda pre-scan so javac-compatible {@code $N} numbering stays
 * source-ordered, flipping nest-host state for top-level classes, writing
 * the class header and {@code this$0} synthetic field for inner classes,
 * emitting members in source order, and appending a default constructor
 * when none was authored.
 */
public final class ClassBodyGenerator {

    private final @NotNull ClassGenerator owner;

    /**
     * Binds this helper to its owning class generator.
     *
     * @param owner class generator this helper cooperates with
     */
    public ClassBodyGenerator(@NotNull ClassGenerator owner) {
        this.owner = owner;
    }

    /**
     * Emits bytecode for {@code classDecl} as a regular class.
     *
     * @param classDecl         class declaration being emitted
     * @param packageName       package name for top-level classes, else null
     * @param outerInternalName enclosing-class internal name when nested, else null
     * @return class file bytes
     */
    public byte @NotNull [] generate(@NotNull ClassDeclaration classDecl, @Nullable String packageName, @Nullable String outerInternalName) {
        owner.lambdaCounter().set(0);
        if (outerInternalName == null) owner.anonClassCounter().set(0);
        owner.clearAnonClassBytecodes();
        owner.assertUsed(owner.containsAssertStatement(classDecl));
        Map<NewExpression, Integer> savedAnonIdx = owner.swapAnonIndexAssignments(new IdentityHashMap<>());
        Map<LambdaExpression, Integer> savedLambdaIdx = owner.swapLambdaIndexAssignments(new IdentityHashMap<>());
        new AnonCounter(new int[]{0}, owner.anonIndexAssignments(), new int[]{0}, owner.lambdaIndexAssignments()).scanClass(classDecl);
        if (outerInternalName == null) {
            owner.nestHostInternal(owner.internalNameUnresolved(classDecl, packageName));
            owner.clearNestMemberInternals();
            owner.collectNestMemberInternals(classDecl, owner.nestHostInternal());
        }
        ClassWriter cw = owner.createClassWriter();
        String internalName = outerInternalName != null ? outerInternalName + "$" + classDecl.name() : owner.toInternalName(classDecl.name(), packageName);
        if (classDecl.typeParameters() != null && !classDecl.typeParameters().isEmpty())
            owner.typeResolver().registerTypeParameters(classDecl.typeParameters());
        for (AstNode member : classDecl.members()) {
            if (member instanceof ClassDeclaration inner) {
                owner.typeResolver().registerInnerClass(inner.name(), internalName);
            }
        }
        String superInternal = classDecl.superClass() != null ? owner.typeResolver().resolveInternalName(classDecl.superClass()) : "java/lang/Object";
        String[] interfaces = owner.resolveInterfaces(classDecl.interfaces());
        String signature = owner.classSignature(classDecl);

        int access = classDecl.modifiers() | Opcodes.ACC_SUPER;
        if (outerInternalName != null && owner.outerIsInterfaceLike() && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0) {
            access |= Opcodes.ACC_PUBLIC;
        }
        boolean isNonStaticInner = outerInternalName != null && (classDecl.modifiers() & Opcodes.ACC_STATIC) == 0;
        if (outerInternalName != null) access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);
        String[] savedOuter = owner.swapOuter(isNonStaticInner ? outerInternalName : null, outerInternalName);
        owner.classHeaderEmitter().emit(cw, classDecl, internalName, access, signature, superInternal, interfaces, outerInternalName, isNonStaticInner, owner.nestHostInternal(), owner.nestMemberInternals());

        if (isNonStaticInner) {
            FieldVisitor fv = cw.visitField(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "this$0", "L" + outerInternalName + ";", null, null);
            fv.visitEnd();
        }

        Map<String, ResolvedType> fieldTypes = new HashMap<>();
        Map<String, SelfMethodInfo> selfMethods = new HashMap<>();
        owner.collectFieldsAndMethods(cw, classDecl, internalName, fieldTypes, selfMethods);

        owner.methodEmitter().emitInSourceOrder(cw, classDecl, internalName, superInternal, fieldTypes, selfMethods);

        boolean hasConstructor = classDecl.members().stream().anyMatch(m -> m instanceof MethodDeclaration md && md.name().equals("<init>"));
        if (!hasConstructor) {
            owner.methodEmitter().emitDefaultConstructor(cw, internalName, superInternal, classDecl, fieldTypes, selfMethods, isNonStaticInner ? outerInternalName : null);
        }

        cw.visitEnd();
        byte[] result = cw.toByteArray();
        owner.restoreOuter(savedOuter[0], savedOuter[1]);
        owner.swapAnonIndexAssignments(savedAnonIdx);
        owner.swapLambdaIndexAssignments(savedLambdaIdx);
        return result;
    }
}
