package net.vansencool.vanta.classpath;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Bytecode-derived view of a classpath class. Used when {@link Class#forName} fails
 * (typically due to {@link LinkageError} from missing transitive dependencies) but the
 * .class bytes are still readable from the classpath. Provides enough method/constructor
 * info for overload resolution without ever linking the class.
 */
public final class AsmClassInfo {

    private final @NotNull String internalName;
    private final @Nullable String superInternalName;
    private final @NotNull String[] interfaceInternalNames;
    private final int access;
    private final @NotNull List<MethodInfo> methods;
    private final @NotNull List<FieldInfo> fields;

    private AsmClassInfo(@NotNull String internalName, @Nullable String superInternalName,
                         @NotNull String[] interfaceInternalNames, int access,
                         @NotNull List<MethodInfo> methods, @NotNull List<FieldInfo> fields) {
        this.internalName = internalName;
        this.superInternalName = superInternalName;
        this.interfaceInternalNames = interfaceInternalNames;
        this.access = access;
        this.methods = methods;
        this.fields = fields;
    }

    /**
     * Parses class bytes via ASM and extracts method signatures and class metadata.
     * Returns {@code null} if the bytes are not a valid class file.
     */
    public static @Nullable AsmClassInfo parse(byte @NotNull [] bytes) {
        try {
            ClassReader cr = new ClassReader(bytes);
            List<MethodInfo> methods = new ArrayList<>();
            List<FieldInfo> fields = new ArrayList<>();
            String[] holder = new String[2];
            int[] accessHolder = new int[1];
            String[][] ifaceHolder = new String[1][];
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int acc, String name, String signature, String superName, String[] interfaces) {
                    holder[0] = name;
                    holder[1] = superName;
                    accessHolder[0] = acc;
                    ifaceHolder[0] = interfaces != null ? interfaces : new String[0];
                }

                @Override
                public MethodVisitor visitMethod(int acc, String name, String descriptor, String signature, String[] exceptions) {
                    methods.add(new MethodInfo(name, descriptor, acc));
                    return null;
                }

                @Override
                public FieldVisitor visitField(int acc, String name, String descriptor, String signature, Object value) {
                    fields.add(new FieldInfo(name, descriptor, acc, value));
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new AsmClassInfo(holder[0], holder[1], ifaceHolder[0], accessHolder[0], methods, fields);
        } catch (Exception e) {
            return null;
        }
    }

    public @NotNull String internalName() {
        return internalName;
    }

    public @Nullable String superInternalName() {
        return superInternalName;
    }

    public @NotNull String[] interfaceInternalNames() {
        return interfaceInternalNames;
    }

    public int access() {
        return access;
    }

    public boolean isInterface() {
        return (access & Opcodes.ACC_INTERFACE) != 0;
    }

    public @NotNull List<MethodInfo> methods() {
        return methods;
    }

    public @NotNull List<FieldInfo> fields() {
        return fields;
    }

    /**
     * Lightweight method metadata extracted from a classfile constant pool.
     */
    public record MethodInfo(@NotNull String name, @NotNull String descriptor, int access) {

        public boolean isStatic() {
            return (access & Opcodes.ACC_STATIC) != 0;
        }

        public boolean isVarArgs() {
            return (access & Opcodes.ACC_VARARGS) != 0;
        }
    }

    /**
     * Lightweight field metadata extracted from a classfile constant pool.
     */
    public record FieldInfo(@NotNull String name, @NotNull String descriptor, int access,
                            @Nullable Object constantValue) {
        public boolean isStatic() {
            return (access & Opcodes.ACC_STATIC) != 0;
        }
    }
}
