<div align="center">

# **Vanta**
### *A blazing-fast, embeddable Java-to-JVM bytecode compiler*

**A modern compiler library built for speed and correctness**

Fast, embeddable, standard-compliant, bytecode-accurate.
Vanta compiles standard Java source code directly to `.class` bytecode, serving as a lightweight, library-friendly alternative to javac.

**Design goal:** produce instructions that are identical to `javac` at runtime for Java 17 source.

</div>

> [!IMPORTANT]
> Vanta is in **beta**.
>
> Self-compilation works end-to-end. Vanta compiles its own source and the output runs correctly.
>
> Remaining diffs vs javac are ~99% cosmetic (label numbering, slot ordering, synthetic layout) with no runtime impact. A small handful of real semantic diffs remain, mostly around generic `CHECKCAST` preservation on deeply nested parameterized types.
>
> Documentation is in progress.

---

## Features at a glance

- **Blazing-fast compilation** - up to 35x faster than javac depending on workload. See [Performance](#performance) for details
- **Low Footprint** - The jar with dependencies is less than 700 kb
- **Full Java 17 support** - classes, interfaces, enums, records, generics, annotations, and much more
- **Classpath-aware** - resolves user-supplied jars and directories
- **No JDK needed** - Vanta does not require a JDK unlike javac
- **Native Image** - Supports being compiled ahead-of-time into native executables (and WebAssembly) using GraalVM Native Image

---

## Installation

### Gradle

```groovy
repositories {
    maven { url = 'https://repository.vansencool.net' }
}

dependencies {
    implementation 'net.vansencool:Vanta:1.0.0-BETA'
}
```

### Maven

```xml
<repository>
    <id>vansencool</id>
    <url>https://repository.vansencool.net</url>
</repository>

<dependency>
    <groupId>net.vansencool</groupId>
    <artifactId>Vanta</artifactId>
    <version>1.0.0-BETA</version>
</dependency>
```

---

## Quick Start

### Compiling a class

```java
VantaCompiler compiler = new VantaCompiler();

String source = """
    public class Hello {
        public static void main(String[] args) {
            System.out.println("Hello from Vanta!");
        }
    }
    """;

Map<String, byte[]> classes = compiler.compile("Hello.java", source);
byte[] bytecode = classes.get("Hello");
```

### Compiling with a custom classpath

```java
// 1) From a raw classpath string (platform-separated entries)
VantaCompiler compiler = VantaCompiler.withClasspath("libs/mylib.jar:out/classes");

// 2) From an existing ClassLoader hierarchy
VantaCompiler compiler = VantaCompiler.withClassLoader(Thread.currentThread().getContextClassLoader());

// 3) Manual: build a ClasspathManager yourself
ClasspathManager cp = new ClasspathManager();
cp.addEntry(Path.of("libs/mylib.jar"));
cp.addEntry(Path.of("out/classes"));
cp.addClassLoader(someClassLoader);
cp.addClasspath(System.getProperty("java.class.path"));
VantaCompiler compiler = new VantaCompiler(cp);
```

---

## Performance

Benchmark results via JMH on modern hardware.

**Hardware:**
- CPU: AMD Ryzen 9 9900X3D
- RAM: 32 GB DDR5 (5200 MT/s)
- JVM: Java 26 (GraalVM)
- OS: Linux 6.8

### Results

| Scenario | javac | Vanta | Vanta (parallel) | Speedup |
|---|---|---|---|---|
| **Gson (warm)** | 91.69 ms | 15.59 ms | 2.55 ms | **~5.9x / ~36x parallel** |
| **Gson (cold)** | 707.93 ms | 298.09 ms | 236.65 ms | **~2.4x / ~3.0x parallel** |
| **Self-compilation (warm)** | 182.95 ms | 31.64 ms | 4.98 ms | **~5.8x / ~37x parallel** |
| **Self-compilation (cold)** | 989.52 ms | 407.91 ms | 324.38 ms | **~2.4x / ~3.1x parallel** |

---

## What Vanta supports

### Type declarations

| Feature | Status |
|---|---|
| `class` | Supported |
| `interface` | Supported |
| `enum` (with `values()`, `valueOf()`, `$VALUES`) | Supported |
| `record` (with accessors, `equals`, `hashCode`, `toString`) | Supported |
| Inner classes | Supported |
| Generic type parameters | Supported |
| `extends` / `implements` | Supported |
| All access and non-access modifiers | Supported |
| Annotations on types, methods, fields (with full attribute values) | Supported |
| Annotation type declarations (`@interface` with `default` values) | Supported |
| Static initializers (`<clinit>`) | Supported |
| Auto-generated default constructors | Supported |

### Statements

| Statement | Status |
|---|---|
| `if / else if / else` | Supported |
| `while` | Supported |
| `do-while` | Supported |
| `for` (C-style) | Supported |
| Enhanced `for` | Supported |
| `switch` statement | Supported |
| `try / catch / finally` | Supported |
| `throw` | Supported |
| `break` and `continue` (with labels) | Supported |
| Labeled statements | Supported |
| `return` | Supported |
| Variable declarations (`var` included) | Supported |
| `synchronized` block | Supported |
| `assert` | Supported |
| `yield` (switch expression arm) | Supported |

### Expressions

| Expression | Status |
|---|---|
| All primitive literals (`int`, `long`, `float`, `double`, `char`, `boolean`, `null`) | Supported |
| String literals and text blocks | Supported |
| All arithmetic operators (`+`, `-`, `*`, `/`, `%`) | Supported |
| All bitwise operators (`&`, `\|`, `^`, `~`, `<<`, `>>`, `>>>`) | Supported |
| All comparison and logical operators | Supported |
| All compound assignments (`+=`, `-=`, `*=`, ..., `>>>=`) | Supported |
| Prefix and postfix `++` / `--` (with `iinc` optimization) | Supported |
| Assignment as expression | Supported |
| Method calls (virtual, static, interface, super/this) | Supported |
| Field access and static field access | Supported |
| Array access, array creation, array initializers | Supported |
| `new` object creation | Supported |
| Cast expressions (primitive and reference) | Supported |
| Pattern `instanceof` with variable binding | Supported |
| Ternary `? :` | Supported |
| Switch expressions (arrow and traditional) | Supported |
| String concatenation via `StringConcatFactory` (`invokedynamic`) | Supported |
| `this` and `super` expressions | Supported |
| Lambda expressions | Supported |

### Type resolution

| Feature | Status |
|---|---|
| Primitive types and `void` | Supported |
| Named and wildcard imports | Supported |
| `java.lang` implicit resolution | Supported |
| Classpath-backed method and field resolution via reflection | Supported |
| Correct dispatch opcode selection | Supported |
| Constructor overload resolution | Supported |

---

## What Vanta does not yet support

These features are either partially implemented or not started.

- **Byte-for-byte parity with javac** - bytecode is semantically identical in many cases and runs the same, but label numbering, local slot ordering, and synthetic class layout differ. No runtime impact.
- **Generic type tracking** - some collection element types (e.g. `Map<K,V>.get` returning `V` inside a nested call chain) are not always propagated, so the resulting `CHECKCAST` or unbox can land on the erased type instead of the parameterized one.
- **String `switch`** - not emitted as the hash-based pattern javac uses.
- **Compile-time constant inlining** - `static final` primitive/String constants are not always inlined like javac does.

---

## License

GNU General Public License v3.0 - see the LICENSE file for details.
