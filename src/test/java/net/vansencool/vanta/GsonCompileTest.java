package net.vansencool.vanta;

import net.vansencool.vanta.util.Equiv;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compiles the entire bundled GSON source tree with both javac and Vanta,
 * then verifies that Vanta's output is instruction-equivalent to javac's.
 */
public class GsonCompileTest {

    @Test
    public void compileEntireGson() throws Exception {
        int failures = Equiv.compareDirectories("GSON", Paths.get("test-libraries/gson"));
        if (failures > 0) fail("failures=" + failures + " (see stdout)");
    }
}
