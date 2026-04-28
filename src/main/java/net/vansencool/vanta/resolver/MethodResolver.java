package net.vansencool.vanta.resolver;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.classpath.AsmClassInfo.MethodInfo;
import net.vansencool.vanta.classpath.ClasspathManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves method and field references against the classpath using reflection.
 * Returns proper JVM descriptors, owner types, and invoke opcodes.
 */
public record MethodResolver(@NotNull ClasspathManager classpathManager) {

    private static final ConcurrentHashMap<Long, Boolean> ASSIGNABLE_CACHE = new ConcurrentHashMap<>();

    public static boolean isDescriptorAssignable(@NotNull String argDesc, @NotNull String paramDesc) {
        return isAssignable(argDesc, paramDesc, null);
    }

    private static boolean isAssignable(@NotNull String argDesc, @NotNull String paramDesc, @Nullable ClasspathManager cp) {
        if (argDesc.equals(paramDesc)) return true;
        if (paramDesc.equals("Ljava/lang/Object;") && argDesc.startsWith("L")) return true;
        if (paramDesc.equals("Ljava/lang/Object;") && argDesc.startsWith("[")) return true;
        if (argDesc.length() == 1 && paramDesc.startsWith("L")) {
            String boxed = primitiveBoxDescriptor(argDesc);
            if (boxed != null && isAssignable(boxed, paramDesc, cp)) return true;
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
            return isAssignable(argElem, paramElem, cp);
        }
        if (argDesc.startsWith("[") && paramDesc.startsWith("L")) {
            return "Ljava/lang/Object;".equals(paramDesc) || "Ljava/lang/Cloneable;".equals(paramDesc) || "Ljava/io/Serializable;".equals(paramDesc);
        }
        if (!argDesc.startsWith("L") || !paramDesc.startsWith("L")) return false;
        long key = ((long) argDesc.hashCode() << 32) ^ (paramDesc.hashCode() & 0xffffffffL);
        Boolean cached = ASSIGNABLE_CACHE.get(key);
        if (cached != null) return cached;
        boolean result;
        String argInternal = argDesc.substring(1, argDesc.length() - 1);
        String paramInternal = paramDesc.substring(1, paramDesc.length() - 1);
        if (cp != null) {
            result = isAssignableViaClasspath(argInternal, paramInternal, cp);
        } else {
            try {
                Class<?> paramClass = Class.forName(paramInternal.replace('/', '.'));
                Class<?> argClass = Class.forName(argInternal.replace('/', '.'));
                result = paramClass.isAssignableFrom(argClass);
            } catch (ClassNotFoundException e) {
                result = false;
            }
        }
        ASSIGNABLE_CACHE.put(key, result);
        return result;
    }

    /**
     * Subtype check that consults the {@link ClasspathManager} so classes
     * compiled in-memory participate. Falls back to
     * {@link Class#forName} when the classpath has nothing for the name.
     */
    private static boolean isAssignableViaClasspath(@NotNull String argInternal, @NotNull String paramInternal, @NotNull ClasspathManager cp) {
        if (argInternal.equals(paramInternal)) return true;
        if ("java/lang/Object".equals(paramInternal)) return true;
        Class<?> param = cp.loadClass(paramInternal);
        Class<?> arg = cp.loadClass(argInternal);
        if (param != null && arg != null) {
            try {
                return param.isAssignableFrom(arg);
            } catch (LinkageError ignored) {
            }
        }
        AsmClassInfo info = cp.asmClassInfo(argInternal);
        if (info != null) {
            if (info.superInternalName() != null) {
                if (info.superInternalName().equals(paramInternal)) return true;
                if (isAssignableViaClasspath(info.superInternalName(), paramInternal, cp)) return true;
            }
            for (String i : info.interfaceInternalNames()) {
                if (i.equals(paramInternal)) return true;
                if (isAssignableViaClasspath(i, paramInternal, cp)) return true;
            }
        }
        return false;
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

    private static boolean isMoreSpecific(Class<?>[] bestParams, Class<?>[] mParams) {
        return isMoreSpecific(bestParams, mParams, null);
    }

    private static boolean isMoreSpecific(Class<?>[] bestParams, Class<?>[] mParams, @Nullable List<String> argDescs) {
        if (bestParams.length != mParams.length) return false;
        boolean mParamsMoreSpecific = true;
        for (int i = 0; i < mParams.length; i++) {
            Class<?> bp = bestParams[i];
            Class<?> mp = mParams[i];
            if (bp == mp) continue;
            if (bp == Object.class && mp.isPrimitive()) continue;
            if (bp.isPrimitive() && mp.isPrimitive()) {
                if (primitiveWidensTo(mp, bp)) continue;
                mParamsMoreSpecific = false;
                break;
            }
            boolean argPrim = argDescs != null && i < argDescs.size() && argDescs.get(i) != null && argDescs.get(i).length() == 1;
            if (mp.isPrimitive()) {
                if (argPrim || argDescs == null) continue;
                mParamsMoreSpecific = false;
                break;
            }
            if (bp.isPrimitive()) {
                if (argPrim || argDescs == null) {
                    mParamsMoreSpecific = false;
                    break;
                }
                continue;
            }
            if (bp.isAssignableFrom(mp)) continue;
            mParamsMoreSpecific = false;
            break;
        }
        return mParamsMoreSpecific;
    }

    private static boolean primitiveWidensTo(Class<?> from, Class<?> to) {
        if (from == to) return true;
        String order = "byte short int long float double";
        int fi = order.indexOf(from.getName()), ti = order.indexOf(to.getName());
        if (from == char.class) fi = order.indexOf("int") - 1;
        return fi >= 0 && ti > fi;
    }

    public boolean isDescriptorAssignableWithClasspath(@NotNull String argDesc, @NotNull String paramDesc) {
        return isAssignable(argDesc, paramDesc, classpathManager);
    }

    private boolean isAssignable(@NotNull String argDesc, @NotNull String paramDesc) {
        return isAssignable(argDesc, paramDesc, classpathManager);
    }

    /**
     * Counts the arity of the single abstract method (SAM) on a functional-interface class,
     * or {@code -1} if the class is not a functional interface.
     */
    private int samArity(@NotNull Class<?> clazz) {
        if (!clazz.isInterface()) return -1;
        int abstractArity = -1;
        for (Method m : classpathManager.cachedMethods(clazz)) {
            if (!Modifier.isAbstract(m.getModifiers())) continue;
            if (m.isDefault()) continue;
            if (abstractArity >= 0) return -1;
            abstractArity = m.getParameterCount();
        }
        return abstractArity;
    }

    /**
     * Resolves a method call disambiguating by lambda/method-ref argument arities when
     * the plain overload resolution is ambiguous (e.g. {@code schedule(Runnable)} vs
     * {@code schedule(Consumer<Runnable>)}). Falls back to {@link #resolveMethod(String, String, int)}
     * when no lambda hints narrow the choice.
     */
    public @Nullable ResolvedMethod resolveMethodWithLambdaHints(@NotNull String ownerInternal, @NotNull String methodName, int argCount, boolean @NotNull [] isLambdaArg, int @NotNull [] lambdaArities) {
        boolean anyLambda = false;
        for (boolean b : isLambdaArg)
            if (b) {
                anyLambda = true;
                break;
            }
        if (!anyLambda) return resolveMethod(ownerInternal, methodName, argCount);

        Class<?> clazz = classpathManager.loadClass(ownerInternal);
        if (clazz == null)
            return resolveMethodFromAsmWithLambdaHints(ownerInternal, methodName, argCount, isLambdaArg, lambdaArities);

        ResolvedMethod best = null;
        Method bestReflective = null;
        for (Method m : classpathManager.methodsNamed(clazz, methodName)) {
            if (m.isBridge()) continue;
            if (m.getParameterCount() != argCount) continue;
            Class<?>[] params = m.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < argCount; i++) {
                if (!isLambdaArg[i]) continue;
                int sam = samArity(params[i]);
                if (sam < 0) {
                    match = false;
                    break;
                }
                if (lambdaArities[i] >= 0 && sam != lambdaArities[i]) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            String descriptor = classpathManager.methodDescriptor(m);
            boolean isStatic = Modifier.isStatic(m.getModifiers());
            String actualOwner;
            int opcode;
            boolean isInterface;
            if (isStatic) {
                opcode = Opcodes.INVOKESTATIC;
                actualOwner = m.getDeclaringClass().getName().replace('.', '/');
                isInterface = m.getDeclaringClass().isInterface();
            } else {
                boolean ownerIsInterface = clazz.isInterface();
                opcode = ownerIsInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                actualOwner = ownerInternal;
                isInterface = ownerIsInterface;
                if (m.getDeclaringClass() == Object.class && !ownerIsInterface) actualOwner = "java/lang/Object";
            }
            ResolvedMethod candidate = new ResolvedMethod(actualOwner, methodName, descriptor, opcode, isInterface, m);
            if (best == null) {
                best = candidate;
                bestReflective = m;
            } else {
                Class<?>[] bestParams = bestReflective.getParameterTypes();
                if (isMoreSpecific(bestParams, params)) {
                    best = candidate;
                    bestReflective = m;
                }
            }
        }
        return best != null ? best : resolveMethod(ownerInternal, methodName, argCount);
    }

    public @Nullable ResolvedMethod resolveMethod(@NotNull String ownerInternal, @NotNull String methodName, int argCount) {
        Class<?> clazz = classpathManager.loadClass(ownerInternal);
        String effectiveOwner = ownerInternal;
        if (clazz == null) {
            ResolvedMethod asm = resolveMethodFromAsm(ownerInternal, methodName, argCount);
            if (asm != null) return asm;
            clazz = classpathManager.loadClass("java/lang/Object");
            effectiveOwner = "java/lang/Object";
        }
        if (clazz == null) return null;
        boolean ownerIsInterface = clazz.isInterface();
        ResolvedMethod best = null;
        Method bestMethod = null;

        Method[] candidates = classpathManager.methodsNamed(clazz, methodName);
        Method[] objectExtras = null;
        if (ownerIsInterface) {
            Class<?> objectClass = classpathManager.loadClass("java/lang/Object");
            if (objectClass != null) objectExtras = classpathManager.methodsNamed(objectClass, methodName);
        }
        int total = candidates.length + (objectExtras != null ? objectExtras.length : 0);
        for (int ci = 0; ci < total; ci++) {
            Method m = ci < candidates.length ? candidates[ci] : objectExtras[ci - candidates.length];
            if (ci >= candidates.length) {
                int mods = m.getModifiers();
                if (!Modifier.isPublic(mods) || Modifier.isStatic(mods)) continue;
            }
            if (m.isBridge()) continue;
            boolean exactMatch = m.getParameterCount() == argCount;
            boolean varargMatch = m.isVarArgs() && argCount >= m.getParameterCount() - 1;
            if (!exactMatch && !varargMatch) continue;
            String descriptor = classpathManager.methodDescriptor(m);
            boolean isStatic = Modifier.isStatic(m.getModifiers());
            String actualOwner;
            int opcode;
            boolean isInterface;
            if (isStatic) {
                opcode = Opcodes.INVOKESTATIC;
                actualOwner = m.getDeclaringClass().getName().replace('.', '/');
                isInterface = m.getDeclaringClass().isInterface();
            } else if (m.getDeclaringClass() == Object.class && ownerIsInterface) {
                opcode = Opcodes.INVOKEVIRTUAL;
                actualOwner = "java/lang/Object";
                isInterface = false;
            } else {
                isInterface = ownerIsInterface;
                opcode = ownerIsInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                actualOwner = effectiveOwner;
                if (m.getDeclaringClass() == Object.class) actualOwner = "java/lang/Object";
            }
            ResolvedMethod candidate = new ResolvedMethod(actualOwner, methodName, descriptor, opcode, isInterface, m);
            if (best == null) {
                best = candidate;
                bestMethod = m;
            } else {
                boolean bestIsVararg = bestMethod.isVarArgs() && !exactMatch;
                boolean mIsVararg = m.isVarArgs() && !exactMatch;
                if (bestIsVararg && !mIsVararg) {
                    best = candidate;
                    bestMethod = m;
                } else if (!mIsVararg || bestIsVararg) {
                    Class<?>[] bestParams = bestMethod.getParameterTypes();
                    Class<?>[] mParams = m.getParameterTypes();
                    if (bestParams.length == mParams.length) {
                        boolean mParamsMoreSpecific = isMoreSpecific(bestParams, mParams);
                        boolean bestParamsMoreSpecific = isMoreSpecific(mParams, bestParams);
                        boolean isMoreSpecific = false;
                        if (mParamsMoreSpecific && !bestParamsMoreSpecific) {
                            isMoreSpecific = true;
                        } else if (mParamsMoreSpecific) {
                            if (bestMethod.getReturnType().isAssignableFrom(m.getReturnType())) {
                                isMoreSpecific = true;
                            }
                        }
                        if (isMoreSpecific) {
                            best = candidate;
                            bestMethod = m;
                        }
                    }
                }
            }
        }
        if (best != null) return best;
        return resolveMethodFromAsm(ownerInternal, methodName, argCount);
    }

    /**
     * Public entry to the declared-methods walk used for methods like
     * {@code Object.clone()} that {@link Class#getMethods()} filters out.
     * Callers guard with context to avoid picking up private/synthetic
     * helpers when a real resolution would succeed.
     */
    public @Nullable ResolvedMethod resolveDeclaredProtected(@NotNull String ownerInternal, @NotNull String methodName, int argCount) {
        return resolveDeclaredMethodChain(ownerInternal, methodName, argCount, null);
    }

    /**
     * Falls back to {@link Class#getDeclaredMethods()} up the superclass chain
     * when {@link Class#getMethods()} filtered out the target (e.g. when looking
     * for {@code Object.clone()}, which is {@code protected}). Matches the same
     * arity/descriptor rules as the public-method path.
     */
    private @Nullable ResolvedMethod resolveDeclaredMethodChain(@NotNull String ownerInternal, @NotNull String methodName, int argCount, @Nullable List<String> argDescriptors) {
        Class<?> clazz = classpathManager.loadClass(ownerInternal);
        if (clazz == null) return null;
        for (Class<?> walk = clazz; walk != null; walk = walk.getSuperclass()) {
            for (Method m : walk.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != argCount && !m.isVarArgs()) continue;
                if (argDescriptors != null && m.getParameterCount() == argCount) {
                    Class<?>[] params = m.getParameterTypes();
                    boolean ok = true;
                    for (int i = 0; i < params.length; i++) {
                        if (!isAssignable(argDescriptors.get(i), classpathManager.classDescriptor(params[i]), classpathManager)) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) continue;
                }
                if (Modifier.isPrivate(m.getModifiers())) continue;
                String descriptor = classpathManager.methodDescriptor(m);
                boolean isStatic = Modifier.isStatic(m.getModifiers());
                int opcode = isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
                String actualOwner = m.getDeclaringClass().getName().replace('.', '/');
                return new ResolvedMethod(actualOwner, methodName, descriptor, opcode, false, m);
            }
        }
        return null;
    }

    public @Nullable ResolvedMethod resolveMethod(@NotNull String ownerInternal, @NotNull String methodName, @NotNull List<String> argDescriptors) {
        Class<?> clazz = classpathManager.loadClass(ownerInternal);
        String effectiveOwner = ownerInternal;
        if (clazz == null) {
            ResolvedMethod asm = resolveMethodFromAsm(ownerInternal, methodName, argDescriptors);
            if (asm != null) return asm;
            clazz = classpathManager.loadClass("java/lang/Object");
            effectiveOwner = "java/lang/Object";
        }
        if (clazz == null) return null;
        boolean ownerIsInterface = clazz.isInterface();
        ResolvedMethod best = null;
        Method bestMethod = null;

        Method[] candidates2 = classpathManager.methodsNamed(clazz, methodName);
        Method[] objectExtras2 = null;
        if (ownerIsInterface) {
            Class<?> objectClass = classpathManager.loadClass("java/lang/Object");
            if (objectClass != null) objectExtras2 = classpathManager.methodsNamed(objectClass, methodName);
        }
        int total2 = candidates2.length + (objectExtras2 != null ? objectExtras2.length : 0);
        for (int ci = 0; ci < total2; ci++) {
            Method m = ci < candidates2.length ? candidates2[ci] : objectExtras2[ci - candidates2.length];
            if (ci >= candidates2.length) {
                int mods = m.getModifiers();
                if (!Modifier.isPublic(mods) || Modifier.isStatic(mods)) continue;
            }
            boolean exactCount = m.getParameterCount() == argDescriptors.size();
            boolean varargCount = m.isVarArgs() && argDescriptors.size() >= m.getParameterCount() - 1;
            if (!exactCount && !varargCount) continue;
            String descriptor = classpathManager.methodDescriptor(m);
            Type[] paramTypes = classpathManager.argumentTypes(descriptor);
            boolean applicable = true;
            boolean varargMatchesAsArray = false;
            if (m.isVarArgs() && argDescriptors.size() == paramTypes.length) {
                for (int i = 0; i < paramTypes.length; i++) {
                    if (!isAssignable(argDescriptors.get(i), paramTypes[i].getDescriptor())) {
                        applicable = false;
                        break;
                    }
                }
                if (applicable) varargMatchesAsArray = true;
            }
            if (!varargMatchesAsArray) {
                int checkCount = m.isVarArgs() ? Math.min(paramTypes.length - 1, argDescriptors.size()) : paramTypes.length;
                applicable = true;
                for (int i = 0; i < checkCount; i++) {
                    if (i >= argDescriptors.size() || !isAssignable(argDescriptors.get(i), paramTypes[i].getDescriptor())) {
                        applicable = false;
                        break;
                    }
                }
                if (m.isVarArgs() && applicable) {
                    String elemDesc = paramTypes[paramTypes.length - 1].getDescriptor().substring(1);
                    for (int i = paramTypes.length - 1; i < argDescriptors.size(); i++) {
                        if (!isAssignable(argDescriptors.get(i), elemDesc)) {
                            applicable = false;
                            break;
                        }
                    }
                }
            }
            if (!applicable) continue;

            boolean isStatic = Modifier.isStatic(m.getModifiers());
            int opcode;
            String actualOwner;
            boolean isInterface;
            if (isStatic) {
                opcode = Opcodes.INVOKESTATIC;
                actualOwner = m.getDeclaringClass().getName().replace('.', '/');
                isInterface = m.getDeclaringClass().isInterface();
            } else if (m.getDeclaringClass() == Object.class && ownerIsInterface) {
                opcode = Opcodes.INVOKEVIRTUAL;
                actualOwner = "java/lang/Object";
                isInterface = false;
            } else {
                opcode = ownerIsInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                actualOwner = effectiveOwner;
                isInterface = ownerIsInterface;
                if (m.getDeclaringClass() == Object.class) actualOwner = "java/lang/Object";
            }
            ResolvedMethod candidate = new ResolvedMethod(actualOwner, methodName, descriptor, opcode, isInterface, m);

            if (best == null) {
                best = candidate;
                bestMethod = m;
            } else {
                boolean bestIsVararg = bestMethod.isVarArgs() && bestMethod.getParameterCount() != argDescriptors.size();
                boolean mIsVararg = m.isVarArgs() && m.getParameterCount() != argDescriptors.size();
                if (bestIsVararg && !mIsVararg) {
                    best = candidate;
                    bestMethod = m;
                } else if (!mIsVararg || bestIsVararg) {
                    boolean isMoreSpecific = false;
                    Class<?>[] bestParams = bestMethod.getParameterTypes();
                    Class<?>[] mParams = m.getParameterTypes();
                    boolean mParamsMoreSpecific = isMoreSpecific(bestParams, mParams, argDescriptors);
                    boolean bestParamsMoreSpecific = isMoreSpecific(mParams, bestParams, argDescriptors);
                    if (mParamsMoreSpecific && !bestParamsMoreSpecific) {
                        isMoreSpecific = true;
                    } else if (mParamsMoreSpecific) {
                        if (bestMethod.getReturnType().isAssignableFrom(m.getReturnType())) {
                            isMoreSpecific = true;
                        }
                    }
                    if (isMoreSpecific) {
                        best = candidate;
                        bestMethod = m;
                    }
                }
            }
        }
        if (best != null) return best;
        ResolvedMethod countBased = resolveMethod(ownerInternal, methodName, argDescriptors.size());
        if (countBased != null) return countBased;
        return resolveMethodFromAsm(ownerInternal, methodName, argDescriptors);
    }

    /**
     * Resolves a constructor call.
     *
     * @param ownerInternal the internal name of the class being constructed
     * @param argCount      the number of constructor arguments
     * @return the constructor descriptor, or null if not found
     */
    public @Nullable String resolveConstructor(@NotNull String ownerInternal, int argCount) {
        Class<?> clazz = classpathManager.loadClass(ownerInternal);
        if (clazz == null) return resolveConstructorFromAsm(ownerInternal, argCount, null);
        for (Constructor<?> c : classpathManager.cachedDeclaredConstructors(clazz)) {
            if (c.getParameterCount() == argCount) {
                return Type.getConstructorDescriptor(c);
            }
        }
        return resolveConstructorFromAsm(ownerInternal, argCount, null);
    }

    /**
     * Resolves a constructor call using argument type descriptors for precise matching.
     * Falls back to count based matching if type based matching fails.
     *
     * @param ownerInternal  the internal name of the class being constructed
     * @param argDescriptors the JVM descriptors of the argument types
     * @return the constructor descriptor, or null if not found
     */
    public @Nullable String resolveConstructor(@NotNull String ownerInternal, @NotNull List<String> argDescriptors) {
        Class<?> clazz = classpathManager.loadClass(ownerInternal);
        if (clazz == null) return resolveConstructorFromAsm(ownerInternal, argDescriptors.size(), argDescriptors);
        Constructor<?> best = null;
        for (Constructor<?> c : classpathManager.cachedDeclaredConstructors(clazz)) {
            if (Modifier.isPrivate(c.getModifiers())) continue;
            if (c.getParameterCount() != argDescriptors.size()) continue;
            Class<?>[] params = c.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                String paramDesc = classpathManager.classDescriptor(params[i]);
                if (!isAssignable(argDescriptors.get(i), paramDesc)) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            if (best == null) {
                best = c;
            } else {
                Class<?>[] bp = best.getParameterTypes();
                boolean candMoreSpecific = true;
                for (int i = 0; i < params.length; i++) {
                    if (!bp[i].isAssignableFrom(params[i])) {
                        candMoreSpecific = false;
                        break;
                    }
                }
                if (candMoreSpecific) best = c;
            }
        }
        if (best != null) return Type.getConstructorDescriptor(best);
        Constructor<?> bestVarArg = null;
        for (Constructor<?> c : classpathManager.cachedDeclaredConstructors(clazz)) {
            if (Modifier.isPrivate(c.getModifiers())) continue;
            if (!c.isVarArgs()) continue;
            int fixed = c.getParameterCount() - 1;
            if (argDescriptors.size() < fixed) continue;
            Class<?>[] params = c.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < fixed; i++) {
                if (!isAssignable(argDescriptors.get(i), classpathManager.classDescriptor(params[i]))) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            Class<?> varElem = params[fixed].getComponentType();
            String elemDesc = classpathManager.classDescriptor(varElem);
            for (int i = fixed; i < argDescriptors.size(); i++) {
                if (!isAssignable(argDescriptors.get(i), elemDesc)) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            if (bestVarArg == null) bestVarArg = c;
        }
        if (bestVarArg != null) return Type.getConstructorDescriptor(bestVarArg);
        String byCount = resolveConstructor(ownerInternal, argDescriptors.size());
        if (byCount != null) return byCount;
        return resolveConstructorFromAsm(ownerInternal, argDescriptors.size(), argDescriptors);
    }

    /**
     * Resolves a field access.
     *
     * @param ownerInternal the internal name of the owner class
     * @param fieldName     the field name
     * @return the resolved field info, or null if not found
     */
    public @Nullable ResolvedField resolveField(@NotNull String ownerInternal, @NotNull String fieldName) {
        Class<?> clazz = classpathManager.loadClass(ownerInternal);
        if (clazz == null) return resolveFieldFromAsm(ownerInternal, fieldName);
        Field f = classpathManager.fieldByName(clazz, fieldName);
        if (f == null) return resolveFieldFromAsm(ownerInternal, fieldName);
        try {
            String descriptor = classpathManager.classDescriptor(f.getType());
            String actualOwner = f.getDeclaringClass().getName().replace('.', '/');
            boolean isStatic = Modifier.isStatic(f.getModifiers());
            return new ResolvedField(actualOwner, fieldName, descriptor, isStatic);
        } catch (LinkageError e) {
            return resolveFieldFromAsm(ownerInternal, fieldName);
        }
    }

    private @Nullable ResolvedField resolveFieldFromAsm(@NotNull String ownerInternal, @NotNull String fieldName) {
        AsmClassInfo info = classpathManager.asmClassInfo(ownerInternal);
        if (info == null) return null;
        for (AsmClassInfo.FieldInfo f : info.fields()) {
            if (f.name().equals(fieldName)) {
                boolean isStatic = (f.access() & Opcodes.ACC_STATIC) != 0;
                return new ResolvedField(ownerInternal, fieldName, f.descriptor(), isStatic);
            }
        }
        if (info.superInternalName() != null) {
            return resolveFieldFromAsm(info.superInternalName(), fieldName);
        }
        return null;
    }

    /**
     * ASM-based lambda-arity-aware overload resolution. Mirrors
     * {@link #resolveMethodWithLambdaHints} but reads classfile bytes when reflection fails.
     */
    private @Nullable ResolvedMethod resolveMethodFromAsmWithLambdaHints(@NotNull String ownerInternal, @NotNull String methodName, int argCount, boolean @NotNull [] isLambdaArg, int @NotNull [] lambdaArities) {
        AsmClassInfo info = classpathManager.asmClassInfo(ownerInternal);
        if (info == null) return resolveMethod(ownerInternal, methodName, argCount);
        ResolvedMethod best = null;
        for (MethodInfo m : info.methods()) {
            if (!m.name().equals(methodName)) continue;
            Type[] params = Type.getArgumentTypes(m.descriptor());
            if (params.length != argCount) continue;
            boolean match = true;
            for (int i = 0; i < argCount; i++) {
                if (!isLambdaArg[i]) continue;
                if (params[i].getSort() != Type.OBJECT) {
                    match = false;
                    break;
                }
                int sam = asmSamArity(params[i].getInternalName());
                if (sam < 0) {
                    match = false;
                    break;
                }
                if (lambdaArities[i] >= 0 && sam != lambdaArities[i]) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            int opcode = m.isStatic() ? Opcodes.INVOKESTATIC : (info.isInterface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL);
            String actualOwner = info.internalName();
            best = new ResolvedMethod(actualOwner, methodName, m.descriptor(), opcode, info.isInterface());
            break;
        }
        return best != null ? best : resolveMethod(ownerInternal, methodName, argCount);
    }

    /**
     * Returns the SAM (single abstract method) arity of an interface read via ASM, or
     * {@code -1} if the type is not a functional interface.
     */
    private int asmSamArity(@NotNull String internalName) {
        AsmClassInfo info = classpathManager.asmClassInfo(internalName);
        if (info == null || !info.isInterface()) return -1;
        int abstractArity = -1;
        for (MethodInfo m : info.methods()) {
            if ((m.access() & Opcodes.ACC_ABSTRACT) == 0) continue;
            if ((m.access() & Opcodes.ACC_STATIC) != 0) continue;
            Type[] params = Type.getArgumentTypes(m.descriptor());
            if (abstractArity >= 0) return -1;
            abstractArity = params.length;
        }
        return abstractArity;
    }

    /**
     * Resolves a constructor descriptor by reading classfile bytes via ASM. Used when
     * {@link Class#forName} fails for the owner.
     */
    private @Nullable String resolveConstructorFromAsm(@NotNull String ownerInternal, int argCount, @Nullable List<String> argDescriptors) {
        AsmClassInfo info = classpathManager.asmClassInfo(ownerInternal);
        if (info == null) return null;
        MethodInfo best = null;
        for (MethodInfo m : info.methods()) {
            if (!"<init>".equals(m.name())) continue;
            if ((m.access() & Opcodes.ACC_PRIVATE) != 0) continue;
            Type[] params = Type.getArgumentTypes(m.descriptor());
            if (params.length != argCount) continue;
            if (argDescriptors != null) {
                boolean match = true;
                for (int i = 0; i < params.length; i++) {
                    if (!isAssignable(argDescriptors.get(i), params[i].getDescriptor())) {
                        match = false;
                        break;
                    }
                }
                if (!match) continue;
            }
            if (best == null) {
                best = m;
            } else {
                Type[] bp = Type.getArgumentTypes(best.descriptor());
                boolean candMoreSpecific = true;
                for (int i = 0; i < params.length; i++) {
                    if (!isAssignable("L" + bp[i].getInternalName() + ";", "L" + params[i].getInternalName() + ";")) {
                        candMoreSpecific = false;
                        break;
                    }
                }
                if (candMoreSpecific) best = m;
            }
        }
        return best != null ? best.descriptor() : null;
    }

    /**
     * Resolves a method by reading classfile bytes via ASM, walking the super/interface
     * chain. Used when {@link Class#forName} fails (e.g. LinkageError due to missing
     * transitive deps) but the classfile is readable from the classpath.
     */
    private @Nullable ResolvedMethod resolveMethodFromAsm(@NotNull String ownerInternal, @NotNull String methodName, int argCount) {
        AsmClassInfo info = classpathManager.asmClassInfo(ownerInternal);
        if (info == null) return null;
        ResolvedMethod best = walkAsmMethods(info, ownerInternal, methodName, argCount, null, info.isInterface());
        if (best != null) return best;
        return walkAsmSupers(ownerInternal, info, methodName, argCount, null);
    }

    /**
     * Same as {@link #resolveMethodFromAsm(String, String, int)} but with descriptor-aware
     * applicability checking when arg descriptors are available.
     */
    private @Nullable ResolvedMethod resolveMethodFromAsm(@NotNull String ownerInternal, @NotNull String methodName, @NotNull List<String> argDescriptors) {
        AsmClassInfo info = classpathManager.asmClassInfo(ownerInternal);
        if (info == null) return null;
        ResolvedMethod best = walkAsmMethods(info, ownerInternal, methodName, argDescriptors.size(), argDescriptors, info.isInterface());
        if (best != null) return best;
        return walkAsmSupers(ownerInternal, info, methodName, argDescriptors.size(), argDescriptors);
    }

    /**
     * Recursively walks the super class and superinterface chain looking for a method
     * matching the given name/arity (and optionally arg descriptors).
     */
    private @Nullable ResolvedMethod walkAsmSupers(@NotNull String ownerInternal, @NotNull AsmClassInfo info, @NotNull String methodName, int argCount, @Nullable List<String> argDescriptors) {
        boolean ownerIsInterface = info.isInterface();
        if (info.superInternalName() != null) {
            AsmClassInfo superInfo = classpathManager.asmClassInfo(info.superInternalName());
            if (superInfo != null) {
                ResolvedMethod m = walkAsmMethods(superInfo, ownerInternal, methodName, argCount, argDescriptors, ownerIsInterface);
                if (m != null) return m;
                ResolvedMethod up = walkAsmSupers(ownerInternal, superInfo, methodName, argCount, argDescriptors);
                if (up != null) return up;
            }
        }
        for (String iface : info.interfaceInternalNames()) {
            AsmClassInfo ifaceInfo = classpathManager.asmClassInfo(iface);
            if (ifaceInfo == null) continue;
            ResolvedMethod m = walkAsmMethods(ifaceInfo, ownerInternal, methodName, argCount, argDescriptors, ownerIsInterface);
            if (m != null) return m;
        }
        return null;
    }

    /**
     * Searches the method list of {@code info} for a method matching {@code methodName}
     * with the given arity (and optionally with descriptor-assignable arguments).
     */
    private @Nullable ResolvedMethod walkAsmMethods(@NotNull AsmClassInfo info, @NotNull String emitOwner, @NotNull String methodName, int argCount, @Nullable List<String> argDescriptors, boolean emitOwnerIsInterface) {
        for (MethodInfo m : info.methods()) {
            if (!m.name().equals(methodName)) continue;
            Type[] params = Type.getArgumentTypes(m.descriptor());
            boolean exact = params.length == argCount;
            boolean varargs = m.isVarArgs() && argCount >= params.length - 1;
            if (!exact && !varargs) continue;
            if (argDescriptors != null && exact) {
                boolean applicable = true;
                for (int i = 0; i < params.length; i++) {
                    if (!isAssignable(argDescriptors.get(i), params[i].getDescriptor())) {
                        applicable = false;
                        break;
                    }
                }
                if (!applicable) continue;
            }
            int opcode;
            String actualOwner;
            boolean isInterface;
            if (m.isStatic()) {
                opcode = Opcodes.INVOKESTATIC;
                actualOwner = info.internalName();
                isInterface = info.isInterface();
            } else {
                opcode = emitOwnerIsInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                actualOwner = emitOwner;
                isInterface = emitOwnerIsInterface;
            }
            return new ResolvedMethod(actualOwner, methodName, m.descriptor(), opcode, isInterface);
        }
        return null;
    }

    /**
     * Result of resolving a method. The {@code reflective} field holds the underlying
     * {@link Method} when reflection-based resolution succeeded, enabling callers to
     * inspect generic parameter types (e.g. {@code Consumer<Runnable>}).
     */
    public record ResolvedMethod(@NotNull String owner, @NotNull String name, @NotNull String descriptor, int opcode,
                                 boolean isInterface, @Nullable Method reflective) {
        public ResolvedMethod(@NotNull String owner, @NotNull String name, @NotNull String descriptor, int opcode, boolean isInterface) {
            this(owner, name, descriptor, opcode, isInterface, null);
        }
    }

    /**
     * Result of resolving a field.
     */
    public record ResolvedField(@NotNull String owner, @NotNull String name, @NotNull String descriptor,
                                boolean isStatic) {
    }
}
