package net.vansencool.vanta.server;

import net.vansencool.vanta.ParallelMode;
import net.vansencool.vanta.VantaCompiler;
import net.vansencool.vanta.exception.CompilationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implements the Vanta server wire protocol over stdin/stdout.
 *
 * <p>Request format:
 * <pre>
 * COMPILE
 * CLASSPATH: entry1:entry2   (optional)
 * THREADS: smart             (optional; smart = auto parallel, files=N, methods=N, hybrid=F,M, or bare int for file workers)
 * FILES: N
 *
 * FILE: Foo.java
 * &lt;source lines&gt;
 * ENDFILE
 *
 * FILE: Bar.java
 * &lt;source lines&gt;
 * ENDFILE
 * END
 * </pre>
 *
 * <p>Response format:
 * <pre>
 * RESULT
 * CLASSES: N
 *
 * CLASS: com/example/Foo
 * STATUS: OK
 * BYTES: &lt;base64&gt;
 * ENDCLASS
 *
 * CLASS: com/example/Bar
 * STATUS: ERROR
 * ERROR: &lt;message&gt;
 * ENDCLASS
 * END
 * </pre>
 *
 * <p>All files in a single request are compiled together via {@code compileAll}, giving the type
 * resolver full cross-file context. Source lines equal to exactly {@code ENDFILE} or {@code END}
 * are escaped with a leading backslash; the parser strips it on read.
 */
public final class ServerProtocol {

    private static final String ESCAPE_PREFIX = "\\";
    private static final Pattern HYBRID_PATTERN = Pattern.compile("hybrid=(\\d+),(\\d+)");

    private ServerProtocol() {
    }

    /**
     * Runs the server loop, reading requests from stdin and writing responses to stdout until EOF or QUIT.
     */
    public static void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter out = new PrintWriter(System.out, true);

        out.println("READY");

        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.equals("QUIT")) {
                break;
            }
            if (line.equals("COMPILE")) {
                CompileRequest request = readRequest(in);
                CompileResponse response = execute(request);
                writeResponse(out, response);
            }
        }
    }

    private static @NotNull CompileRequest readRequest(@NotNull BufferedReader in) throws IOException {
        String classpath = null;
        ParallelMode parallelMode = null;
        Map<String, String> files = new LinkedHashMap<>();

        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("CLASSPATH: ")) {
                classpath = line.substring("CLASSPATH: ".length());
            } else if (line.startsWith("THREADS: ")) {
                parallelMode = parseThreads(line.substring("THREADS: ".length()).trim());
            } else if (line.startsWith("FILE: ")) {
                String filename = line.substring("FILE: ".length());
                StringBuilder source = new StringBuilder();
                String srcLine;
                while ((srcLine = in.readLine()) != null) {
                    if (srcLine.equals("ENDFILE")) break;
                    if (srcLine.startsWith(ESCAPE_PREFIX) && isReserved(srcLine.substring(1))) {
                        source.append(srcLine, 1, srcLine.length());
                    } else {
                        source.append(srcLine);
                    }
                    source.append('\n');
                }
                files.put(filename, source.toString());
            } else if (line.equals("END")) {
                break;
            }
        }

        return new CompileRequest(classpath, parallelMode, files);
    }

    /**
     * Parses the value of a {@code THREADS:} header into a {@link ParallelMode}.
     * Accepts: {@code smart}, {@code files=N}, {@code methods=N}, {@code hybrid=F,M}, or a bare integer for file workers.
     */
    private static @Nullable ParallelMode parseThreads(@NotNull String value) {
        if (value.equals("smart")) return ParallelMode.smart();
        if (value.startsWith("files=")) {
            try {
                return ParallelMode.files(Integer.parseInt(value.substring(6)));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value.startsWith("methods=")) {
            try {
                return ParallelMode.methods(Integer.parseInt(value.substring(8)));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        Matcher m = HYBRID_PATTERN.matcher(value);
        if (m.matches()) {
            try {
                return ParallelMode.hybrid(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            int n = Integer.parseInt(value);
            return n > 1 ? ParallelMode.files(n) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isReserved(@NotNull String line) {
        return line.equals("ENDFILE") || line.equals("END");
    }

    private static @NotNull CompileResponse execute(@NotNull CompileRequest request) {
        VantaCompiler compiler = request.classpath() != null
                ? VantaCompiler.withClasspath(request.classpath())
                : new VantaCompiler();
        try {
            Map<String, byte[]> result = request.parallelMode() != null
                    ? compiler.compileAllParallel(request.files(), request.parallelMode())
                    : compiler.compileAll(request.files());
            return CompileResponse.success(result);
        } catch (CompilationException e) {
            return CompileResponse.error(e.getMessage());
        }
    }

    private static void writeResponse(@NotNull PrintWriter out, @NotNull CompileResponse response) {
        if (response.globalError() != null) {
            out.println("RESULT");
            out.println("CLASSES: 0");
            out.println();
            out.println("CLASS: <none>");
            out.println("STATUS: ERROR");
            out.println("ERROR: " + response.globalError());
            out.println("ENDCLASS");
            out.println("END");
            return;
        }

        Map<String, byte[]> classes = Objects.requireNonNull(response.classes());
        out.println("RESULT");
        out.println("CLASSES: " + classes.size());
        out.println();

        Base64.Encoder encoder = Base64.getEncoder();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            out.println("CLASS: " + entry.getKey());
            out.println("STATUS: OK");
            out.println("BYTES: " + encoder.encodeToString(entry.getValue()));
            out.println("ENDCLASS");
        }

        out.println("END");
    }

    private record CompileRequest(@Nullable String classpath, @Nullable ParallelMode parallelMode,
                                  @NotNull Map<String, String> files) {
    }

    private record CompileResponse(@Nullable String globalError, @Nullable Map<String, byte[]> classes) {

        static @NotNull CompileResponse success(@NotNull Map<String, byte[]> classes) {
            return new CompileResponse(null, classes);
        }

        static @NotNull CompileResponse error(@NotNull String message) {
            return new CompileResponse(message, Map.of());
        }
    }
}
