package net.vansencool.vanta.symbol.registry;

import net.vansencool.vanta.classpath.AsmClassInfo;
import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.CompilationUnit;
import net.vansencool.vanta.parser.ast.declaration.TypeKind;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.symbol.type.TypeSymbol;
import net.vansencool.vanta.symbol.type.asm.AsmTypeSymbol;
import net.vansencool.vanta.symbol.type.ast.AstTypeSymbol;
import net.vansencool.vanta.symbol.type.reflection.ReflectionTypeSymbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central index of every type known to the compiler. Looks up by JVM
 * internal name, returning the appropriate kind of {@link TypeSymbol}: an
 * AST backed symbol for sources Vanta has parsed, a reflection backed
 * symbol for classes loadable from the system classpath, or an ASM backed
 * symbol for classpath bytes that resist reflection.
 */
public final class TypeRegistry {

    private final @NotNull ClasspathManager classpathManager;
    private final @NotNull Map<String, TypeSymbol> cache = new ConcurrentHashMap<>();
    private final @NotNull Map<String, AstEntry> astSources = new ConcurrentHashMap<>();

    public TypeRegistry(@NotNull ClasspathManager classpathManager) {
        this.classpathManager = classpathManager;
    }

    public @NotNull ClasspathManager classpathManager() {
        return classpathManager;
    }

    /**
     * Registers every top level and nested class declared in {@code cu} so a
     * later {@link #lookup} returns an AST backed symbol with full source
     * positions and unerased generic info. The supplied {@code typeResolver}
     * carries the import scope of {@code cu} and is reused by symbols built
     * from that source file.
     */
    public void register(@NotNull String sourceFile, @NotNull CompilationUnit cu, @NotNull TypeResolver typeResolver) {
        String pkg = cu.packageName() == null ? "" : cu.packageName().replace('.', '/');
        for (AstNode decl : cu.typeDeclarations()) {
            if (decl instanceof ClassDeclaration cd) {
                String internal = pkg.isEmpty() ? cd.name() : pkg + "/" + cd.name();
                registerRecursive(sourceFile, cu, cd, internal, typeResolver, null);
            }
        }
    }

    private void registerRecursive(@NotNull String sourceFile, @NotNull CompilationUnit cu, @NotNull ClassDeclaration cd, @NotNull String internalName, @NotNull TypeResolver typeResolver, @Nullable String enclosingNonStaticOuter) {
        astSources.put(internalName, new AstEntry(sourceFile, cu, cd, typeResolver, enclosingNonStaticOuter));
        cache.remove(internalName);
        for (AstNode m : cd.members()) {
            if (m instanceof ClassDeclaration nested) {
                boolean implicitStatic = nested.kind() != TypeKind.CLASS;
                boolean explicitStatic = (nested.modifiers() & Opcodes.ACC_STATIC) != 0;
                String childOuter = (implicitStatic || explicitStatic) ? null : internalName;
                registerRecursive(sourceFile, cu, nested, internalName + "$" + nested.name(), typeResolver, childOuter);
            }
        }
    }

    public @Nullable TypeSymbol lookup(@NotNull String internalName) {
        TypeSymbol cached = cache.get(internalName);
        if (cached != null) return cached;
        AstEntry ast = astSources.get(internalName);
        if (ast != null) {
            TypeSymbol sym = new AstTypeSymbol(ast.declaration(), internalName, ast.typeResolver(), this, ast.sourceFile(), ast.enclosingNonStaticOuter());
            cache.put(internalName, sym);
            return sym;
        }
        Class<?> cls = classpathManager.loadClass(internalName);
        if (cls != null) {
            TypeSymbol sym = new ReflectionTypeSymbol(cls, this, classpathManager);
            cache.put(internalName, sym);
            return sym;
        }
        AsmClassInfo info = classpathManager.asmClassInfo(internalName);
        if (info != null) {
            TypeSymbol sym = new AsmTypeSymbol(info, this);
            cache.put(internalName, sym);
            return sym;
        }
        return null;
    }

    /**
     * @return true when {@code internalName} has a Vanta parsed source
     * declaration registered. Used to distinguish "compiled in this batch"
     * from "loaded from a JAR".
     */
    public boolean hasAstSource(@NotNull String internalName) {
        return astSources.containsKey(internalName);
    }

    private record AstEntry(@NotNull String sourceFile, @NotNull CompilationUnit cu,
                            @NotNull ClassDeclaration declaration, @NotNull TypeResolver typeResolver,
                            @Nullable String enclosingNonStaticOuter) {
    }
}
