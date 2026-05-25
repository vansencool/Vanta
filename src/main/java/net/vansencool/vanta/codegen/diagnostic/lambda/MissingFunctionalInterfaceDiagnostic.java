package net.vansencool.vanta.codegen.diagnostic.lambda;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.CandidateSearch;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Diagnostic for a lambda or method reference whose target interface internal name is not loadable.
 */
public final class MissingFunctionalInterfaceDiagnostic {

    private MissingFunctionalInterfaceDiagnostic() {
    }

    /**
     * @param ctx              method context being compiled
     * @param spanOwner        AST node whose span anchors the highlight
     * @param interfaceInternal internal name of the missing functional interface
     * @param construct        short label for the construct (e.g. {@code "lambda"} or {@code "method reference"})
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull AstNode spanOwner, @NotNull String interfaceInternal, @NotNull String construct) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building missing functional interface diagnostic for " + construct);
        SpanTable spans = cg.spanTable();
        Span span = spans.span(spanOwner);
        if (span == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for " + construct + " referencing interface '" + interfaceInternal + "'");
        String sourceText = SourceLines.lineAt(cg.source(), span.startLine());
        SourceRange range = SourceRange.ofSingleLine(span, sourceText.length());
        String typeName = interfaceInternal.replace('/', '.');
        int lastSlash = interfaceInternal.lastIndexOf('/');
        String simpleName = lastSlash < 0 ? interfaceInternal : interfaceInternal.substring(lastSlash + 1);

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(span.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("target functional interface '" + typeName + "' could not be loaded")
                .label(construct + " targets a type that is not on the classpath or among compiled sources")
                .note("looked up '" + typeName + "' in compiled sources and every loaded JAR on the classpath, neither defined it");

        List<String> candidates = CandidateSearch.sameSimpleName(ctx.methodResolver().classpathManager().typeRegistry(), simpleName);
        for (String candidate : candidates) {
            builder.help("add: import " + candidate.replace('/', '.') + ";");
        }
        if (candidates.isEmpty()) {
            builder.help("add the JAR containing '" + typeName + "' to the classpath, or import the matching class");
        }
        return builder.build();
    }
}
