package net.vansencool.vanta;

import net.vansencool.vanta.util.Equiv;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compiles the entire bundled ASM source tree with both javac and Vanta,
 * then verifies that Vanta's output is instruction-equivalent to javac's.
 */
public class AsmCompileTest {

    @Test
    public void compileEntireAsm() throws Exception {
        int failures = Equiv.compareDirectories("ASM", Paths.get("test-libraries/asm"));
        if (failures > 0) fail("failures=" + failures + " (see stdout)");
    }
}
