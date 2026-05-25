package net.vansencool.vanta.codegen.diagnostic.lambda;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.LambdaExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Diagnostic for a lambda whose target type is not a functional interface (has no SAM).
 */
public final class LambdaNoSamDiagnostic {

    private LambdaNoSamDiagnostic() {
    }

    /**
     * @param ctx           method context being compiled
     * @param lambda        lambda whose target type lacks a SAM
     * @param interfaceSym  resolved target type symbol
     * @return diagnostic describing the failure
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull LambdaExpression lambda, @NotNull TypeSymbol interfaceSym) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building lambda SAM diagnostic at line " + lambda.line());
        SpanTable spans = cg.spanTable();
        Span lambdaSpan = spans.span(lambda);
        if (lambdaSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for lambda at line " + lambda.line());
        String sourceText = SourceLines.lineAt(cg.source(), lambdaSpan.startLine());
        SourceRange range = SourceRange.ofSingleLine(lambdaSpan, sourceText.length());
        String typeName = interfaceSym.internalName().replace('/', '.');
        int abstractCount = countAbstractMethods(interfaceSym);
        String reason = !interfaceSym.isInterface()
                ? "'" + typeName + "' is a class, not an interface, so it has no single abstract method to satisfy"
                : abstractCount == 0
                ? "interface '" + typeName + "' has no abstract methods, so a lambda has no method to implement"
                : "interface '" + typeName + "' has " + abstractCount + " abstract methods, a lambda can only implement an interface with exactly one";

        return Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(lambdaSpan.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("target type '" + typeName + "' is not a functional interface")
                .label("lambda cannot bind to a non functional target")
                .note(reason)
                .help("assign or pass this lambda to a variable whose type is an interface with exactly one abstract method, e.g. java.lang.Runnable or java.util.function.Function")
                .build();
    }

    private static int countAbstractMethods(@NotNull TypeSymbol type) {
        Set<String> seen = new HashSet<>();
        List<MethodSymbol> abstractMethods = new ArrayList<>();
        collectAbstract(type, abstractMethods, seen, new HashSet<>());
        return abstractMethods.size();
    }

    private static void collectAbstract(@NotNull TypeSymbol type, @NotNull List<MethodSymbol> out, @NotNull Set<String> signatures, @NotNull Set<TypeSymbol> visited) {
        if (!visited.add(type)) return;
        for (MethodSymbol m : type.methods()) {
            if (!m.isAbstract()) continue;
            String sig = m.name() + m.descriptor();
            if (signatures.add(sig)) out.add(m);
        }
        TypeSymbol sup = type.superclass();
        if (sup != null) collectAbstract(sup, out, signatures, visited);
        for (TypeSymbol iface : type.interfaces()) collectAbstract(iface, out, signatures, visited);
    }
}
