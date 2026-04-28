package net.vansencool.vanta;

import net.vansencool.vanta.exception.CompilationException;
import net.vansencool.vanta.server.ServerProtocol;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line interface for the Vanta compiler.
 *
 * <pre>
 * Usage: vanta [options] &lt;source files...&gt;
 *   -cp &lt;path&gt;    classpath (platform separator)
 *   -d &lt;dir&gt;      output directory (default: current dir)
 *   --server      run as a persistent compile server (stdin/stdout protocol)
 * </pre>
 */
public final class VantaCLI {

    private VantaCLI() {
    }

    public static void main(String @NotNull [] args) {
        if (args.length == 0) {
            printHelp();
            System.exit(1);
        }

        String classpath = null;
        String outputDir = ".";
        boolean serverMode = false;
        List<String> sourceFiles = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cp", "-classpath" -> {
                    if (++i >= args.length) die("-cp requires an argument");
                    classpath = args[i];
                }
                case "-d" -> {
                    if (++i >= args.length) die("-d requires an argument");
                    outputDir = args[i];
                }
                case "--server" -> serverMode = true;
                default -> {
                    if (args[i].startsWith("-")) die("Unknown option: " + args[i]);
                    sourceFiles.add(args[i]);
                }
            }
        }

        if (serverMode) {
            try {
                ServerProtocol.run();
            } catch (IOException e) {
                die("Server IO error: " + e.getMessage());
            }
            return;
        }

        if (sourceFiles.isEmpty()) die("No source files specified");

        VantaCompiler compiler = classpath != null
                ? VantaCompiler.withClasspath(classpath)
                : new VantaCompiler();

        compile(compiler, readSources(sourceFiles), outputDir);
    }

    private static void compile(@NotNull VantaCompiler compiler, @NotNull Map<String, String> sources, @NotNull String outputDir) {
        Path outPath = Paths.get(outputDir);
        try {
            Files.createDirectories(outPath);
        } catch (IOException e) {
            die("Cannot create output directory: " + outputDir);
        }
        try {
            Map<String, byte[]> result = compiler.compileAll(sources);
            int written = 0;
            for (Map.Entry<String, byte[]> entry : result.entrySet()) {
                Path classFile = outPath.resolve(entry.getKey() + ".class");
                Files.createDirectories(classFile.getParent());
                Files.write(classFile, entry.getValue());
                written++;
            }
            System.out.println(written + " class file(s) written to " + outPath.toAbsolutePath());
        } catch (CompilationException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            die("IO error: " + e.getMessage());
        }
    }

    private static @NotNull Map<String, String> readSources(@NotNull List<String> sourceFiles) {
        Map<String, String> sources = new HashMap<>();
        for (String file : sourceFiles) {
            Path p = Paths.get(file);
            if (!Files.isRegularFile(p)) die("Not a file: " + file);
            try {
                sources.put(file, Files.readString(p));
            } catch (IOException e) {
                die("Cannot read " + file + ": " + e.getMessage());
            }
        }
        return sources;
    }

    private static void printHelp() {
        System.out.println("vanta - blazing-fast Java compiler");
        System.out.println("Usage: vanta [options] <source files...>");
        System.out.println("  -cp <path>    classpath (platform path separator)");
        System.out.println("  -d <dir>      output directory (default: current dir)");
        System.out.println("  --server      persistent compile server over stdin/stdout");
    }

    private static void die(@NotNull String message) {
        System.err.println("error: " + message);
        System.exit(1);
    }
}
