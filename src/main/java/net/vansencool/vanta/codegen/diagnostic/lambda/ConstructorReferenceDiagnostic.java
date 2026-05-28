package net.vansencool.vanta.codegen.diagnostic.lambda;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.DiagnosticBuilder;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.expression.MethodReferenceExpression;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import net.vansencool.vanta.symbol.method.MethodSymbol;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic for a constructor reference {@code Type::new} whose target has no constructor of the SAM arity.
 */
public final class ConstructorReferenceDiagnostic {

    private ConstructorReferenceDiagnostic() {
    }

    /**
     * @param ctx      method context being compiled
     * @param ref      offending constructor reference
     * @param ownerSym resolved target type symbol
     * @param samArity arity of the target functional interface SAM
     * @return diagnostic describing the rejection
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull MethodReferenceExpression ref, @NotNull TypeSymbol ownerSym, int samArity) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building constructor reference diagnostic at line " + ref.line());
        SpanTable spans = cg.spanTable();
        Span refSpan = spans.span(ref);
        if (refSpan == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for constructor reference at line " + ref.line());
        String sourceText = SourceLines.lineAt(cg.source(), refSpan.startLine());
        SourceRange range = SourceRange.ofSingleLine(refSpan, sourceText.length());
        String ownerName = ownerSym.internalName().replace('/', '.');
        List<MethodSymbol> ctors = constructors(ownerSym);
        String plural = samArity == 1 ? "" : "s";

        DiagnosticBuilder builder = Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .at(refSpan.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("constructor reference '" + ownerName + "::new' has no constructor with " + samArity + " parameter" + plural)
                .label("target SAM expects a " + samArity + "-argument constructor, none found")
                .note("the target functional interface's abstract method takes " + samArity + " argument" + plural + ", so the constructor must take the same");
        if (ctors.isEmpty()) {
            builder.help("class '" + ownerName + "' declares no public constructor, add one with " + samArity + " parameter" + plural);
        } else {
            for (MethodSymbol c : ctors) {
                int arity = c.parameterTypes().size();
                builder.help("available constructor '" + ownerName + "::new' takes " + arity + " parameter" + (arity == 1 ? "" : "s"));
            }
        }
        return builder.build();
    }

    private static @NotNull List<MethodSymbol> constructors(@NotNull TypeSymbol owner) {
        List<MethodSymbol> out = new ArrayList<>();
        for (MethodSymbol m : owner.methods()) {
            if ("<init>".equals(m.name())) out.add(m);
        }
        return out;
    }
}
