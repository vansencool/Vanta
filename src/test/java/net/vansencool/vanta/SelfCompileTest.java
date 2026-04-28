package net.vansencool.vanta;

import net.vansencool.vanta.util.Equiv;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compiles Vanta's own source tree with both javac and Vanta,
 * then verifies that Vanta's output is instruction-equivalent to javac's.
 */
public class SelfCompileTest {

    @Test
    public void selfCompileEntireCodebase() throws Exception {
        int failures = Equiv.compareDirectories("SELF", Paths.get("src/main/java"));
        if (failures > 0) fail("failures=" + failures + " (see stdout)");
    }
}
