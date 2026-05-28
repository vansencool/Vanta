package net.vansencool.vanta.codegen.diagnostic.flow;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import net.vansencool.vanta.parser.ast.statement.Statement;
import org.jetbrains.annotations.NotNull;

/**
 * Diagnostic for a {@code break} or {@code continue} statement that has no
 * enclosing loop or switch to target.
 */
public final class JumpOutsideLoopDiagnostic {

    private JumpOutsideLoopDiagnostic() {
    }

    /**
     * @param ctx     method context being compiled
     * @param stmt    offending break or continue statement
     * @param keyword either {@code "break"} or {@code "continue"}
     * @param allowedTargets which constructs can normally enclose this jump (used in the note)
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull Statement stmt, @NotNull String keyword, @NotNull String allowedTargets) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building jump outside loop diagnostic for `" + keyword + "`");
        SpanTable spans = cg.spanTable();
        Span span = spans.span(stmt);
        if (span == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for `" + keyword + "` at line " + stmt.line());
        String sourceText = SourceLines.lineAt(cg.source(), span.startLine());
        SourceRange range = SourceRange.ofSingleLine(span, sourceText.length());
        return Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .fullSource(cg.source())
                .at(span.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("`" + keyword + "` outside of a " + allowedTargets)
                .label("no enclosing " + allowedTargets + " for `" + keyword + "` to target")
                .note("`" + keyword + "` may only appear inside " + allowedTargets + " constructs")
                .build();
    }
}
