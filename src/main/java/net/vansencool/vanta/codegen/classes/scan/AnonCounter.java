package net.vansencool.vanta.codegen.classes.scan;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.EnumConstant;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.expression.ArrayAccessExpression;
import net.vansencool.vanta.parser.ast.expression.AssignmentExpression;
import net.vansencool.vanta.parser.ast.expression.BinaryExpression;
import net.vansencool.vanta.parser.ast.expression.CastExpression;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.expression.FieldAccessExpression;
import net.vansencool.vanta.parser.ast.expression.InstanceofExpression;
import net.vansencool.vanta.parser.ast.expression.LambdaExpression;
import net.vansencool.vanta.parser.ast.expression.MethodCallExpression;
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
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * AST walker that counts every {@code new X(...){...}} expression and
 * {@link LambdaExpression} found inside a class declaration and its member
 * bodies. Optionally records an identity-keyed index for each occurrence so
 * downstream code generation can reuse the same {@code $N} suffix javac
 * would pick, preserving source-order numbering.
 */
public final class AnonCounter {

    private final int[] counter;
    private final @NotNull Map<NewExpression, Integer> assignments;
    private final int[] lambdaCounter;
    private final @NotNull Map<LambdaExpression, Integer> lambdaAssignments;

    /**
     * Counts both anonymous classes and lambdas, recording source-order
     * indices for each. Used by the primary pre-scan before class emission.
     *
     * @param counter           single-cell int holder for anon classes
     * @param assignments       receives per-{@link NewExpression} anon indices
     * @param lambdaCounter     single-cell int holder for lambdas
     * @param lambdaAssignments receives per-{@link LambdaExpression} lambda indices
     */
    public AnonCounter(int[] counter, @NotNull Map<NewExpression, Integer> assignments, int[] lambdaCounter, @NotNull Map<LambdaExpression, Integer> lambdaAssignments) {
        this.counter = counter;
        this.assignments = assignments;
        this.lambdaCounter = lambdaCounter;
        this.lambdaAssignments = lambdaAssignments;
    }

    /**
     * Walks every member, enum-constant argument, and enum-constant body of
     * {@code classDecl}, bumping the anon counter for each anonymous class
     * found and recording its source-order index when an assignments map was
     * supplied. Does not recurse into nested class declarations since each
     * nested class gets its own counter run.
     *
     * @param classDecl declaration to scan
     */
    public void scanClass(@NotNull ClassDeclaration classDecl) {
        for (AstNode member : classDecl.members()) {
            if (member instanceof MethodDeclaration md && md.body() != null) scanBlock(md.body());
            else if (member instanceof FieldDeclaration fd) {
                for (FieldDeclarator d : fd.declarators()) {
                    if (d.initializer() != null) scanExpr(d.initializer());
                }
            }
        }
        if (classDecl.enumConstants() != null) {
            for (EnumConstant ec : classDecl.enumConstants()) {
                if (ec.classBody() != null) counter[0]++;
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
     * Recursively walks each statement in {@code block}, deferring to
     * {@link #scanStmt(Statement)} for per-node dispatch.
     *
     * @param block block statement to walk
     */
    private void scanBlock(@NotNull BlockStatement block) {
        for (Statement s : block.statements()) scanStmt(s);
    }

    /**
     * Per-statement dispatch for the AST walker. Recurses into nested blocks,
     * loop bodies, try/catch/finally, switch cases, and labeled statements so
     * anonymous classes declared anywhere in the method body are counted.
     *
     * @param s statement to scan
     */
    private void scanStmt(@NotNull Statement s) {
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
            if (f.updaters() != null) for (Expression u : f.updaters()) scanExpr(u);
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
        if (s instanceof SwitchStatement sw) {
            scanExpr(sw.selector());
            for (SwitchCase sc : sw.cases()) for (Statement is : sc.statements()) scanStmt(is);
            return;
        }
        if (s instanceof LabeledStatement ls) {
            scanStmt(ls.statement());
            return;
        }
        if (s instanceof VariableDeclarationStatement vds) {
            for (VariableDeclarator vd : vds.declarators()) {
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
     * Per-expression dispatch. Detects anonymous-class {@code new} shapes and
     * {@link LambdaExpression}s, recording both in source order, and recurses
     * through the rest of the expression tree.
     *
     * @param e expression to scan
     */
    private void scanExpr(@NotNull Expression e) {
        if (e instanceof NewExpression ne) {
            if (ne.anonymousClassBody() != null) {
                counter[0]++;
                assignments.put(ne, counter[0]);
            }
            for (Expression a : ne.arguments()) scanExpr(a);
            if (ne.anonymousClassBody() != null) {
                for (AstNode m : ne.anonymousClassBody()) {
                    if (m instanceof MethodDeclaration md && md.body() != null) scanBlock(md.body());
                }
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
        if (e instanceof NewArrayExpression na) {
            for (Expression d : na.dimensionExpressions()) scanExpr(d);
            return;
        }
        if (e instanceof InstanceofExpression io) {
            scanExpr(io.expression());
            return;
        }
        if (e instanceof SwitchExpression swe) {
            scanExpr(swe.selector());
            for (SwitchCase sc : swe.cases()) for (Statement is : sc.statements()) scanStmt(is);
            return;
        }
        if (e instanceof LambdaExpression le) {
            if (le.body() != null) scanStmt(le.body());
            if (le.expressionBody() != null) scanExpr(le.expressionBody());
            if (lambdaCounter != null) {
                int idx = lambdaCounter[0]++;
                lambdaAssignments.put(le, idx);
            }
        }
    }
}
