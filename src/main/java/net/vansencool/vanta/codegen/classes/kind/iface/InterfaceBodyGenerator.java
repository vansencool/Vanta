package net.vansencool.vanta.codegen.classes.kind.iface;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits the bytecode for a Java interface declaration. Interfaces lower to an
 * abstract class with {@code ACC_INTERFACE}, every declared method emitted as
 * {@code ACC_PUBLIC} (and {@code ACC_ABSTRACT} when bodyless), and any
 * constant-initialised fields carrying a {@code ConstantValue} attribute
 * while the non-constant ones flow into {@code <clinit>}.
 */
public final class InterfaceBodyGenerator {

    private final @NotNull ClassGenerator owner;

    /**
     * Binds this generator to the owning class generator so it can share the
     * resolver, classpath, header emitter, and method emitter.
     *
     * @param owner class generator this helper cooperates with
     */
    public InterfaceBodyGenerator(@NotNull ClassGenerator owner) {
        this.owner = owner;
    }

    /**
     * Emits bytecode for {@code classDecl} as an interface.
     *
     * @param classDecl         interface declaration being emitted
     * @param packageName       package name for top-level interfaces, else null
     * @param outerInternalName enclosing-class internal name when nested, else null
     * @return class file bytes
     */
    public byte @NotNull [] generate(@NotNull ClassDeclaration classDecl, @Nullable String packageName, @Nullable String outerInternalName) {
        ClassWriter cw = owner.createClassWriter();
        String internalName = outerInternalName != null ? outerInternalName + "$" + classDecl.name() : owner.toInternalName(classDecl.name(), packageName);
        String[] interfaces = owner.resolveInterfaces(classDecl.interfaces());
        String signature = owner.classSignature(classDecl);
        int access = classDecl.modifiers() | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        if (outerInternalName != null) access |= Opcodes.ACC_STATIC;
        if (owner.outerIsInterfaceLike() && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0) {
            access |= Opcodes.ACC_PUBLIC;
        }
        if (outerInternalName != null) access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);

        owner.classHeaderEmitter().emit(cw, classDecl, internalName, access, signature, "java/lang/Object", interfaces, outerInternalName, false, owner.nestHostInternal(), owner.nestMemberInternals());

        Map<String, ResolvedType> fieldTypes = new HashMap<>();
        Map<String, SelfMethodInfo> selfMethods = new HashMap<>();

        for (AstNode member : classDecl.members()) {
            if (member instanceof FieldDeclaration fieldDecl) {
                int fieldMods = fieldDecl.modifiers() | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
                for (FieldDeclarator declarator : fieldDecl.declarators()) {
                    TypeNode effectiveType = fieldDecl.type();
                    if (declarator.extraArrayDimensions() > 0) {
                        effectiveType = effectiveType.withExtraDimensions(declarator.extraArrayDimensions());
                    }
                    String descriptor = owner.typeResolver().resolveDescriptor(effectiveType);
                    Object constantValue = owner.constantFolder().resolveConstantValue(fieldDecl, declarator);
                    FieldVisitor fv = cw.visitField(fieldMods, declarator.name(), descriptor, null, constantValue);
                    fv.visitEnd();
                    fieldTypes.put(declarator.name(), owner.typeResolver().resolve(effectiveType));
                }
            } else if (member instanceof MethodDeclaration methodDecl) {
                if (methodDecl.typeParameters() != null && !methodDecl.typeParameters().isEmpty())
                    owner.typeResolver().registerTypeParameters(methodDecl.typeParameters());
                List<TypeNode> paramTypes = new ArrayList<>();
                for (Parameter p : methodDecl.parameters()) paramTypes.add(p.type());
                String desc = owner.typeResolver().methodDescriptor(paramTypes, methodDecl.returnType());
                boolean isStatic = (methodDecl.modifiers() & Opcodes.ACC_STATIC) != 0;
                SelfMethodInfo info = new SelfMethodInfo(internalName, methodDecl.name(), desc, isStatic);
                String baseKey = methodDecl.name() + ":" + methodDecl.parameters().size();
                if (!selfMethods.containsKey(baseKey)) selfMethods.put(baseKey, info);
                else selfMethods.put(baseKey + "#" + desc, info);
            }
        }

        for (AstNode member : classDecl.members()) {
            if (member instanceof MethodDeclaration methodDecl) {
                int methodAccess = methodDecl.modifiers() | Opcodes.ACC_PUBLIC;
                if (methodDecl.body() == null) {
                    methodAccess |= Opcodes.ACC_ABSTRACT;
                }
                owner.methodEmitter().emitMethodWithAccess(cw, methodDecl, internalName, fieldTypes, selfMethods, methodAccess);
            }
        }

        owner.staticInitEmitter().emitInterfaceClinit(cw, classDecl, internalName, fieldTypes, selfMethods);

        cw.visitEnd();
        return cw.toByteArray();
    }
}
