package net.vansencool.vanta;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.util.Equiv;
import net.vansencool.vanta.util.JavacCompiler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression coverage for multi-dimensional array initializer literals against
 * javac, which previously only emitted correct bytecode for single-dim forms.
 */
public class MultiDimArrayTest {

    @Test
    public void twoDimIntInitializer() {
        assertBytecodeEquivalent("""
                public class MD2 {
                    int[][] grid() { return new int[][] {{1,2,3},{4,5,6}}; }
                }
                """, "MD2");
    }

    @Test
    public void threeDimIntInitializer() {
        assertBytecodeEquivalent("""
                public class MD3 {
                    int[][][] cube() { return new int[][][] {{{1,2},{3,4}},{{5,6},{7,8}}}; }
                }
                """, "MD3");
    }

    @Test
    public void twoDimStringInitializer() {
        assertBytecodeEquivalent("""
                public class MDS {
                    String[][] grid() { return new String[][] {{"a","b"},{"c","d"}}; }
                }
                """, "MDS");
    }

    @Test
    public void bareNestedInitializerInDeclaration() {
        assertBytecodeEquivalent("""
                public class MDB {
                    int[][] grid() {
                        int[][] g = {{1,2},{3,4}};
                        return g;
                    }
                }
                """, "MDB");
    }

    private void assertBytecodeEquivalent(String source, String className) {
        try {
            VantaCompiler compiler = new VantaCompiler(new ClasspathManager());
            Map<String, byte[]> vantaOut = compiler.compile(source, className + ".java");
            byte[] vantaBytes = vantaOut.get(className);
            if (vantaBytes == null) fail("vanta produced no bytecode for " + className);
            byte[] javacBytes = JavacCompiler.compileSingle(className, source);
            if (javacBytes == null) fail("javac produced no bytecode for " + className);
            ClassNode vn = Equiv.parse(vantaBytes);
            ClassNode jn = Equiv.parse(javacBytes);
            assertEquals(Collections.emptyList(), Equiv.diff(jn, vn), className + " diverged from javac");
        } catch (Exception e) {
            fail(e);
        }
    }
}
