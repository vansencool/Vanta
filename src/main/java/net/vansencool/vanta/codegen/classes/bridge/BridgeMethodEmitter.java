package net.vansencool.vanta.codegen.classes.bridge;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.classpath.AsmClassInfo.MethodInfo;
import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.TypeResolver;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Emits bridge methods that forward covariant / erased overrides to their
 * declared target. Walks each method's super and interface chain looking for
 * signatures that share a name and arity but differ in parameter or return
 * erasure, then synthesises a {@code ACC_BRIDGE | ACC_SYNTHETIC} forwarder
 * that calls the real method.
 */
public final class BridgeMethodEmitter {

    private final @NotNull TypeResolver typeResolver;
    private final @NotNull ClasspathManager classpathManager;

    /**
     * Creates an emitter bound to the surrounding resolver and classpath.
     *
     * @param typeResolver     resolves source type nodes to internal names
     * @param classpathManager loads super/interface classes and their methods
     */
    public BridgeMethodEmitter(@NotNull TypeResolver typeResolver, @NotNull ClasspathManager classpathManager) {
        this.typeResolver = typeResolver;
        this.classpathManager = classpathManager;
    }

    /**
     * Emits bridges for every overridable method of {@code classDecl} against
     * its declared super and interfaces.
     *
     * @param cw           class writer for the owner
     * @param classDecl    the declaration whose overrides need bridging
     * @param internalName internal name of the owner class
     */
    public void emit(@NotNull ClassWriter cw, @NotNull ClassDeclaration classDecl, @NotNull String internalName) {
        List<String> supers = new ArrayList<>();
        if (classDecl.superClass() != null) supers.add(typeResolver.resolveInternalName(classDecl.superClass()));
        else supers.add("java/lang/Object");
        for (TypeNode iface : classDecl.interfaces()) supers.add(typeResolver.resolveInternalName(iface));
        emitForMembers(cw, classDecl.members(), internalName, supers);
    }

    /**
     * Emits bridge methods for every overridable method in {@code members}.
     * Supports anonymous-class bodies directly by letting callers pass the
     * appropriate super/interface internal names.
     *
     * @param cw           target class writer
     * @param members      candidate member nodes (only {@link MethodDeclaration}s are considered)
     * @param internalName owner class's internal name
     * @param supers       internal names of the owner's direct super and interfaces
     */
    public void emitForMembers(@NotNull ClassWriter cw, @NotNull List<AstNode> members, @NotNull String internalName, @NotNull List<String> supers) {
        Set<String> emitted = new HashSet<>();
        for (AstNode member : members) {
            if (!(member instanceof MethodDeclaration md)) continue;
            if ("<init>".equals(md.name()) || "<clinit>".equals(md.name())) continue;
            if ((md.modifiers() & Opcodes.ACC_STATIC) != 0) continue;
            if ((md.modifiers() & Opcodes.ACC_PRIVATE) != 0) continue;
            List<TypeNode> paramTypes = new ArrayList<>();
            for (Parameter p : md.parameters()) paramTypes.add(p.type());
            String myReturnDesc = typeResolver.resolveDescriptor(md.returnType());
            String myDesc = typeResolver.methodDescriptor(paramTypes, md.returnType());
            for (BridgeTarget bt : collectBridgeTargets(supers, md.name(), paramTypes.size(), myReturnDesc, myDesc)) {
                String key = md.name() + bt.descriptor();
                if (!emitted.add(key)) continue;
                MethodVisitor bmv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC, md.name(), bt.descriptor(), null, null);
                bmv.visitCode();
                bmv.visitVarInsn(Opcodes.ALOAD, 0);
                int slot = 1;
                Type[] bridgeParams = Type.getArgumentTypes(bt.descriptor());
                Type[] targetParams = Type.getArgumentTypes(myDesc);
                for (int i = 0; i < bridgeParams.length; i++) {
                    bmv.visitVarInsn(OpcodeUtils.loadOpcodeForDescriptor(bridgeParams[i].getDescriptor()), slot);
                    slot += OpcodeUtils.descriptorStackSize(bridgeParams[i].getDescriptor());
                    int targetSort = targetParams[i].getSort();
                    boolean needsCheckcast = targetSort == Type.OBJECT || targetSort == Type.ARRAY;
                    if (needsCheckcast && !bridgeParams[i].getDescriptor().equals(targetParams[i].getDescriptor())) {
                        String castTo = targetSort == Type.ARRAY ? targetParams[i].getDescriptor() : targetParams[i].getInternalName();
                        bmv.visitTypeInsn(Opcodes.CHECKCAST, castTo);
                    }
                }
                bmv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalName, md.name(), myDesc, false);
                Type myReturn = Type.getReturnType(myDesc);
                bmv.visitInsn(OpcodeUtils.returnOpcodeForDescriptor(myReturn.getDescriptor()));
                bmv.visitMaxs(0, 0);
                bmv.visitEnd();
            }
        }
    }

    /**
     * Gathers every distinct bridge descriptor that needs a forwarder for the
     * given source-declared signature, walking across all {@code supers}.
     *
     * @param supers       internal names of candidate super/interface owners
     * @param methodName   source method name
     * @param paramCount   source method arity
     * @param myReturnDesc declared return descriptor on source method
     * @param myDesc       full source descriptor (used to skip identity matches)
     * @return distinct bridge targets covering every erased/covariant variant
     */
    private @NotNull List<BridgeTarget> collectBridgeTargets(@NotNull List<String> supers, @NotNull String methodName, int paramCount, @NotNull String myReturnDesc, @NotNull String myDesc) {
        List<BridgeTarget> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(myDesc);
        for (String superInternal : supers) {
            collectBridgesFrom(superInternal, methodName, paramCount, myReturnDesc, myDesc, seen, result);
        }
        return result;
    }

    /**
     * Recursive hierarchy walker used by {@link #collectBridgeTargets}.
     * Consults reflection first, falling back to ASM {@link AsmClassInfo} when
     * a class can't be loaded by the classpath.
     *
     * @param ownerInternal current owner being inspected
     * @param methodName    source method name to match
     * @param paramCount    required arity
     * @param myReturnDesc  return descriptor of the source method
     * @param myDesc        full descriptor of the source method
     * @param seen          descriptors already emitted or rejected
     * @param out           accumulator for new bridge targets
     */
    private void collectBridgesFrom(@NotNull String ownerInternal, @NotNull String methodName, int paramCount, @NotNull String myReturnDesc, @NotNull String myDesc, @NotNull Set<String> seen, @NotNull List<BridgeTarget> out) {
        Class<?> clazz = classpathManager.loadClass(ownerInternal);
        if (clazz != null) {
            try {
                for (Class<?> walk = clazz; walk != null; walk = walk.getSuperclass()) {
                    for (Method m : walk.getDeclaredMethods()) {
                        if (!m.getName().equals(methodName)) continue;
                        if (m.getParameterCount() != paramCount) continue;
                        int mods = m.getModifiers();
                        if (Modifier.isStatic(mods) || Modifier.isPrivate(mods)) continue;
                        String desc = classpathManager.methodDescriptor(m);
                        if (desc.equals(myDesc)) continue;
                        if (seen.contains(desc)) continue;
                        if (isBridgeOverride(myDesc, desc)) {
                            seen.add(desc);
                            out.add(new BridgeTarget(desc));
                        }
                    }
                }
                for (Class<?> iface : clazz.getInterfaces()) {
                    collectBridgesFrom(iface.getName().replace('.', '/'), methodName, paramCount, myReturnDesc, myDesc, seen, out);
                }
                return;
            } catch (LinkageError ignored) {
            }
        }
        AsmClassInfo info = classpathManager.asmClassInfo(ownerInternal);
        if (info == null) return;
        for (MethodInfo m : info.methods()) {
            if (!m.name().equals(methodName)) continue;
            if (m.isStatic()) continue;
            if (Type.getArgumentTypes(m.descriptor()).length != paramCount) continue;
            String desc = m.descriptor();
            if (desc.equals(myDesc) || seen.contains(desc)) continue;
            String otherReturn = desc.substring(desc.indexOf(')') + 1);
            if (myReturnDesc.equals(otherReturn)) continue;
            if (otherReturn.startsWith("L") && myReturnDesc.startsWith("L")) {
                seen.add(desc);
                out.add(new BridgeTarget(desc));
            }
        }
        if (info.superInternalName() != null)
            collectBridgesFrom(info.superInternalName(), methodName, paramCount, myReturnDesc, myDesc, seen, out);
        for (String iface : info.interfaceInternalNames())
            collectBridgesFrom(iface, methodName, paramCount, myReturnDesc, myDesc, seen, out);
    }

    /**
     * True when {@code childDesc} is a legal erased/covariant override of
     * {@code parentDesc}: parameter types are contravariantly assignable and
     * the return type is covariantly assignable. Rejects pure overloads where
     * neither descriptor is an erased view of the other.
     *
     * @param childDesc  descriptor of the source-declared method
     * @param parentDesc descriptor of the candidate parent method
     * @return true when a bridge from {@code parentDesc} to {@code childDesc} is valid
     */
    private boolean isBridgeOverride(@NotNull String childDesc, @NotNull String parentDesc) {
        Type[] cp = Type.getArgumentTypes(childDesc);
        Type[] pp = Type.getArgumentTypes(parentDesc);
        if (cp.length != pp.length) return false;
        boolean anyDiff = false;
        for (int i = 0; i < cp.length; i++) {
            String c = cp[i].getDescriptor();
            String p = pp[i].getDescriptor();
            if (c.equals(p)) continue;
            if (!(p.startsWith("L") || p.startsWith("["))) return false;
            if (!(c.startsWith("L") || c.startsWith("["))) return false;
            if (!isReferenceAssignable(c, p)) return false;
            anyDiff = true;
        }
        String cr = Type.getReturnType(childDesc).getDescriptor();
        String pr = Type.getReturnType(parentDesc).getDescriptor();
        if (!cr.equals(pr)) {
            if (!(cr.startsWith("L") || cr.startsWith("["))) return false;
            if (!(pr.startsWith("L") || pr.startsWith("["))) return false;
            if (!isReferenceAssignable(cr, pr)) return false;
            anyDiff = true;
        }
        return anyDiff;
    }

    /**
     * True when a reference type {@code childDesc} can be stored into a
     * variable typed {@code parentDesc}, via reflection first and ASM
     * fallback.
     *
     * @param childDesc  child JVM reference descriptor (starts with {@code L} or {@code [})
     * @param parentDesc parent JVM reference descriptor
     * @return true when {@code child} is a subtype of {@code parent}
     */
    private boolean isReferenceAssignable(@NotNull String childDesc, @NotNull String parentDesc) {
        if ("Ljava/lang/Object;".equals(parentDesc)) return true;
        if (childDesc.equals(parentDesc)) return true;
        String ci = childDesc.startsWith("L") ? childDesc.substring(1, childDesc.length() - 1) : null;
        String pi = parentDesc.startsWith("L") ? parentDesc.substring(1, parentDesc.length() - 1) : null;
        if (ci == null || pi == null) return false;
        Class<?> c = classpathManager.loadClass(ci);
        Class<?> p = classpathManager.loadClass(pi);
        if (c != null && p != null) {
            try {
                return p.isAssignableFrom(c);
            } catch (LinkageError ignored) {
            }
        }
        return walkAsmAssignable(ci, pi);
    }

    /**
     * ASM-driven subtype check used when reflection can't load either class.
     * Walks the super + interface chain looking for {@code parentInternal}.
     *
     * @param childInternal  candidate subtype's internal name
     * @param parentInternal candidate supertype's internal name
     * @return true when {@code parentInternal} appears anywhere above {@code childInternal}
     */
    private boolean walkAsmAssignable(@NotNull String childInternal, @NotNull String parentInternal) {
        if (childInternal.equals(parentInternal)) return true;
        AsmClassInfo info = classpathManager.asmClassInfo(childInternal);
        if (info == null) return false;
        if (info.superInternalName() != null && walkAsmAssignable(info.superInternalName(), parentInternal))
            return true;
        for (String iface : info.interfaceInternalNames()) if (walkAsmAssignable(iface, parentInternal)) return true;
        return false;
    }

    /**
     * Descriptor-only record distinguishing a single bridge target: all
     * other metadata (name, owner, source descriptor) is known from the
     * enclosing context.
     */
    private record BridgeTarget(@NotNull String descriptor) {
    }
}
