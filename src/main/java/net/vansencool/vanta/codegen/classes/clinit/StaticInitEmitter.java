package net.vansencool.vanta.codegen.classes.clinit;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.StatementGenerator;
import net.vansencool.vanta.codegen.classes.visitor.RecordingMethodVisitor;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.scope.Scope;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.Set;

/**
 * Emits {@code <clinit>} static initializers for class, interface, and record
 * bodies. Collapses the three near-duplicate clinit setup patterns that used
 * to live inline in {@link ClassGenerator}: configuring a
 * {@link MethodContext}, iterating static-field initializers (skipping
 * compile-time constants), and running any user-authored static blocks.
 */
public final class StaticInitEmitter {

    private final @NotNull ClassGenerator owner;

    /**
     * Binds this emitter to the owning generator so nested-class caches,
     * lambda/anon counters, and enclosing-outer state can be read directly
     * instead of threaded through each call.
     *
     * @param owner class generator the emitter cooperates with
     */
    public StaticInitEmitter(@NotNull ClassGenerator owner) {
        this.owner = owner;
    }

    /**
     * Emits a {@code <clinit>} for a regular class or record only when the
     * declaration actually has static state that needs initialising at runtime
     * (non-constant field initializer or static block).
     *
     * @param cw            target class writer
     * @param classDecl     class declaration whose static initializers are emitted
     * @param internalName  internal name of the owning class
     * @param superInternal internal name of the super class (used for MethodContext)
     * @param fieldTypes    resolved types of declared fields on the owning class
     * @param selfMethods   self-method table for resolving same-class calls
     */
    public void emitClassClinit(@NotNull ClassWriter cw, @NotNull ClassDeclaration classDecl, @NotNull String internalName, @NotNull String superInternal, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Set<String> staticFieldNames, @NotNull Map<String, SelfMethodInfo> selfMethods) {
        if (!owner.hasStaticFieldInitOrBlock(classDecl)) return;
        RecordingMethodVisitor buffer = new RecordingMethodVisitor();
        buffer.visitCode();
        ClinitRig rig = buildRig(cw, buffer, internalName, superInternal, fieldTypes, staticFieldNames, selfMethods);
        emitStaticFieldAndBlocks(classDecl, internalName, buffer, rig);
        finishAndFlush(cw, buffer);
    }

    /**
     * Emits a {@code <clinit>} for an interface body. Only fires when at least
     * one non-constant field initializer exists, since constant-valued fields
     * are lowered via the {@code ConstantValue} attribute at declaration
     * time.
     *
     * @param cw           target class writer
     * @param classDecl    interface declaration whose fields are scanned
     * @param internalName internal name of the interface
     * @param fieldTypes   resolved types of declared fields
     * @param selfMethods  self-method table for same-class calls
     */
    public void emitInterfaceClinit(@NotNull ClassWriter cw, @NotNull ClassDeclaration classDecl, @NotNull String internalName, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Set<String> staticFieldNames, @NotNull Map<String, SelfMethodInfo> selfMethods) {
        boolean needsClinit = false;
        for (AstNode m : classDecl.members()) {
            if (m instanceof FieldDeclaration fd) {
                for (FieldDeclarator d : fd.declarators()) {
                    if (d.initializer() != null && owner.constantFolder().resolveConstantValue(fd, d) == null) {
                        needsClinit = true;
                        break;
                    }
                }
            }
            if (needsClinit) break;
        }
        if (!needsClinit) return;

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        ClinitRig rig = buildRig(cw, mv, internalName, "java/lang/Object", fieldTypes, staticFieldNames, selfMethods);

        for (AstNode m : classDecl.members()) {
            if (!(m instanceof FieldDeclaration fd)) continue;
            for (FieldDeclarator d : fd.declarators()) {
                if (d.initializer() == null || owner.constantFolder().resolveConstantValue(fd, d) != null) continue;
                TypeNode eff = fd.type();
                if (d.extraArrayDimensions() > 0) eff = eff.withExtraDimensions(d.extraArrayDimensions());
                ResolvedType resolved = owner.typeResolver().resolve(eff);
                rig.exprGen.generate(d.initializer(), resolved);
                rig.exprGen.numericCoercion().adaptForStore(resolved, d.initializer());
                mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, d.name(), resolved.descriptor());
            }
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Allocates and wires up a {@link MethodContext} + matching
     * {@link ExpressionGenerator}/{@link StatementGenerator} pair for a
     * static initializer. Consolidates the state-copying boilerplate shared
     * by class/record/interface clinits.
     *
     * @param cw            class writer the clinit is being emitted onto
     * @param mv            method visitor (real or buffered) receiving bytecode
     * @param internalName  internal name of the owning class
     * @param superInternal super-class internal name
     * @param fieldTypes    resolved types of declared fields
     * @param selfMethods   self-method table for same-class calls
     * @return ready-to-use emitter bundle
     */
    private @NotNull ClinitRig buildRig(@NotNull ClassWriter cw, @NotNull MethodVisitor mv, @NotNull String internalName, @NotNull String superInternal, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Set<String> staticFieldNames, @NotNull Map<String, SelfMethodInfo> selfMethods) {
        MethodContext ctx = new MethodContext(mv, new Scope(0), owner.typeResolver(), new MethodResolver(owner.classpathManager()), internalName, superInternal, true, selfMethods);
        ctx.enclosingOuterInternal(owner.currentEnclosingOuter());
        ctx.enclosingStaticOuter(owner.currentStaticOuter());
        ctx.nestedClassFields(owner.nestedClassFields());
        ctx.nestedClassMethods(owner.nestedClassMethods());
        ctx.nestedClassConstants(owner.nestedClassConstants());
        ctx.setupLambdaSupport(cw, owner.lambdaCounter(), "<clinit>");
        ctx.setupAnonClassSupport(owner, cw, owner.anonClassCounter(), "<clinit>", owner.anonClassBytecodes(), owner.anonClassNames());
        for (Map.Entry<String, ResolvedType> e : fieldTypes.entrySet()) {
            ctx.typeInferrer().registerField(e.getKey(), e.getValue(), staticFieldNames.contains(e.getKey()));
        }
        ExpressionGenerator exprGen = new ExpressionGenerator(ctx);
        StatementGenerator stmtGen = new StatementGenerator(ctx, exprGen);
        return new ClinitRig(ctx, exprGen, stmtGen);
    }

    /**
     * Runs each non-constant static field initializer and each static block
     * in source order onto {@code buffer}.
     *
     * @param classDecl    class declaration being scanned
     * @param internalName internal name used for {@code PUTSTATIC}
     * @param buffer       buffered method visitor receiving bytecode
     * @param rig          ready expression/statement generators bound to the clinit
     */
    private void emitStaticFieldAndBlocks(@NotNull ClassDeclaration classDecl, @NotNull String internalName, @NotNull RecordingMethodVisitor buffer, @NotNull ClinitRig rig) {
        for (AstNode member : classDecl.members()) {
            if (member instanceof FieldDeclaration fd && (fd.modifiers() & Opcodes.ACC_STATIC) != 0) {
                for (FieldDeclarator declarator : fd.declarators()) {
                    if (declarator.initializer() == null || owner.constantFolder().resolveConstantValue(fd, declarator) != null)
                        continue;
                    TypeNode effectiveType = fd.type();
                    if (declarator.extraArrayDimensions() > 0) {
                        effectiveType = effectiveType.withExtraDimensions(declarator.extraArrayDimensions());
                    }
                    ResolvedType resolved = owner.typeResolver().resolve(effectiveType);
                    rig.exprGen.generate(declarator.initializer(), resolved);
                    rig.exprGen.numericCoercion().adaptForStore(resolved, declarator.initializer());
                    buffer.visitFieldInsn(Opcodes.PUTSTATIC, internalName, declarator.name(), resolved.descriptor());
                }
            } else if (member instanceof BlockStatement staticBlock) {
                for (Statement stmt : staticBlock.statements()) {
                    rig.stmtGen.generate(stmt);
                }
            }
        }
    }

    /**
     * Closes out the buffered clinit with RETURN/maxs/end and replays it onto
     * a fresh {@code <clinit>} MethodVisitor on {@code cw}.
     *
     * @param cw     target class writer
     * @param buffer recording visitor holding the clinit body
     */
    private void finishAndFlush(@NotNull ClassWriter cw, @NotNull RecordingMethodVisitor buffer) {
        buffer.visitInsn(Opcodes.RETURN);
        buffer.visitMaxs(0, 0);
        buffer.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        buffer.replayInto(mv);
    }

    /**
     * Bundles the {@link MethodContext} and generator pair used for a single
     * clinit emission so {@link #buildRig} can return all three in one go.
     */
    private record ClinitRig(@NotNull MethodContext ctx, @NotNull ExpressionGenerator exprGen,
                             @NotNull StatementGenerator stmtGen) {
    }
}
