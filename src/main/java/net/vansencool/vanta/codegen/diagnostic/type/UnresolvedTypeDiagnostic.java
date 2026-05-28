package net.vansencool.vanta.codegen.diagnostic.type;

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
import net.vansencool.vanta.parser.ast.type.TypeNode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Diagnostic for a type reference that does not resolve against the classpath or known sources.
 */
public final class UnresolvedTypeDiagnostic {

    private UnresolvedTypeDiagnostic() {
    }

    /**
     * @param ctx          method context being compiled
     * @param spanOwner    AST node whose span anchors the highlight
     * @param type         the unresolvable type node
     * @param contextLabel where the unresolved type appears (e.g. {@code "new expression"})
     * @return diagnostic describing the failure
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull AstNode spanOwner, @NotNull TypeNode type, @NotNull String contextLabel) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building unresolved type diagnostic for '" + type.name() + "'");
        SpanTable spans = cg.spanTable();
        Span typeSpan = spans.span(type);
        Span ownerSpan = spans.span(spanOwner);
        Span anchor = typeSpan != null ? typeSpan : ownerSpan;
        if (anchor == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for type '" + type.name() + "' or its enclosing " + contextLabel + " at line " + type.line());
        String sourceText = SourceLines.lineAt(cg.source(), anchor.startLine());
        SourceRange range = SourceRange.ofSingleLine(anchor, sourceText.length());

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(anchor.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("cannot resolve type '" + type.name() + "' in " + contextLabel)
                .label("type '" + type.name() + "' not found on classpath or among imports")
                .note("checked the imports of this source file, wildcard imports, the enclosing package, and every loaded JAR, none declared '" + type.name() + "'");

        if (!type.name().contains(".") && !type.name().contains("/")) {
            List<String> candidates = CandidateSearch.sameSimpleName(ctx.methodResolver().classpathManager().typeRegistry(), type.name());
            for (String candidate : candidates) {
                builder.help("add: import " + candidate.replace('/', '.') + ";");
            }
        }
        return builder.build();
    }
}
