package net.vansencool.vanta.resolver;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.classpath.AsmClassInfo.MethodInfo;
import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.parser.ast.expression.ArrayAccessExpression;
import net.vansencool.vanta.parser.ast.expression.ArrayInitializerExpression;
import net.vansencool.vanta.parser.ast.expression.AssignmentExpression;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.CastExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.InstanceofExpression;
import net.vansencool.vanta.parser.ast.expression.LiteralExpression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.NewArrayExpression;
import net.vansencool.vanta.parser.ast.expression.NewExpression;
import net.vansencool.vanta.parser.ast.expression.ParenExpression;
import net.vansencool.vanta.parser.ast.expression.SuperExpression;
import net.vansencool.vanta.parser.ast.expression.TernaryExpression;
import net.vansencool.vanta.parser.ast.expression.ThisExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.scope.Scope;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infers the resolved type of an expression using scope, type resolver, and classpath lookups.
 */
public final class ExpressionTypeInferrer {

    private static final @NotNull ResolvedType NULL_SENTINEL = new ResolvedType("__null_sentinel__", "V__sentinel", 0);
    private final @NotNull Scope scope;
    private final @NotNull TypeResolver typeResolver;
    private final @NotNull ClasspathManager classpathManager;
    private final @NotNull String classInternalName;
    private final @NotNull Map<String, ResolvedType> classFields;
    private final @NotNull Set<String> staticFields;
    private final @NotNull IdentityHashMap<Expression, ResolvedType> inferCache = new IdentityHashMap<>();
    private @Nullable String superInternalName;
    private @Nullable Map<String, SelfMethodInfo> selfMethods;
    private @Nullable String enclosingOuterInternal;
    private @Nullable String enclosingStaticOuter;
    private @Nullable Map<String, Map<String, ResolvedType>> nestedClassFields;
    private @Nullable Map<String, Map<String, SelfMethodInfo>> nestedClassMethods;
    private @Nullable Map<String, ResolvedType> capturedFields;

    public ExpressionTypeInferrer(@NotNull Scope scope, @NotNull TypeResolver typeResolver, @NotNull ClasspathManager classpathManager, @NotNull String classInternalName) {
        this.scope = scope;
        this.typeResolver = typeResolver;
        this.classpathManager = classpathManager;
        this.classInternalName = classInternalName;
        this.classFields = new HashMap<>();
        this.staticFields = new HashSet<>();
    }

    /**
     * Converts a Class into a ResolvedType.
     *
     * @param clazz the class
     * @return the resolved type
     */
    public static @NotNull ResolvedType classToResolvedType(@NotNull Class<?> clazz) {
        if (clazz == void.class) return ResolvedType.VOID;
        if (clazz == boolean.class) return ResolvedType.BOOLEAN;
        if (clazz == byte.class) return ResolvedType.BYTE;
        if (clazz == short.class) return ResolvedType.SHORT;
        if (clazz == char.class) return ResolvedType.CHAR;
        if (clazz == int.class) return ResolvedType.INT;
        if (clazz == long.class) return ResolvedType.LONG;
        if (clazz == float.class) return ResolvedType.FLOAT;
        if (clazz == double.class) return ResolvedType.DOUBLE;
        if (clazz.isArray()) {
            return classToResolvedType(clazz.getComponentType()).asArray(1);
        }
        return ResolvedType.ofObject(clazz.getName().replace('.', '/'));
    }

    public void nestedClassMethods(@Nullable Map<String, Map<String, SelfMethodInfo>> nested) {
        this.nestedClassMethods = nested;
    }

    public void enclosingOuterInternal(@Nullable String outer) {
        this.enclosingOuterInternal = outer;
    }

    /**
     * Records the superclass of the class currently being compiled so
     * {@code super.foo(...)} calls resolve against the actual parent even
     * when the class itself has not yet been written to the classpath (e.g.
     * anonymous classes generated during an outer class's compile).
     */
    public void superInternalName(@NotNull String superInternal) {
        this.superInternalName = superInternal;
    }

    public void enclosingStaticOuter(@Nullable String outer) {
        this.enclosingStaticOuter = outer;
    }

    public void nestedClassFields(@Nullable Map<String, Map<String, ResolvedType>> nested) {
        this.nestedClassFields = nested;
    }

    public void capturedFields(@Nullable Map<String, ResolvedType> captured) {
        this.capturedFields = captured;
    }

    public void registerSelfMethods(@NotNull Map<String, SelfMethodInfo> selfMethods) {
        this.selfMethods = selfMethods;
    }

    /**
     * Returns the boxed wrapper type when {@code t} is primitive, otherwise {@code t}.
     * Used so a ternary like {@code cond ? null : x} reports the wrapper type rather
     * than the primitive of the non-null branch.
     */
    private @Nullable ResolvedType boxedIfPrimitive(@Nullable ResolvedType t) {
        if (t == null || !t.isPrimitive()) return t;
        return switch (t.descriptor()) {
            case "I" -> ResolvedType.ofObject("java/lang/Integer");
            case "J" -> ResolvedType.ofObject("java/lang/Long");
            case "F" -> ResolvedType.ofObject("java/lang/Float");
            case "D" -> ResolvedType.ofObject("java/lang/Double");
            case "Z" -> ResolvedType.ofObject("java/lang/Boolean");
            case "B" -> ResolvedType.ofObject("java/lang/Byte");
            case "S" -> ResolvedType.ofObject("java/lang/Short");
            case "C" -> ResolvedType.ofObject("java/lang/Character");
            default -> t;
        };
    }

    private @Nullable ResolvedType descriptorReturnType(@NotNull String descriptor) {
        int close = descriptor.indexOf(')');
        if (close < 0) return null;
        String ret = descriptor.substring(close + 1);
        if (ret.startsWith("[")) return ResolvedType.fromDescriptor(ret);
        return switch (ret) {
            case "V" -> null;
            case "I" -> ResolvedType.INT;
            case "J" -> ResolvedType.LONG;
            case "F" -> ResolvedType.FLOAT;
            case "D" -> ResolvedType.DOUBLE;
            case "Z" -> ResolvedType.BOOLEAN;
            case "B" -> ResolvedType.BYTE;
            case "S" -> ResolvedType.SHORT;
            case "C" -> ResolvedType.CHAR;
            default -> {
                if (ret.startsWith("L") && ret.endsWith(";")) {
                    yield ResolvedType.ofObject(ret.substring(1, ret.length() - 1));
                }
                yield null;
            }
        };
    }

    private @Nullable Method getBestMethod(@NotNull MethodCallExpression call, Class<?> clazz) {
        Method best = null;
        String methodName = call.methodName();
        Method[] namedMethods = classpathManager.methodsNamed(clazz, methodName);
        Method[] objectNamed = null;
        if (clazz.isInterface()) {
            objectNamed = classpathManager.methodsNamed(Object.class, methodName);
        }
        int totalLen = namedMethods.length + (objectNamed != null ? objectNamed.length : 0);
        if (totalLen == 0) return null;
        String[] argDescs = new String[call.arguments().size()];
        boolean[] isNullArg = new boolean[call.arguments().size()];
        boolean allInferred = true;
        for (int i = 0; i < argDescs.length; i++) {
            ResolvedType t = infer(call.arguments().get(i));
            if (t == null) {
                allInferred = false;
                break;
            }
            argDescs[i] = t.descriptor();
            isNullArg[i] = t == ResolvedType.NULL;
        }
        for (int ci = 0; ci < totalLen; ci++) {
            Method m = ci < namedMethods.length ? namedMethods[ci] : objectNamed[ci - namedMethods.length];
            if (ci >= namedMethods.length) {
                int mods = m.getModifiers();
                if (!Modifier.isPublic(mods) || Modifier.isStatic(mods)) continue;
            }
            if (m.isBridge()) continue;
            int paramCount = m.getParameterCount();
            int argCount = call.arguments().size();
            boolean isVarargs = m.isVarArgs();
            if (!isVarargs) {
                if (paramCount != argCount) continue;
            } else {
                if (argCount < paramCount - 1) continue;
            }
            if (allInferred) {
                Class<?>[] params = m.getParameterTypes();
                boolean applicable = true;
                int fixedCount = isVarargs ? paramCount - 1 : paramCount;
                for (int i = 0; i < fixedCount; i++) {
                    String paramDesc = classpathManager.classDescriptor(params[i]);
                    if (isNullArg[i]) {
                        if (params[i].isPrimitive()) {
                            applicable = false;
                            break;
                        }
                        continue;
                    }
                    if (!MethodResolver.isDescriptorAssignable(argDescs[i], paramDesc)) {
                        applicable = false;
                        break;
                    }
                }
                if (applicable && isVarargs) {
                    Class<?> varargElem = params[paramCount - 1].getComponentType();
                    String varargArrayDesc = classpathManager.classDescriptor(params[paramCount - 1]);
                    String varargElemDesc = classpathManager.classDescriptor(varargElem);
                    if (!(argCount == paramCount && MethodResolver.isDescriptorAssignable(argDescs[argCount - 1], varargArrayDesc))) {
                        for (int i = fixedCount; i < argCount; i++) {
                            if (isNullArg[i]) {
                                if (varargElem.isPrimitive()) {
                                    applicable = false;
                                    break;
                                }
                                continue;
                            }
                            if (!MethodResolver.isDescriptorAssignable(argDescs[i], varargElemDesc)) {
                                applicable = false;
                                break;
                            }
                        }
                    }
                }
                if (!applicable) continue;
            }
            if (best == null) {
                best = m;
            } else if (m.getDeclaringClass() == clazz && best.getDeclaringClass() != clazz) {
                best = m;
            } else if (!m.getDeclaringClass().isAssignableFrom(best.getDeclaringClass())) {
                best = m;
            } else if (isMethodMoreSpecific(m, best, argDescs, allInferred)) {
                best = m;
            }
        }
        return best;
    }

    /**
     * True when {@code cand}'s parameter types are all assignable to {@code other}'s
     * but not the reverse, i.e. {@code cand} matches a strict subset of call sites.
     * Used by overload resolution so {@code Math.max(long, long)} is preferred over
     * {@code Math.max(double, double)} when both would accept long arguments.
     */
    private boolean isMethodMoreSpecific(@NotNull Method cand, @NotNull Method other, String @Nullable [] argDescs, boolean argsInferred) {
        Class<?>[] cp = cand.getParameterTypes();
        Class<?>[] op = other.getParameterTypes();
        if (cp.length != op.length) return false;
        boolean candNarrower = false;
        for (int i = 0; i < cp.length; i++) {
            if (cp[i].equals(op[i])) continue;
            boolean argIsPrim = argsInferred && argDescs != null && i < argDescs.length && argDescs[i] != null && argDescs[i].length() == 1;
            if (cp[i].isPrimitive() && !op[i].isPrimitive()) {
                if (argIsPrim) {
                    candNarrower = true;
                    continue;
                }
                return false;
            }
            if (!cp[i].isPrimitive() && op[i].isPrimitive()) {
                if (argIsPrim) return false;
                candNarrower = true;
                continue;
            }
            if (!isWidening(cp[i], op[i])) return false;
            candNarrower = true;
        }
        return candNarrower;
    }

    private boolean isWidening(@NotNull Class<?> from, @NotNull Class<?> to) {
        if (from == to) return true;
        if (from.isPrimitive() && to.isPrimitive()) {
            String order = "BSIJFD";
            int f = order.indexOf(primitiveLetter(from));
            int t = order.indexOf(primitiveLetter(to));
            return f >= 0 && t >= 0 && f < t;
        }
        if (!from.isPrimitive() && !to.isPrimitive()) return to.isAssignableFrom(from);
        return false;
    }

    private char primitiveLetter(@NotNull Class<?> c) {
        if (c == byte.class) return 'B';
        if (c == short.class) return 'S';
        if (c == char.class) return 'I';
        if (c == int.class) return 'I';
        if (c == long.class) return 'J';
        if (c == float.class) return 'F';
        if (c == double.class) return 'D';
        return '?';
    }

    /**
     * Registers a field declared on the class being compiled.
     *
     * @param name the field name
     * @param type the resolved type
     */
    public void registerField(@NotNull String name, @NotNull ResolvedType type) {
        classFields.put(name, type);
    }

    public void registerField(@NotNull String name, @NotNull ResolvedType type, boolean isStatic) {
        classFields.put(name, type);
        if (isStatic) staticFields.add(name);
        else staticFields.remove(name);
    }

    public void copyFieldsFrom(@NotNull ExpressionTypeInferrer other) {
        classFields.putAll(other.classFields);
        staticFields.addAll(other.staticFields);
    }

    public @Nullable ResolvedType inferField(@NotNull String name) {
        return classFields.get(name);
    }

    public boolean isStaticField(@NotNull String name) {
        return staticFields.contains(name);
    }

    /**
     * Infers the type of an expression.
     *
     * @param expr the expression
     * @return the inferred type, or null if unknown
     */
    public @Nullable ResolvedType infer(@NotNull Expression expr) {
        boolean cacheable = expr instanceof MethodCallExpression || expr instanceof FieldAccessExpression
                || expr instanceof NewExpression || expr instanceof NewArrayExpression
                || expr instanceof CastExpression;
        if (cacheable) {
            ResolvedType cached = inferCache.get(expr);
            if (cached != null && cached != NULL_SENTINEL) return cached;
        }
        ResolvedType result = inferUncached(expr);
        if (cacheable && result != null) inferCache.put(expr, result);
        return result;
    }

    /**
     * Dispatches expression-shape branches to the specific inference helpers.
     * {@link #infer(Expression)} wraps this with an identity-keyed cache for
     * shapes whose resolved type depends only on the AST node (method calls,
     * field accesses, constructor invocations, casts), so redundant re-inference
     * across overload resolution, argument emission, and generic-return checks
     * stays cheap.
     */
    private @Nullable ResolvedType inferUncached(@NotNull Expression expr) {
        if (expr instanceof LiteralExpression lit) return inferLiteral(lit);
        if (expr instanceof NameExpression name) return inferName(name);
        if (expr instanceof ThisExpression) return ResolvedType.ofObject(classInternalName);
        if (expr instanceof SuperExpression) {
            if (superInternalName != null) return ResolvedType.ofObject(superInternalName);
            AsmClassInfo selfInfo = classpathManager.asmClassInfo(classInternalName);
            if (selfInfo != null && selfInfo.superInternalName() != null) {
                return ResolvedType.ofObject(selfInfo.superInternalName());
            }
            return ResolvedType.ofObject("java/lang/Object");
        }
        if (expr instanceof NewExpression newExpr) return typeResolver.resolve(newExpr.type());
        if (expr instanceof NewArrayExpression newArr)
            return typeResolver.resolve(newArr.elementType()).asArray(newArr.dimensionExpressions().size() + newArr.extraDimensions());
        if (expr instanceof CastExpression cast) return typeResolver.resolve(cast.type());
        if (expr instanceof ParenExpression paren) return infer(paren.expression());
        if (expr instanceof TernaryExpression ternary) {
            ResolvedType thenT = infer(ternary.thenExpression());
            ResolvedType elseT = infer(ternary.elseExpression());
            if (thenT == null || thenT == ResolvedType.NULL) return boxedIfPrimitive(elseT);
            if (elseT == null || elseT == ResolvedType.NULL) return boxedIfPrimitive(thenT);
            if (thenT.isPrimitive() && !elseT.isPrimitive()) return boxedIfPrimitive(thenT);
            if (!thenT.isPrimitive() && elseT.isPrimitive()) return boxedIfPrimitive(elseT);
            return thenT;
        }
        if (expr instanceof InstanceofExpression) return ResolvedType.BOOLEAN;
        if (expr instanceof ArrayAccessExpression arrayAccess) return inferArrayAccess(arrayAccess);
        if (expr instanceof ArrayInitializerExpression) return ResolvedType.INT.asArray(1);
        if (expr instanceof BinaryExpression binary) return inferBinary(binary);
        if (expr instanceof UnaryExpression unary) return inferUnary(unary);
        if (expr instanceof AssignmentExpression assign) return infer(assign.value());
        if (expr instanceof MethodCallExpression call) return inferMethodCall(call);
        if (expr instanceof FieldAccessExpression field) return inferFieldAccess(field);
        return null;
    }

    private @Nullable ResolvedType inferLiteral(@NotNull LiteralExpression lit) {
        return switch (lit.literalType()) {
            case INT_LITERAL -> ResolvedType.INT;
            case LONG_LITERAL -> ResolvedType.LONG;
            case FLOAT_LITERAL -> ResolvedType.FLOAT;
            case DOUBLE_LITERAL -> ResolvedType.DOUBLE;
            case CHAR_LITERAL -> ResolvedType.CHAR;
            case STRING_LITERAL, TEXT_BLOCK -> ResolvedType.ofObject("java/lang/String");
            case TRUE, FALSE -> ResolvedType.BOOLEAN;
            case NULL -> ResolvedType.NULL;
            default -> null;
        };
    }

    private @Nullable ResolvedType inferName(@NotNull NameExpression name) {
        LocalVariable local = scope.resolve(name.name());
        if (local != null) return local.type();
        ResolvedType field = classFields.get(name.name());
        if (field != null) return field;
        if (capturedFields != null) {
            ResolvedType cap = capturedFields.get(name.name());
            if (cap != null) return cap;
        }
        String enclosingOuter = enclosingOuterInternal();
        if (enclosingOuter != null) {
            ResolvedType outerField = inferFieldFromAsm(enclosingOuter, name.name());
            if (outerField != null) return outerField;
            if (nestedClassFields != null) {
                Map<String, ResolvedType> outerFields = nestedClassFields.get(enclosingOuter);
                if (outerFields != null && outerFields.containsKey(name.name())) return outerFields.get(name.name());
            }
        }
        String staticOwner = typeResolver.resolveStaticFieldOwner(name.name());
        if (staticOwner != null) {
            Class<?> c = classpathManager.loadClass(staticOwner);
            if (c != null) {
                try {
                    Field f = c.getField(name.name());
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive()) {
                        return switch (ft.getName()) {
                            case "int" -> ResolvedType.INT;
                            case "long" -> ResolvedType.LONG;
                            case "float" -> ResolvedType.FLOAT;
                            case "double" -> ResolvedType.DOUBLE;
                            case "boolean" -> ResolvedType.BOOLEAN;
                            case "byte" -> ResolvedType.BYTE;
                            case "short" -> ResolvedType.SHORT;
                            case "char" -> ResolvedType.CHAR;
                            default -> null;
                        };
                    }
                    return ResolvedType.ofObject(ft.getName().replace('.', '/'));
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        return null;
    }

    private @NotNull ResolvedType inferBinary(@NotNull BinaryExpression binary) {
        String op = binary.operator();
        if ("&&".equals(op) || "||".equals(op) || "==".equals(op) || "!=".equals(op) || "<".equals(op) || "<=".equals(op) || ">".equals(op) || ">=".equals(op)) {
            return ResolvedType.BOOLEAN;
        }
        ResolvedType left = infer(binary.left());
        if (left != null && left.equals(ResolvedType.ofObject("java/lang/String"))) {
            return ResolvedType.ofObject("java/lang/String");
        }
        ResolvedType right = infer(binary.right());
        if (right != null && right.equals(ResolvedType.ofObject("java/lang/String"))) {
            return ResolvedType.ofObject("java/lang/String");
        }
        return promoteArithmetic(left, right);
    }

    private @NotNull ResolvedType promoteArithmetic(@Nullable ResolvedType left, @Nullable ResolvedType right) {
        String l = left != null && left.isPrimitive() ? left.descriptor() : "I";
        String r = right != null && right.isPrimitive() ? right.descriptor() : "I";
        if ("D".equals(l) || "D".equals(r)) return ResolvedType.DOUBLE;
        if ("F".equals(l) || "F".equals(r)) return ResolvedType.FLOAT;
        if ("J".equals(l) || "J".equals(r)) return ResolvedType.LONG;
        return ResolvedType.INT;
    }

    private @NotNull ResolvedType inferUnary(@NotNull UnaryExpression unary) {
        if ("!".equals(unary.operator())) return ResolvedType.BOOLEAN;
        ResolvedType operand = infer(unary.operand());
        return operand != null ? operand : ResolvedType.INT;
    }

    /**
     * Walks a chain of {@link FieldAccessExpression} nodes joining each
     * simple name with {@code .} until it bottoms out at a
     * {@link NameExpression}. Returns the dotted name, or null when the
     * chain hits a non-name expression (method call, array access, this).
     * Used to recognise fully-qualified static calls like
     * {@code Type.getReturnType(desc)} whose target is
     * parsed as a nested field-access chain.
     */
    private @Nullable String flattenFieldAccessName(@NotNull Expression expr) {
        if (expr instanceof NameExpression ne) return ne.name();
        if (expr instanceof FieldAccessExpression fa) {
            String inner = flattenFieldAccessName(fa.target());
            if (inner == null) return null;
            return inner + "." + fa.fieldName();
        }
        return null;
    }

    private @NotNull ResolvedType inferArrayAccess(@NotNull ArrayAccessExpression arrayAccess) {
        ResolvedType arrayType = infer(arrayAccess.array());
        if (arrayType != null && arrayType.isArray()) {
            String desc = arrayType.descriptor();
            return new ResolvedType(desc.substring(1), desc.substring(1), 1);
        }
        return ResolvedType.INT;
    }

    private @Nullable ResolvedType inferMethodCall(@NotNull MethodCallExpression call) {
        if (call.target() == null) {
            if (selfMethods != null) {
                SelfMethodInfo info = selfMethods.get(call.methodName() + ":" + call.arguments().size());
                if (info != null) return descriptorReturnType(info.descriptor());
            }
            String staticOwner = typeResolver.resolveStaticMethodOwner(call.methodName());
            if (staticOwner != null) {
                Class<?> c = classpathManager.loadClass(staticOwner);
                if (c != null) {
                    Method best = getBestMethod(call, c);
                    if (best != null && Modifier.isStatic(best.getModifiers())) {
                        return classToResolvedType(best.getReturnType());
                    }
                }
            }
            String outer = enclosingOuterInternal();
            String checkOuter = outer != null ? outer : enclosingStaticOuter;
            if (checkOuter != null && nestedClassMethods != null) {
                Map<String, SelfMethodInfo> nm = nestedClassMethods.get(checkOuter);
                if (nm != null) {
                    SelfMethodInfo info = nm.get(call.methodName() + ":" + call.arguments().size());
                    if (info != null) return descriptorReturnType(info.descriptor());
                }
            }
            if (checkOuter != null) {
                Class<?> c = classpathManager.loadClass(checkOuter);
                if (c != null) {
                    Method m = findInstanceMethodByName(c, call.methodName(), call.arguments().size());
                    if (m != null) return classToResolvedType(m.getReturnType());
                }
            }
            AsmClassInfo selfInfo = classpathManager.asmClassInfo(classInternalName);
            while (selfInfo != null) {
                for (AsmClassInfo.MethodInfo mi : selfInfo.methods()) {
                    if (!mi.name().equals(call.methodName())) continue;
                    if (Type.getArgumentTypes(mi.descriptor()).length != call.arguments().size())
                        continue;
                    return descriptorReturnType(mi.descriptor());
                }
                if (selfInfo.superInternalName() == null) break;
                selfInfo = classpathManager.asmClassInfo(selfInfo.superInternalName());
            }
            Class<?> objectClass = classpathManager.loadClass("java/lang/Object");
            if (objectClass != null) {
                Method m = findInstanceMethodByName(objectClass, call.methodName(), call.arguments().size());
                if (m != null) return classToResolvedType(m.getReturnType());
            }
            return null;
        }
        ResolvedType tt = infer(call.target());
        if (tt != null && tt.internalName() != null && nestedClassMethods != null) {
            Map<String, SelfMethodInfo> nm = nestedClassMethods.get(tt.internalName());
            if (nm != null) {
                SelfMethodInfo info = nm.get(call.methodName() + ":" + call.arguments().size());
                if (info != null) return descriptorReturnType(info.descriptor());
            }
        }
        ResolvedType targetType = infer(call.target());
        if (targetType == null && call.target() instanceof NameExpression nameTarget) {
            ResolvedType resolved = typeResolver.resolve(new TypeNode(nameTarget.name(), null, 0, call.line()));
            if (resolved.internalName() != null) {
                Class<?> clazz = classpathManager.loadClass(resolved.internalName());
                if (clazz != null) {
                    Method best = getBestMethod(call, clazz);
                    if (best != null && Modifier.isStatic(best.getModifiers())) {
                        ResolvedType generic = resolveStaticGenericReturn(best, call);
                        return generic != null ? generic : classToResolvedType(best.getReturnType());
                    }
                }
                return inferStaticReturnFromAsm(resolved.internalName(), call);
            }
            return null;
        }
        if (targetType == null && call.target() instanceof FieldAccessExpression fa) {
            String fqn = flattenFieldAccessName(fa);
            if (fqn != null && fqn.indexOf('.') > 0) {
                String internal = typeResolver.resolveInternalName(new TypeNode(fqn, null, 0, call.line()));
                if (!"I".equals(internal)) {
                    Class<?> clazz = classpathManager.loadClass(internal);
                    if (clazz != null) {
                        Method best = getBestMethod(call, clazz);
                        if (best != null && Modifier.isStatic(best.getModifiers())) {
                            ResolvedType generic = resolveStaticGenericReturn(best, call);
                            return generic != null ? generic : classToResolvedType(best.getReturnType());
                        }
                    }
                    ResolvedType asmResult = inferStaticReturnFromAsm(internal, call);
                    if (asmResult != null) return asmResult;
                }
            }
        }
        if (targetType == null || targetType.internalName() == null) return null;
        Class<?> clazz = classpathManager.loadClass(targetType.internalName());
        if (clazz == null) {
            ResolvedType fromAsm = inferInstanceReturnFromAsm(targetType.internalName(), call);
            if (fromAsm != null) return fromAsm;
            clazz = classpathManager.loadClass("java/lang/Object");
        }
        if (clazz == null) return null;
        Method best = getBestMethod(call, clazz);
        if (best == null) {
            return inferInstanceReturnFromAsm(targetType.internalName(), call);
        }
        java.lang.reflect.Type genericReturn = best.getGenericReturnType();
        if (genericReturn instanceof TypeVariable<?> tv && targetType.typeArguments() != null) {
            TypeVariable<?>[] classTypeParams = best.getDeclaringClass().getTypeParameters();
            for (int i = 0; i < classTypeParams.length && i < targetType.typeArguments().size(); i++) {
                if (classTypeParams[i].getName().equals(tv.getName())) {
                    return targetType.typeArguments().get(i);
                }
            }
        }
        if (genericReturn instanceof TypeVariable<?> tv) {
            ResolvedType fromArgs = inferMethodTypeVar(best, tv, call);
            if (fromArgs != null) return fromArgs;
        }
        if (genericReturn instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> rawCls) {
            ResolvedType rt = classToResolvedType(rawCls);
            List<ResolvedType> args = new ArrayList<>();
            for (java.lang.reflect.Type ta : pt.getActualTypeArguments()) {
                args.add(genericTypeArgToResolved(ta, targetType));
            }
            return rt.withTypeArguments(args);
        }
        return classToResolvedType(best.getReturnType());
    }

    /**
     * Infers a static method's return type with generic narrowing. Handles
     * {@code T[] copyOf(T[], int)} (GenericArrayType) and plain {@code T foo()}
     * (TypeVariable) by binding the method-level type variable from the
     * concrete argument types supplied at the call site.
     */
    private @Nullable ResolvedType resolveStaticGenericReturn(@NotNull Method method, @NotNull MethodCallExpression call) {
        java.lang.reflect.Type genericReturn = method.getGenericReturnType();
        if (genericReturn instanceof TypeVariable<?> tv) {
            ResolvedType bound = inferMethodTypeVar(method, tv, call);
            if (bound != null) return bound;
        }
        if (genericReturn instanceof GenericArrayType gat) {
            java.lang.reflect.Type comp = gat.getGenericComponentType();
            if (comp instanceof TypeVariable<?> tv) {
                ResolvedType boundElem = inferMethodTypeVarFromArrayArg(method, tv, call);
                if (boundElem != null && boundElem.internalName() != null) {
                    return boundElem.asArray(1);
                }
            }
        }
        return null;
    }

    /**
     * Binds a method-level type variable by finding a parameter whose generic
     * type is {@code T[]} and reading the concrete array element type from the
     * matching argument. Used for {@code Arrays.copyOf(T[], int)} so the
     * narrowed return type feeds the downstream checkcast.
     */
    private @Nullable ResolvedType inferMethodTypeVarFromArrayArg(@NotNull Method method, @NotNull TypeVariable<?> tv, @NotNull MethodCallExpression call) {
        java.lang.reflect.Type[] genericParams = method.getGenericParameterTypes();
        for (int i = 0; i < genericParams.length && i < call.arguments().size(); i++) {
            java.lang.reflect.Type gp = genericParams[i];
            if (gp instanceof GenericArrayType gat && gat.getGenericComponentType() instanceof TypeVariable<?> ptv
                    && ptv.getName().equals(tv.getName())) {
                ResolvedType argType = infer(call.arguments().get(i));
                if (argType == null || !argType.isArray()) continue;
                String desc = argType.descriptor();
                if (desc.length() < 2 || desc.charAt(0) != '[') continue;
                String elemDesc = desc.substring(1);
                if (!elemDesc.startsWith("L") || !elemDesc.endsWith(";")) continue;
                return ResolvedType.ofObject(elemDesc.substring(1, elemDesc.length() - 1));
            }
        }
        return null;
    }

    private @Nullable ResolvedType inferMethodTypeVar(@NotNull Method method, @NotNull TypeVariable<?> tv, @NotNull MethodCallExpression call) {
        java.lang.reflect.Type[] genericParams = method.getGenericParameterTypes();
        for (int i = 0; i < genericParams.length && i < call.arguments().size(); i++) {
            java.lang.reflect.Type gp = genericParams[i];
            if (gp instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> rawCls && rawCls == Class.class) {
                java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                if (typeArgs.length != 1 || !(typeArgs[0] instanceof TypeVariable<?> argTv)) continue;
                if (!argTv.getName().equals(tv.getName())) continue;
                Expression arg = call.arguments().get(i);
                if (!(arg instanceof FieldAccessExpression fa) || !"class".equals(fa.fieldName())) continue;
                if (!(fa.target() instanceof NameExpression ne)) continue;
                ResolvedType resolved = typeResolver.resolve(new TypeNode(ne.name(), null, 0, call.line()));
                if (resolved.internalName() != null) return resolved;
            } else if (gp instanceof TypeVariable<?> argTv && argTv.getName().equals(tv.getName())) {
                ResolvedType argType = infer(call.arguments().get(i));
                if (argType != null && argType.internalName() != null && !"java/lang/Object".equals(argType.internalName()))
                    return argType;
            }
        }
        return null;
    }

    /**
     * Maps a {@link Type} type argument from a method's generic
     * signature into a {@link ResolvedType}, substituting class-level type variables
     * from {@code receiverType.typeArguments()} when available.
     */
    private @NotNull ResolvedType genericTypeArgToResolved(@NotNull java.lang.reflect.Type t, @Nullable ResolvedType receiverType) {
        if (t instanceof Class<?> c) return classToResolvedType(c);
        if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> rc) {
            ResolvedType base = classToResolvedType(rc);
            List<ResolvedType> args = new ArrayList<>();
            for (java.lang.reflect.Type inner : pt.getActualTypeArguments()) {
                args.add(genericTypeArgToResolved(inner, receiverType));
            }
            return base.withTypeArguments(args);
        }
        if (t instanceof WildcardType wt) {
            java.lang.reflect.Type[] upper = wt.getUpperBounds();
            if (upper.length > 0) return genericTypeArgToResolved(upper[0], receiverType);
        }
        if (t instanceof TypeVariable<?> tv && receiverType != null && receiverType.typeArguments() != null && receiverType.internalName() != null) {
            Class<?> recvCls = classpathManager.loadClass(receiverType.internalName());
            if (recvCls != null) {
                TypeVariable<?>[] params = recvCls.getTypeParameters();
                for (int i = 0; i < params.length && i < receiverType.typeArguments().size(); i++) {
                    if (params[i].getName().equals(tv.getName())) return receiverType.typeArguments().get(i);
                }
            }
        }
        return ResolvedType.ofObject("java/lang/Object");
    }

    private @Nullable ResolvedType inferFieldAccess(@NotNull FieldAccessExpression fieldAccess) {
        if ("class".equals(fieldAccess.fieldName())) return ResolvedType.ofObject("java/lang/Class");
        if ("this".equals(fieldAccess.fieldName()) && fieldAccess.target() instanceof NameExpression ne) {
            String enclosingOuter = enclosingOuterInternal();
            if (enclosingOuter != null) {
                String outerSimple = enclosingOuter.substring(enclosingOuter.lastIndexOf('/') + 1);
                if (outerSimple.equals(ne.name()) || enclosingOuter.replace('/', '.').endsWith("." + ne.name())) {
                    return ResolvedType.ofObject(enclosingOuter);
                }
            }
        }
        if (fieldAccess.target() instanceof ThisExpression) {
            return classFields.get(fieldAccess.fieldName());
        }
        ResolvedType targetType = infer(fieldAccess.target());
        if (targetType != null && targetType.isArray() && "length".equals(fieldAccess.fieldName())) {
            return ResolvedType.INT;
        }
        if (targetType == null && fieldAccess.target() instanceof NameExpression nameTarget && scope.resolve(nameTarget.name()) == null) {
            ResolvedType resolved = typeResolver.resolve(new TypeNode(nameTarget.name(), null, 0, fieldAccess.line()));
            if (resolved.internalName() != null) targetType = resolved;
        }
        if (targetType == null || targetType.internalName() == null) return null;
        String nestedCandidate = targetType.internalName() + "$" + fieldAccess.fieldName();
        if (classpathManager.exists(nestedCandidate)) return ResolvedType.ofObject(nestedCandidate);
        Class<?> clazz = classpathManager.loadClass(targetType.internalName());
        if (clazz == null) {
            if (targetType.internalName().equals(classInternalName)) return classFields.get(fieldAccess.fieldName());
            return inferFieldFromAsm(targetType.internalName(), fieldAccess.fieldName());
        }
        Field f = classpathManager.fieldByName(clazz, fieldAccess.fieldName());
        if (f == null) {
            if (targetType.internalName().equals(classInternalName))
                return classFields.get(fieldAccess.fieldName());
            return inferFieldFromAsm(targetType.internalName(), fieldAccess.fieldName());
        }
        try {
            return genericFieldType(f, targetType);
        } catch (LinkageError e) {
            return inferFieldFromAsm(targetType.internalName(), fieldAccess.fieldName());
        }
    }

    /**
     * Lifts {@link Field#getGenericType} into a {@link ResolvedType} that
     * preserves type arguments (e.g. {@code List<LocalVariableNode>} instead
     * of raw {@code List}). Falls back to the erased raw type when the
     * generic signature cannot be cleanly mapped (type variables with no
     * receiver substitution, nested parameterized types we cannot resolve).
     */
    private @NotNull ResolvedType genericFieldType(@NotNull Field field, @Nullable ResolvedType receiverType) {
        java.lang.reflect.Type generic = field.getGenericType();
        if (generic instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            ResolvedType base = classToResolvedType(raw);
            List<ResolvedType> args = new ArrayList<>();
            for (java.lang.reflect.Type ta : pt.getActualTypeArguments()) {
                args.add(genericTypeArgToResolved(ta, receiverType));
            }
            return base.withTypeArguments(args);
        }
        return classToResolvedType(field.getType());
    }

    private @Nullable Method findInstanceMethodByName(@NotNull Class<?> cls, @NotNull String name, int argCount) {
        for (Method m : classpathManager.methodsNamed(cls, name)) {
            if (m.getParameterCount() == argCount) return m;
        }
        return null;
    }

    private @Nullable String enclosingOuterInternal() {
        if (enclosingOuterInternal != null) return enclosingOuterInternal;
        AsmClassInfo info = classpathManager.asmClassInfo(classInternalName);
        if (info == null) return null;
        if ((info.access() & java.lang.reflect.Modifier.STATIC) != 0) return null;
        for (AsmClassInfo.FieldInfo f : info.fields()) {
            if ("this$0".equals(f.name()) && f.descriptor().startsWith("L") && f.descriptor().endsWith(";")) {
                return f.descriptor().substring(1, f.descriptor().length() - 1);
            }
        }
        return null;
    }

    private @Nullable ResolvedType inferFieldFromAsm(@NotNull String ownerInternal, @NotNull String fieldName) {
        AsmClassInfo info = classpathManager.asmClassInfo(ownerInternal);
        while (info != null) {
            for (AsmClassInfo.FieldInfo f : info.fields()) {
                if (f.name().equals(fieldName)) return descriptorToType(f.descriptor());
            }
            if (info.superInternalName() == null) break;
            info = classpathManager.asmClassInfo(info.superInternalName());
        }
        return null;
    }

    private @NotNull ResolvedType descriptorToType(@NotNull String desc) {
        return switch (desc) {
            case "I" -> ResolvedType.INT;
            case "J" -> ResolvedType.LONG;
            case "F" -> ResolvedType.FLOAT;
            case "D" -> ResolvedType.DOUBLE;
            case "Z" -> ResolvedType.BOOLEAN;
            case "B" -> ResolvedType.BYTE;
            case "S" -> ResolvedType.SHORT;
            case "C" -> ResolvedType.CHAR;
            default -> {
                if (desc.startsWith("L") && desc.endsWith(";"))
                    yield ResolvedType.ofObject(desc.substring(1, desc.length() - 1));
                if (desc.startsWith("[")) yield ResolvedType.ofObject("java/lang/Object");
                yield ResolvedType.ofObject("java/lang/Object");
            }
        };
    }

    /**
     * Resolves the return type of a static method via ASM-parsed classfile metadata.
     * Used as a fallback when {@link Class#forName} fails for the owner class but the
     * classfile is still readable from the classpath.
     */
    private @Nullable ResolvedType inferStaticReturnFromAsm(@NotNull String ownerInternal, @NotNull MethodCallExpression call) {
        AsmClassInfo info = classpathManager.asmClassInfo(ownerInternal);
        if (info == null) return null;
        MethodInfo m = pickAsmMethod(info, call);
        if (m == null || !m.isStatic()) return null;
        return descriptorReturnType(m.descriptor());
    }

    /**
     * Resolves the return type of an instance method via ASM-parsed classfile metadata,
     * walking the super chain. Used when {@link Class#forName} fails for the receiver type.
     */
    private @Nullable ResolvedType inferInstanceReturnFromAsm(@NotNull String ownerInternal, @NotNull MethodCallExpression call) {
        AsmClassInfo info = classpathManager.asmClassInfo(ownerInternal);
        while (info != null) {
            MethodInfo m = pickAsmMethod(info, call);
            if (m != null) return descriptorReturnType(m.descriptor());
            String nextSuper = info.superInternalName();
            if (nextSuper == null) break;
            Class<?> nc = classpathManager.loadClass(nextSuper);
            if (nc != null) {
                while (nc != null) {
                    Method m2 = findInstanceMethodByName(nc, call.methodName(), call.arguments().size());
                    if (m2 != null) return classToResolvedType(m2.getReturnType());
                    nc = nc.getSuperclass();
                }
                return null;
            }
            info = classpathManager.asmClassInfo(nextSuper);
        }
        Class<?> c = classpathManager.loadClass("java/lang/Object");
        if (c != null) {
            Method m = findInstanceMethodByName(c, call.methodName(), call.arguments().size());
            if (m != null) return classToResolvedType(m.getReturnType());
        }
        return null;
    }

    /**
     * Picks the best matching method from ASM info, by name and arity.
     */
    private @Nullable MethodInfo pickAsmMethod(@NotNull AsmClassInfo info, @NotNull MethodCallExpression call) {
        for (MethodInfo m : info.methods()) {
            if (!m.name().equals(call.methodName())) continue;
            Type[] params = Type.getArgumentTypes(m.descriptor());
            if (params.length == call.arguments().size()) return m;
            if (m.isVarArgs() && call.arguments().size() >= params.length - 1) return m;
        }
        return null;
    }
}
