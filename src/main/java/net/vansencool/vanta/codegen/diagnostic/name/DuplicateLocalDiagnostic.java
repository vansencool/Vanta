package net.vansencool.vanta.codegen.diagnostic.name;

import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.context.MethodContext;
import net.vansencool.vanta.codegen.diagnostic.util.SourceRange;
import net.vansencool.vanta.codegen.diagnostic.util.TypeCompatibility;
import net.vansencool.vanta.diagnostic.Diagnostic;
import net.vansencool.vanta.diagnostic.Severity;
import net.vansencool.vanta.diagnostic.fix.Applicability;
import net.vansencool.vanta.diagnostic.fix.Edit;
import net.vansencool.vanta.diagnostic.fix.Fix;
import net.vansencool.vanta.diagnostic.util.SourceLines;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.span.Span;
import net.vansencool.vanta.parser.ast.span.SpanTable;
import net.vansencool.vanta.resolver.scope.LocalVariable;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Diagnostic for a local variable declaration whose name already exists in the enclosing scope.
 */
public final class DuplicateLocalDiagnostic {

    private DuplicateLocalDiagnostic() {
    }

    /**
     * @param ctx       method context being compiled
     * @param spanOwner declaration AST node, used for the main span
     * @param name      duplicate variable name
     * @param existing  the previously declared variable that this declaration would shadow
     * @return diagnostic describing the redeclaration
     */
    public static @NotNull Diagnostic build(@NotNull MethodContext ctx, @NotNull AstNode spanOwner, @NotNull String name, @NotNull LocalVariable existing) {
        ClassGenerator cg = ctx.classGenerator();
        if (cg == null) throw new IllegalStateException("internal compiler error: no class generator on method context while building duplicate local diagnostic for '" + name + "'");
        SpanTable spans = cg.spanTable();
        Span span = spans.span(spanOwner);
        if (span == null) throw new IllegalStateException("internal compiler error: parser failed to record a span for declaration of '" + name + "'");
        String sourceText = SourceLines.lineAt(cg.source(), span.startLine());
        SourceRange range = SourceRange.ofSingleLine(span, sourceText.length());

        int nameStartCol = sourceText.indexOf(name, range.startCol());
        return Diagnostic.builder()
                .severity(Severity.ERROR)
                .sourceFile(cg.sourceFile())
                .fullSource(cg.source())
                .at(span.startLine(), sourceText)
                .highlight(range.startCol(), range.endCol())
                .title("variable '" + name + "' is already declared in this scope")
                .label("redeclaration of local '" + name + "'")
                .note("'" + name + "' is already a local of type " + TypeCompatibility.display(existing.type()) + ", a second declaration in the same scope is not allowed")
                .fix(nameStartCol >= 0 ? new Fix(
                        "rename to '" + name + "2'",
                        "keep both bindings distinct",
                        List.of(Edit.replace(span.startLine(), nameStartCol, nameStartCol + name.length(), name + "2", "rename here")),
                        Applicability.MAYBE_INCORRECT
                ) : new Fix("rename this declaration", null, List.of(), Applicability.UNSPECIFIED))
                .build();
    }
}
