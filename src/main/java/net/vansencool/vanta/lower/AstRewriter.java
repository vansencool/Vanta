package net.vansencool.vanta.lower;

import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.CompilationUnit;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Identity preserving recursive walker for {@link LowerPass} authors.
 * Override one or more {@code rewriteX} methods; everything else copies
 * straight through. Returns the same instance when no child changed so
 * downstream passes can cheaply skip unchanged subtrees.
 */
public abstract class AstRewriter implements LowerPass {

    @Override
    public @NotNull CompilationUnit lower(@NotNull CompilationUnit unit) {
        List<AstNode> out = new ArrayList<>(unit.typeDeclarations().size());
        boolean changed = false;
        for (AstNode decl : unit.typeDeclarations()) {
            AstNode r = decl instanceof ClassDeclaration cd ? rewriteClass(cd) : decl;
            if (r != decl) changed = true;
            out.add(r);
        }
        if (!changed) return unit;
        return new CompilationUnit(unit.packageName(), unit.imports(), out, unit.commentTable(), unit.spanTable(), unit.line());
    }

    protected @NotNull ClassDeclaration rewriteClass(@NotNull ClassDeclaration cd) {
        List<AstNode> members = new ArrayList<>(cd.members().size());
        boolean changed = false;
        for (AstNode m : cd.members()) {
            AstNode r;
            if (m instanceof MethodDeclaration md && md.body() != null) {
                BlockStatement b = rewriteBlock(md.body());
                r = b == md.body() ? md : new MethodDeclaration(md.name(), md.modifiers(), md.returnType(), md.typeParameters(), md.parameters(), b, md.defaultValue(), md.annotations(), md.isVarargs(), md.line());
            } else if (m instanceof ClassDeclaration nested) {
                r = rewriteClass(nested);
            } else {
                r = m;
            }
            if (r != m) changed = true;
            members.add(r);
        }
        if (!changed) return cd;
        return new ClassDeclaration(cd.name(), cd.modifiers(), cd.typeParameters(), cd.superClass(), cd.interfaces(), members, cd.annotations(), cd.kind(), cd.enumConstants(), cd.recordComponents(), cd.line());
    }

    protected @NotNull Statement rewriteStatement(@NotNull Statement stmt) {
        if (stmt instanceof BlockStatement b) return rewriteBlock(b);
        if (stmt instanceof TryStatement ts) return rewriteTry(ts);
        if (stmt instanceof IfStatement is) return rewriteIf(is);
        if (stmt instanceof WhileStatement ws) {
            Statement b = rewriteStatement(ws.body());
            return b == ws.body() ? ws : new WhileStatement(ws.condition(), b, ws.line());
        }
        if (stmt instanceof DoWhileStatement dw) {
            Statement b = rewriteStatement(dw.body());
            return b == dw.body() ? dw : new DoWhileStatement(b, dw.condition(), dw.line());
        }
        if (stmt instanceof ForStatement fs) {
            Statement b = rewriteStatement(fs.body());
            return b == fs.body() ? fs : new ForStatement(fs.initializers(), fs.condition(), fs.updaters(), b, fs.line());
        }
        if (stmt instanceof ForEachStatement fe) {
            Statement b = rewriteStatement(fe.body());
            return b == fe.body() ? fe : new ForEachStatement(fe.variableType(), fe.variableName(), fe.iterable(), b, fe.modifiers(), fe.line());
        }
        if (stmt instanceof LabeledStatement ls) {
            Statement b = rewriteStatement(ls.statement());
            return b == ls.statement() ? ls : new LabeledStatement(ls.label(), b, ls.line());
        }
        if (stmt instanceof SwitchStatement ss) return rewriteSwitch(ss);
        if (stmt instanceof SynchronizedStatement sy) {
            BlockStatement b = rewriteBlock(sy.body());
            return b == sy.body() ? sy : new SynchronizedStatement(sy.lock(), b, sy.line());
        }
        return stmt;
    }

    protected @NotNull BlockStatement rewriteBlock(@NotNull BlockStatement b) {
        List<Statement> out = new ArrayList<>(b.statements().size());
        boolean changed = false;
        for (Statement s : b.statements()) {
            Statement r = rewriteStatement(s);
            if (r != s) changed = true;
            out.add(r);
        }
        return changed ? new BlockStatement(out, b.line()) : b;
    }

    protected @NotNull Statement rewriteTry(@NotNull TryStatement ts) {
        BlockStatement nt = rewriteBlock(ts.tryBlock());
        List<CatchClause> ncs = new ArrayList<>(ts.catchClauses().size());
        boolean changed = nt != ts.tryBlock();
        for (CatchClause cc : ts.catchClauses()) {
            BlockStatement nb = rewriteBlock(cc.body());
            if (nb != cc.body()) changed = true;
            ncs.add(nb != cc.body() ? new CatchClause(cc.exceptionTypes(), cc.variableName(), nb, cc.line()) : cc);
        }
        BlockStatement nf = ts.finallyBlock() == null ? null : rewriteBlock(ts.finallyBlock());
        if (nf != ts.finallyBlock()) changed = true;
        return changed ? new TryStatement(ts.resources(), nt, ncs, nf, ts.line()) : ts;
    }

    protected @NotNull Statement rewriteIf(@NotNull IfStatement is) {
        Statement t = rewriteStatement(is.thenBranch());
        Statement e = is.elseBranch() == null ? null : rewriteStatement(is.elseBranch());
        return t == is.thenBranch() && e == is.elseBranch() ? is : new IfStatement(is.condition(), t, e, is.line());
    }

    protected @NotNull Statement rewriteSwitch(@NotNull SwitchStatement ss) {
        List<SwitchCase> cases = new ArrayList<>(ss.cases().size());
        boolean changed = false;
        for (SwitchCase c : ss.cases()) {
            List<Statement> body = new ArrayList<>(c.statements().size());
            boolean cc = false;
            for (Statement s : c.statements()) {
                Statement r = rewriteStatement(s);
                if (r != s) cc = true;
                body.add(r);
            }
            if (cc) {
                changed = true;
                cases.add(new SwitchCase(c.labels(), body, c.isDefault(), c.isArrow(), c.line()));
            } else cases.add(c);
        }
        return changed ? new SwitchStatement(ss.selector(), cases, ss.line()) : ss;
    }
}
