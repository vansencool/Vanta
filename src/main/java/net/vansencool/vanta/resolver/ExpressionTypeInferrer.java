package net.vansencool.vanta.resolver;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.codegen.SelfMethodInfo;
import net.vansencool.vanta.codegen.classes.literal.LiteralParser;
import net.vansencool.vanta.lexer.token.TokenType;
import net.vansencool.vanta.parser.ast.expression.ArrayAccessExpression;
import net.vansencool.vanta.parser.ast.expression.ArrayInitializerExpression;
import net.vansencool.vanta.parser.ast.expression.AssignmentExpression;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.CastExpression;
import net.vansencool.vanta.parser.ast.expression.ClassLiteralExpression;
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
import net.vansencool.vanta.symbol.field.FieldSymbol;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import net.vansencool.vanta.symbol.type.TypeParameterSymbol;
import net.vansencool.vanta.symbol.type.TypeRef;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infers the resolved type of an expression. Walks the symbol layer
 * exposed by {@link TypeRegistry} so AST sources, classpath classes,
 * and asm bytes funnel through one shape.
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

    private static boolean isPrimitiveWidening(@NotNull String from, @NotNull String to) {
        if (from.equals(to)) return true;
        String order = "BSIJFD";
        int fi = order.indexOf(from);
        int ti = order.indexOf(to);
        if ("C".equals(from)) fi = order.indexOf("I") - 1;
        return fi >= 0 && ti > fi;
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
     * Applies the JLS 15.25 numeric rules to a conditional whose arms are
     * both primitive: equal types stay, a byte/short/char arm absorbs an int
     * literal arm that fits its range, byte with short yields short, and
     * everything else takes binary numeric promotion.
     */
    private @NotNull ResolvedType conditionalNumericType(@NotNull ResolvedType thenT, @NotNull ResolvedType elseT, @NotNull TernaryExpression ternary) {
        String a = thenT.descriptor();
        String b = elseT.descriptor();
        if (a.equals(b)) return thenT;
        if (isSubIntDesc(a) && "I".equals(b) && intLiteralFits(ternary.elseExpression(), a)) return thenT;
        if (isSubIntDesc(b) && "I".equals(a) && intLiteralFits(ternary.thenExpression(), b)) return elseT;
        if (("B".equals(a) && "S".equals(b)) || ("S".equals(a) && "B".equals(b))) return ResolvedType.SHORT;
        if ("D".equals(a) || "D".equals(b)) return ResolvedType.DOUBLE;
        if ("F".equals(a) || "F".equals(b)) return ResolvedType.FLOAT;
        if ("J".equals(a) || "J".equals(b)) return ResolvedType.LONG;
        if ("Z".equals(a) || "Z".equals(b)) return thenT;
        return ResolvedType.INT;
    }

    private static boolean isSubIntDesc(@NotNull String desc) {
        return "B".equals(desc) || "S".equals(desc) || "C".equals(desc);
    }

    /**
     * @return true when {@code arm} is an int literal whose value fits the range of {@code targetDesc}
     */
    private static boolean intLiteralFits(@NotNull Expression arm, @NotNull String targetDesc) {
        Expression e = arm;
        while (e instanceof ParenExpression p) e = p.expression();
        if (!(e instanceof LiteralExpression lit) || lit.literalType() != TokenType.INT_LITERAL) return false;
        int v;
        try {
            v = LiteralParser.parseIntLiteral(lit.value());
        } catch (RuntimeException ex) {
            return false;
        }
        return switch (targetDesc) {
            case "B" -> v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE;
            case "S" -> v >= Short.MIN_VALUE && v <= Short.MAX_VALUE;
            case "C" -> v >= Character.MIN_VALUE && v <= Character.MAX_VALUE;
            default -> false;
        };
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
        return descriptorToType(descriptor.substring(close + 1));
    }

    private @Nullable ResolvedType descriptorToType(@NotNull String desc) {
        if ("V".equals(desc)) return null;
        if (desc.startsWith("[")) return ResolvedType.fromDescriptor(desc);
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
                yield null;
            }
        };
    }

    private @NotNull ResolvedType refToResolved(@NotNull TypeRef ref) {
        String desc = ref.descriptor();
        if ("V".equals(desc)) return ResolvedType.VOID;
        if (ref.isPrimitive()) {
            ResolvedType prim = descriptorToType(desc);
            if (prim != null) return prim;
        }
        if (ref.arrayDimensions() > 0) {
            return ResolvedType.fromDescriptor(desc);
        }
        String internal = ref.internalName();
        if (internal == null) return ResolvedType.ofObject("java/lang/Object");
        if (ref.typeArguments().isEmpty()) return ResolvedType.ofObject(internal);
        ResolvedType base = ResolvedType.ofObject(internal);
        List<ResolvedType> args = new ArrayList<>(ref.typeArguments().size());
        for (TypeRef arg : ref.typeArguments()) args.add(refToResolved(arg));
        return base.withTypeArguments(args);
    }

    /**
     * Picks the best matching method on a symbol given the inferred argument
     * types. Walks the symbol super chain and any inherited interfaces.
     * Replaces the old reflection driven {@code getBestMethod}; falls back to
     * arity matching when arguments cannot be inferred.
     */
    private @Nullable MethodSymbol bestMethodOf(@NotNull TypeSymbol owner, @NotNull MethodCallExpression call) {
        TypeRegistry registry = classpathManager.typeRegistry();
        String name = call.methodName();
        int argCount = call.arguments().size();
        String[] argDescs = new String[argCount];
        boolean[] isNullArg = new boolean[argCount];
        boolean allInferred = true;
        for (int i = 0; i < argCount; i++) {
            ResolvedType t = infer(call.arguments().get(i));
            if (t == null) {
                allInferred = false;
                break;
            }
            argDescs[i] = t.descriptor();
            isNullArg[i] = t == ResolvedType.NULL;
        }

        MethodSymbol best = null;
        Set<String> visited = new HashSet<>();
        for (MethodSymbol m : collectMethodsByName(owner, name, visited)) {
            if (m.isBridge()) continue;
            int paramCount = m.parameterTypes().size();
            boolean isVarargs = m.isVarargs();
            if (!isVarargs && paramCount != argCount) continue;
            if (isVarargs && argCount < paramCount - 1) continue;
            if (allInferred && !isApplicable(m, argDescs, isNullArg)) continue;
            if (best == null) {
                best = m;
                continue;
            }
            boolean bestEffVarargs = best.isVarargs() && !invokedInArrayForm(best, argDescs, allInferred);
            boolean mEffVarargs = m.isVarargs() && !invokedInArrayForm(m, argDescs, allInferred);
            if (bestEffVarargs != mEffVarargs) {
                if (bestEffVarargs) best = m;
                continue;
            }
            String bestOwner = best.owner().internalName();
            String mOwner = m.owner().internalName();
            if (mOwner.equals(owner.internalName()) && !bestOwner.equals(owner.internalName())) {
                best = m;
            } else if (!isSubtypeOrEqual(bestOwner, mOwner, registry)) {
                best = m;
            } else if (isMethodMoreSpecific(m, best, argDescs, allInferred)) {
                best = m;
            }
        }
        return best;
    }

    private @NotNull List<MethodSymbol> collectMethodsByName(@NotNull TypeSymbol owner, @NotNull String name, @NotNull Set<String> visited) {
        List<MethodSymbol> out = new ArrayList<>();
        TypeSymbol cur = owner;
        while (cur != null && visited.add(cur.internalName())) {
            for (MethodSymbol m : cur.methods()) {
                if (m.name().equals(name)) out.add(m);
            }
            cur = cur.superclass();
        }
        if (owner.isInterface()) {
            TypeSymbol objectClass = classpathManager.typeRegistry().lookup("java/lang/Object");
            if (objectClass != null && visited.add(objectClass.internalName())) {
                for (MethodSymbol m : objectClass.methods()) {
                    if (!m.name().equals(name)) continue;
                    if (m.isStatic()) continue;
                    if ((m.access() & java.lang.reflect.Modifier.PUBLIC) == 0) continue;
                    out.add(m);
                }
            }
        }
        for (TypeSymbol iface : owner.interfaces()) {
            if (!visited.add(iface.internalName())) continue;
            for (MethodSymbol m : iface.methods()) {
                if (m.name().equals(name)) out.add(m);
            }
        }
        return out;
    }

    private boolean isSubtypeOrEqual(@NotNull String a, @NotNull String b, @NotNull TypeRegistry registry) {
        if (a.equals(b)) return true;
        TypeSymbol sym = registry.lookup(a);
        while (sym != null) {
            if (sym.internalName().equals(b)) return true;
            for (TypeSymbol iface : sym.interfaces()) {
                if (iface.internalName().equals(b)) return true;
                if (isSubtypeOrEqual(iface.internalName(), b, registry)) return true;
            }
            sym = sym.superclass();
        }
        return false;
    }

    private boolean isApplicable(@NotNull MethodSymbol m, @NotNull String @NotNull [] argDescs, boolean @NotNull [] isNullArg) {
        List<TypeRef> params = m.parameterTypes();
        boolean isVarargs = m.isVarargs();
        int paramCount = params.size();
        int argCount = argDescs.length;
        int fixedCount = isVarargs ? paramCount - 1 : paramCount;
        for (int i = 0; i < fixedCount; i++) {
            String paramDesc = params.get(i).descriptor();
            if (isNullArg[i]) {
                if (params.get(i).isPrimitive()) return false;
                continue;
            }
            if (!MethodResolver.isDescriptorAssignable(argDescs[i], paramDesc)) return false;
        }
        if (!isVarargs) return true;
        TypeRef varargsParam = params.get(paramCount - 1);
        String varargsArrayDesc = varargsParam.descriptor();
        if (argCount == paramCount && MethodResolver.isDescriptorAssignable(argDescs[argCount - 1], varargsArrayDesc))
            return true;
        if (!varargsArrayDesc.startsWith("[")) return true;
        String varargsElemDesc = varargsArrayDesc.substring(1);
        boolean elemPrimitive = varargsElemDesc.length() == 1;
        for (int i = fixedCount; i < argCount; i++) {
            if (isNullArg[i]) {
                if (elemPrimitive) return false;
                continue;
            }
            if (!MethodResolver.isDescriptorAssignable(argDescs[i], varargsElemDesc)) return false;
        }
        return true;
    }

    /**
     * True when {@code cand}'s parameter types are all assignable to {@code other}'s
     * but not the reverse. Used so {@code Math.max(long, long)} is preferred over
     * {@code Math.max(double, double)} when both would accept long arguments.
     */
    private boolean isMethodMoreSpecific(@NotNull MethodSymbol cand, @NotNull MethodSymbol other, String @Nullable [] argDescs, boolean argsInferred) {
        List<TypeRef> cp = cand.parameterTypes();
        List<TypeRef> op = other.parameterTypes();
        if (cp.size() != op.size()) return false;
        boolean candNarrower = false;
        for (int i = 0; i < cp.size(); i++) {
            String cpDesc = cp.get(i).descriptor();
            String opDesc = op.get(i).descriptor();
            if (cpDesc.equals(opDesc)) continue;
            boolean argIsPrim = argsInferred && argDescs != null && i < argDescs.length && argDescs[i] != null && argDescs[i].length() == 1;
            boolean cpPrim = cp.get(i).isPrimitive();
            boolean opPrim = op.get(i).isPrimitive();
            if (cpPrim && !opPrim) {
                if (argIsPrim) {
                    candNarrower = true;
                    continue;
                }
                return false;
            }
            if (!cpPrim && opPrim) {
                if (argIsPrim) return false;
                candNarrower = true;
                continue;
            }
            if (cpPrim && opPrim) {
                if (!isPrimitiveWidening(cpDesc, opDesc)) return false;
                candNarrower = true;
                continue;
            }
            return false;
        }
        return candNarrower;
    }

    /**
     * Infers the type of an expression.
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

    private @Nullable ResolvedType inferUncached(@NotNull Expression expr) {
        if (expr instanceof LiteralExpression lit) return inferLiteral(lit);
        if (expr instanceof NameExpression name) return inferName(name);
        if (expr instanceof ThisExpression) return ResolvedType.ofObject(classInternalName);
        if (expr instanceof SuperExpression) {
            if (superInternalName != null) return ResolvedType.ofObject(superInternalName);
            TypeSymbol selfSym = classpathManager.typeRegistry().lookup(classInternalName);
            if (selfSym != null && selfSym.superclass() != null)
                return ResolvedType.ofObject(selfSym.superclass().internalName());
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
            if (thenT.isPrimitive() && elseT.isPrimitive()) return conditionalNumericType(thenT, elseT, ternary);
            return thenT;
        }
        if (expr instanceof InstanceofExpression) return ResolvedType.BOOLEAN;
        if (expr instanceof ClassLiteralExpression) return ResolvedType.ofObject("java/lang/Class");
        if (expr instanceof ArrayAccessExpression arrayAccess) return inferArrayAccess(arrayAccess);
        if (expr instanceof ArrayInitializerExpression init) {
            if (init.elements().isEmpty()) return null;
            ResolvedType elemType = infer(init.elements().get(0));
            if (elemType == null) return null;
            return elemType.asArray(1);
        }
        if (expr instanceof BinaryExpression binary) return inferBinary(binary);
        if (expr instanceof UnaryExpression unary) return inferUnary(unary);
        if (expr instanceof AssignmentExpression assign) {
            ResolvedType targetType = infer(assign.target());
            return targetType != null ? targetType : infer(assign.value());
        }
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
            ResolvedType outerField = lookupField(enclosingOuter, name.name(), null);
            if (outerField != null) return outerField;
            if (nestedClassFields != null) {
                Map<String, ResolvedType> outerFields = nestedClassFields.get(enclosingOuter);
                if (outerFields != null && outerFields.containsKey(name.name())) return outerFields.get(name.name());
            }
        }
        String staticOwner = typeResolver.resolveStaticFieldOwner(name.name());
        if (staticOwner != null) {
            ResolvedType staticField = lookupField(staticOwner, name.name(), null);
            return staticField;
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
     * {@link NameExpression}.
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
        TypeRegistry registry = classpathManager.typeRegistry();
        if (call.target() == null) {
            ResolvedType selfResult = lookupInstanceReturn(classInternalName, call, null);
            if (selfResult != null) return selfResult;
            if (selfMethods != null) {
                SelfMethodInfo info = selfMethods.get(call.methodName() + ":" + call.arguments().size());
                if (info != null) return descriptorReturnType(info.descriptor());
            }
            String staticOwner = typeResolver.resolveStaticMethodOwner(call.methodName());
            if (staticOwner != null) {
                ResolvedType result = lookupStaticReturn(staticOwner, call);
                if (result != null) return result;
            }
            String outer = enclosingOuterInternal();
            String checkOuter = outer != null ? outer : enclosingStaticOuter;
            if (checkOuter != null) {
                ResolvedType outerResult = lookupInstanceReturn(checkOuter, call, null);
                if (outerResult != null) return outerResult;
                if (nestedClassMethods != null) {
                    Map<String, SelfMethodInfo> nm = nestedClassMethods.get(checkOuter);
                    if (nm != null) {
                        SelfMethodInfo info = nm.get(call.methodName() + ":" + call.arguments().size());
                        if (info != null) return descriptorReturnType(info.descriptor());
                    }
                }
            }
            return lookupInstanceReturn("java/lang/Object", call, null);
        }
        ResolvedType targetType = infer(call.target());
        if (targetType != null && targetType.internalName() != null && nestedClassMethods != null) {
            Map<String, SelfMethodInfo> nm = nestedClassMethods.get(targetType.internalName());
            if (nm != null) {
                SelfMethodInfo info = nm.get(call.methodName() + ":" + call.arguments().size());
                if (info != null) return descriptorReturnType(info.descriptor());
            }
        }
        if (targetType == null && call.target() instanceof NameExpression nameTarget) {
            ResolvedType resolved = typeResolver.resolve(new TypeNode(nameTarget.name(), null, 0, call.line()));
            if (resolved.internalName() != null) {
                return lookupStaticReturn(resolved.internalName(), call);
            }
            return null;
        }
        if (targetType == null && call.target() instanceof FieldAccessExpression fa) {
            String fqn = flattenFieldAccessName(fa);
            if (fqn != null && fqn.indexOf('.') > 0) {
                String internal = typeResolver.resolveInternalName(new TypeNode(fqn, null, 0, call.line()));
                if (!"I".equals(internal)) {
                    return lookupStaticReturn(internal, call);
                }
            }
        }
        if (targetType != null && targetType.descriptor().startsWith("[")) {
            if ("clone".equals(call.methodName()) && call.arguments().isEmpty()) return targetType;
            return lookupInstanceReturn("java/lang/Object", call, null);
        }
        if (targetType == null || targetType.internalName() == null) return null;
        return lookupInstanceReturn(targetType.internalName(), call, targetType);
    }

    private @Nullable ResolvedType lookupStaticReturn(@NotNull String ownerInternal, @NotNull MethodCallExpression call) {
        TypeSymbol owner = classpathManager.typeRegistry().lookup(ownerInternal);
        if (owner == null) return null;
        MethodSymbol best = bestMethodOf(owner, call);
        if (best == null) return null;
        if (!best.isStatic()) return null;
        return resolveReturnType(best, null, call);
    }

    private @Nullable ResolvedType lookupInstanceReturn(@NotNull String ownerInternal, @NotNull MethodCallExpression call, @Nullable ResolvedType receiverType) {
        TypeSymbol owner = classpathManager.typeRegistry().lookup(ownerInternal);
        if (owner == null) return null;
        MethodSymbol best = bestMethodOf(owner, call);
        if (best == null) return null;
        return resolveReturnType(best, receiverType, call);
    }

    /**
     * Resolves a method's return type, substituting class level type
     * variables from {@code receiverType.typeArguments()} when the return
     * type is a type variable. Method level type variables are bound from
     * the call site arguments when the only mention is in a parameter
     * position.
     */
    private @Nullable ResolvedType resolveReturnType(@NotNull MethodSymbol method, @Nullable ResolvedType receiverType, @Nullable MethodCallExpression call) {
        TypeRef ret = method.returnType();
        if (ret.isTypeVariable()) {
            String name = ret.typeVariableName();
            if (name != null && receiverType != null && receiverType.typeArguments() != null) {
                List<TypeParameterSymbol> classParams = method.owner().typeParameters();
                for (int i = 0; i < classParams.size() && i < receiverType.typeArguments().size(); i++) {
                    if (classParams.get(i).name().equals(name)) return withReturnDims(receiverType.typeArguments().get(i), ret);
                }
            }
            if (call != null) {
                ResolvedType fromArgs = bindMethodTypeVariableFromCallArgs(method, name, call);
                if (fromArgs != null) return withReturnDims(fromArgs, ret);
            }
        }
        ResolvedType base = refToResolved(ret);
        if (!ret.typeArguments().isEmpty() && base.internalName() != null) {
            List<ResolvedType> args = new ArrayList<>(ret.typeArguments().size());
            for (TypeRef arg : ret.typeArguments()) args.add(substituteTypeRef(arg, method, receiverType, call));
            base = base.withTypeArguments(args);
        }
        return base;
    }

    /**
     * Reapplies the array dimensions of a {@code T[]} shaped return to the bound type variable value.
     */
    private static @NotNull ResolvedType withReturnDims(@NotNull ResolvedType bound, @NotNull TypeRef ret) {
        return ret.arrayDimensions() > 0 ? bound.asArray(ret.arrayDimensions()) : bound;
    }

    /**
     * True when a varargs method is being called with its trailing array passed directly, which competes as fixed arity per JLS 15.12.2.
     */
    private boolean invokedInArrayForm(@NotNull MethodSymbol m, String @NotNull [] argDescs, boolean argsInferred) {
        if (!m.isVarargs() || !argsInferred) return false;
        List<TypeRef> params = m.parameterTypes();
        if (params.isEmpty() || argDescs.length != params.size()) return false;
        String last = argDescs[argDescs.length - 1];
        if (last == null || !last.startsWith("[")) return false;
        return MethodResolver.isDescriptorAssignable(last, params.get(params.size() - 1).descriptor());
    }

    /**
     * Binds a method level type variable {@code tvName} by scanning
     * {@code method.parameterTypes()} for the first parameter declared as that
     * type variable and inferring the corresponding call site argument's type.
     */
    private @Nullable ResolvedType bindMethodTypeVariableFromCallArgs(@NotNull MethodSymbol method, @Nullable String tvName, @NotNull MethodCallExpression call) {
        if (tvName == null) return null;
        List<TypeRef> params = method.parameterTypes();
        List<Expression> args = call.arguments();
        for (int i = 0; i < params.size() && i < args.size(); i++) {
            TypeRef p = params.get(i);
            if (p.isTypeVariable() && tvName.equals(p.typeVariableName())) {
                ResolvedType argType = infer(args.get(i));
                if (argType == null || argType == ResolvedType.NULL) continue;
                if (p.arrayDimensions() > 0) {
                    String desc = argType.descriptor();
                    int strip = 0;
                    while (strip < p.arrayDimensions() && strip < desc.length() && desc.charAt(strip) == '[') strip++;
                    if (strip == p.arrayDimensions()) return ResolvedType.fromDescriptor(desc.substring(strip));
                    continue;
                }
                return argType;
            }
        }
        return null;
    }

    private @NotNull ResolvedType substituteTypeRef(@NotNull TypeRef ref, @NotNull MethodSymbol method, @Nullable ResolvedType receiverType, @Nullable MethodCallExpression call) {
        if (ref.isTypeVariable()) {
            String name = ref.typeVariableName();
            if (name != null && receiverType != null && receiverType.typeArguments() != null) {
                List<TypeParameterSymbol> classParams = method.owner().typeParameters();
                for (int i = 0; i < classParams.size() && i < receiverType.typeArguments().size(); i++) {
                    if (classParams.get(i).name().equals(name)) return receiverType.typeArguments().get(i);
                }
            }
            if (call != null) {
                ResolvedType fromArgs = bindMethodTypeVariableFromCallArgs(method, name, call);
                if (fromArgs != null) return fromArgs;
            }
            return ResolvedType.ofObject("java/lang/Object");
        }
        ResolvedType base = refToResolved(ref);
        if (ref.typeArguments().isEmpty() || base.internalName() == null) return base;
        List<ResolvedType> args = new ArrayList<>(ref.typeArguments().size());
        for (TypeRef arg : ref.typeArguments()) args.add(substituteTypeRef(arg, method, receiverType, call));
        return base.withTypeArguments(args);
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
        if (targetType != null && targetType.isArray() && "length".equals(fieldAccess.fieldName()))
            return ResolvedType.INT;
        if (targetType == null && fieldAccess.target() instanceof NameExpression nameTarget && scope.resolve(nameTarget.name()) == null) {
            ResolvedType resolved = typeResolver.resolve(new TypeNode(nameTarget.name(), null, 0, fieldAccess.line()));
            if (resolved.internalName() != null) targetType = resolved;
        }
        if (targetType == null || targetType.internalName() == null) return null;
        String nestedCandidate = targetType.internalName() + "$" + fieldAccess.fieldName();
        TypeSymbol nestedSym = classpathManager.typeRegistry().lookup(nestedCandidate);
        if (nestedSym != null) return ResolvedType.ofObject(nestedCandidate);
        if (targetType.internalName().equals(classInternalName)) {
            ResolvedType ownField = classFields.get(fieldAccess.fieldName());
            if (ownField != null) return ownField;
        }
        return lookupField(targetType.internalName(), fieldAccess.fieldName(), targetType);
    }

    /**
     * Walks the type symbol super chain and interfaces to find a matching
     * field, then substitutes any class level type variables in the field
     * type with the receiver's type arguments.
     */
    private @Nullable ResolvedType lookupField(@NotNull String ownerInternal, @NotNull String fieldName, @Nullable ResolvedType receiverType) {
        TypeRegistry registry = classpathManager.typeRegistry();
        TypeSymbol owner = registry.lookup(ownerInternal);
        Set<String> visited = new HashSet<>();
        while (owner != null && visited.add(owner.internalName())) {
            for (FieldSymbol f : owner.fields()) {
                if (f.name().equals(fieldName)) return resolveFieldType(f, receiverType);
            }
            for (TypeSymbol iface : owner.interfaces()) {
                if (!visited.add(iface.internalName())) continue;
                for (FieldSymbol f : iface.fields()) {
                    if (f.name().equals(fieldName)) return resolveFieldType(f, receiverType);
                }
            }
            owner = owner.superclass();
        }
        return null;
    }

    private @NotNull ResolvedType resolveFieldType(@NotNull FieldSymbol field, @Nullable ResolvedType receiverType) {
        TypeRef type = field.type();
        if (type.isTypeVariable() && receiverType != null && receiverType.typeArguments() != null) {
            String name = type.typeVariableName();
            if (name != null) {
                List<TypeParameterSymbol> classParams = field.owner().typeParameters();
                for (int i = 0; i < classParams.size() && i < receiverType.typeArguments().size(); i++) {
                    if (classParams.get(i).name().equals(name)) return receiverType.typeArguments().get(i);
                }
            }
        }
        ResolvedType base = refToResolved(type);
        if (!type.typeArguments().isEmpty() && base.internalName() != null) {
            List<ResolvedType> args = new ArrayList<>(type.typeArguments().size());
            for (TypeRef arg : type.typeArguments()) args.add(substituteFieldArg(arg, field, receiverType));
            base = base.withTypeArguments(args);
        }
        return base;
    }

    private @NotNull ResolvedType substituteFieldArg(@NotNull TypeRef ref, @NotNull FieldSymbol field, @Nullable ResolvedType receiverType) {
        if (ref.isTypeVariable() && receiverType != null && receiverType.typeArguments() != null) {
            String name = ref.typeVariableName();
            if (name != null) {
                List<TypeParameterSymbol> classParams = field.owner().typeParameters();
                for (int i = 0; i < classParams.size() && i < receiverType.typeArguments().size(); i++) {
                    if (classParams.get(i).name().equals(name)) return receiverType.typeArguments().get(i);
                }
            }
            return ResolvedType.ofObject("java/lang/Object");
        }
        ResolvedType base = refToResolved(ref);
        if (ref.typeArguments().isEmpty() || base.internalName() == null) return base;
        List<ResolvedType> args = new ArrayList<>(ref.typeArguments().size());
        for (TypeRef arg : ref.typeArguments()) args.add(substituteFieldArg(arg, field, receiverType));
        return base.withTypeArguments(args);
    }

    private @Nullable String enclosingOuterInternal() {
        return enclosingOuterInternal;
    }
}
