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
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Diagnostic for a method reference whose target type is not a functional interface.
 */
public final class MethodReferenceNoSamDiagnostic {

    private MethodReferenceNoSamDiagnostic() {
    }

    /**
     * @param ctx          method context being compiled
     * @param ref          offending method reference
     * @param interfaceSym resolved target type symbol
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull MethodReferenceExpression ref, @NotNull TypeSymbol interfaceSym) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building method reference SAM diagnostic at line " + ref.line());
        SpanTable spans = cg.spanTable();
        Span refSpan = spans.span(ref);
        if (refSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for method reference at line " + ref.line());
        String sourceText = SourceLines.lineAt(cg.source(), refSpan.startLine());
        SourceRange range = SourceRange.ofSingleLine(refSpan, sourceText.length());
        String typeName = interfaceSym.internalName().replace('/', '.');
        int abstractCount = countAbstract(interfaceSym);
        String reason = !interfaceSym.isInterface()
                ? "'" + typeName + "' is a class, not an interface, so it has no single abstract method"
                : abstractCount == 0
                ? "interface '" + typeName + "' has no abstract methods to satisfy"
                : "interface '" + typeName + "' has " + abstractCount + " abstract methods, a method reference can only bind to an interface with exactly one";

        return Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(refSpan.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("target type '" + typeName + "' is not a functional interface")
                .label("method reference cannot bind to a non functional target")
                .note(reason)
                .help("assign or pass this method reference to a variable whose type is a single abstract method interface")
                .build();
    }

    private static int countAbstract(@NotNull TypeSymbol type) {
        Set<String> seen = new HashSet<>();
        Set<TypeSymbol> visited = new HashSet<>();
        collect(type, seen, visited);
        return seen.size();
    }

    private static void collect(@NotNull TypeSymbol type, @NotNull Set<String> seen, @NotNull Set<TypeSymbol> visited) {
        if (!visited.add(type)) return;
        for (MethodSymbol m : type.methods()) {
            if (m.isAbstract()) seen.add(m.name() + m.descriptor());
        }
        TypeSymbol sup = type.superclass();
        if (sup != null) collect(sup, seen, visited);
        for (TypeSymbol iface : type.interfaces()) collect(iface, seen, visited);
    }
}
