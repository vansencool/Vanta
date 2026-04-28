package net.vansencool.vanta.codegen.classes.kind.record;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.RecordComponent;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits bytecode for a Java record declaration. Lays down each component as
 * a private-final field + {@code RecordComponent} attribute entry, emits the
 * canonical constructor, accessor methods, and the synthetic
 * {@code toString}/{@code hashCode}/{@code equals} trio routed through
 * {@code java.lang.runtime.ObjectMethods}. Any user-declared overrides of
 * those trio methods short-circuit the synthesis.
 */
public final class RecordBodyGenerator {

    private final @NotNull ClassGenerator owner;

    /**
     * @param owner class generator this helper cooperates with
     */
    public RecordBodyGenerator(@NotNull ClassGenerator owner) {
        this.owner = owner;
    }

    /**
     * Emits bytecode for {@code classDecl} as a record.
     *
     * @param classDecl         record declaration being emitted
     * @param packageName       enclosing package for top-level records, else null
     * @param outerInternalName enclosing-class internal name when nested, else null
     * @return class file bytes
     */
    public byte @NotNull [] generate(@NotNull ClassDeclaration classDecl, @Nullable String packageName, @Nullable String outerInternalName) {
        ClassWriter cw = owner.createClassWriter();
        String internalName = outerInternalName != null ? outerInternalName + "$" + classDecl.name() : owner.toInternalName(classDecl.name(), packageName);
        String[] interfaces = owner.resolveInterfaces(classDecl.interfaces());
        String signature = owner.classSignature(classDecl);
        int access = classDecl.modifiers() | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_RECORD;
        if (outerInternalName != null) access |= Opcodes.ACC_STATIC;
        if (outerInternalName != null) access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);

        owner.classHeaderEmitter().emit(cw, classDecl, internalName, access, signature, "java/lang/Record", interfaces, outerInternalName, false, owner.nestHostInternal(), owner.nestMemberInternals());

        List<RecordComponent> components = classDecl.recordComponents() != null ? classDecl.recordComponents() : List.of();

        for (RecordComponent component : components) {
            String descriptor = owner.typeResolver().resolveDescriptor(component.type());
            FieldVisitor fv = cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, component.name(), descriptor, null, null);
            fv.visitEnd();
            cw.visitRecordComponent(component.name(), descriptor, null).visitEnd();
        }

        Map<String, ResolvedType> fieldTypes = new HashMap<>();
        for (RecordComponent component : components) {
            fieldTypes.put(component.name(), owner.typeResolver().resolve(component.type()));
        }
        Map<String, SelfMethodInfo> selfMethods = new HashMap<>();
        owner.collectFieldsAndMethods(cw, classDecl, internalName, fieldTypes, selfMethods);
        StringBuilder canonicalDesc = new StringBuilder("(");
        for (RecordComponent component : components)
            canonicalDesc.append(owner.typeResolver().resolveDescriptor(component.type()));
        canonicalDesc.append(")V");
        selfMethods.put("<init>:" + components.size(), new SelfMethodInfo(internalName, "<init>", canonicalDesc.toString(), false));
        owner.methodEmitter().emitMemberMethods(cw, classDecl, internalName, "java/lang/Record", fieldTypes, selfMethods);

        Set<String> declaredMethods = new HashSet<>();
        for (AstNode member : classDecl.members()) {
            if (member instanceof MethodDeclaration md) declaredMethods.add(md.name());
        }

        int ctorAccess = classDecl.modifiers() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
        if (ctorAccess == 0) ctorAccess = Opcodes.ACC_PUBLIC;
        emitConstructor(cw, internalName, components, ctorAccess);
        emitAccessors(cw, internalName, components, declaredMethods);
        if (!declaredMethods.contains("toString")) emitToString(cw, internalName, components);
        if (!declaredMethods.contains("hashCode")) emitHashCode(cw, internalName, components);
        if (!declaredMethods.contains("equals")) emitEquals(cw, internalName, components);
        owner.staticInitEmitter().emitClassClinit(cw, classDecl, internalName, "java/lang/Record", fieldTypes, selfMethods);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Emits the canonical constructor that assigns each component parameter
     * to its matching field after delegating to {@code Record#<init>}.
     */
    private void emitConstructor(@NotNull ClassWriter cw, @NotNull String internalName, @NotNull List<RecordComponent> components, int access) {
        StringBuilder descBuilder = new StringBuilder("(");
        for (RecordComponent component : components) {
            descBuilder.append(owner.typeResolver().resolveDescriptor(component.type()));
        }
        descBuilder.append(")V");
        String descriptor = descBuilder.toString();

        MethodVisitor mv = cw.visitMethod(access, "<init>", descriptor, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Record", "<init>", "()V", false);

        int slot = 1;
        for (RecordComponent component : components) {
            ResolvedType type = owner.typeResolver().resolve(component.type());
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(OpcodeUtils.loadOpcode(type), slot);
            mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, component.name(), owner.typeResolver().resolveDescriptor(component.type()));
            slot += type.stackSize();
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emits one accessor method per record component, skipping any component
     * whose name the user already declared as a method.
     */
    private void emitAccessors(@NotNull ClassWriter cw, @NotNull String internalName, @NotNull List<RecordComponent> components, @NotNull Set<String> declaredMethods) {
        for (RecordComponent component : components) {
            if (declaredMethods.contains(component.name())) continue;
            String descriptor = owner.typeResolver().resolveDescriptor(component.type());
            ResolvedType type = owner.typeResolver().resolve(component.type());
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, component.name(), "()" + descriptor, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, internalName, component.name(), descriptor);
            mv.visitInsn(OpcodeUtils.returnOpcode(type));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    /**
     * Builds the static bootstrap arguments passed to
     * {@code ObjectMethods#bootstrap} for synthesised toString/hashCode/equals:
     * the record class token, a {@code ;}-separated component-name string, and
     * one {@code Handle} per component's backing field.
     */
    private Object @NotNull [] bootstrapArgs(@NotNull String internalName, @NotNull List<RecordComponent> components) {
        Object[] args = new Object[2 + components.size()];
        args[0] = Type.getType("L" + internalName + ";");
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) names.append(";");
            names.append(components.get(i).name());
        }
        args[1] = names.toString();
        for (int i = 0; i < components.size(); i++) {
            RecordComponent c = components.get(i);
            String desc = owner.typeResolver().resolveDescriptor(c.type());
            args[2 + i] = new Handle(Opcodes.H_GETFIELD, internalName, c.name(), desc, false);
        }
        return args;
    }

    /**
     * Emits a {@code toString()} that routes through
     * {@code ObjectMethods#bootstrap} for javac-compatible formatting.
     */
    private void emitToString(@NotNull ClassWriter cw, @NotNull String internalName, @NotNull List<RecordComponent> components) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "toString", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInvokeDynamicInsn("toString", "(L" + internalName + ";)Ljava/lang/String;", owner.objectMethodsBootstrap(), bootstrapArgs(internalName, components));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emits a {@code hashCode()} routed through the same ObjectMethods bootstrap.
     */
    private void emitHashCode(@NotNull ClassWriter cw, @NotNull String internalName, @NotNull List<RecordComponent> components) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "hashCode", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInvokeDynamicInsn("hashCode", "(L" + internalName + ";)I", owner.objectMethodsBootstrap(), bootstrapArgs(internalName, components));
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emits an {@code equals(Object)} routed through the same ObjectMethods bootstrap.
     */
    private void emitEquals(@NotNull ClassWriter cw, @NotNull String internalName, @NotNull List<RecordComponent> components) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "equals", "(Ljava/lang/Object;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInvokeDynamicInsn("equals", "(L" + internalName + ";Ljava/lang/Object;)Z", owner.objectMethodsBootstrap(), bootstrapArgs(internalName, components));
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
