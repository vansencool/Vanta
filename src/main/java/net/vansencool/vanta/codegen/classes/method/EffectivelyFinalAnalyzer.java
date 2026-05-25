package net.vansencool.vanta.codegen.classes.method;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.expression.ArrayAccessExpression;
import net.vansencool.vanta.parser.ast.expression.ArrayInitializerExpression;
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
import net.vansencool.vanta.parser.ast.statement.AssertStatement;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.BreakStatement;
import net.vansencool.vanta.parser.ast.statement.CatchClause;
import net.vansencool.vanta.parser.ast.statement.ContinueStatement;
import net.vansencool.vanta.parser.ast.statement.DoWhileStatement;
import net.vansencool.vanta.parser.ast.statement.ExpressionStatement;
import net.vansencool.vanta.parser.ast.statement.ForEachStatement;
import net.vansencool.vanta.parser.ast.statement.ForStatement;
import net.vansencool.vanta.parser.ast.statement.IfStatement;
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
import net.vansencool.vanta.parser.ast.statement.YieldStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a method body using JLS 16.1.7 style definite assignment / definite
 * unassignment to identify every local that is reassigned in a way that
 * disqualifies it from being effectively final.
 *
 * <p>Each assignment to a local is permitted only when the local is definitely
 * unassigned (DU) at that point. Assignments inside nested lambdas and
 * anonymous classes do not affect the enclosing scope and so are not walked
 * here.
 */
public final class EffectivelyFinalAnalyzer {

    private EffectivelyFinalAnalyzer() {
    }

    /**
     * @param method method declaration to analyze
     * @return map from local name to the AST nodes that constitute disqualifying reassignments
     */
    public static @NotNull Map<String, List<AstNode>> analyze(@NotNull MethodDeclaration method) {
        State state = new State();
        for (Parameter p : method.parameters()) state.markAssigned(p.name());
        if (method.body() != null) state.visit(method.body());
        return state.reassignments;
    }

    /**
     * Mutable analyzer state shared across the recursive walk. Tracks which
     * locals are currently definitely unassigned and accumulates the
     * disqualifying reassignment sites.
     */
    private static final class State {

        private final @NotNull Set<String> tracked = new HashSet<>();
        private final @NotNull Set<String> du = new HashSet<>();
        private final @NotNull Map<String, List<AstNode>> reassignments = new HashMap<>();

        void declare(@NotNull String name, boolean hasInit) {
            tracked.add(name);
            if (hasInit) du.remove(name);
            else du.add(name);
        }

        void markAssigned(@NotNull String name) {
            du.remove(name);
        }

        void recordReassignment(@NotNull String name, @NotNull AstNode site) {
            reassignments.computeIfAbsent(name, k -> new ArrayList<>()).add(site);
        }

        @NotNull Set<String> snapshotDu() {
            return new HashSet<>(du);
        }

        void restoreDu(@NotNull Set<String> snapshot) {
            du.clear();
            du.addAll(snapshot);
        }

        void intersectDu(@NotNull Set<String> other) {
            du.retainAll(other);
        }

        void visit(@Nullable AstNode node) {
            if (node == null) return;
            if (node instanceof BlockStatement b) {
                for (Statement s : b.statements()) visit(s);
            } else if (node instanceof ExpressionStatement es) {
                visit(es.expression());
            } else if (node instanceof VariableDeclarationStatement v) {
                for (VariableDeclarator d : v.declarators()) {
                    if (d.initializer() != null) visit(d.initializer());
                    declare(d.name(), d.initializer() != null);
                }
            } else if (node instanceof AssignmentExpression ae) {
                visit(ae.value());
                if (ae.target() instanceof NameExpression n) {
                    String name = n.name();
                    if (tracked.contains(name)) {
                        if (!du.contains(name)) recordReassignment(name, ae);
                        markAssigned(name);
                    }
                } else {
                    visit(ae.target());
                }
            } else if (node instanceof UnaryExpression ue) {
                if (("++".equals(ue.operator()) || "--".equals(ue.operator())) && ue.operand() instanceof NameExpression n) {
                    String name = n.name();
                    if (tracked.contains(name)) {
                        if (!du.contains(name)) recordReassignment(name, ue);
                        markAssigned(name);
                    }
                }
                visit(ue.operand());
            } else if (node instanceof IfStatement is) {
                visit(is.condition());
                Set<String> beforeBranches = snapshotDu();
                visit(is.thenBranch());
                Set<String> afterThen = snapshotDu();
                restoreDu(beforeBranches);
                if (is.elseBranch() != null) {
                    visit(is.elseBranch());
                    intersectDu(afterThen);
                } else {
                    intersectDu(afterThen);
                }
            } else if (node instanceof ForStatement fs) {
                if (fs.initializers() != null) for (Statement init : fs.initializers()) visit(init);
                if (fs.condition() != null) visit(fs.condition());
                Set<String> beforeBody = snapshotDu();
                visit(fs.body());
                if (fs.updaters() != null) for (Expression u : fs.updaters()) visit(u);
                restoreDu(beforeBody);
            } else if (node instanceof ForEachStatement fe) {
                visit(fe.iterable());
                declare(fe.variableName(), true);
                Set<String> beforeBody = snapshotDu();
                visit(fe.body());
                restoreDu(beforeBody);
            } else if (node instanceof WhileStatement ws) {
                visit(ws.condition());
                Set<String> beforeBody = snapshotDu();
                visit(ws.body());
                restoreDu(beforeBody);
            } else if (node instanceof DoWhileStatement dw) {
                Set<String> beforeBody = snapshotDu();
                visit(dw.body());
                visit(dw.condition());
                restoreDu(beforeBody);
            } else if (node instanceof ReturnStatement rs) {
                if (rs.value() != null) visit(rs.value());
            } else if (node instanceof ThrowStatement ts) {
                visit(ts.expression());
            } else if (node instanceof TryStatement ts) {
                Set<String> beforeTry = snapshotDu();
                visit(ts.tryBlock());
                Set<String> afterTry = snapshotDu();
                for (CatchClause cc : ts.catchClauses()) {
                    restoreDu(beforeTry);
                    visit(cc.body());
                    afterTry.retainAll(snapshotDu());
                }
                restoreDu(afterTry);
                if (ts.finallyBlock() != null) visit(ts.finallyBlock());
            } else if (node instanceof SwitchStatement ss) {
                visit(ss.selector());
                Set<String> beforeBranches = snapshotDu();
                Set<String> intersection = null;
                boolean hasDefault = false;
                for (SwitchCase c : ss.cases()) {
                    if (c.isDefault()) hasDefault = true;
                    restoreDu(beforeBranches);
                    for (Statement s : c.statements()) visit(s);
                    Set<String> branchDu = snapshotDu();
                    if (intersection == null) intersection = branchDu;
                    else intersection.retainAll(branchDu);
                }
                if (intersection == null || !hasDefault) restoreDu(beforeBranches);
                else restoreDu(intersection);
            } else if (node instanceof SynchronizedStatement sy) {
                visit(sy.lock());
                visit(sy.body());
            } else if (node instanceof AssertStatement as) {
                visit(as.condition());
                if (as.message() != null) visit(as.message());
            } else if (node instanceof YieldStatement ys) {
                visit(ys.value());
            } else if (node instanceof BinaryExpression be) {
                visit(be.left());
                visit(be.right());
            } else if (node instanceof TernaryExpression te) {
                visit(te.condition());
                Set<String> beforeBranches = snapshotDu();
                visit(te.thenExpression());
                Set<String> afterThen = snapshotDu();
                restoreDu(beforeBranches);
                visit(te.elseExpression());
                intersectDu(afterThen);
            } else if (node instanceof MethodCallExpression mc) {
                if (mc.target() != null) visit(mc.target());
                for (Expression a : mc.arguments()) visit(a);
            } else if (node instanceof FieldAccessExpression fa) {
                visit(fa.target());
            } else if (node instanceof ArrayAccessExpression aa) {
                visit(aa.array());
                visit(aa.index());
            } else if (node instanceof ArrayInitializerExpression ai) {
                for (Expression e : ai.elements()) visit(e);
            } else if (node instanceof NewExpression ne) {
                for (Expression a : ne.arguments()) visit(a);
            } else if (node instanceof NewArrayExpression na) {
                for (Expression d : na.dimensionExpressions()) visit(d);
                if (na.initializer() != null) visit(na.initializer());
            } else if (node instanceof CastExpression ce) {
                visit(ce.expression());
            } else if (node instanceof InstanceofExpression ie) {
                visit(ie.expression());
            } else if (node instanceof ParenExpression pe) {
                visit(pe.expression());
            } else if (node instanceof SwitchExpression se) {
                visit(se.selector());
                Set<String> beforeBranches = snapshotDu();
                Set<String> intersection = null;
                for (SwitchCase c : se.cases()) {
                    restoreDu(beforeBranches);
                    for (Statement s : c.statements()) visit(s);
                    Set<String> branchDu = snapshotDu();
                    if (intersection == null) intersection = branchDu;
                    else intersection.retainAll(branchDu);
                }
                restoreDu(intersection == null ? beforeBranches : intersection);
            } else if (node instanceof BreakStatement || node instanceof ContinueStatement) {
                // jumps do not contribute to flow-out DU here
            } else if (node instanceof LambdaExpression le) {
                Set<String> savedTracked = new HashSet<>(tracked);
                Set<String> savedDu = snapshotDu();
                for (Parameter p : le.parameters()) tracked.remove(p.name());
                if (le.body() != null) visit(le.body());
                else if (le.expressionBody() != null) visit(le.expressionBody());
                tracked.clear();
                tracked.addAll(savedTracked);
                restoreDu(savedDu);
            } else if (node instanceof NewExpression) {
                // anonymous class bodies belong to their own method scopes; assignments inside do not count against the enclosing method's locals
            }
        }
    }
}
