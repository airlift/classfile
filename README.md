# Classfile

Classfile is a high-level, declarative Java 25 library for generating and loading
Java classes at runtime. It combines a fluent expression language, source-like
control flow, automatic code splitting, and target-aware class loading.

The central idea is simple: **describe what the generated code should do and let the
compiler make it work**. Classfile manages local-variable slots, labels, runtime
bindings, linkage, and physical layout, while you author a logical Java program.

## Why Classfile?

### Java-like authoring

Variables and parameters are typed expressions, so generated method bodies read like
the operations they perform:

```java
ClassDefinition definition = ClassDefinition.define(ClassDesc.of("example.GeneratedIntFunction"))
        .access(PUBLIC, FINAL)
        .addInterface(IntUnaryOperator.class);
definition.defaultConstructor().access(PUBLIC);

Parameter value = Parameter.arg("value", int.class);
MethodDefinition method = definition.method("applyAsInt", int.class, value)
        .access(PUBLIC);

method.body()
        .append(IfStatement.builder()
                .condition(value.lessThan(constantInt(0)))
                .then(value.negate().ret())
                .build())
        .ret(value.multiply(constantInt(2)));

StandardClassDefiner definer = StandardClassDefiner.builder(ClassLoader.getSystemClassLoader())
        .build();
Class<? extends IntUnaryOperator> generatedClass = definer.defineUnit(
                ClassCompiler.forTarget(definer.compilationTarget())
                        .compileUnit(definition.build()))
        .primaryClass(IntUnaryOperator.class);

IntUnaryOperator function = generatedClass.getConstructor().newInstance();
int result = function.applyAsInt(21); // 42
```

The API includes fields, constructors, records, fluent expressions, lexical locals,
structured loops, switches, and exception handling. Symbolic JDK descriptors allow
generated code to refer to classes that have not been defined or loaded yet.
Expressions and blocks are reusable values, so complex fragments can be composed and
placed multiple times while Classfile assigns fresh physical state for each use.

### Automatic code splitting

Large generated methods eventually exceed JVM classfile limits or become too large
for the JIT to optimize. Traditionally, every code generator must rediscover those
limits and manually divide its work into helper methods and classes.

Classfile does that planning automatically. It can split large expressions and
statement sequences into deterministic helper methods, move generated helpers into
companion classes when necessary, and link the resulting physical unit for either
nominal or hidden-class definition. The authored model remains unchanged.

Compilation returns an inspectable report containing exact emitted sizes and
non-fatal optimization warnings. Hard JVM limits remain errors; missed optimization
targets do not prevent otherwise valid generated code from running.

See [Automatic code splitting](docs/AUTOMATIC_CODE_SPLITTING.md) for the planning
model, supported transformations, and limits.

### Target-aware loading and linkage

The compiler targets the class loader or `MethodHandles.Lookup` that will define the
result. It validates accessibility and class identity for that environment before
loading and adapts bound method handles when their signatures contain types that are
not visible from generated code.

Runtime-bound constants and method handles are carried through class-loader data for
nominal classes and JDK class data for hidden classes. Callers load a complete
`CompiledUnit` and retrieve its primary class without managing compiler-generated
helpers.

### Built on the JDK

Classfile emits and verifies classes with the `java.lang.classfile` API built into
Java 25. Class generation stays aligned with the classfile support of the running
JDK, and production code has no third-party dependencies.

## Documentation

- [User guide](docs/USER_GUIDE.md) covers class and method authoring, expressions,
  control flow, compilation, and loading.
- [Automatic code splitting](docs/AUTOMATIC_CODE_SPLITTING.md) explains physical
  planning, reports, and limits.
- [Migration guide](docs/MIGRATION_GUIDE.md) maps the Airlift Bytecode API to
  Classfile.
- [Executable examples](docs/EXAMPLES.md) indexes behavior tests for the complete
  API, including Trino-shaped generators and large-code stress cases.
- [Design](docs/DESIGN.md) defines model reuse, scoping, validation, and runtime
  invariants.
- [Development](docs/DEVELOPMENT.md) covers building and contributing.

## License

Classfile is licensed under the [Apache License 2.0](LICENSE).
