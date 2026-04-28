package net.vansencool.vanta.codegen;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.codegen.classes.annotation.AnnotationEmitter;
import net.vansencool.vanta.codegen.classes.bridge.BridgeMethodEmitter;
import net.vansencool.vanta.codegen.classes.clinit.StaticInitEmitter;
import net.vansencool.vanta.codegen.classes.constant.ConstantFolder;
import net.vansencool.vanta.codegen.classes.enumswitch.EnumSwitchScanner;
import net.vansencool.vanta.codegen.classes.header.ClassHeaderEmitter;
import net.vansencool.vanta.codegen.classes.kind.anno.AnnotationTypeBodyGenerator;
import net.vansencool.vanta.codegen.classes.kind.clazz.ClassBodyGenerator;
import net.vansencool.vanta.codegen.classes.kind.enumkind.EnumBodyGenerator;
import net.vansencool.vanta.codegen.classes.kind.iface.InterfaceBodyGenerator;
import net.vansencool.vanta.codegen.classes.kind.record.RecordBodyGenerator;
import net.vansencool.vanta.codegen.classes.method.MethodEmitter;
import net.vansencool.vanta.codegen.classes.opcode.OpcodeUtils;
import net.vansencool.vanta.codegen.classes.scan.AnonCounter;
import net.vansencool.vanta.codegen.classes.scan.AssertScanner;
import net.vansencool.vanta.codegen.classes.writer.VantaClassWriter;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.EnumConstant;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclaration;
import net.vansencool.vanta.parser.ast.declaration.FieldDeclarator;
import net.vansencool.vanta.parser.ast.declaration.MethodDeclaration;
import net.vansencool.vanta.parser.ast.declaration.Parameter;
import net.vansencool.vanta.parser.ast.declaration.RecordComponent;
import net.vansencool.vanta.parser.ast.declaration.TypeKind;
import net.vansencool.vanta.parser.ast.expression.LambdaExpression;
import net.vansencool.vanta.parser.ast.expression.NewExpression;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Top-level dispatcher for class-file generation. Owns the per-compilation-unit
 * and per-class state (counters, nested-class caches, nest-host and outer-class
 * trackers) plus the specialised emitters that actually produce bytecode, and
 * routes each type declaration to its matching {@code *BodyGenerator} based on
 * {@link TypeKind}. Emitters read back into this class through the
 * public-accessor surface so the shared state has a single owner.
 */
public final class ClassGenerator {

    private static final @NotNull String OBJECT_METHODS_DESC =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;";

    private final @NotNull Handle objectMethodsBootstrap =
            new Handle(Opcodes.H_INVOKESTATIC, "java/lang/runtime/ObjectMethods", "bootstrap", OBJECT_METHODS_DESC, false);

    private final @NotNull ClasspathManager classpathManager;
    private final @NotNull TypeResolver typeResolver;
    private final @Nullable String sourceFile;

    private final @NotNull AtomicInteger lambdaCounter = new AtomicInteger(0);
    private final @NotNull List<byte[]> anonClassBytecodes = new ArrayList<>();
    private final @NotNull List<String> anonClassNames = new ArrayList<>();
    private final @NotNull Map<String, Map<String, ResolvedType>> nestedClassFields = new HashMap<>();
    private final @NotNull Map<String, Map<String, SelfMethodInfo>> nestedClassMethods = new HashMap<>();
    private final @NotNull Map<String, Map<String, Object>> nestedClassConstants = new HashMap<>();
    private final @NotNull List<String> nestMemberInternals = new ArrayList<>();
    private final @NotNull LinkedHashMap<String, LinkedHashMap<String, Integer>> enumSwitchMaps = new LinkedHashMap<>();
    private final @NotNull ConstantFolder constantFolder;
    private final @NotNull AnnotationEmitter annotationEmitter;
    private final @NotNull BridgeMethodEmitter bridgeMethodEmitter;
    private final @NotNull ClassHeaderEmitter classHeaderEmitter;
    private final @NotNull StaticInitEmitter staticInitEmitter;
    private final @NotNull MethodEmitter methodEmitter;
    private final @NotNull AnnotationTypeBodyGenerator annotationTypeBodyGenerator;
    private final @NotNull InterfaceBodyGenerator interfaceBodyGenerator;
    private final @NotNull ClassBodyGenerator classBodyGenerator;
    private final @NotNull EnumBodyGenerator enumBodyGenerator;
    private final @NotNull RecordBodyGenerator recordBodyGenerator;
    private @NotNull AtomicInteger anonClassCounter = new AtomicInteger(0);
    private @NotNull Map<NewExpression, Integer> anonIndexAssignments = new IdentityHashMap<>();
    private @NotNull Map<LambdaExpression, Integer> lambdaIndexAssignments = new IdentityHashMap<>();
    private @Nullable String currentEnclosingOuter;
    private @Nullable String currentStaticOuter;
    private @Nullable String nestHostInternal;
    private boolean outerIsInterfaceLike;
    private @Nullable TypeKind currentOwnerKind;
    private @Nullable ClassDeclaration currentClassDecl;
    private @Nullable String switchMapSyntheticName;
    private boolean assertUsed;

    /**
     * @param classpathManager classpath used to load external classes and cache common-super queries
     * @param typeResolver     resolver used by every emitter for source-to-internal-name conversion
     * @param sourceFile       source file name written into the {@code SourceFile} attribute, or null
     */
    public ClassGenerator(@NotNull ClasspathManager classpathManager, @NotNull TypeResolver typeResolver, @Nullable String sourceFile) {
        this.classpathManager = classpathManager;
        this.typeResolver = typeResolver;
        this.sourceFile = sourceFile;
        this.constantFolder = new ConstantFolder(typeResolver, classpathManager);
        this.annotationEmitter = new AnnotationEmitter(typeResolver, classpathManager);
        this.bridgeMethodEmitter = new BridgeMethodEmitter(typeResolver, classpathManager);
        this.classHeaderEmitter = new ClassHeaderEmitter(sourceFile, annotationEmitter);
        this.staticInitEmitter = new StaticInitEmitter(this);
        this.methodEmitter = new MethodEmitter(this);
        this.annotationTypeBodyGenerator = new AnnotationTypeBodyGenerator(this);
        this.interfaceBodyGenerator = new InterfaceBodyGenerator(this);
        this.classBodyGenerator = new ClassBodyGenerator(this);
        this.enumBodyGenerator = new EnumBodyGenerator(this);
        this.recordBodyGenerator = new RecordBodyGenerator(this);
    }

    /**
     * Generates bytecode for a top-level type declaration, including any
     * synthetic {@code Outer$N} class carrying external-enum switch maps.
     *
     * @param classDecl   type declaration to emit
     * @param packageName enclosing package, or null for the default package
     * @return class file bytes for the top-level declaration
     */
    public byte @NotNull [] generate(@NotNull ClassDeclaration classDecl, @Nullable String packageName) {
        String outerInternal = toInternalName(classDecl.name(), packageName);
        collectNestedClassFields(classDecl, outerInternal);
        anonIndexAssignments = new IdentityHashMap<>();
        lambdaIndexAssignments = new IdentityHashMap<>();
        new AnonCounter(new int[]{0}, anonIndexAssignments, new int[]{0}, lambdaIndexAssignments).scanClass(classDecl);
        scanEnumSwitches(classDecl, outerInternal);
        byte[] bytes = generate(classDecl, packageName, null);
        if (switchMapSyntheticName != null) {
            byte[] syn = generateSwitchMapClass();
            anonClassBytecodes.add(syn);
            anonClassNames.add(switchMapSyntheticName);
            classpathManager.registerInMemoryClass(switchMapSyntheticName, syn);
        }
        return bytes;
    }

    /**
     * Generates bytecode for a type declaration, dispatching by kind to the
     * matching body generator. Saves and restores the current-owner state
     * around the call so nested emissions don't clobber the enclosing context.
     *
     * @param classDecl         type declaration to emit
     * @param packageName       enclosing package, or null for the default package
     * @param outerInternalName enclosing class internal name, or null for top-level types
     * @return class file bytes
     */
    public byte @NotNull [] generate(@NotNull ClassDeclaration classDecl, @Nullable String packageName, @Nullable String outerInternalName) {
        TypeKind savedKind = currentOwnerKind;
        ClassDeclaration savedDecl = currentClassDecl;
        currentOwnerKind = classDecl.kind();
        currentClassDecl = classDecl;
        constantFolder.currentOwnerKind(classDecl.kind());
        constantFolder.currentClassDecl(classDecl);
        try {
            return switch (classDecl.kind()) {
                case CLASS -> classBodyGenerator.generate(classDecl, packageName, outerInternalName);
                case INTERFACE -> interfaceBodyGenerator.generate(classDecl, packageName, outerInternalName);
                case ENUM -> enumBodyGenerator.generate(classDecl, packageName, outerInternalName);
                case RECORD -> recordBodyGenerator.generate(classDecl, packageName, outerInternalName);
                case ANNOTATION -> annotationTypeBodyGenerator.generate(classDecl, packageName, outerInternalName);
            };
        } finally {
            currentOwnerKind = savedKind;
            currentClassDecl = savedDecl;
            constantFolder.currentOwnerKind(savedKind);
            constantFolder.currentClassDecl(savedDecl);
        }
    }

    /**
     * Generates every nested type declared under {@code classDecl}, keyed by
     * their resulting internal names.
     *
     * @param classDecl   outer declaration whose nested types are emitted
     * @param packageName enclosing package, or null
     * @return map from inner-class internal name to its class-file bytes
     */
    public @NotNull Map<String, byte[]> generateInnerClasses(@NotNull ClassDeclaration classDecl, @Nullable String packageName) {
        String outerInternalName = toInternalName(classDecl.name(), packageName);
        collectNestedClassFields(classDecl, outerInternalName);
        nestHostInternal = outerInternalName;
        return generateInnerClassesRecursive(classDecl, packageName, outerInternalName);
    }

    /**
     * Drains and returns every anonymous-class bytecode accumulated during
     * the current top-level class emission, clearing the internal buffers.
     *
     * @return map from anon-class internal name to its class-file bytes
     */
    public @NotNull Map<String, byte[]> getAndClearAnonClassBytecodes() {
        Map<String, byte[]> result = new HashMap<>();
        for (int i = 0; i < anonClassBytecodes.size(); i++) {
            result.put(anonClassNames.get(i), anonClassBytecodes.get(i));
        }
        anonClassBytecodes.clear();
        anonClassNames.clear();
        return result;
    }

    /**
     * @return constant folder bound to this generator's resolver/classpath
     */
    public @NotNull ConstantFolder constantFolder() {
        return constantFolder;
    }

    /**
     * @return annotation emitter used for class/method/field attribute attachment
     */
    public @NotNull AnnotationEmitter annotationEmitter() {
        return annotationEmitter;
    }

    /**
     * @return bridge-method emitter shared with helpers (e.g. lambda/anon synthesis)
     */
    public @NotNull BridgeMethodEmitter bridgeMethodEmitter() {
        return bridgeMethodEmitter;
    }

    /**
     * @return shared header-emitter bound to this generator's source-file debug name
     */
    public @NotNull ClassHeaderEmitter classHeaderEmitter() {
        return classHeaderEmitter;
    }

    /**
     * @return static-init emitter bound to this generator
     */
    public @NotNull StaticInitEmitter staticInitEmitter() {
        return staticInitEmitter;
    }

    /**
     * @return method emitter used for delegating method-body generation
     */
    public @NotNull MethodEmitter methodEmitter() {
        return methodEmitter;
    }

    /**
     * @return classpath manager this generator loads external classes through
     */
    public @NotNull ClasspathManager classpathManager() {
        return classpathManager;
    }

    /**
     * @return resolver used for all type/descriptor/internal-name resolution
     */
    public @NotNull TypeResolver typeResolver() {
        return typeResolver;
    }

    /**
     * @return source-file name for the {@code SourceFile} debug attribute, or null
     */
    public @Nullable String sourceFile() {
        return sourceFile;
    }

    /**
     * @return ObjectMethods bootstrap handle used for record toString/hashCode/equals
     */
    public @NotNull Handle objectMethodsBootstrap() {
        return objectMethodsBootstrap;
    }

    /**
     * @return shared lambda index counter so synthetic lambda methods get unique suffixes
     */
    public @NotNull AtomicInteger lambdaCounter() {
        return lambdaCounter;
    }

    /**
     * @return shared anonymous-class index counter matching javac's source-order numbering
     */
    public @NotNull AtomicInteger anonClassCounter() {
        return anonClassCounter;
    }

    /**
     * @return accumulator list that receives generated anonymous-class bytecode
     */
    public @NotNull List<byte[]> anonClassBytecodes() {
        return anonClassBytecodes;
    }

    /**
     * @return accumulator list of internal names for generated anonymous classes
     */
    public @NotNull List<String> anonClassNames() {
        return anonClassNames;
    }

    /**
     * @return identity map from anonymous-class {@code new} expressions to their {@code $N} index
     */
    public @NotNull Map<NewExpression, Integer> anonIndexAssignments() {
        return anonIndexAssignments;
    }

    /**
     * @return identity map from lambdas to their pre-assigned source-order index
     */
    public @NotNull Map<LambdaExpression, Integer> lambdaIndexAssignments() {
        return lambdaIndexAssignments;
    }

    /**
     * @return cache of nested-class field types populated during generation
     */
    public @NotNull Map<String, Map<String, ResolvedType>> nestedClassFields() {
        return nestedClassFields;
    }

    /**
     * @return cache of nested-class self-method info populated during generation
     */
    public @NotNull Map<String, Map<String, SelfMethodInfo>> nestedClassMethods() {
        return nestedClassMethods;
    }

    /**
     * @return cache of nested-class constant-valued static finals populated during generation
     */
    public @NotNull Map<String, Map<String, Object>> nestedClassConstants() {
        return nestedClassConstants;
    }

    /**
     * @return current non-static enclosing class internal name, or null when none
     */
    public @Nullable String currentEnclosingOuter() {
        return currentEnclosingOuter;
    }

    /**
     * @return current static-enclosing class internal name, or null when not nested
     */
    public @Nullable String currentStaticOuter() {
        return currentStaticOuter;
    }

    /**
     * @return nest-host internal name for the currently generating top-level class, or null
     */
    public @Nullable String nestHostInternal() {
        return nestHostInternal;
    }

    /**
     * @return list of internal names advertised as nest members on the nest host
     */
    public @NotNull List<String> nestMemberInternals() {
        return nestMemberInternals;
    }

    /**
     * @return true when the currently-generating class sits inside an interface or annotation
     */
    public boolean outerIsInterfaceLike() {
        return outerIsInterfaceLike;
    }

    /**
     * @return true when {@code assert} was used anywhere in the current class
     */
    public boolean assertUsed() {
        return assertUsed;
    }

    /**
     * Swaps in a fresh anon-index assignment map and returns the previous one
     * so body generators can scope their own indexing without bleeding into
     * the enclosing class's numbering.
     *
     * @param fresh map the body generator will use during its run
     * @return previously-installed map for restoration
     */
    public @NotNull Map<NewExpression, Integer> swapAnonIndexAssignments(@NotNull Map<NewExpression, Integer> fresh) {
        Map<NewExpression, Integer> prev = anonIndexAssignments;
        anonIndexAssignments = fresh;
        return prev;
    }

    /**
     * Lambda-index companion to {@link #swapAnonIndexAssignments(Map)}.
     *
     * @param fresh map the body generator will use during its run
     * @return previously-installed map for restoration
     */
    public @NotNull Map<LambdaExpression, Integer> swapLambdaIndexAssignments(@NotNull Map<LambdaExpression, Integer> fresh) {
        Map<LambdaExpression, Integer> prev = lambdaIndexAssignments;
        lambdaIndexAssignments = fresh;
        return prev;
    }

    /**
     * Swaps current-outer state for a single class emission. The returned
     * array is fed back to {@link #restoreOuter(String, String)} to undo the
     * change after emission.
     *
     * @param enclosing   new non-static enclosing internal name, or null
     * @param staticOuter new static-enclosing internal name, or null
     * @return two-element array {@code [prevEnclosing, prevStaticOuter]}
     */
    public @Nullable String @NotNull [] swapOuter(@Nullable String enclosing, @Nullable String staticOuter) {
        String prevE = currentEnclosingOuter;
        String prevS = currentStaticOuter;
        currentEnclosingOuter = enclosing;
        currentStaticOuter = staticOuter;
        return new String[]{prevE, prevS};
    }

    /**
     * Restores outer state captured by {@link #swapOuter(String, String)}.
     *
     * @param enclosing   previous non-static enclosing internal name
     * @param staticOuter previous static-enclosing internal name
     */
    public void restoreOuter(@Nullable String enclosing, @Nullable String staticOuter) {
        currentEnclosingOuter = enclosing;
        currentStaticOuter = staticOuter;
    }

    /**
     * Sets the {@code assertUsed} flag. Body generators pre-set it after
     * scanning with {@link AssertScanner}; the statement generator also flips
     * it on via {@code assertUsed(true)} when it emits an assert late.
     *
     * @param used whether the class body uses {@code assert} anywhere
     */
    public void assertUsed(boolean used) {
        this.assertUsed = used;
    }

    /**
     * Installs the nest-host internal name for the currently-generating
     * top-level class.
     *
     * @param internal nest-host internal name, or null
     */
    public void nestHostInternal(@Nullable String internal) {
        this.nestHostInternal = internal;
    }

    /**
     * Adds a single nest-member internal name (e.g. a synthetic anon-class
     * name) to the current nest-host's advertised members.
     *
     * @param memberInternal internal name to append
     */
    public void addNestMember(@NotNull String memberInternal) {
        nestMemberInternals.add(memberInternal);
    }

    /**
     * Resets the nest-member list so a new top-level class starts empty.
     */
    public void clearNestMemberInternals() {
        nestMemberInternals.clear();
    }

    /**
     * Resets the anon-class bytecode accumulator between top-level classes.
     */
    public void clearAnonClassBytecodes() {
        anonClassBytecodes.clear();
    }

    /**
     * @param expr anonymous-class creation expression
     * @return pre-assigned {@code $N} index, or a freshly-minted one when the
     * expression wasn't part of the initial pre-scan
     */
    public int anonClassIndexFor(@NotNull NewExpression expr) {
        Integer assigned = anonIndexAssignments.get(expr);
        if (assigned != null) return assigned;
        return anonClassCounter.incrementAndGet();
    }

    /**
     * @param expr lambda expression
     * @return pre-assigned lambda index, or a freshly-minted one when the
     * lambda wasn't part of the initial pre-scan
     */
    public int lambdaIndexFor(@NotNull LambdaExpression expr) {
        Integer assigned = lambdaIndexAssignments.get(expr);
        if (assigned != null) return assigned;
        return lambdaCounter.getAndIncrement();
    }

    /**
     * Returns the top-level enclosing class internal name for assert
     * synthesis. Javac uses the outermost class's
     * {@code desiredAssertionStatus()} so nested classes share one
     * enabled/disabled decision.
     *
     * @param internal fully-qualified internal name, possibly nested
     * @return outermost segment before the first {@code $}
     */
    public @NotNull String topEnclosingInternal(@NotNull String internal) {
        int idx = internal.indexOf('$');
        return idx > 0 ? internal.substring(0, idx) : internal;
    }

    /**
     * Walks {@code classDecl} looking for any {@code assert} statement.
     *
     * @param classDecl declaration to scan
     * @return true when the class body uses {@code assert}
     */
    public boolean containsAssertStatement(@NotNull ClassDeclaration classDecl) {
        boolean[] found = {false};
        new AssertScanner(found).scanClass(classDecl);
        return found[0];
    }

    /**
     * @param classDecl declaration to test
     * @return true when the class needs a runtime {@code <clinit>} (non-constant
     * static initializers, static blocks, or assert synthesis)
     */
    public boolean hasStaticFieldInitOrBlock(@NotNull ClassDeclaration classDecl) {
        for (AstNode m : classDecl.members()) {
            if (m instanceof FieldDeclaration fd && (fd.modifiers() & Opcodes.ACC_STATIC) != 0) {
                for (FieldDeclarator d : fd.declarators()) {
                    if (d.initializer() != null && constantFolder.resolveConstantValue(fd, d) == null) return true;
                }
            } else if (m instanceof MethodDeclaration md && "<clinit>".equals(md.name())) {
                return true;
            }
        }
        return assertUsed;
    }

    /**
     * Computes the JVM internal name for the top-level class in this
     * compilation unit without consulting the type resolver. Used to pre-seed
     * the nest-host name before actual emission begins.
     *
     * @param classDecl   class declaration
     * @param packageName enclosing package, or null
     * @return slash-separated internal name
     */
    public @NotNull String internalNameUnresolved(@NotNull ClassDeclaration classDecl, @Nullable String packageName) {
        return toInternalName(classDecl.name(), packageName);
    }

    /**
     * Pre-scans a top-level class for every named nested class recursively
     * and appends each one's internal name to the nest-members list so the
     * later {@code NestMembers} attribute advertises the whole nestmate set.
     *
     * @param classDecl     declaration to scan
     * @param outerInternal internal name of the current level
     */
    public void collectNestMemberInternals(@NotNull ClassDeclaration classDecl, @NotNull String outerInternal) {
        for (AstNode member : classDecl.members()) {
            if (member instanceof ClassDeclaration inner) {
                String innerInternal = outerInternal + "$" + inner.name();
                nestMemberInternals.add(innerInternal);
                collectNestMemberInternals(inner, innerInternal);
            }
        }
        int anonCount = anonIndexAssignments.size();
        String topInternal = nestHostInternal;
        if (outerInternal.equals(topInternal)) {
            for (int i = 1; i <= anonCount; i++) {
                nestMemberInternals.add(topInternal + "$" + i);
            }
        }
    }

    /**
     * Builds the JVM internal name for a class given its simple name and
     * package. Package-less classes flatten to just the simple name.
     *
     * @param className   simple class name as it appears in source
     * @param packageName enclosing package, or null for the default package
     * @return slash-separated internal name
     */
    public @NotNull String toInternalName(@NotNull String className, @Nullable String packageName) {
        if (packageName == null || packageName.isEmpty()) return className;
        return packageName.replace('.', '/') + "/" + className;
    }

    /**
     * Creates a fresh {@link ClassWriter} wired to the common super-class
     * cache used across generated classes.
     *
     * @return class writer configured for frame and max computation
     */
    public @NotNull ClassWriter createClassWriter() {
        return new VantaClassWriter(classpathManager);
    }

    /**
     * Resolves interface type nodes to their internal names.
     *
     * @param interfaces interface type nodes
     * @return array of internal names (empty when no interfaces)
     */
    public @NotNull String[] resolveInterfaces(@NotNull List<TypeNode> interfaces) {
        if (interfaces.isEmpty()) return new String[0];
        String[] result = new String[interfaces.size()];
        for (int i = 0; i < interfaces.size(); i++) {
            result[i] = typeResolver.resolveInternalName(interfaces.get(i));
        }
        return result;
    }

    /**
     * Builds a generic signature for a class, or returns null if the class
     * has no type parameters (so no {@code Signature} attribute is needed).
     *
     * @param classDecl class declaration
     * @return generic signature, or null
     */
    public @Nullable String classSignature(@NotNull ClassDeclaration classDecl) {
        if (classDecl.typeParameters() == null || classDecl.typeParameters().isEmpty()) return null;
        StringBuilder sb = new StringBuilder("<");
        for (var tp : classDecl.typeParameters()) {
            sb.append(tp.name()).append(':');
            if (tp.bounds() != null && !tp.bounds().isEmpty()) {
                for (TypeNode bound : tp.bounds()) {
                    sb.append('L').append(typeResolver.resolveInternalName(bound)).append(';');
                }
            } else {
                sb.append("Ljava/lang/Object;");
            }
        }
        sb.append('>');
        if (classDecl.superClass() != null) {
            sb.append('L').append(typeResolver.resolveInternalName(classDecl.superClass())).append(';');
        } else {
            sb.append("Ljava/lang/Object;");
        }
        for (TypeNode iface : classDecl.interfaces()) {
            sb.append('L').append(typeResolver.resolveInternalName(iface)).append(';');
        }
        return sb.toString();
    }

    /**
     * Populates {@code fieldTypes} and {@code selfMethods} from the members
     * of {@code classDecl}, emits each declared field, and caches constant
     * values on {@link #nestedClassConstants}. Shared across body generators
     * so member scanning has one source of truth.
     *
     * @param cw           class writer receiving field emissions
     * @param classDecl    class being scanned
     * @param internalName owner internal name
     * @param fieldTypes   output map for declared field types
     * @param selfMethods  output map for self-method info
     */
    public void collectFieldsAndMethods(@NotNull ClassWriter cw, @NotNull ClassDeclaration classDecl, @NotNull String internalName, @NotNull Map<String, ResolvedType> fieldTypes, @NotNull Map<String, SelfMethodInfo> selfMethods) {
        Map<String, Object> constants = new HashMap<>();
        for (AstNode member : classDecl.members()) {
            if (member instanceof FieldDeclaration fieldDecl) {
                generateField(cw, fieldDecl);
                for (FieldDeclarator declarator : fieldDecl.declarators()) {
                    TypeNode effectiveType = fieldDecl.type();
                    if (declarator.extraArrayDimensions() > 0) {
                        effectiveType = effectiveType.withExtraDimensions(declarator.extraArrayDimensions());
                    }
                    fieldTypes.put(declarator.name(), typeResolver.resolve(effectiveType));
                    Object cv = constantFolder.resolveConstantValue(fieldDecl, declarator);
                    if (cv != null) constants.put(declarator.name(), cv);
                }
            } else if (member instanceof MethodDeclaration methodDecl) {
                if (methodDecl.typeParameters() != null && !methodDecl.typeParameters().isEmpty())
                    typeResolver.registerTypeParameters(methodDecl.typeParameters());
                List<TypeNode> paramTypes = new ArrayList<>();
                for (Parameter p : methodDecl.parameters()) paramTypes.add(p.type());
                String desc = typeResolver.methodDescriptor(paramTypes, methodDecl.returnType());
                boolean isStatic = (methodDecl.modifiers() & Opcodes.ACC_STATIC) != 0;
                SelfMethodInfo info = new SelfMethodInfo(internalName, methodDecl.name(), desc, isStatic);
                String baseKey = methodDecl.name() + ":" + methodDecl.parameters().size();
                if (!selfMethods.containsKey(baseKey)) selfMethods.put(baseKey, info);
                else selfMethods.put(baseKey + "#" + desc, info);
            }
        }
        if (!constants.isEmpty()) nestedClassConstants.put(internalName, constants);
    }

    /**
     * @param enumInternal external enum internal name
     * @param constantName simple constant identifier
     * @return switch-map key, or null when the enum isn't externally-sourced
     * (callers fall back to a direct {@code ordinal()} switch)
     */
    public @Nullable Integer externalEnumSwitchKey(@NotNull String enumInternal, @NotNull String constantName) {
        LinkedHashMap<String, Integer> map = enumSwitchMaps.get(enumInternal);
        if (map == null) return null;
        return map.get(constantName);
    }

    /**
     * @param enumInternal enum internal name
     * @return true when {@code enumInternal} was recorded as an external
     * switch target and needs the {@code $SwitchMap} indirection
     */
    public boolean hasExternalEnumSwitch(@NotNull String enumInternal) {
        return enumSwitchMaps.containsKey(enumInternal);
    }

    /**
     * @return synthetic switch-map class internal name when one was
     * generated this compilation unit, or null otherwise
     */
    public @Nullable String switchMapSyntheticName() {
        return switchMapSyntheticName;
    }

    /**
     * @param enumInternal enum internal name
     * @return mangled {@code $SwitchMap$...} field name to use inside the
     * synthetic switch-map class
     */
    public @NotNull String switchMapFieldFor(@NotNull String enumInternal) {
        return switchMapFieldName(enumInternal);
    }

    /**
     * Walks every nested declaration below {@code classDecl}, populating
     * {@link #nestedClassFields} / {@link #nestedClassMethods} so later
     * expression-level resolution can look up members of a sibling class
     * without re-parsing its body.
     *
     * @param classDecl    declaration to scan
     * @param internalName internal name of the current level
     */
    private void collectNestedClassFields(@NotNull ClassDeclaration classDecl, @NotNull String internalName) {
        if (classDecl.typeParameters() != null && !classDecl.typeParameters().isEmpty())
            typeResolver.registerTypeParameters(classDecl.typeParameters());
        Map<String, SelfMethodInfo> methods = new HashMap<>();
        for (AstNode member : classDecl.members()) {
            if (member instanceof MethodDeclaration md) {
                List<TypeNode> paramTypes = new ArrayList<>();
                for (Parameter p : md.parameters()) paramTypes.add(p.type());
                String desc = typeResolver.methodDescriptor(paramTypes, md.returnType());
                boolean isStatic = (md.modifiers() & Opcodes.ACC_STATIC) != 0;
                String key = md.name() + ":" + md.parameters().size();
                methods.putIfAbsent(key, new SelfMethodInfo(internalName, md.name(), desc, isStatic));
            }
        }
        nestedClassMethods.put(internalName, methods);
        Map<String, ResolvedType> fields = new HashMap<>();
        if (classDecl.recordComponents() != null) {
            for (RecordComponent component : classDecl.recordComponents()) {
                fields.put(component.name(), typeResolver.resolve(component.type()));
            }
        }
        if (classDecl.kind() == TypeKind.ENUM && classDecl.enumConstants() != null) {
            ResolvedType enumType = ResolvedType.ofObject(internalName);
            for (EnumConstant c : classDecl.enumConstants()) fields.put(c.name(), enumType);
        }
        for (AstNode member : classDecl.members()) {
            if (member instanceof FieldDeclaration fd) {
                for (FieldDeclarator decl : fd.declarators()) {
                    TypeNode eff = fd.type();
                    if (decl.extraArrayDimensions() > 0) eff = eff.withExtraDimensions(decl.extraArrayDimensions());
                    fields.put(decl.name(), typeResolver.resolve(eff));
                }
            } else if (member instanceof ClassDeclaration inner) {
                collectNestedClassFields(inner, internalName + "$" + inner.name());
            }
        }
        nestedClassFields.put(internalName, fields);
    }

    /**
     * Drives {@link EnumSwitchScanner} and decides whether a synthetic
     * {@code Outer$N} switch-map class is needed for this compilation unit.
     *
     * @param classDecl     outer declaration being compiled
     * @param outerInternal outer class's internal name
     */
    private void scanEnumSwitches(@NotNull ClassDeclaration classDecl, @NotNull String outerInternal) {
        enumSwitchMaps.clear();
        switchMapSyntheticName = null;
        EnumSwitchScanner scanner = new EnumSwitchScanner(outerInternal, enumSwitchMaps, typeResolver, classpathManager);
        scanner.scanClass(classDecl);
        if (!enumSwitchMaps.isEmpty()) {
            int anonCount = anonIndexAssignments.size();
            switchMapSyntheticName = outerInternal + "$" + (anonCount + 1);
        }
    }

    /**
     * Builds the synthetic {@code Outer$N} class that holds the per-enum
     * {@code $SwitchMap$Enum} int arrays used by external-enum switch
     * dispatch. Each field is populated in a static initialiser that catches
     * {@link NoSuchFieldError} per constant so a runtime version skew in the
     * enum (e.g. a constant removed) does not abort class loading.
     *
     * @return class file bytes for the synthetic switch-map class
     */
    private byte @NotNull [] generateSwitchMapClass() {
        String internal = Objects.requireNonNull(switchMapSyntheticName, "switchMapSyntheticName not set");
        ClassWriter cw = createClassWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC, internal, null, "java/lang/Object", null);
        if (sourceFile != null) cw.visitSource(sourceFile, null);
        cw.visitInnerClass(internal, null, null, 0);
        for (String enumInternal : enumSwitchMaps.keySet()) {
            String fieldName = switchMapFieldName(enumInternal);
            FieldVisitor fv = cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, fieldName, "[I", null, null);
            fv.visitEnd();
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        for (Map.Entry<String, LinkedHashMap<String, Integer>> entry : enumSwitchMaps.entrySet()) {
            String enumInternal = entry.getKey();
            String fieldName = switchMapFieldName(enumInternal);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, enumInternal, "values", "()[L" + enumInternal + ";", false);
            mv.visitInsn(Opcodes.ARRAYLENGTH);
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internal, fieldName, "[I");
            for (Map.Entry<String, Integer> c : entry.getValue().entrySet()) {
                Label tryStart = new Label();
                Label tryEnd = new Label();
                Label handler = new Label();
                Label after = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/NoSuchFieldError");
                mv.visitLabel(tryStart);
                mv.visitFieldInsn(Opcodes.GETSTATIC, internal, fieldName, "[I");
                mv.visitFieldInsn(Opcodes.GETSTATIC, enumInternal, c.getKey(), "L" + enumInternal + ";");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, enumInternal, "ordinal", "()I", false);
                OpcodeUtils.pushInt(mv, c.getValue());
                mv.visitInsn(Opcodes.IASTORE);
                mv.visitLabel(tryEnd);
                mv.visitJumpInsn(Opcodes.GOTO, after);
                mv.visitLabel(handler);
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitLabel(after);
            }
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * @param enumInternal external enum internal name
     * @return mangled field name {@code $SwitchMap$pkg$Enum} used inside the
     * synthetic switch-map class
     */
    private @NotNull String switchMapFieldName(@NotNull String enumInternal) {
        return "$SwitchMap$" + enumInternal.replace('/', '$');
    }

    /**
     * Recursive driver behind {@link #generateInnerClasses}. Flips
     * {@link #outerIsInterfaceLike} for the duration of each nested
     * emission and uses a fresh anon counter per inner so their synthetic
     * class names don't collide with outer-scope anonymous classes.
     *
     * @param classDecl         enclosing declaration
     * @param packageName       enclosing package, or null
     * @param outerInternalName enclosing class's internal name
     * @return map from nested class internal name to its bytecode
     */
    private @NotNull Map<String, byte[]> generateInnerClassesRecursive(@NotNull ClassDeclaration classDecl, @Nullable String packageName, @NotNull String outerInternalName) {
        Map<String, byte[]> result = new HashMap<>();
        boolean savedOuterIsInterfaceLike = outerIsInterfaceLike;
        outerIsInterfaceLike = classDecl.kind() == TypeKind.INTERFACE || classDecl.kind() == TypeKind.ANNOTATION;
        for (AstNode member : classDecl.members()) {
            if (member instanceof ClassDeclaration inner) {
                String innerInternalName = outerInternalName + "$" + inner.name();
                AtomicInteger savedAnon = anonClassCounter;
                anonClassCounter = new AtomicInteger(0);
                byte[] bytecode = generate(inner, packageName, outerInternalName);
                result.put(innerInternalName, bytecode);
                result.putAll(getAndClearAnonClassBytecodes());
                result.putAll(generateInnerClassesRecursive(inner, packageName, innerInternalName));
                anonClassCounter = savedAnon;
            }
        }
        outerIsInterfaceLike = savedOuterIsInterfaceLike;
        return result;
    }

    /**
     * Emits a single field declaration, folding constant initializers into
     * the {@code ConstantValue} attribute when eligible and coercing
     * {@link Integer}-typed folds into the wider JVM primitive form required
     * by the field's descriptor.
     *
     * @param cw        class writer receiving the field
     * @param fieldDecl field declaration
     */
    private void generateField(@NotNull ClassWriter cw, @NotNull FieldDeclaration fieldDecl) {
        for (FieldDeclarator declarator : fieldDecl.declarators()) {
            TypeNode effectiveType = fieldDecl.type();
            if (declarator.extraArrayDimensions() > 0) {
                effectiveType = effectiveType.withExtraDimensions(declarator.extraArrayDimensions());
            }
            String descriptor = typeResolver.resolveDescriptor(effectiveType);
            Object constantValue = constantFolder.resolveConstantValue(fieldDecl, declarator);
            if (constantValue instanceof Integer iv) {
                switch (descriptor) {
                    case "J" -> constantValue = iv.longValue();
                    case "F" -> constantValue = iv.floatValue();
                    case "D" -> constantValue = iv.doubleValue();
                }
            }
            FieldVisitor fv = cw.visitField(fieldDecl.modifiers(), declarator.name(), descriptor, null, constantValue);
            annotationEmitter.emitFieldAnnotations(fv, fieldDecl.annotations());
            fv.visitEnd();
        }
    }
}
