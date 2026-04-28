package net.vansencool.vanta.codegen.expression.call;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.LambdaExpression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.expression.MethodReferenceExpression;
import net.vansencool.vanta.resolver.MethodResolver;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Method resolution lookups for the call site. Picks the right
 * {@code SelfMethodInfo} or {@code ResolvedMethod} by threading argument
 * descriptor inference and lambda arity hints through the resolver. Pure
 * resolution, no bytecode emission.
 */
public final class MethodResolutionHelper {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator providing context and the
     *                shared type inferrer
     */
    public MethodResolutionHelper(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Builds the {@code name:arity} key used as the entry point into the
     * self method map without the per call {@link StringBuilder} the
     * {@code +} operator would synthesise.
     *
     * @param name  method name
     * @param arity argument count
     * @return key string
     */
    public static @NotNull String selfMethodKey(@NotNull String name, int arity) {
        return name + ':' + arity;
    }

    /**
     * Resolves the matching {@code <init>} descriptor on {@code ownerInternal}
     * for the given argument list. Falls back through inferred type
     * resolution, arity only resolution, the local self methods map, and
     * finally a synthetic {@code Object} typed descriptor.
     *
     * @param ownerInternal owning class internal name
     * @param args          constructor arguments
     * @return method descriptor of the matching {@code <init>}
     */
    public @NotNull String resolveConstructorDescriptor(@NotNull String ownerInternal, @NotNull List<Expression> args) {
        return resolveConstructorDescriptor(ownerInternal, args, null);
    }

    /**
     * @param ownerInternal  owning class internal name
     * @param args           constructor arguments
     * @param enclosingOuter optional enclosing outer instance descriptor
     *                       prepended for inner class constructors
     * @return method descriptor of the matching {@code <init>}
     */
    public @NotNull String resolveConstructorDescriptor(@NotNull String ownerInternal, @NotNull List<Expression> args, @Nullable String enclosingOuter) {
        MethodContext ctx = exprGen.ctx();
        List<String> argDescriptors = new ArrayList<>();
        if (enclosingOuter != null) argDescriptors.add("L" + enclosingOuter + ";");
        boolean allInferred = true;
        for (Expression arg : args) {
            ResolvedType type = ctx.typeInferrer().infer(arg);
            if (type == null) {
                allInferred = false;
                break;
            }
            argDescriptors.add(type.descriptor());
        }
        if (allInferred) {
            String resolved = ctx.methodResolver().resolveConstructor(ownerInternal, argDescriptors);
            if (resolved != null) return resolved;
        }
        int totalCount = args.size() + (enclosingOuter != null ? 1 : 0);
        String resolved = ctx.methodResolver().resolveConstructor(ownerInternal, totalCount);
        if (resolved != null) return resolved;
        SelfMethodInfo selfInfo = ctx.selfMethods().get("<init>:" + totalCount);
        if (selfInfo != null) return selfInfo.descriptor();
        StringBuilder sb = new StringBuilder("(");
        if (enclosingOuter != null) sb.append("L").append(enclosingOuter).append(";");
        sb.append("Ljava/lang/Object;".repeat(args.size())).append(")V");
        return sb.toString();
    }

    /**
     * Searches the super chain for a protected or package private method that
     * the public only classpath lookup would not surface. Used as a last
     * chance fallback for same class calls so subclasses can invoke protected
     * helpers like {@code ClassLoader.defineClass}.
     *
     * @param superInternal starting super class internal name
     * @param methodName    method name
     * @param argCount      argument count
     * @return resolved method targeting the current class, or null
     */
    public @Nullable MethodResolver.ResolvedMethod resolveInheritedProtectedMethod(@NotNull String superInternal, @NotNull String methodName, int argCount) {
        MethodContext ctx = exprGen.ctx();
        Class<?> c = ctx.methodResolver().classpathManager().loadClass(superInternal);
        while (c != null) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (LinkageError ignored) {
                return null;
            }
            for (Method m : methods) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != argCount) continue;
                int mods = m.getModifiers();
                if (Modifier.isPrivate(mods)) continue;
                String desc = ctx.methodResolver().classpathManager().methodDescriptor(m);
                int op = Modifier.isStatic(mods) ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
                return new MethodResolver.ResolvedMethod(ctx.classInternalName(), methodName, desc, op, false, m);
            }
            try {
                c = c.getSuperclass();
            } catch (LinkageError ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Picks the most specific overload of a self defined method matching the
     * argument descriptors at the call site. Falls through to the arity
     * keyed entry when no overloads exist or none match.
     *
     * @param call call site
     * @return matching {@code SelfMethodInfo}, or null when no method of the
     * right name and arity exists
     */
    public @Nullable SelfMethodInfo resolveSelfMethod(@NotNull MethodCallExpression call) {
        MethodContext ctx = exprGen.ctx();
        Map<String, SelfMethodInfo> selfMethods = ctx.selfMethods();
        String baseKey = selfMethodKey(call.methodName(), call.arguments().size());
        SelfMethodInfo info = selfMethods.get(baseKey);
        if (info == null) return null;
        if (!ctx.selfMethodsHasOverloads()) return info;
        List<SelfMethodInfo> overloads = null;
        int baseKeyLen = baseKey.length();
        for (Map.Entry<String, SelfMethodInfo> e : selfMethods.entrySet()) {
            String k = e.getKey();
            if (k.length() <= baseKeyLen) continue;
            if (k.charAt(baseKeyLen) != '#') continue;
            if (!k.startsWith(baseKey)) continue;
            if (overloads == null) overloads = new ArrayList<>(4);
            overloads.add(e.getValue());
        }
        if (overloads == null) return info;
        int argCount = call.arguments().size();
        String[] argDescs = new String[argCount];
        for (int i = 0; i < argCount; i++) {
            ResolvedType t = ctx.typeInferrer().infer(call.arguments().get(i));
            if (t == null) return info;
            argDescs[i] = t.descriptor();
        }
        overloads.add(info);
        SelfMethodInfo best = null;
        Type[] bestParams = null;
        for (SelfMethodInfo cand : overloads) {
            Type[] params = ctx.methodResolver().classpathManager().argumentTypes(cand.descriptor());
            if (params.length != argCount) continue;
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                if (!ctx.methodResolver().isDescriptorAssignableWithClasspath(argDescs[i], params[i].getDescriptor())) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                if (best == null) {
                    best = cand;
                    bestParams = params;
                } else {
                    boolean candMoreSpecific = true;
                    for (int i = 0; i < params.length; i++) {
                        if (!ctx.methodResolver().isDescriptorAssignableWithClasspath(params[i].getDescriptor(), bestParams[i].getDescriptor())) {
                            candMoreSpecific = false;
                            break;
                        }
                    }
                    if (candMoreSpecific) {
                        best = cand;
                        bestParams = params;
                    }
                }
            }
        }
        return best != null ? best : info;
    }

    /**
     * Resolves a method on {@code ownerInternal} using inferred argument
     * descriptors when available, otherwise falling back to lambda or method
     * reference arity hints so functional interface arguments still match.
     *
     * @param ownerInternal owning class internal name
     * @param methodName    method name
     * @param arguments     argument expressions
     * @return resolved method, or null when no overload matches
     */
    public @Nullable MethodResolver.ResolvedMethod resolveMethodWithArgTypes(@NotNull String ownerInternal, @NotNull String methodName, @NotNull List<Expression> arguments) {
        MethodContext ctx = exprGen.ctx();
        List<String> argDescriptors = new ArrayList<>();
        int[] lambdaArities = new int[arguments.size()];
        boolean[] isLambdaArg = new boolean[arguments.size()];
        boolean allInferred = true;
        for (int i = 0; i < arguments.size(); i++) {
            Expression arg = arguments.get(i);
            if (arg instanceof LambdaExpression le) {
                isLambdaArg[i] = true;
                lambdaArities[i] = le.parameters().size();
            } else if (arg instanceof MethodReferenceExpression) {
                isLambdaArg[i] = true;
                lambdaArities[i] = -1;
            }
            ResolvedType type = ctx.typeInferrer().infer(arg);
            if (type == null) {
                allInferred = false;
                continue;
            }
            argDescriptors.add(type.descriptor());
        }
        if (allInferred) {
            MethodResolver.ResolvedMethod resolved = ctx.methodResolver().resolveMethod(ownerInternal, methodName, argDescriptors);
            if (resolved != null) return resolved;
        }
        return ctx.methodResolver().resolveMethodWithLambdaHints(ownerInternal, methodName, arguments.size(), isLambdaArg, lambdaArities);
    }
}
