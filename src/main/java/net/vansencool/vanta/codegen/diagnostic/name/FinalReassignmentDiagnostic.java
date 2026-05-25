package net.vansencool.vanta.codegen.diagnostic.name;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostic for an assignment to a target marked {@code final}.
 */
public final class FinalReassignmentDiagnostic {

    private FinalReassignmentDiagnostic() {
    }

    /**
     * @param ctx         method context being compiled
     * @param spanOwner   assignment AST node, used for the main span
     * @param targetName  what is being reassigned (e.g. {@code "local 'x'"}, {@code "field 'COUNT'"})
     * @param declaration original declaration of the target, used as a context line, or null when unknown
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull AstNode spanOwner, @NotNull String targetName, @Nullable AstNode declaration) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building final reassignment diagnostic");
        SpanTable spans = cg.spanTable();
        Span span = spans.span(spanOwner);
        if (span == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for assignment to " + targetName);
        String sourceText = SourceLines.lineAt(cg.source(), span.startLine());
        SourceRange range = SourceRange.ofSingleLine(span, sourceText.length());

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(span.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("cannot reassign final " + targetName)
                .label(targetName + " was declared final");
        if (declaration != null) {
            Span declSpan = spans.span(declaration);
            if (declSpan != null) {
                String declLine = SourceLines.lineAt(cg.source(), declSpan.startLine());
                SourceRange declRange = SourceRange.ofSingleLine(declSpan, declLine.length());
                builder.context(declSpan.startLine(), declLine, declRange.startCol(), declRange.endCol(), targetName + " declared final here");
            }
        }
        builder.note("final variables can be assigned exactly once at declaration or in the constructor, never again");
        builder.help("remove the 'final' modifier on the declaration if reassignment is intentional");
        builder.help("introduce a new non final variable to hold the new value");
        return builder.build();
    }
}
