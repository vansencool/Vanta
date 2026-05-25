package net.vansencool.vanta.codegen.diagnostic.sw;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.fix.Applicability;
import net.vansencool.vanta.diagnostic.fix.Edit;
import net.vansencool.vanta.diagnostic.fix.Fix;
import net.vansencool.vanta.diagnostic.util.SourceLines;

import java.util.List;
import net.vansencool.vanta.parser.ast.expression.Expression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;

/**
 * Diagnostic for a case label in a {@code String} switch that is not a string literal.
 */
public final class StringCaseLabelDiagnostic {

    private StringCaseLabelDiagnostic() {
    }

    /**
     * @param ctx   method context being compiled
     * @param label offending case label expression
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull Expression label) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building string case label diagnostic at line " + label.line());
        SpanTable spans = cg.spanTable();
        Span span = spans.span(label);
        if (span == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for case label at line " + label.line());
        String sourceText = SourceLines.lineAt(cg.source(), span.startLine());
        SourceRange range = SourceRange.ofSingleLine(span, sourceText.length());
        String kind = label.getClass().getSimpleName();

        String labelText = sourceText.substring(Math.max(0, range.startCol()), Math.min(sourceText.length(), range.endCol()));
        String quoted = "\"" + labelText + "\"";
        return Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .fullSource(cg.source())
                .at(span.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("switch on String requires every case label to be a string constant expression")
                .label("this case label is a " + kind + ", not a constant string")
                .note("case labels in a String switch must be compile time constant strings (the JLS requires constant expressions)")
                .fix(new Fix(
                        "inline the constant as a quoted string literal",
                        "rewrites the case label as the literal value the constant resolves to",
                        List.of(Edit.replace(span.startLine(), range.startCol(), range.endCol(), quoted, "use a string literal here")),
                        Applicability.HAS_PLACEHOLDER
                ))
                .build();
    }
}
