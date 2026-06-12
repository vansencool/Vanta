package net.vansencool.vanta.codegen.classes.bridge;

import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

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
    private final @NotNull TypeRegistry registry;

    public BridgeMethodEmitter(@NotNull TypeResolver typeResolver, @NotNull TypeRegistry registry) {
        this.typeResolver = typeResolver;
        this.registry = registry;
    }

    public void emit(@NotNull ClassWriter cw, @NotNull ClassDeclaration classDecl, @NotNull String internalName) {
        List<String> supers = new ArrayList<>();
        if (classDecl.superClass() != null) supers.add(typeResolver.resolveInternalName(classDecl.superClass()));
        else supers.add("java/lang/Object");
        for (TypeNode iface : classDecl.interfaces()) supers.add(typeResolver.resolveInternalName(iface));
        emitForMembers(cw, classDecl.members(), internalName, supers);
    }

    public void emitForMembers(@NotNull ClassWriter cw, @NotNull List<AstNode> members, @NotNull String internalName, @NotNull List<String> supers) {
        Set<String> emitted = new HashSet<>();
        Object entryScope = typeResolver.captureScope();
        for (AstNode member : members) {
            if (!(member instanceof MethodDeclaration md)) continue;
            boolean hasTypeParams = md.typeParameters() != null && !md.typeParameters().isEmpty();
            if (hasTypeParams) {
                typeResolver.adoptScope(entryScope);
                typeResolver.registerTypeParameters(md.typeParameters());
            }
            List<TypeNode> paramTypes = new ArrayList<>();
            for (Parameter p : md.parameters()) paramTypes.add(p.type());
            emitted.add(md.name() + typeResolver.methodDescriptor(paramTypes, md.returnType()));
            if (hasTypeParams) typeResolver.adoptScope(entryScope);
        }
        for (AstNode member : members) {
            if (!(member instanceof MethodDeclaration md)) continue;
            if ("<init>".equals(md.name()) || "<clinit>".equals(md.name())) continue;
            if ((md.modifiers() & Opcodes.ACC_STATIC) != 0) continue;
            if ((md.modifiers() & Opcodes.ACC_PRIVATE) != 0) continue;
            boolean hasTypeParams = md.typeParameters() != null && !md.typeParameters().isEmpty();
            if (hasTypeParams) {
                typeResolver.adoptScope(entryScope);
                typeResolver.registerTypeParameters(md.typeParameters());
            }
            List<TypeNode> paramTypes = new ArrayList<>();
            for (Parameter p : md.parameters()) paramTypes.add(p.type());
            String myReturnDesc = typeResolver.resolveDescriptor(md.returnType());
            String myDesc = typeResolver.methodDescriptor(paramTypes, md.returnType());
            if (hasTypeParams) typeResolver.adoptScope(entryScope);
            for (BridgeTarget bt : collectBridgeTargets(supers, md.name(), paramTypes.size(), myReturnDesc, myDesc)) {
                String key = md.name() + bt.descriptor();
                if (!emitted.add(key)) continue;
                emitBridge(cw, internalName, md.name(), myDesc, bt.descriptor());
            }
        }
    }

    private void emitBridge(@NotNull ClassWriter cw, @NotNull String internalName, @NotNull String methodName, @NotNull String targetDesc, @NotNull String bridgeDesc) {
        MethodVisitor bmv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC, methodName, bridgeDesc, null, null);
        bmv.visitCode();
        bmv.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        Type[] bridgeParams = Type.getArgumentTypes(bridgeDesc);
        Type[] targetParams = Type.getArgumentTypes(targetDesc);
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
        bmv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalName, methodName, targetDesc, false);
        Type myReturn = Type.getReturnType(targetDesc);
        bmv.visitInsn(OpcodeUtils.returnOpcodeForDescriptor(myReturn.getDescriptor()));
        bmv.visitMaxs(0, 0);
        bmv.visitEnd();
    }

    private @NotNull List<BridgeTarget> collectBridgeTargets(@NotNull List<String> supers, @NotNull String methodName, int paramCount, @NotNull String myReturnDesc, @NotNull String myDesc) {
        List<BridgeTarget> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(myDesc);
        Set<String> visited = new HashSet<>();
        for (String superInternal : supers) {
            collectFrom(superInternal, methodName, paramCount, myReturnDesc, myDesc, seen, result, visited);
        }
        return result;
    }

    private void collectFrom(@NotNull String ownerInternal, @NotNull String methodName, int paramCount, @NotNull String myReturnDesc, @NotNull String myDesc, @NotNull Set<String> seen, @NotNull List<BridgeTarget> out, @NotNull Set<String> visited) {
        if (!visited.add(ownerInternal)) return;
        TypeSymbol owner = registry.lookup(ownerInternal);
        if (owner == null) return;
        for (MethodSymbol m : owner.methods()) {
            if (!m.name().equals(methodName)) continue;
            if (m.isStatic()) continue;
            if ((m.access() & Opcodes.ACC_PRIVATE) != 0) continue;
            if (m.parameterTypes().size() != paramCount) continue;
            String desc = m.descriptor();
            if (desc.equals(myDesc) || seen.contains(desc)) continue;
            if (isBridgeOverride(myDesc, desc, m)) {
                seen.add(desc);
                out.add(new BridgeTarget(desc));
            }
        }
        TypeSymbol sup = owner.superclass();
        if (sup != null)
            collectFrom(sup.internalName(), methodName, paramCount, myReturnDesc, myDesc, seen, out, visited);
        for (TypeSymbol iface : owner.interfaces()) {
            collectFrom(iface.internalName(), methodName, paramCount, myReturnDesc, myDesc, seen, out, visited);
        }
    }

    private boolean isBridgeOverride(@NotNull String childDesc, @NotNull String parentDesc, @NotNull MethodSymbol parent) {
        Type[] cp = Type.getArgumentTypes(childDesc);
        Type[] pp = Type.getArgumentTypes(parentDesc);
        if (cp.length != pp.length) return false;
        List<TypeRef> parentParams = parent.parameterTypes();
        boolean anyDiff = false;
        for (int i = 0; i < cp.length; i++) {
            String c = cp[i].getDescriptor();
            String p = pp[i].getDescriptor();
            if (c.equals(p)) continue;
            if (i >= parentParams.size() || !containsTypeVariable(parentParams.get(i))) return false;
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

    private static boolean containsTypeVariable(@NotNull TypeRef ref) {
        if (ref.isTypeVariable()) return true;
        for (TypeRef ta : ref.typeArguments()) {
            if (containsTypeVariable(ta)) return true;
        }
        return false;
    }

    private boolean isReferenceAssignable(@NotNull String childDesc, @NotNull String parentDesc) {
        if ("Ljava/lang/Object;".equals(parentDesc)) return true;
        if (childDesc.equals(parentDesc)) return true;
        String ci = childDesc.startsWith("L") ? childDesc.substring(1, childDesc.length() - 1) : null;
        String pi = parentDesc.startsWith("L") ? parentDesc.substring(1, parentDesc.length() - 1) : null;
        if (ci == null || pi == null) return false;
        return walkAssignable(ci, pi, new HashSet<>());
    }

    private boolean walkAssignable(@NotNull String childInternal, @NotNull String parentInternal, @NotNull Set<String> visited) {
        if (childInternal.equals(parentInternal)) return true;
        if (!visited.add(childInternal)) return false;
        TypeSymbol child = registry.lookup(childInternal);
        if (child == null) return false;
        TypeSymbol sup = child.superclass();
        if (sup != null && walkAssignable(sup.internalName(), parentInternal, visited)) return true;
        for (TypeSymbol iface : child.interfaces()) {
            if (walkAssignable(iface.internalName(), parentInternal, visited)) return true;
        }
        return false;
    }

    private record BridgeTarget(@NotNull String descriptor) {
    }
}
