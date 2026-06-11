package net.vansencool.vanta;

import net.vansencool.vanta.util.Equiv;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compiles the entire bundled Commons Lang source tree with both javac and
 * Vanta, then verifies that Vanta's output is instruction-equivalent to
 * javac's.
 */
public class CommonsLangCompileTest {

    @Test
    public void compileEntireCommonsLang() throws Exception {
        int failures = Equiv.compareDirectories("COMMONS-LANG", Paths.get("test-libraries/commons-lang"));
        if (failures > 0) fail("failures=" + failures + " (see stdout)");
    }
}
