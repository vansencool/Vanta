package net.vansencool.vanta.codegen.diagnostic.flow;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import net.vansencool.vanta.parser.ast.statement.Statement;
import org.jetbrains.annotations.NotNull;

/**
 * Diagnostic for a statement that the compiler can prove will never execute.
 */
public final class UnreachableCodeDiagnostic {

    private UnreachableCodeDiagnostic() {
    }

    /**
     * @param ctx        method context being compiled
     * @param statement  first unreachable statement
     * @param terminator preceding statement that ended control flow
     * @return diagnostic describing the unreachable region
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull Statement statement, @NotNull Statement terminator) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building unreachable code diagnostic");
        SpanTable spans = cg.spanTable();
        Span span = spans.span(statement);
        Span termSpan = spans.span(terminator);
        if (span == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for statement at line " + statement.line());
        if (termSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for terminator at line " + terminator.line());
        String source = cg.source();
        String sourceText = SourceLines.lineAt(source, span.startLine());
        SourceRange range = SourceRange.ofSingleLine(span, sourceText.length());

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(span.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("unreachable statement")
                .label("this statement cannot be reached");
        String termText = SourceLines.lineAt(source, termSpan.startLine());
        SourceRange termRange = SourceRange.ofSingleLine(termSpan, termText.length());
        builder.context(termSpan.startLine(), termText, termRange.startCol(), termRange.endCol(), "control flow leaves the block here");
        builder.note("the preceding statement always terminates this block (return, throw, break, continue, or an infinite loop without break)");
        builder.help("delete the unreachable statement");
        builder.help("if it should run, move it above the terminator or remove the terminator's unconditional exit");
        return builder.build();
    }
}
