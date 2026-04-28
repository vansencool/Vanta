package net.vansencool.vanta.codegen.classes.enumswitch;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.EnumConstant;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.declaration.TypeKind;
import net.vansencool.vanta.parser.ast.expression.ArrayAccessExpression;
import net.vansencool.vanta.parser.ast.expression.AssignmentExpression;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.CastExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.InstanceofExpression;
import net.vansencool.vanta.parser.ast.expression.LambdaExpression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.NewArrayExpression;
import net.vansencool.vanta.parser.ast.expression.NewExpression;
import net.vansencool.vanta.parser.ast.expression.ParenExpression;
import net.vansencool.vanta.parser.ast.expression.SwitchExpression;
import net.vansencool.vanta.parser.ast.expression.TernaryExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.CatchClause;
import net.vansencool.vanta.parser.ast.statement.DoWhileStatement;
import net.vansencool.vanta.parser.ast.statement.ExpressionStatement;
import net.vansencool.vanta.parser.ast.statement.ForEachStatement;
import net.vansencool.vanta.parser.ast.statement.ForStatement;
import net.vansencool.vanta.parser.ast.statement.IfStatement;
import net.vansencool.vanta.parser.ast.statement.LabeledStatement;
import net.vansencool.vanta.parser.ast.statement.ReturnStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.statement.SwitchCase;
import net.vansencool.vanta.parser.ast.statement.SwitchStatement;
import net.vansencool.vanta.parser.ast.statement.SynchronizedStatement;
import net.vansencool.vanta.parser.ast.statement.ThrowStatement;
import net.vansencool.vanta.parser.ast.statement.TryStatement;
import net.vansencool.vanta.parser.ast.statement.VariableDeclarationStatement;
import net.vansencool.vanta.parser.ast.statement.VariableDeclarator;
import net.vansencool.vanta.parser.ast.statement.WhileStatement;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AST walker that finds every {@code switch} (statement or expression) whose
 * selector resolves to an external enum and records each referenced constant
 * in source order. The populated map becomes the input to synthetic
 * {@code $SwitchMap$Enum} field and {@code Outer$1} static-init emission so
 * javac-compatible switch layouts result.
 */
public final class EnumSwitchScanner {

    private final @NotNull String outerInternal;
    private final @NotNull LinkedHashMap<String, LinkedHashMap<String, Integer>> enumSwitchMaps;
    private final @NotNull TypeResolver typeResolver;
    private final @NotNull ClasspathManager classpathManager;
    private final @NotNull ArrayDeque<Map<String, String>> localScopes = new ArrayDeque<>();
    private @NotNull ClassDeclaration selfOwner;

    /**
     * Binds this scanner to the outer class whose enums are being scanned,
     * the map it writes into, and the shared resolver/classpath used for
     * selector-type inference.
     *
     * @param outerInternal    internal name of the owning top-level class
     * @param enumSwitchMaps   accumulator mapping enum internal name to ordered constant set
     * @param typeResolver     resolver used to resolve selector type expressions
     * @param classpathManager classpath manager used to load classes and inspect methods
     */
    public EnumSwitchScanner(@NotNull String outerInternal,
                             @NotNull LinkedHashMap<String, LinkedHashMap<String, Integer>> enumSwitchMaps,
                             @NotNull TypeResolver typeResolver,
                             @NotNull ClasspathManager classpathManager) {
        this.outerInternal = outerInternal;
        this.enumSwitchMaps = enumSwitchMaps;
        this.typeResolver = typeResolver;
        this.classpathManager = classpathManager;
        this.selfOwner = dummyDecl();
    }

    /**
     * Entry point that tracks the current self-owner so no-target method
     * calls can be resolved against the enclosing class, then walks the body.
     *
     * @param classDecl class to scan
     */
    public void scanClass(@NotNull ClassDeclaration classDecl) {
        ClassDeclaration prev = selfOwner;
        selfOwner = classDecl;
        try {
            scanClassBody(classDecl);
        } finally {
            selfOwner = prev;
        }
    }

    /**
     * Placeholder declaration used before {@link #scanClass(ClassDeclaration)}
     * has set a real owner. Avoids null-check noise elsewhere.
     *
     * @return empty class declaration stub
     */
    private @NotNull ClassDeclaration dummyDecl() {
        return new ClassDeclaration("$",
                Opcodes.ACC_PUBLIC,
                null, null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                TypeKind.CLASS, null, null, 0);
    }

    /**
     * Resolves a simple name against the active local-scope stack.
     *
     * @param name identifier to look up
     * @return source-level type name the identifier was declared with, or null
     */
    private @Nullable String lookupLocal(@NotNull String name) {
        for (Map<String, String> frame : localScopes) {
            String t = frame.get(name);
            if (t != null) return t;
        }
        return null;
    }

    /**
     * Walks every member of {@code classDecl}, pushing parameter scopes for
     * methods and recursing into nested class declarations so nested switches
     * get their own self-owner.
     *
     * @param classDecl declaration being scanned
     */
    private void scanClassBody(@NotNull ClassDeclaration classDecl) {
        for (AstNode member : classDecl.members()) {
            if (member instanceof MethodDeclaration md && md.body() != null) {
                Map<String, String> paramScope = new HashMap<>();
                for (Parameter p : md.parameters()) paramScope.put(p.name(), p.type().name());
                localScopes.push(paramScope);
                try {
                    scanBlock(md.body());
                } finally {
                    localScopes.pop();
                }
            } else if (member instanceof FieldDeclaration fd) {
                for (FieldDeclarator d : fd.declarators()) {
                    if (d.initializer() != null) scanExpr(d.initializer());
                }
            } else if (member instanceof ClassDeclaration nested) {
                scanClass(nested);
            }
        }
        if (classDecl.enumConstants() != null) {
            for (EnumConstant ec : classDecl.enumConstants()) {
                for (Expression arg : ec.arguments()) scanExpr(arg);
                if (ec.classBody() != null) {
                    for (AstNode m : ec.classBody()) {
                        if (m instanceof MethodDeclaration md && md.body() != null) scanBlock(md.body());
                    }
                }
            }
        }
    }

    /**
     * Walks each statement in {@code block} under a fresh local scope so
     * block-local variable declarations only shadow within the block.
     *
     * @param block block statement to walk
     */
    private void scanBlock(@NotNull BlockStatement block) {
        localScopes.push(new HashMap<>());
        try {
            for (Statement s : block.statements()) scanStmt(s);
        } finally {
            localScopes.pop();
        }
    }

    /**
     * Per-statement dispatch. Registers any enum switch and recurses through
     * every statement shape that can contain more statements.
     *
     * @param s statement to inspect
     */
    private void scanStmt(@NotNull Statement s) {
        if (s instanceof SwitchStatement sw) {
            registerEnumSwitch(sw.selector(), sw.cases());
            scanExpr(sw.selector());
            for (SwitchCase sc : sw.cases()) {
                for (Statement inner : sc.statements()) scanStmt(inner);
            }
            return;
        }
        if (s instanceof BlockStatement bs) {
            scanBlock(bs);
            return;
        }
        if (s instanceof IfStatement ifs) {
            scanExpr(ifs.condition());
            scanStmt(ifs.thenBranch());
            if (ifs.elseBranch() != null) scanStmt(ifs.elseBranch());
            return;
        }
        if (s instanceof ForStatement f) {
            if (f.initializers() != null) for (Statement st : f.initializers()) scanStmt(st);
            if (f.condition() != null) scanExpr(f.condition());
            if (f.updaters() != null) for (Expression ue : f.updaters()) scanExpr(ue);
            scanStmt(f.body());
            return;
        }
        if (s instanceof ForEachStatement fe) {
            scanExpr(fe.iterable());
            scanStmt(fe.body());
            return;
        }
        if (s instanceof WhileStatement ws) {
            scanExpr(ws.condition());
            scanStmt(ws.body());
            return;
        }
        if (s instanceof DoWhileStatement dw) {
            scanStmt(dw.body());
            scanExpr(dw.condition());
            return;
        }
        if (s instanceof TryStatement ts) {
            scanBlock(ts.tryBlock());
            for (CatchClause cc : ts.catchClauses()) scanBlock(cc.body());
            if (ts.finallyBlock() != null) scanBlock(ts.finallyBlock());
            return;
        }
        if (s instanceof SynchronizedStatement ss) {
            scanExpr(ss.lock());
            scanBlock(ss.body());
            return;
        }
        if (s instanceof LabeledStatement ls) {
            scanStmt(ls.statement());
            return;
        }
        if (s instanceof VariableDeclarationStatement vds) {
            Map<String, String> top = localScopes.peek();
            for (VariableDeclarator vd : vds.declarators()) {
                if (top != null) top.put(vd.name(), vds.type().name());
                if (vd.initializer() != null) scanExpr(vd.initializer());
            }
            return;
        }
        if (s instanceof ExpressionStatement es) {
            scanExpr(es.expression());
            return;
        }
        if (s instanceof ReturnStatement rs) {
            if (rs.value() != null) scanExpr(rs.value());
            return;
        }
        if (s instanceof ThrowStatement th) {
            scanExpr(th.expression());
        }
    }

    /**
     * Per-expression dispatch. Detects switch expressions and recurses
     * through the rest of the expression tree.
     *
     * @param e expression to inspect
     */
    private void scanExpr(@NotNull Expression e) {
        if (e instanceof SwitchExpression swe) {
            registerEnumSwitch(swe.selector(), swe.cases());
            scanExpr(swe.selector());
            for (SwitchCase sc : swe.cases()) {
                for (Statement inner : sc.statements()) scanStmt(inner);
            }
            return;
        }
        if (e instanceof BinaryExpression b) {
            scanExpr(b.left());
            scanExpr(b.right());
            return;
        }
        if (e instanceof UnaryExpression u) {
            scanExpr(u.operand());
            return;
        }
        if (e instanceof TernaryExpression t) {
            scanExpr(t.condition());
            scanExpr(t.thenExpression());
            scanExpr(t.elseExpression());
            return;
        }
        if (e instanceof MethodCallExpression mc) {
            if (mc.target() != null) scanExpr(mc.target());
            for (Expression a : mc.arguments()) scanExpr(a);
            return;
        }
        if (e instanceof FieldAccessExpression fa) {
            scanExpr(fa.target());
            return;
        }
        if (e instanceof ArrayAccessExpression aa) {
            scanExpr(aa.array());
            scanExpr(aa.index());
            return;
        }
        if (e instanceof AssignmentExpression ae) {
            scanExpr(ae.target());
            scanExpr(ae.value());
            return;
        }
        if (e instanceof CastExpression ce) {
            scanExpr(ce.expression());
            return;
        }
        if (e instanceof ParenExpression pe) {
            scanExpr(pe.expression());
            return;
        }
        if (e instanceof NewExpression ne) {
            for (Expression a : ne.arguments()) scanExpr(a);
            if (ne.anonymousClassBody() != null) {
                for (AstNode m : ne.anonymousClassBody()) {
                    if (m instanceof MethodDeclaration md && md.body() != null) scanBlock(md.body());
                }
            }
            return;
        }
        if (e instanceof NewArrayExpression na) {
            for (Expression d : na.dimensionExpressions()) scanExpr(d);
            return;
        }
        if (e instanceof InstanceofExpression io) {
            scanExpr(io.expression());
            return;
        }
        if (e instanceof LambdaExpression le && le.body() != null) {
            if (le.body() instanceof BlockStatement bb) scanBlock(bb);
            else if (le.body() instanceof Expression ee) scanExpr(ee);
        }
    }

    /**
     * Records every enum constant referenced by the switch whose selector is
     * {@code selector} into {@link #enumSwitchMaps}, but only when the
     * selector is an external enum (same-compilation-unit enums use direct
     * {@code ordinal()} switches instead).
     *
     * @param selector switch selector expression
     * @param cases    parsed {@code case} labels
     */
    private void registerEnumSwitch(@NotNull Expression selector, @NotNull List<SwitchCase> cases) {
        ResolvedType selType;
        try {
            selType = selectorType(selector);
        } catch (Throwable t) {
            return;
        }
        if (selType == null || selType.internalName() == null) return;
        String enumInternal = selType.internalName();
        if (!isExternalEnum(enumInternal)) return;
        LinkedHashMap<String, Integer> map = enumSwitchMaps.computeIfAbsent(enumInternal, k -> new LinkedHashMap<>());
        for (SwitchCase sc : cases) {
            if (sc.isDefault() || sc.labels() == null) continue;
            for (Expression lab : sc.labels()) {
                String name = enumConstantName(lab);
                if (name != null && !map.containsKey(name)) map.put(name, map.size() + 1);
            }
        }
    }

    /**
     * Best-effort resolution of {@code selector}'s static type, restricted to
     * the enum shapes the scanner cares about. Method-call selectors walk the
     * return-type chain; name/field expressions consult the resolver.
     *
     * @param selector switch selector
     * @return resolved enum type or null when the selector isn't a known enum
     */
    private @Nullable ResolvedType selectorType(@NotNull Expression selector) {
        if (selector instanceof MethodCallExpression mc) {
            ResolvedType r = inferMethodCallReturnType(mc);
            if (r != null && r.internalName() != null && isEnum(r.internalName())) return r;
        }
        if (selector instanceof NameExpression ne) {
            String name = ne.name();
            ResolvedType via = typeResolver.resolve(new TypeNode(name, null, 0, ne.line()));
            if (via.internalName() != null && isEnum(via.internalName())) return via;
        }
        if (selector instanceof FieldAccessExpression fa) {
            String name = fa.fieldName();
            Expression tgt = fa.target();
            if (tgt instanceof NameExpression tn) {
                ResolvedType tt = typeResolver.resolve(new TypeNode(tn.name(), null, 0, tn.line()));
                if (tt.internalName() != null) {
                    Class<?> c = classpathManager.loadClass(tt.internalName());
                    if (c != null) {
                        try {
                            Field f = c.getField(name);
                            Class<?> ft = f.getType();
                            if (ft.isEnum()) return ResolvedType.ofObject(ft.getName().replace('.', '/'));
                        } catch (ReflectiveOperationException | LinkageError ignored) {
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Infers the return type of a chained method call, restricted to the
     * enum-returning paths the scanner cares about. Defers to
     * {@link #inferSelfMethodReturn(MethodCallExpression)} for no-target
     * calls against the enclosing class.
     *
     * @param mc method-call expression being examined
     * @return resolved return type when the call's tail method returns an enum, else null
     */
    private @Nullable ResolvedType inferMethodCallReturnType(@NotNull MethodCallExpression mc) {
        ResolvedType target = null;
        if (mc.target() instanceof MethodCallExpression inner) target = inferMethodCallReturnType(inner);
        else if (mc.target() instanceof NameExpression ne) {
            String localType = lookupLocal(ne.name());
            if (localType != null) {
                ResolvedType rt = typeResolver.resolve(new TypeNode(localType, null, 0, ne.line()));
                if (rt.internalName() != null && classpathManager.loadClass(rt.internalName()) != null) {
                    target = rt;
                }
            }
            if (target == null) {
                ResolvedType maybe = typeResolver.resolve(new TypeNode(ne.name(), null, 0, ne.line()));
                if (maybe.internalName() != null && classpathManager.loadClass(maybe.internalName()) != null) {
                    target = maybe;
                }
            }
        } else if (mc.target() == null) {
            return inferSelfMethodReturn(mc);
        }
        if (target == null || target.internalName() == null) return null;
        Class<?> c = classpathManager.loadClass(target.internalName());
        if (c == null) return null;
        Method[] methods;
        try {
            methods = c.getMethods();
        } catch (LinkageError ignored) {
            return null;
        }
        for (Method m : methods) {
            if (!m.getName().equals(mc.methodName())) continue;
            if (m.getParameterCount() != mc.arguments().size()) continue;
            Class<?> ret = m.getReturnType();
            if (ret.isEnum()) return ResolvedType.ofObject(ret.getName().replace('.', '/'));
            return null;
        }
        return null;
    }

    /**
     * Looks up the return type of a no-target method call by scanning the
     * enclosing class declaration's own methods. Lets the scanner detect
     * enum selectors that come from self calls like
     * {@code switch (current().type())}, where {@code current()} is
     * declared in the same source file.
     *
     * @param mc method-call expression whose target is null
     * @return resolved return type of the matching self method, or null
     */
    private @Nullable ResolvedType inferSelfMethodReturn(@NotNull MethodCallExpression mc) {
        for (AstNode member : selfOwner.members()) {
            if (!(member instanceof MethodDeclaration md)) continue;
            if (!md.name().equals(mc.methodName())) continue;
            if (md.parameters().size() != mc.arguments().size()) continue;
            return typeResolver.resolve(md.returnType());
        }
        return null;
    }

    /**
     * True when {@code internalName} can be loaded and is an enum.
     *
     * @param internalName JVM internal class name
     * @return true when the class is an enum
     */
    private boolean isEnum(@NotNull String internalName) {
        Class<?> c = classpathManager.loadClass(internalName);
        return c != null && c.isEnum();
    }

    /**
     * True when {@code enumInternal} is an enum declared outside the
     * currently-generating top-level class. Same-compilation-unit enums
     * short-circuit to {@code ordinal()} switches instead.
     *
     * @param enumInternal candidate enum internal name
     * @return true when the enum is external to the owning top-level class
     */
    private boolean isExternalEnum(@NotNull String enumInternal) {
        if (!isEnum(enumInternal)) return false;
        if (enumInternal.equals(outerInternal)) return false;
        return !enumInternal.startsWith(outerInternal + "$");
    }

    /**
     * Extracts the constant name from a case label expression.
     *
     * @param lab case label
     * @return enum-constant identifier, or null when the label isn't a simple name
     */
    private @Nullable String enumConstantName(@NotNull Expression lab) {
        if (lab instanceof NameExpression ne) return ne.name();
        if (lab instanceof FieldAccessExpression fa) return fa.fieldName();
        return null;
    }
}
