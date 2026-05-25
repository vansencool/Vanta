package net.vansencool.vanta.codegen.diagnostic.imp;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.exception.CompilationException;
import net.vansencool.vanta.parser.ast.declaration.CompilationUnit;
import net.vansencool.vanta.parser.ast.declaration.ImportDeclaration;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Validates every {@link ImportDeclaration} in a compilation unit against the
 * classpath and registered sources.
 */
public final class ImportValidator {

    private ImportValidator() {
    }

    /**
     * Throws {@link CompilationException} for the first unresolvable import.
     */
    public static void validate(@NotNull ClasspathManager cp, @NotNull String source, @Nullable String sourceFile, @NotNull CompilationUnit cu) {
        for (ImportDeclaration decl : cu.imports()) {
            String fq = decl.name();
            if (decl.isStatic() && decl.isWildcard()) {
                String ownerFq = fq.endsWith(".*") ? fq.substring(0, fq.length() - 2) : fq;
                if (!nestedAwareExists(cp, ownerFq)) {
                    throw new CompilationException(UnresolvedImportDiagnostic.build(cp, source, sourceFile, cu.spanTable(), decl, ownerFq));
                }
            } else if (decl.isWildcard()) {
                String ownerFq = fq.endsWith(".*") ? fq.substring(0, fq.length() - 2) : fq;
                String ownerInternal = ownerFq.replace('.', '/');
                if (!cp.exists(ownerInternal) && !packagePopulated(cp, ownerInternal)) {
                    throw new CompilationException(UnresolvedImportDiagnostic.build(cp, source, sourceFile, cu.spanTable(), decl, ownerFq));
                }
            } else if (decl.isStatic()) {
                int lastDot = fq.lastIndexOf('.');
                String ownerFq = lastDot > 0 ? fq.substring(0, lastDot) : fq;
                if (!nestedAwareExists(cp, ownerFq)) {
                    throw new CompilationException(UnresolvedImportDiagnostic.build(cp, source, sourceFile, cu.spanTable(), decl, ownerFq));
                }
            } else {
                if (!nestedAwareExists(cp, fq)) {
                    throw new CompilationException(UnresolvedImportDiagnostic.build(cp, source, sourceFile, cu.spanTable(), decl, fq));
                }
            }
        }
    }

    /**
     * @return true when {@code fq} resolves to a top level class, or to a
     * nested class by reinterpreting trailing dotted segments as {@code $}
     * separated nested names
     */
    private static boolean nestedAwareExists(@NotNull ClasspathManager cp, @NotNull String fq) {
        String[] parts = fq.split("\\.");
        for (int split = parts.length; split >= 1; split--) {
            StringBuilder internal = new StringBuilder(parts[0]);
            for (int i = 1; i < split; i++) internal.append('/').append(parts[i]);
            for (int i = split; i < parts.length; i++) internal.append('$').append(parts[i]);
            if (cp.exists(internal.toString())) return true;
        }
        return false;
    }

    /**
     * @return true when any source registered type lives under {@code packageInternal}
     */
    private static boolean packagePopulated(@NotNull ClasspathManager cp, @NotNull String packageInternal) {
        String prefix = packageInternal + "/";
        TypeRegistry registry = cp.typeRegistry();
        for (String name : registry.astSourceNames()) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
