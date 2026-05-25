package net.vansencool.vanta.codegen.diagnostic.imp;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.codegen.diagnostic.util.CandidateSearch;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.codegen.diagnostic.util.StringDistance;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.declaration.ImportDeclaration;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Diagnostic for an import statement whose target type, package, or static member cannot be resolved.
 */
public final class UnresolvedImportDiagnostic {

    private UnresolvedImportDiagnostic() {
    }

    /**
     * @param cp        classpath manager (for fuzzy suggestions)
     * @param source    full source text
     * @param sourceFile source file name
     * @param spans     span table for the parsed unit
     * @param decl      offending import declaration
     * @param missingFq the fully qualified name that did not resolve (may be the import name or its prefix)
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull ClasspathManager cp, @NotNull String source, @Nullable String sourceFile, @NotNull SpanTable spans, @NotNull ImportDeclaration decl, @NotNull String missingFq) {
        Span span = spans.span(decl);
        if (span == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for import '" + decl.name() + "' at line " + decl.line());
        String sourceText = SourceLines.lineAt(source, span.startLine());
        SourceRange range = SourceRange.ofSingleLine(span, sourceText.length());

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(sourceFile)
                .at(span.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("cannot resolve import '" + decl.name() + "'")
                .label("'" + missingFq + "' is not on the classpath or among compiled sources")
                .note("checked every loaded JAR, the JDK module image, and source files registered in this compilation, none declared '" + missingFq + "'");

        int lastDot = decl.name().lastIndexOf('.');
        if (lastDot > 0) {
            String simple = decl.name().substring(lastDot + 1);
            if (!"*".equals(simple)) {
                Set<String> already = new LinkedHashSet<>();
                for (String candidate : CandidateSearch.sameSimpleName(cp.typeRegistry(), simple)) {
                    String dotted = candidate.replace('/', '.');
                    if (already.add(dotted)) builder.help("did you mean: import " + dotted + ";");
                }
                for (String candidate : classpathByExactSimpleName(cp, simple)) {
                    String dotted = candidate.replace('/', '.');
                    if (already.add(dotted)) builder.help("did you mean: import " + dotted + ";");
                }
                if (already.size() < 5) {
                    for (String candidate : classpathByEditDistance(cp, missingFq.replace('.', '/'), 3)) {
                        String dotted = candidate.replace('/', '.');
                        if (already.add(dotted)) builder.help("did you mean: import " + dotted + ";");
                        if (already.size() >= 5) break;
                    }
                }
            }
        }
        builder.help("remove the import if the dependency is unused, or add the missing JAR / source file");
        return builder.build();
    }

    private static @NotNull List<String> classpathByExactSimpleName(@NotNull ClasspathManager cp, @NotNull String simple) {
        List<String> out = new ArrayList<>();
        String suffix = "/" + simple;
        for (String internal : cp.enumerateClasses()) {
            if (internal.endsWith(suffix) || internal.equals(simple)) out.add(internal);
            if (out.size() >= 5) break;
        }
        return out;
    }

    private static @NotNull List<String> classpathByEditDistance(@NotNull ClasspathManager cp, @NotNull String wanted, int threshold) {
        List<String> out = new ArrayList<>();
        for (String internal : cp.enumerateClasses()) {
            if (Math.abs(internal.length() - wanted.length()) > threshold) continue;
            if (StringDistance.levenshtein(internal, wanted) <= threshold) out.add(internal);
            if (out.size() >= 5) break;
        }
        return out;
    }
}
