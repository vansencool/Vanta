package net.vansencool.vanta.codegen.classes.scan;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.EnumConstant;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.statement.AssertStatement;
import net.vansencool.vanta.parser.ast.statement.BlockStatement;
import net.vansencool.vanta.parser.ast.statement.CatchClause;
import net.vansencool.vanta.parser.ast.statement.DoWhileStatement;
import net.vansencool.vanta.parser.ast.statement.ForEachStatement;
import net.vansencool.vanta.parser.ast.statement.ForStatement;
import net.vansencool.vanta.parser.ast.statement.IfStatement;
import net.vansencool.vanta.parser.ast.statement.LabeledStatement;
import net.vansencool.vanta.parser.ast.statement.Statement;
import net.vansencool.vanta.parser.ast.statement.SwitchCase;
import net.vansencool.vanta.parser.ast.statement.SwitchStatement;
import net.vansencool.vanta.parser.ast.statement.SynchronizedStatement;
import net.vansencool.vanta.parser.ast.statement.TryStatement;
import net.vansencool.vanta.parser.ast.statement.WhileStatement;
import org.jetbrains.annotations.NotNull;

/**
 * AST walker that short-circuits on the first {@code assert} statement in a
 * class body. Used to decide whether a synthetic {@code $assertionsDisabled}
 * field + clinit guard needs emitting, mirroring javac's assert lowering.
 * Does not recurse into nested class declarations since each nested class
 * emits its own guard separately.
 */
public final class AssertScanner {

    private final boolean[] found;

    /**
     * Binds this scanner to a caller-supplied single-cell flag that the scan
     * flips to {@code true} the moment an {@code assert} statement is seen.
     *
     * @param found single-cell boolean holder used as an output signal
     */
    public AssertScanner(boolean[] found) {
        this.found = found;
    }

    /**
     * Walks every member, enum-constant argument, and enum-constant body of
     * {@code classDecl}, stopping early on the first assert.
     *
     * @param classDecl declaration to scan
     */
    public void scanClass(@NotNull ClassDeclaration classDecl) {
        for (AstNode m : classDecl.members()) {
            if (found[0]) return;
            if (m instanceof MethodDeclaration md && md.body() != null) scanBlock(md.body());
        }
        if (classDecl.enumConstants() != null) {
            for (EnumConstant ec : classDecl.enumConstants()) {
                if (ec.classBody() != null) {
                    for (AstNode m : ec.classBody()) {
                        if (m instanceof MethodDeclaration md && md.body() != null) scanBlock(md.body());
                    }
                }
            }
        }
    }

    /**
     * Walks each statement in {@code block}, bailing out the moment
     * {@link #found} is set.
     *
     * @param block block to walk
     */
    private void scanBlock(@NotNull BlockStatement block) {
        for (Statement s : block.statements()) {
            if (found[0]) return;
            scanStmt(s);
        }
    }

    /**
     * Per-statement dispatch. Recurses into every statement shape that can
     * enclose more statements (blocks, loops, try/catch/finally, switch
     * cases, labeled statements) so an assert buried anywhere is detected.
     *
     * @param s statement to scan
     */
    private void scanStmt(@NotNull Statement s) {
        if (found[0]) return;
        if (s instanceof AssertStatement) {
            found[0] = true;
            return;
        }
        if (s instanceof BlockStatement bs) {
            scanBlock(bs);
            return;
        }
        if (s instanceof IfStatement ifs) {
            scanStmt(ifs.thenBranch());
            if (ifs.elseBranch() != null) scanStmt(ifs.elseBranch());
            return;
        }
        if (s instanceof ForStatement f) {
            scanStmt(f.body());
            return;
        }
        if (s instanceof ForEachStatement fe) {
            scanStmt(fe.body());
            return;
        }
        if (s instanceof WhileStatement ws) {
            scanStmt(ws.body());
            return;
        }
        if (s instanceof DoWhileStatement dw) {
            scanStmt(dw.body());
            return;
        }
        if (s instanceof TryStatement ts) {
            scanBlock(ts.tryBlock());
            for (CatchClause cc : ts.catchClauses()) scanBlock(cc.body());
            if (ts.finallyBlock() != null) scanBlock(ts.finallyBlock());
            return;
        }
        if (s instanceof SynchronizedStatement ss) scanBlock(ss.body());
        if (s instanceof SwitchStatement sw) {
            for (SwitchCase sc : sw.cases()) for (Statement is : sc.statements()) scanStmt(is);
        }
        if (s instanceof LabeledStatement ls) scanStmt(ls.statement());
    }

}
