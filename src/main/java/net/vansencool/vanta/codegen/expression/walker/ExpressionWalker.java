package net.vansencool.vanta.codegen.expression.walker;

import net.vansencool.vanta.codegen.ExpressionGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
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
import net.vansencool.vanta.parser.ast.expression.MethodReferenceExpression;
import net.vansencool.vanta.parser.ast.expression.NameExpression;
import net.vansencool.vanta.parser.ast.expression.NewArrayExpression;
import net.vansencool.vanta.parser.ast.expression.NewExpression;
import net.vansencool.vanta.parser.ast.expression.ParenExpression;
import net.vansencool.vanta.parser.ast.expression.SwitchExpression;
import net.vansencool.vanta.parser.ast.expression.TernaryExpression;
import net.vansencool.vanta.parser.ast.expression.UnaryExpression;
import net.vansencool.vanta.parser.ast.statement.AssertStatement;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.CatchClause;
import net.vansencool.vanta.parser.ast.statement.DoWhileStatement;
import net.vansencool.vanta.parser.ast.statement.ExpressionStatement;
import net.vansencool.vanta.parser.ast.statement.ForEachStatement;
import net.vansencool.vanta.parser.ast.statement.ForStatement;
import net.vansencool.vanta.parser.ast.statement.IfStatement;
import net.vansencool.vanta.parser.ast.statement.LabeledStatement;
import net.vansencool.vanta.parser.ast.statement.ResourceDeclaration;
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
import net.vansencool.vanta.resolver.scope.LocalVariable;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Recursive AST walks that flatten a lambda body or anonymous-class body into
 * the set of expressions it references. Used to find captures (outer locals
 * and outer fields touched from inside a lambda or anonymous class) without
 * re-emitting any bytecode.
 */
public final class ExpressionWalker {

    private final @NotNull ExpressionGenerator exprGen;

    /**
     * @param exprGen owning expression generator used only to reach the
     *                surrounding {@link MethodContext}
     */
    public ExpressionWalker(@NotNull ExpressionGenerator exprGen) {
        this.exprGen = exprGen;
    }

    /**
     * Walks an anonymous class body and records the free-name references that
     * need to be captured as synthetic {@code val$X} fields. Shadowed names
     * (own fields, own methods, method parameters) are filtered out.
     *
     * @param members  anonymous class members
     * @param captures mutable map to accumulate captures into, keyed by name
     */
    public void collectAnonCaptures(@NotNull List<AstNode> members, @NotNull LinkedHashMap<String, LocalVariable> captures) {
        MethodContext ctx = exprGen.ctx();
        Set<String> anonOwnFields = new HashSet<>();
        Set<String> anonOwnMethods = new HashSet<>();
        for (AstNode member : members) {
            if (member instanceof FieldDeclaration fd) {
                for (FieldDeclarator d : fd.declarators()) anonOwnFields.add(d.name());
            } else if (member instanceof MethodDeclaration md) {
                anonOwnMethods.add(md.name());
            }
        }
        for (AstNode member : members) {
            if (member instanceof MethodDeclaration md && md.body() != null) {
                Set<String> paramNames = new HashSet<>();
                for (Parameter p : md.parameters()) paramNames.add(p.name());
                List<Expression> exprs = new ArrayList<>();
                collectFromBlock(md.body(), exprs);
                for (Expression expr : exprs) {
                    if (expr instanceof NameExpression n
                            && !paramNames.contains(n.name())
                            && !anonOwnFields.contains(n.name())
                            && !captures.containsKey(n.name())) {
                        LocalVariable local = ctx.scope().resolve(n.name());
                        if (local != null) {
                            captures.put(n.name(), local);
                        } else if (ctx.capturedFields() != null && ctx.capturedFields().containsKey(n.name())) {
                            ResolvedType t = ctx.capturedFields().get(n.name());
                            captures.put(n.name(), new LocalVariable(n.name(), t, -1));
                        }
                    }
                }
            } else if (member instanceof FieldDeclaration fd) {
                for (FieldDeclarator decl : fd.declarators()) {
                    if (decl.initializer() == null) continue;
                    List<Expression> exprs = new ArrayList<>();
                    collectSubExpressions(decl.initializer(), exprs);
                    for (Expression expr : exprs) {
                        if (expr instanceof NameExpression n
                                && !anonOwnFields.contains(n.name())
                                && !anonOwnMethods.contains(n.name())
                                && !captures.containsKey(n.name())) {
                            LocalVariable local = ctx.scope().resolve(n.name());
                            if (local != null) captures.put(n.name(), local);
                        }
                    }
                }
            }
        }
    }

    /**
     * Appends {@code expr} and all of its recursive sub-expressions to
     * {@code result}. Statements embedded in switch or lambda bodies are
     * followed into.
     *
     * @param expr   root expression
     * @param result accumulator, appended in preorder
     */
    public void collectSubExpressions(@NotNull Expression expr, @NotNull List<Expression> result) {
        result.add(expr);
        if (expr instanceof BinaryExpression bin) {
            collectSubExpressions(bin.left(), result);
            collectSubExpressions(bin.right(), result);
        } else if (expr instanceof UnaryExpression unary) {
            collectSubExpressions(unary.operand(), result);
        } else if (expr instanceof MethodCallExpression mc) {
            if (mc.target() != null) collectSubExpressions(mc.target(), result);
            for (Expression arg : mc.arguments()) collectSubExpressions(arg, result);
        } else if (expr instanceof ParenExpression paren) {
            collectSubExpressions(paren.expression(), result);
        } else if (expr instanceof TernaryExpression ternary) {
            collectSubExpressions(ternary.condition(), result);
            collectSubExpressions(ternary.thenExpression(), result);
            collectSubExpressions(ternary.elseExpression(), result);
        } else if (expr instanceof FieldAccessExpression fa) {
            collectSubExpressions(fa.target(), result);
        } else if (expr instanceof CastExpression cast) {
            collectSubExpressions(cast.expression(), result);
        } else if (expr instanceof ArrayAccessExpression aa) {
            collectSubExpressions(aa.array(), result);
            collectSubExpressions(aa.index(), result);
        } else if (expr instanceof AssignmentExpression assign) {
            collectSubExpressions(assign.target(), result);
            collectSubExpressions(assign.value(), result);
        } else if (expr instanceof NewExpression newExpr) {
            for (Expression arg : newExpr.arguments()) collectSubExpressions(arg, result);
        } else if (expr instanceof NewArrayExpression newArr) {
            for (Expression dim : newArr.dimensionExpressions()) collectSubExpressions(dim, result);
            if (newArr.initializer() != null) collectSubExpressions(newArr.initializer(), result);
        } else if (expr instanceof ArrayInitializerExpression arrInit) {
            for (Expression el : arrInit.elements()) collectSubExpressions(el, result);
        } else if (expr instanceof InstanceofExpression io) {
            collectSubExpressions(io.expression(), result);
        } else if (expr instanceof MethodReferenceExpression mref) {
            collectSubExpressions(mref.target(), result);
        } else if (expr instanceof SwitchExpression sw) {
            collectSubExpressions(sw.selector(), result);
            for (SwitchCase sc : sw.cases()) {
                if (sc.labels() != null) for (Expression lab : sc.labels()) collectSubExpressions(lab, result);
                for (Statement s : sc.statements()) collectFromStatement(s, result);
            }
        } else if (expr instanceof LambdaExpression innerLambda) {
            if (innerLambda.expressionBody() != null) collectSubExpressions(innerLambda.expressionBody(), result);
            if (innerLambda.body() instanceof BlockStatement lb) collectFromBlock(lb, result);
        }
    }

    /**
     * @param block  block statement
     * @param result accumulator
     */
    public void collectFromBlock(@NotNull BlockStatement block, @NotNull List<Expression> result) {
        for (Statement stmt : block.statements()) collectFromStatement(stmt, result);
    }

    /**
     * Appends every expression referenced by {@code stmt} (and its nested
     * statements/expressions) to {@code result}.
     *
     * @param stmt   statement to walk
     * @param result accumulator
     */
    public void collectFromStatement(@NotNull Statement stmt, @NotNull List<Expression> result) {
        if (stmt instanceof ExpressionStatement es) collectSubExpressions(es.expression(), result);
        else if (stmt instanceof ReturnStatement ret && ret.value() != null)
            collectSubExpressions(ret.value(), result);
        else if (stmt instanceof VariableDeclarationStatement varDecl) {
            for (VariableDeclarator decl : varDecl.declarators()) {
                if (decl.initializer() != null) collectSubExpressions(decl.initializer(), result);
            }
        } else if (stmt instanceof BlockStatement inner) collectFromBlock(inner, result);
        else if (stmt instanceof IfStatement ifStmt) {
            collectSubExpressions(ifStmt.condition(), result);
            collectFromStatement(ifStmt.thenBranch(), result);
            if (ifStmt.elseBranch() != null) collectFromStatement(ifStmt.elseBranch(), result);
        } else if (stmt instanceof ForStatement forStmt) {
            if (forStmt.initializers() != null)
                for (Statement i : forStmt.initializers()) collectFromStatement(i, result);
            if (forStmt.condition() != null) collectSubExpressions(forStmt.condition(), result);
            if (forStmt.updaters() != null) for (Expression u : forStmt.updaters()) collectSubExpressions(u, result);
            collectFromStatement(forStmt.body(), result);
        } else if (stmt instanceof ForEachStatement fe) {
            collectSubExpressions(fe.iterable(), result);
            collectFromStatement(fe.body(), result);
        } else if (stmt instanceof WhileStatement ws) {
            collectSubExpressions(ws.condition(), result);
            collectFromStatement(ws.body(), result);
        } else if (stmt instanceof DoWhileStatement dw) {
            collectFromStatement(dw.body(), result);
            collectSubExpressions(dw.condition(), result);
        } else if (stmt instanceof TryStatement ts) {
            for (ResourceDeclaration rd : ts.resources()) collectSubExpressions(rd.initializer(), result);
            collectFromBlock(ts.tryBlock(), result);
            for (CatchClause cc : ts.catchClauses()) collectFromBlock(cc.body(), result);
            if (ts.finallyBlock() != null) collectFromBlock(ts.finallyBlock(), result);
        } else if (stmt instanceof SwitchStatement ss) {
            collectSubExpressions(ss.selector(), result);
            for (SwitchCase sc : ss.cases()) {
                if (sc.labels() != null) for (Expression lab : sc.labels()) collectSubExpressions(lab, result);
                for (Statement s : sc.statements()) collectFromStatement(s, result);
            }
        } else if (stmt instanceof ThrowStatement th) {
            collectSubExpressions(th.expression(), result);
        } else if (stmt instanceof YieldStatement yi) {
            collectSubExpressions(yi.value(), result);
        } else if (stmt instanceof SynchronizedStatement sy) {
            collectSubExpressions(sy.lock(), result);
            collectFromBlock(sy.body(), result);
        } else if (stmt instanceof AssertStatement as) {
            collectSubExpressions(as.condition(), result);
            if (as.message() != null) collectSubExpressions(as.message(), result);
        } else if (stmt instanceof LabeledStatement ls) {
            collectFromStatement(ls.statement(), result);
        }
    }
}
