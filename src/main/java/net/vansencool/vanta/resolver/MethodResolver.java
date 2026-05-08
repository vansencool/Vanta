package net.vansencool.vanta.resolver;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.method.reflection.ReflectionMethodSymbol;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves method, field, and constructor references against the classpath
 * via {@link TypeRegistry}. Returns JVM descriptors, owner internal names,
 * and invoke opcodes the codegen needs.
 */
public record MethodResolver(@NotNull ClasspathManager classpathManager) {

    private static final ConcurrentHashMap<Long, Boolean> ASSIGNABLE_CACHE = new ConcurrentHashMap<>();

    public static boolean isDescriptorAssignable(@NotNull String argDesc, @NotNull String paramDesc) {
        return isAssignable(argDesc, paramDesc, null);
    }

    private static boolean isAssignable(@NotNull String argDesc, @NotNull String paramDesc, @Nullable TypeRegistry registry) {
        if (argDesc.equals(paramDesc)) return true;
        if (paramDesc.equals("Ljava/lang/Object;") && argDesc.startsWith("L")) return true;
        if (paramDesc.equals("Ljava/lang/Object;") && argDesc.startsWith("[")) return true;
        if (argDesc.length() == 1 && paramDesc.startsWith("L")) {
            String boxed = primitiveBoxDescriptor(argDesc);
            if (boxed != null && isAssignable(boxed, paramDesc, registry)) return true;
        }
        if (paramDesc.length() == 1 && argDesc.startsWith("L")) {
            String boxed = primitiveBoxDescriptor(paramDesc);
            if (argDesc.equals(boxed)) return true;
        }
        if (argDesc.length() == 1 && paramDesc.length() == 1) {
            return isPrimitiveWidening(argDesc, paramDesc);
        }
        if (argDesc.startsWith("[") && paramDesc.startsWith("[")) {
            String argElem = argDesc.substring(1);
            String paramElem = paramDesc.substring(1);
            if (argElem.length() == 1 || paramElem.length() == 1) return argElem.equals(paramElem);
            return isAssignable(argElem, paramElem, registry);
        }
        if (argDesc.startsWith("[") && paramDesc.startsWith("L")) {
            return "Ljava/lang/Object;".equals(paramDesc) || "Ljava/lang/Cloneable;".equals(paramDesc) || "Ljava/io/Serializable;".equals(paramDesc);
        }
        if (!argDesc.startsWith("L") || !paramDesc.startsWith("L")) return false;
        long key = ((long) argDesc.hashCode() << 32) ^ (paramDesc.hashCode() & 0xffffffffL);
        Boolean cached = ASSIGNABLE_CACHE.get(key);
        if (cached != null) return cached;
        String argInternal = argDesc.substring(1, argDesc.length() - 1);
        String paramInternal = paramDesc.substring(1, paramDesc.length() - 1);
        boolean result = registry != null
                ? isSubtype(argInternal, paramInternal, registry)
                : forNameSubtype(argInternal, paramInternal);
        ASSIGNABLE_CACHE.put(key, result);
        return result;
    }

    /**
     * Walks the symbol super chain to decide whether {@code argInternal} is a
     * subtype of {@code paramInternal}. Used during overload resolution and
     * cast checks.
     */
    private static boolean isSubtype(@NotNull String argInternal, @NotNull String paramInternal, @NotNull TypeRegistry registry) {
        if (argInternal.equals(paramInternal)) return true;
        if ("java/lang/Object".equals(paramInternal)) return true;
        TypeSymbol arg = registry.lookup(argInternal);
        if (arg == null) return false;
        TypeSymbol superSym = arg.superclass();
        if (superSym != null) {
            if (superSym.internalName().equals(paramInternal)) return true;
            if (isSubtype(superSym.internalName(), paramInternal, registry)) return true;
        }
        for (TypeSymbol iface : arg.interfaces()) {
            if (iface.internalName().equals(paramInternal)) return true;
            if (isSubtype(iface.internalName(), paramInternal, registry)) return true;
        }
        return false;
    }

    private static boolean forNameSubtype(@NotNull String argInternal, @NotNull String paramInternal) {
        try {
            Class<?> paramClass = Class.forName(paramInternal.replace('/', '.'));
            Class<?> argClass = Class.forName(argInternal.replace('/', '.'));
            return paramClass.isAssignableFrom(argClass);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static @Nullable String primitiveBoxDescriptor(@NotNull String prim) {
        return switch (prim) {
            case "Z" -> "Ljava/lang/Boolean;";
            case "B" -> "Ljava/lang/Byte;";
            case "S" -> "Ljava/lang/Short;";
            case "C" -> "Ljava/lang/Character;";
            case "I" -> "Ljava/lang/Integer;";
            case "J" -> "Ljava/lang/Long;";
            case "F" -> "Ljava/lang/Float;";
            case "D" -> "Ljava/lang/Double;";
            default -> null;
        };
    }

    private static boolean isPrimitiveWidening(@NotNull String from, @NotNull String to) {
        if (from.equals(to)) return true;
        String order = "BSIJFD";
        int fi = order.indexOf(from);
        int ti = order.indexOf(to);
        if ("C".equals(from)) fi = order.indexOf("I") - 1;
        return fi >= 0 && ti > fi;
    }

    /**
     * Decides whether {@code mParams} is a more specific overload than
     * {@code bestParams}. Used during overload resolution to pick the most
     * specific applicable method when several apply.
     */
    private static boolean isMoreSpecific(@NotNull TypeRegistry registry, @NotNull List<TypeRef> bestParams, @NotNull List<TypeRef> mParams, @Nullable List<String> argDescs) {
        if (bestParams.size() != mParams.size()) return false;
        for (int i = 0; i < mParams.size(); i++) {
            TypeRef bp = bestParams.get(i);
            TypeRef mp = mParams.get(i);
            if (bp.descriptor().equals(mp.descriptor())) continue;
            if (bp.descriptor().equals("Ljava/lang/Object;") && mp.isPrimitive()) continue;
            if (bp.isPrimitive() && mp.isPrimitive()) {
                if (isPrimitiveWidening(mp.descriptor(), bp.descriptor())) continue;
                return false;
            }
            boolean argPrim = argDescs != null && i < argDescs.size() && argDescs.get(i) != null && argDescs.get(i).length() == 1;
            if (mp.isPrimitive()) {
                if (argPrim || argDescs == null) continue;
                return false;
            }
            if (bp.isPrimitive()) {
                if (argPrim || argDescs == null) return false;
                continue;
            }
            String bpInternal = bp.internalName();
            String mpInternal = mp.internalName();
            if (bpInternal != null && mpInternal != null && isSubtype(mpInternal, bpInternal, registry)) continue;
            return false;
        }
        return true;
    }

    public boolean isDescriptorAssignableWithClasspath(@NotNull String argDesc, @NotNull String paramDesc) {
        return isAssignable(argDesc, paramDesc, classpathManager.typeRegistry());
    }

    private boolean isAssignable(@NotNull String argDesc, @NotNull String paramDesc) {
        return isAssignable(argDesc, paramDesc, classpathManager.typeRegistry());
    }

    /**
     * Counts the arity of the single abstract method on a functional interface
     * symbol, or {@code -1} when the symbol is not a functional interface.
     */
    private int samArity(@NotNull TypeSymbol clazz) {
        if (!clazz.isInterface()) return -1;
        int abstractArity = -1;
        for (MethodSymbol m : clazz.methods()) {
            if (!m.isAbstract()) continue;
            if (m.isStatic()) continue;
            if (abstractArity >= 0) return -1;
            abstractArity = m.parameterTypes().size();
        }
        return abstractArity;
    }

    /**
     * Resolves a call narrowing on lambda or method reference argument
     * arities when plain overload resolution is ambiguous. Falls back to
     * {@link #resolveMethod(String, String, int)} when no lambda hints
     * narrow the choice.
     */
    public @Nullable ResolvedMethod resolveMethodWithLambdaHints(@NotNull String ownerInternal, @NotNull String methodName, int argCount, boolean @NotNull [] isLambdaArg, int @NotNull [] lambdaArities) {
        boolean anyLambda = false;
        for (boolean b : isLambdaArg) {
            if (b) {
                anyLambda = true;
                break;
            }
        }
        if (!anyLambda) return resolveMethod(ownerInternal, methodName, argCount);

        TypeRegistry registry = classpathManager.typeRegistry();
        TypeSymbol owner = registry.lookup(ownerInternal);
        if (owner == null) return resolveMethod(ownerInternal, methodName, argCount);

        ResolvedMethod best = null;
        MethodSymbol bestSymbol = null;
        for (MethodSymbol m : findMethods(owner, methodName, argCount, false)) {
            if (m.isBridge()) continue;
            if (m.parameterTypes().size() != argCount) continue;
            if (!matchesLambdaHints(m.parameterTypes(), isLambdaArg, lambdaArities, registry)) continue;
            ResolvedMethod candidate = buildResolved(m, owner, ownerInternal);
            if (best == null) {
                best = candidate;
                bestSymbol = m;
            } else if (isMoreSpecific(registry, bestSymbol.parameterTypes(), m.parameterTypes(), null)) {
                best = candidate;
                bestSymbol = m;
            }
        }
        return best != null ? best : resolveMethod(ownerInternal, methodName, argCount);
    }

    private boolean matchesLambdaHints(@NotNull List<TypeRef> params, boolean @NotNull [] isLambdaArg, int @NotNull [] lambdaArities, @NotNull TypeRegistry registry) {
        for (int i = 0; i < params.size(); i++) {
            if (!isLambdaArg[i]) continue;
            TypeRef p = params.get(i);
            if (p.internalName() == null || p.isPrimitive()) return false;
            TypeSymbol paramType = registry.lookup(p.internalName());
            if (paramType == null) return false;
            int sam = samArity(paramType);
            if (sam < 0) return false;
            if (lambdaArities[i] >= 0 && sam != lambdaArities[i]) return false;
        }
        return true;
    }

    public @Nullable ResolvedMethod resolveMethod(@NotNull String ownerInternal, @NotNull String methodName, int argCount) {
        TypeRegistry registry = classpathManager.typeRegistry();
        TypeSymbol owner = registry.lookup(ownerInternal);
        String effectiveOwner = ownerInternal;
        if (owner == null) {
            owner = registry.lookup("java/lang/Object");
            effectiveOwner = "java/lang/Object";
        }
        if (owner == null) return null;
        ResolvedMethod best = null;
        MethodSymbol bestSymbol = null;
        for (MethodSymbol m : findMethods(owner, methodName, argCount, true)) {
            if (m.isBridge()) continue;
            int paramCount = m.parameterTypes().size();
            boolean exact = paramCount == argCount;
            boolean varargs = m.isVarargs() && argCount >= paramCount - 1;
            if (!exact && !varargs) continue;
            ResolvedMethod candidate = buildResolved(m, owner, effectiveOwner);
            if (best == null) {
                best = candidate;
                bestSymbol = m;
            } else {
                boolean bestIsVararg = bestSymbol.isVarargs() && bestSymbol.parameterTypes().size() != argCount;
                boolean mIsVararg = m.isVarargs() && paramCount != argCount;
                if (bestIsVararg && !mIsVararg) {
                    best = candidate;
                    bestSymbol = m;
                } else if (!mIsVararg || bestIsVararg) {
                    if (bestSymbol.parameterTypes().size() == m.parameterTypes().size()) {
                        boolean mMore = isMoreSpecific(registry, bestSymbol.parameterTypes(), m.parameterTypes(), null);
                        boolean bMore = isMoreSpecific(registry, m.parameterTypes(), bestSymbol.parameterTypes(), null);
                        boolean replace = false;
                        if (mMore && !bMore) replace = true;
                        else if (mMore && isReturnAssignable(bestSymbol.returnType(), m.returnType(), registry)) replace = true;
                        if (replace) {
                            best = candidate;
                            bestSymbol = m;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Method lookup walks the owner symbol plus its super chain for instance
     * methods. When {@code includeObjectFallback} is true and the owner is an
     * interface, Object's methods join the candidate list (interfaces inherit
     * Object methods virtually).
     */
    private @NotNull List<MethodSymbol> findMethods(@NotNull TypeSymbol owner, @NotNull String name, int argCount, boolean includeObjectFallback) {
        ArrayList<MethodSymbol> out = new ArrayList<>();
        HashSet<String> seenSig = new HashSet<>();
        ArrayDeque<TypeSymbol> queue = new ArrayDeque<>();
        HashSet<String> visited = new HashSet<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            TypeSymbol type = queue.poll();
            if (!visited.add(type.internalName())) continue;
            for (MethodSymbol m : type.methods()) {
                if (!m.name().equals(name)) continue;
                String sig = m.descriptor() + "/" + m.isStatic();
                if (seenSig.add(sig)) out.add(m);
            }
            if (type.superclass() != null) queue.add(type.superclass());
            for (TypeSymbol iface : type.interfaces()) queue.add(iface);
        }
        if (includeObjectFallback && owner.isInterface()) {
            TypeRegistry registry = classpathManager.typeRegistry();
            TypeSymbol objectClass = registry.lookup("java/lang/Object");
            if (objectClass != null) {
                for (MethodSymbol m : objectClass.methods()) {
                    if (!m.name().equals(name)) continue;
                    if (m.isStatic()) continue;
                    if ((m.access() & Opcodes.ACC_PUBLIC) == 0) continue;
                    String sig = m.descriptor() + "/" + m.isStatic();
                    if (seenSig.add(sig)) out.add(m);
                }
            }
        }
        return out;
    }

    private @NotNull ResolvedMethod buildResolved(@NotNull MethodSymbol m, @NotNull TypeSymbol owner, @NotNull String emitOwnerInternal) {
        boolean ownerIsInterface = owner.isInterface();
        boolean isStatic = m.isStatic();
        String actualOwner;
        int opcode;
        boolean isInterface;
        if (isStatic) {
            opcode = Opcodes.INVOKESTATIC;
            actualOwner = m.owner().internalName();
            isInterface = m.owner().isInterface();
        } else if ("java/lang/Object".equals(m.owner().internalName()) && ownerIsInterface) {
            opcode = Opcodes.INVOKEVIRTUAL;
            actualOwner = "java/lang/Object";
            isInterface = false;
        } else {
            opcode = ownerIsInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            actualOwner = emitOwnerInternal;
            isInterface = ownerIsInterface;
            if ("java/lang/Object".equals(m.owner().internalName())) actualOwner = "java/lang/Object";
        }
        return new ResolvedMethod(actualOwner, m.name(), m.descriptor(), opcode, isInterface, m);
    }

    private boolean isReturnAssignable(@NotNull TypeRef bestReturn, @NotNull TypeRef candidateReturn, @NotNull TypeRegistry registry) {
        if (bestReturn.descriptor().equals(candidateReturn.descriptor())) return true;
        String bestInternal = bestReturn.internalName();
        String candidateInternal = candidateReturn.internalName();
        if (bestInternal == null || candidateInternal == null) return false;
        return isSubtype(candidateInternal, bestInternal, registry);
    }

    /**
     * Public entry to the declared-methods walk used for methods like
     * {@code Object.clone()} that are filtered out of the public method list.
     * Callers guard with context to avoid picking up private or synthetic
     * helpers when a real resolution would succeed.
     */
    public @Nullable ResolvedMethod resolveDeclaredProtected(@NotNull String ownerInternal, @NotNull String methodName, int argCount) {
        return resolveDeclaredMethodChain(ownerInternal, methodName, argCount, null);
    }

    /**
     * Walks declared methods up the super chain looking for a method matching
     * the name and arity, including non public ones. Used to find members
     * (like {@code Object.clone()}) that the public method walk filters out.
     */
    private @Nullable ResolvedMethod resolveDeclaredMethodChain(@NotNull String ownerInternal, @NotNull String methodName, int argCount, @Nullable List<String> argDescriptors) {
        TypeRegistry registry = classpathManager.typeRegistry();
        TypeSymbol owner = registry.lookup(ownerInternal);
        if (owner == null) return null;
        for (TypeSymbol walk = owner; walk != null; walk = walk.superclass()) {
            for (MethodSymbol m : walk.methods()) {
                if (!m.name().equals(methodName)) continue;
                int paramCount = m.parameterTypes().size();
                if (paramCount != argCount && !m.isVarargs()) continue;
                if (argDescriptors != null && paramCount == argCount) {
                    boolean ok = true;
                    for (int i = 0; i < paramCount; i++) {
                        if (!isAssignable(argDescriptors.get(i), m.parameterTypes().get(i).descriptor(), registry)) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) continue;
                }
                if ((m.access() & Opcodes.ACC_PRIVATE) != 0) continue;
                int opcode = m.isStatic() ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
                String actualOwner = m.owner().internalName();
                return new ResolvedMethod(actualOwner, m.name(), m.descriptor(), opcode, false, m);
            }
        }
        return null;
    }

    public @Nullable ResolvedMethod resolveMethod(@NotNull String ownerInternal, @NotNull String methodName, @NotNull List<String> argDescriptors) {
        TypeRegistry registry = classpathManager.typeRegistry();
        TypeSymbol owner = registry.lookup(ownerInternal);
        String effectiveOwner = ownerInternal;
        if (owner == null) {
            owner = registry.lookup("java/lang/Object");
            effectiveOwner = "java/lang/Object";
        }
        if (owner == null) return null;
        ResolvedMethod best = null;
        MethodSymbol bestSymbol = null;
        for (MethodSymbol m : findMethods(owner, methodName, argDescriptors.size(), true)) {
            int paramCount = m.parameterTypes().size();
            boolean exact = paramCount == argDescriptors.size();
            boolean varargs = m.isVarargs() && argDescriptors.size() >= paramCount - 1;
            if (!exact && !varargs) continue;
            if (!isApplicable(m, argDescriptors, registry)) continue;
            ResolvedMethod candidate = buildResolved(m, owner, effectiveOwner);
            if (best == null) {
                best = candidate;
                bestSymbol = m;
            } else {
                boolean bestIsVararg = bestSymbol.isVarargs() && bestSymbol.parameterTypes().size() != argDescriptors.size();
                boolean mIsVararg = m.isVarargs() && paramCount != argDescriptors.size();
                if (bestIsVararg && !mIsVararg) {
                    best = candidate;
                    bestSymbol = m;
                } else if (!mIsVararg || bestIsVararg) {
                    boolean mMore = isMoreSpecific(registry, bestSymbol.parameterTypes(), m.parameterTypes(), argDescriptors);
                    boolean bMore = isMoreSpecific(registry, m.parameterTypes(), bestSymbol.parameterTypes(), argDescriptors);
                    boolean replace = false;
                    if (mMore && !bMore) replace = true;
                    else if (mMore && isReturnAssignable(bestSymbol.returnType(), m.returnType(), registry)) replace = true;
                    if (replace) {
                        best = candidate;
                        bestSymbol = m;
                    }
                }
            }
        }
        if (best != null) return best;
        return resolveMethod(ownerInternal, methodName, argDescriptors.size());
    }

    private boolean isApplicable(@NotNull MethodSymbol m, @NotNull List<String> argDescriptors, @NotNull TypeRegistry registry) {
        List<TypeRef> params = m.parameterTypes();
        boolean varargMatchesAsArray = false;
        if (m.isVarargs() && argDescriptors.size() == params.size()) {
            boolean ok = true;
            for (int i = 0; i < params.size(); i++) {
                if (!isAssignable(argDescriptors.get(i), params.get(i).descriptor(), registry)) {
                    ok = false;
                    break;
                }
            }
            if (ok) varargMatchesAsArray = true;
        }
        if (varargMatchesAsArray) return true;
        int checkCount = m.isVarargs() ? Math.min(params.size() - 1, argDescriptors.size()) : params.size();
        for (int i = 0; i < checkCount; i++) {
            if (i >= argDescriptors.size()) return false;
            if (!isAssignable(argDescriptors.get(i), params.get(i).descriptor(), registry)) return false;
        }
        if (m.isVarargs()) {
            String elemDesc = params.get(params.size() - 1).descriptor().substring(1);
            for (int i = params.size() - 1; i < argDescriptors.size(); i++) {
                if (!isAssignable(argDescriptors.get(i), elemDesc, registry)) return false;
            }
        }
        return true;
    }

    /**
     * Resolves a constructor by argument count alone.
     */
    public @Nullable String resolveConstructor(@NotNull String ownerInternal, int argCount) {
        TypeRegistry registry = classpathManager.typeRegistry();
        TypeSymbol owner = registry.lookup(ownerInternal);
        if (owner == null) return null;
        for (MethodSymbol m : owner.methods()) {
            if (!"<init>".equals(m.name())) continue;
            if (m.parameterTypes().size() == argCount) return m.descriptor();
        }
        return null;
    }

    /**
     * Resolves a constructor with full argument descriptors. Falls back to
     * count based matching when no exact descriptor match exists.
     */
    public @Nullable String resolveConstructor(@NotNull String ownerInternal, @NotNull List<String> argDescriptors) {
        TypeRegistry registry = classpathManager.typeRegistry();
        TypeSymbol owner = registry.lookup(ownerInternal);
        if (owner == null) return null;
        MethodSymbol best = null;
        for (MethodSymbol m : owner.methods()) {
            if (!"<init>".equals(m.name())) continue;
            if ((m.access() & Opcodes.ACC_PRIVATE) != 0) continue;
            if (m.parameterTypes().size() != argDescriptors.size()) continue;
            if (!isApplicable(m, argDescriptors, registry)) continue;
            if (best == null) {
                best = m;
            } else if (isMoreSpecific(registry, best.parameterTypes(), m.parameterTypes(), argDescriptors)) {
                best = m;
            }
        }
        if (best != null) return best.descriptor();
        MethodSymbol bestVarArg = null;
        for (MethodSymbol m : owner.methods()) {
            if (!"<init>".equals(m.name())) continue;
            if ((m.access() & Opcodes.ACC_PRIVATE) != 0) continue;
            if (!m.isVarargs()) continue;
            int fixed = m.parameterTypes().size() - 1;
            if (argDescriptors.size() < fixed) continue;
            boolean ok = true;
            for (int i = 0; i < fixed; i++) {
                if (!isAssignable(argDescriptors.get(i), m.parameterTypes().get(i).descriptor(), registry)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            String elemDesc = m.parameterTypes().get(fixed).descriptor().substring(1);
            for (int i = fixed; i < argDescriptors.size(); i++) {
                if (!isAssignable(argDescriptors.get(i), elemDesc, registry)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            if (bestVarArg == null) bestVarArg = m;
        }
        if (bestVarArg != null) return bestVarArg.descriptor();
        return resolveConstructor(ownerInternal, argDescriptors.size());
    }

    /**
     * Resolves a field reference. Walks the symbol super chain so inherited
     * fields resolve too.
     */
    public @Nullable ResolvedField resolveField(@NotNull String ownerInternal, @NotNull String fieldName) {
        TypeRegistry registry = classpathManager.typeRegistry();
        TypeSymbol owner = registry.lookup(ownerInternal);
        if (owner == null) return null;
        for (TypeSymbol walk = owner; walk != null; walk = walk.superclass()) {
            for (FieldSymbol f : walk.fields()) {
                if (!f.name().equals(fieldName)) continue;
                String actualOwner = f.owner().internalName();
                return new ResolvedField(actualOwner, fieldName, f.type().descriptor(), f.isStatic());
            }
        }
        for (TypeSymbol iface : owner.interfaces()) {
            for (FieldSymbol f : iface.fields()) {
                if (!f.name().equals(fieldName)) continue;
                return new ResolvedField(iface.internalName(), fieldName, f.type().descriptor(), f.isStatic());
            }
        }
        return null;
    }

    /**
     * Result of resolving a method. The optional {@code symbol} carries
     * generic parameter info downstream (lambda SAM threading, generic
     * return narrowing).
     */
    public record ResolvedMethod(@NotNull String owner, @NotNull String name, @NotNull String descriptor, int opcode,
                                 boolean isInterface, @Nullable MethodSymbol symbol) {

        public ResolvedMethod(@NotNull String owner, @NotNull String name, @NotNull String descriptor, int opcode, boolean isInterface) {
            this(owner, name, descriptor, opcode, isInterface, null);
        }

        /**
         * Backward compatible reflective accessor. Returns the underlying
         * {@link java.lang.reflect.Method} when the symbol is reflection
         * backed, otherwise {@code null}. Callers should migrate to
         * {@link #symbol()}.
         */
        public @Nullable Method reflective() {
            if (symbol instanceof ReflectionMethodSymbol r) return r.reflective();
            return null;
        }
    }

    public record ResolvedField(@NotNull String owner, @NotNull String name, @NotNull String descriptor,
                                boolean isStatic) {
    }
}
