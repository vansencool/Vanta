package net.vansencool.vanta.codegen.classes.kind.anno;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;

/**
 * Emits bytecode for an annotation type declaration ({@code @interface}).
 * Annotation types lower to an abstract interface carrying
 * {@code ACC_ANNOTATION}, implementing {@code java.lang.annotation.Annotation},
 * with each member turned into an abstract public method and an optional
 * {@code AnnotationDefault} attribute carrying its default value.
 */
public final class AnnotationTypeBodyGenerator {

    private final @NotNull ClassGenerator owner;

    /**
     * Binds this generator to the owning class generator so it can share the
     * header emitter, resolver, and annotation emitter.
     *
     * @param owner class generator this helper cooperates with
     */
    public AnnotationTypeBodyGenerator(@NotNull ClassGenerator owner) {
        this.owner = owner;
    }

    /**
     * Generates the bytecode for {@code classDecl} as an annotation type,
     * returning the emitted class file bytes.
     *
     * @param classDecl         annotation type declaration being emitted
     * @param packageName       package name for top-level annotation types, else null
     * @param outerInternalName internal name of the enclosing class when nested, else null
     * @return class file bytes for the annotation type
     */
    public byte @NotNull [] generate(@NotNull ClassDeclaration classDecl, @Nullable String packageName, @Nullable String outerInternalName) {
        ClassWriter cw = owner.createClassWriter();
        String internalName = outerInternalName != null ? outerInternalName + "$" + classDecl.name() : owner.toInternalName(classDecl.name(), packageName);
        int access = classDecl.modifiers() | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION;
        if (outerInternalName != null) access |= Opcodes.ACC_STATIC;
        if (outerInternalName != null) access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);
        owner.classHeaderEmitter().emit(cw, classDecl, internalName, access, null, "java/lang/Object", new String[]{"java/lang/annotation/Annotation"}, outerInternalName, false, owner.nestHostInternal(), owner.nestMemberInternals());
        for (AstNode member : classDecl.members()) {
            if (member instanceof MethodDeclaration md) {
                int mAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
                String descriptor = owner.typeResolver().methodDescriptor(new ArrayList<>(), md.returnType());
                MethodVisitor mv = cw.visitMethod(mAccess, md.name(), descriptor, null, null);
                owner.annotationEmitter().emitMethodAnnotations(mv, md.annotations());
                if (md.defaultValue() != null) {
                    AnnotationVisitor av = mv.visitAnnotationDefault();
                    owner.annotationEmitter().emitAnnotationValue(av, null, md.defaultValue());
                    av.visitEnd();
                }
                mv.visitEnd();
            }
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
