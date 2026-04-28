package net.vansencool.vanta.codegen.classes.header;

import net.vansencool.vanta.codegen.classes.annotation.AnnotationEmitter;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.TypeKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * Emits the shared class-header preamble for every top-level and nested type
 * kind: the {@code ClassWriter#visit} call, {@code SourceFile} debug
 * attribute, {@code NestHost}/{@code NestMembers} attributes, class-level
 * annotations, {@code InnerClasses} entry for this class, and
 * {@code InnerClasses} entries for its nested siblings. Used by the
 * class/interface/enum/record/annotation-type generators so the repeated
 * preamble lives in one place.
 */
public final class ClassHeaderEmitter {

    private final @Nullable String sourceFile;
    private final @NotNull AnnotationEmitter annotationEmitter;

    /**
     * Creates a header emitter bound to the source-file debug attribute and
     * annotation emitter shared with the class generator.
     *
     * @param sourceFile        source-file name for the {@code SourceFile} attribute, or null
     * @param annotationEmitter emitter used for class-level annotations
     */
    public ClassHeaderEmitter(@Nullable String sourceFile, @NotNull AnnotationEmitter annotationEmitter) {
        this.sourceFile = sourceFile;
        this.annotationEmitter = annotationEmitter;
    }

    /**
     * Emits the shared header for a class, interface, enum, or record body.
     *
     * @param cw                  target class writer
     * @param classDecl           source declaration being emitted
     * @param internalName        internal name of the class being emitted
     * @param access              pre-computed JVM access flags
     * @param signature           generic signature attribute value, or null
     * @param superInternal       internal name of the super class (e.g. {@code "java/lang/Object"})
     * @param interfaces          internal names of implemented interfaces
     * @param outerInternalName   owning-class internal name when nested, else null
     * @param isNonStaticInner    true for non-static inner classes (affects {@code InnerClasses} access bit)
     * @param nestHostInternal    nest host internal name, or null when this isn't part of a nest
     * @param nestMemberInternals nest member internal names listed when this class is the nest host
     */
    public void emit(@NotNull ClassWriter cw, @NotNull ClassDeclaration classDecl, @NotNull String internalName,
                     int access, @Nullable String signature, @NotNull String superInternal,
                     @NotNull String[] interfaces, @Nullable String outerInternalName, boolean isNonStaticInner,
                     @Nullable String nestHostInternal, @NotNull List<String> nestMemberInternals) {
        cw.visit(Opcodes.V17, access, internalName, signature, superInternal, interfaces);
        if (sourceFile != null) cw.visitSource(sourceFile, null);
        if (nestHostInternal != null) {
            if (internalName.equals(nestHostInternal)) {
                for (String member : nestMemberInternals) cw.visitNestMember(member);
            } else {
                cw.visitNestHost(nestHostInternal);
            }
        }
        annotationEmitter.emitClassAnnotations(cw, classDecl.annotations());
        if (outerInternalName != null) {
            int innerAccess = innerAccessForSelf(classDecl, isNonStaticInner);
            cw.visitInnerClass(internalName, outerInternalName, classDecl.name(), innerAccess);
        }
        emitInnerClassAttributes(cw, classDecl, internalName);
    }

    /**
     * Computes the {@code InnerClasses} access flags for a class being emitted
     * as the inner declaration referencing itself: starts from source modifiers,
     * adds {@code ACC_STATIC} unless this is a non-static inner, then layers
     * the kind-specific flags ({@code INTERFACE}, {@code ANNOTATION},
     * {@code ENUM}, {@code RECORD}).
     */
    private int innerAccessForSelf(@NotNull ClassDeclaration classDecl, boolean isNonStaticInner) {
        int innerAccess = classDecl.modifiers();
        if (!isNonStaticInner) innerAccess |= Opcodes.ACC_STATIC;
        if (classDecl.kind() == TypeKind.INTERFACE) innerAccess |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        if (classDecl.kind() == TypeKind.ANNOTATION)
            innerAccess |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION;
        if (classDecl.kind() == TypeKind.ENUM) innerAccess |= Opcodes.ACC_ENUM | Opcodes.ACC_FINAL;
        if (classDecl.kind() == TypeKind.RECORD) innerAccess |= Opcodes.ACC_RECORD | Opcodes.ACC_FINAL;
        return innerAccess;
    }

    /**
     * Computes the {@code InnerClasses} access flags for a nested-sibling
     * declaration listed under its enclosing class: forces {@code ACC_STATIC},
     * promotes package-private to public when the enclosing type is an
     * interface or annotation, then adds kind-specific flags.
     */
    private int innerAccessForNested(@NotNull ClassDeclaration inner, boolean outerIsInterfaceLike) {
        int innerAccess = inner.modifiers() | Opcodes.ACC_STATIC;
        if (outerIsInterfaceLike && (innerAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0) {
            innerAccess |= Opcodes.ACC_PUBLIC;
        }
        if (inner.kind() == TypeKind.INTERFACE) innerAccess |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        if (inner.kind() == TypeKind.ENUM) innerAccess |= Opcodes.ACC_ENUM | Opcodes.ACC_FINAL;
        if (inner.kind() == TypeKind.RECORD) innerAccess |= Opcodes.ACC_RECORD | Opcodes.ACC_FINAL;
        return innerAccess;
    }

    /**
     * Walks the class declaration's nested types and emits one
     * {@code InnerClasses} entry per nested member so the host class's
     * attribute table lists every type declared inside it.
     *
     * @param cw                target class writer for the enclosing class
     * @param classDecl         enclosing class declaration
     * @param outerInternalName internal name of the enclosing class
     */
    public void emitInnerClassAttributes(@NotNull ClassWriter cw, @NotNull ClassDeclaration classDecl, @NotNull String outerInternalName) {
        boolean outerIsInterfaceLike = classDecl.kind() == TypeKind.INTERFACE || classDecl.kind() == TypeKind.ANNOTATION;
        for (AstNode member : classDecl.members()) {
            if (member instanceof ClassDeclaration inner) {
                String innerInternalName = outerInternalName + "$" + inner.name();
                int innerAccess = innerAccessForNested(inner, outerIsInterfaceLike);
                cw.visitInnerClass(innerInternalName, outerInternalName, inner.name(), innerAccess);
            }
        }
    }
}
