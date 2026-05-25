package net.vansencool.vanta.diagnostic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates diagnostics collected during a batch compile, grouped by file
 * and (optionally) by method within each file. Used to recover past the first
 * error so a single run can surface every problem in the build.
 */
public final class DiagnosticReport {

    private final @NotNull Map<String, FileGroup> files = new LinkedHashMap<>();

    public void report(@Nullable String sourceFile, @Nullable String methodKey, @NotNull Diagnostic diagnostic) {
        String fileKey = sourceFile != null ? sourceFile : "<unknown>";
        FileGroup group = files.computeIfAbsent(fileKey, FileGroup::new);
        group.add(methodKey, diagnostic);
    }

    public boolean hasErrors() {
        for (FileGroup g : files.values()) if (g.hasErrors()) return true;
        return false;
    }

    public @NotNull List<FileGroup> fileGroups() {
        return new ArrayList<>(files.values());
    }

    public static final class FileGroup {
        private final @NotNull String file;
        private final @NotNull Map<String, MethodGroup> methods = new LinkedHashMap<>();
        private static final @NotNull String FILE_LEVEL = "";

        FileGroup(@NotNull String file) {
            this.file = file;
        }

        void add(@Nullable String methodKey, @NotNull Diagnostic diagnostic) {
            String key = methodKey != null ? methodKey : FILE_LEVEL;
            methods.computeIfAbsent(key, MethodGroup::new).diagnostics.add(diagnostic);
        }

        public @NotNull String file() {
            return file;
        }

        public @NotNull List<MethodGroup> methodGroups() {
            return new ArrayList<>(methods.values());
        }

        public boolean hasErrors() {
            for (MethodGroup m : methods.values()) {
                for (Diagnostic d : m.diagnostics()) if (d.severity() == Severity.ERROR) return true;
            }
            return false;
        }
    }

    public static final class MethodGroup {
        private final @NotNull String method;
        private final @NotNull List<Diagnostic> diagnostics = new ArrayList<>();

        MethodGroup(@NotNull String method) {
            this.method = method;
        }

        public @NotNull String method() {
            return method;
        }

        public @NotNull List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
