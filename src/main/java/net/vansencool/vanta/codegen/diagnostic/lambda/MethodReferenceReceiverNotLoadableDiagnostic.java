package net.vansencool.vanta.codegen.diagnostic.lambda;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.MethodReferenceExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;

/**
 * Diagnostic for a method reference whose receiver type was inferred but cannot be loaded.
 */
public final class MethodReferenceReceiverNotLoadableDiagnostic {

    private MethodReferenceReceiverNotLoadableDiagnostic() {
    }

    /**
     * @param ctx           method context being compiled
     * @param ref           offending method reference
     * @param receiverInternal internal name of the receiver type that failed to load
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull MethodReferenceExpression ref, @NotNull String receiverInternal) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building method reference receiver load diagnostic at line " + ref.line());
        SpanTable spans = cg.spanTable();
        Span refSpan = spans.span(ref);
        if (refSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for method reference at line " + ref.line());
        String sourceText = SourceLines.lineAt(cg.source(), refSpan.startLine());
        SourceRange range = SourceRange.ofSingleLine(refSpan, sourceText.length());
        String receiverName = receiverInternal.replace('/', '.');

        return Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(refSpan.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("receiver type '" + receiverName + "' of method reference could not be loaded")
                .label("inferred receiver class is not on the classpath or among compiled sources")
                .note("looked up '" + receiverName + "' in compiled sources and every loaded JAR on the classpath, neither defined it")
                .help("add the JAR containing '" + receiverName + "' to the classpath, or import the matching class so the method reference can resolve")
                .build();
    }
}
