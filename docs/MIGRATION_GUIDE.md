# Migrating from Bytecode to Classfile

This guide explains how to move a generator from the ASM-backed
`io.airlift.bytecode` API to the Java 25 `io.airlift.classfile` API. It focuses on
authoring differences, not the implementation details of either backend.

The migration should feel familiar where familiarity helps. Variables and parameters
are still expressions, expression chains remain source-like, method bodies are still
built by appending statements, and the complete logical class is still assembled
before emission. The significant changes remove machinery that generator authors
should not have needed to manage: ASM types and instruction nodes, physical local
slots, mutable node ownership, ad hoc class-loader bindings, and manual code-size
chunking.

## Artifact And Packages

Classfile is published separately from the legacy library:

```xml
<dependency>
    <groupId>io.airlift</groupId>
    <artifactId>classfile</artifactId>
    <version>${classfile.version}</version>
</dependency>
```

Its Java packages are rooted at `io.airlift.classfile`. The legacy artifact remains
`io.airlift:bytecode` with packages rooted at `io.airlift.bytecode`. The distinct
artifact and package names allow both libraries to be present during an incremental
migration without relocation or dependency exclusions. Classfile has no compile or
runtime dependencies beyond Java 25.

## Migration Strategy

Treat the conversion as three separate steps:

1. **Port behavior.** Recreate the existing class, expressions, and control flow with
   the new logical API. Keep existing helper boundaries and behavioral tests.
2. **Adopt the runtime lifecycle.** Compile a `ClassModel` into a `CompiledUnit` for
   the exact nominal or hidden definition target, inspect its report, and define the
   resulting unit.
3. **Remove generator workarounds.** Collapse manual method and companion-class
   chunking where the automatic planner can now do it safely. Consider records,
   compact constructors, reusable blocks, and synthetic expressions after behavior
   is stable.

This ordering keeps failures attributable. Do not simultaneously change generated
semantics, loading strategy, and chunk boundaries unless the existing generator is
too tightly coupled to ASM to permit a staged port.

## API Map

| Legacy API | Classfile API | Migration note |
| --- | --- | --- |
| `io.airlift:bytecode` | `io.airlift:classfile` | Separate artifacts; both can coexist |
| `io.airlift.bytecode.*` | `io.airlift.classfile.*` | Control-flow and expression types now share the root package |
| `Access` and `a(...)` | `java.lang.reflect.AccessFlag` varargs | Use JDK flags directly |
| `ParameterizedType` | `ClassDesc` | JDK symbolic descriptor; does not require loading the class |
| ASM `Type` | `ClassDesc` | No ASM type appears in the public API |
| string method descriptor | `MethodTypeDesc` | Use JDK descriptor types |
| `new ClassDefinition(...)` | `ClassDefinition.define(...)` | Separate factories exist for classes, interfaces, and records |
| `declareField(...)` | `field(...).access(...).build()` | The field builder validates and registers the field |
| `declareMethod(...)` | `method(...).access(...)` | Methods are registered immediately |
| `declareConstructor(...)` | `constructor(...).access(...)` | Constructor invocation is an explicit body statement |
| `getClassInitializer()` | `classInitializer()` | Created lazily and always static |
| `visitSource(...)` | `sourceFile(...)` | Singular builder properties may be set once |
| `getBody()` | `body()` | Returns a chainable `CodeBlock.Builder` |
| `getThis()` | `thisVariable()` | Fails immediately for a static method |
| `getScope().declareVariable(...)` | `body.declare(...)` | The declaring block owns the lexical variable |
| mutable `BytecodeBlock` | `CodeBlock.Builder` then immutable `CodeBlock` | Final blocks are reusable DAG values |
| low-level `BytecodeNode` and instruction nodes | expressions and structured `Statement` values | No public instruction DOM replacement |
| mutable control objects | set-once control-flow builders | Named components make nontrivial control flow readable |
| `LabelNode` | `CodeLabel` | Retained as a discouraged escape hatch |
| custom expression subclass | `SyntheticExpression` | Lower functionally into the standard expression model |
| `ClassGenerator` | `StandardClassDefiner` plus `ClassCompiler` | Compilation is tied to the actual target loader |
| `HiddenClassGenerator` | `HiddenClassDefiner` plus `ClassCompiler` | Runtime bindings use hidden class data |
| `DynamicClassLoader` | `StandardClassDefiner` | Runtime data and generated bundles are managed by the definer |
| ASM verifier and dump visitors | `ClassFileDiagnostics` | Uses the final JDK classfile model and emitted bytes |

Most `getX()` accessors on finalized models become `x()` in the Java record style,
for example `definition.getType()` becomes `model.type()` and
`method.getReturnType()` becomes `method.returnType()`.

## Imports and Access Flags

Replace the custom access enum and set helper:

```java
import static io.airlift.bytecode.Access.FINAL;
import static io.airlift.bytecode.Access.PUBLIC;
import static io.airlift.bytecode.Access.a;
```

with JDK access flags:

```java
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
```

Old declarations commonly passed `a(PUBLIC, FINAL)`. New builders accept the flags
directly:

```java
ClassDefinition definition = ClassDefinition.define(generatedType)
        .access(PUBLIC, FINAL);
```

`access(...)` is a singular builder property. Calling it twice is an error rather
than replacement or accumulation. Invalid locations and combinations, such as a
`private` top-level class or an `abstract final` method, fail during authoring or
model assembly instead of producing a later verifier failure.

## Types and Descriptors

### Loaded Types

Public APIs retain `Class<?>` overloads for the common case:

```java
definition.field("count", int.class);
definition.addInterface(Filter.class);
definition.method("filter", boolean.class, pageParameter);
```

There is no reason to convert every loaded class to `ClassDesc` manually.

### Generated and Unloaded Types

Replace `ParameterizedType` with `ClassDesc` when a class does not exist yet:

```java
// Legacy
ParameterizedType generatedType = typeFromJavaClassName("com.example.GeneratedFilter");

// Classfile
ClassDesc generatedType = ClassDesc.of("com.example.GeneratedFilter");
```

The descriptor can be used for class declarations, fields, method descriptors,
invocations, arrays, and references between generated classes. It does not require
the class to be loaded, which is especially important for generated bundles and
hidden classes.

Use `DescriptorUtils.classDesc(...)` and `DescriptorUtils.methodType(...)` when an
explicit descriptor is useful:

```java
ClassDesc stringType = DescriptorUtils.classDesc(String.class);
MethodTypeDesc predicateType = DescriptorUtils.methodType(boolean.class, int.class);
```

`ClassDesc` describes the JVM type, not its generic signature. Replace generic
information formerly carried by `ParameterizedType` with the corresponding JDK
`ClassSignature`, `MethodSignature`, or `Signature` metadata object on the class,
method, field, or record component.

## Defining a Class

The legacy constructor combines class kind, access, superclass, and interfaces:

```java
ClassDefinition definition = new ClassDefinition(
        a(PUBLIC, FINAL),
        generatedType,
        type(Object.class),
        type(Filter.class));
definition.visitSource("GeneratedFilter.java", null);
```

The new API names those decisions:

```java
ClassDefinition definition = ClassDefinition.define(generatedType)
        .access(PUBLIC, FINAL)
        .addInterface(Filter.class)
        .sourceFile("GeneratedFilter.java");
```

`Object` is the default superclass. Use `superClass(...)` only when a class extends
another class. Use the dedicated factories when the source-level kind matters:

```java
ClassDefinition generatedInterface = ClassDefinition.defineInterface(interfaceType)
        .access(PUBLIC);

ClassDefinition generatedRecord = ClassDefinition.defineRecord(recordType)
        .access(PUBLIC);
```

The factory supplies and validates the classfile flags implied by the kind. Do not
model an interface or record by manually assembling class access bits.

Calling `definition.build()` validates the complete declaration and returns an
immutable `ClassModel`. The mutable definition remains an authoring context; the
returned model is the value supplied to the compiler and can be rendered, validated,
and compiled without changing it.

## Fields

Replace `declareField(...)` with the field builder:

```java
// Legacy
FieldDefinition thresholdField = definition.declareField(
        a(PRIVATE, FINAL),
        "threshold",
        int.class);

// Classfile
FieldDefinition thresholdField = definition.field("threshold", int.class)
        .access(PRIVATE, FINAL)
        .build();
```

Unlike methods, a field is registered only when `build()` is called. Retain the
returned `FieldDefinition`; it is both immutable declaration metadata and a symbolic
field reference accepted by fluent expressions.

Use `constantValue(...)` only for JVM constant-value attribute types on `static
final` fields. Initialize ordinary objects in a constructor or `classInitializer()`.

## Methods and Parameters

Parameters remain expressions. Create each parameter and pass the same object to the
method and its body:

```java
Parameter value = Parameter.arg("value", int.class);
MethodDefinition method = definition.method("normalize", int.class, value)
        .access(PUBLIC, STATIC);

method.body().ret(value.add(constantInt(1)));
```

The old and new concepts map directly:

```java
// Legacy
MethodDefinition method = definition.declareMethod(
        a(PUBLIC),
        "evaluate",
        type(int.class),
        input);
BytecodeBlock body = method.getBody();
Variable thisVariable = method.getThis();

// Classfile
MethodDefinition method = definition.method("evaluate", int.class, input)
        .access(PUBLIC);
CodeBlock.Builder body = method.body();
Variable thisVariable = method.thisVariable();
```

Method parameter names are emitted automatically. Parameter flags and visible or
invisible annotations are configured on `Parameter` before the class snapshot is
built. A `Parameter` declaration belongs to one method; create a new parameter object
for another method even when the name and type are identical.

## Method Bodies and Local Variables

`method.body()` is the normal place to author a method. Chain operations rather than
repeating the receiver:

```java
Variable result = method.body().declare("result", input.add(constantInt(1)));
method.body()
        .append(result.set(result.multiply(constantInt(2))))
        .append(output.invoke("accept", void.class, result))
        .ret(result);
```

Declarations belong to the `CodeBlock.Builder` on which `declare(...)` is called.
The compiler assigns physical local slots only during emission and releases slots at
the end of lexical scopes. Remove code that manually creates, finds, caches, or
releases temporary slots:

```java
// Remove patterns like these
method.getScope().getOrCreateTempVariable(type);
method.getScope().releaseTempVariableForReuse(variable);
method.getScope().getVariable("wasNull");
```

Pass the `Variable` object to code that needs it:

```java
Variable wasNull = method.body().declare("wasNull", constantFalse());
appendFilter(method.body(), wasNull);
```

A nested block may declare the same source name as an outer block because variables
have lexical identity. A single block cannot declare the same name twice. References
to an outer variable capture that variable object explicitly; there is no name-based
lookup ambiguity.

### Reusable Blocks

Finalize a reusable fragment and attach it wherever its captures are valid:

```java
Variable total = method.body().declare("total", constantInt(0));

CodeBlock.Builder fragmentBuilder = CodeBlock.blockBuilder();
Variable temporary = fragmentBuilder.declare("temporary", input.add(constantInt(1)));
CodeBlock fragment = fragmentBuilder
        .append(total.set(total.add(temporary)))
        .build();

method.body()
        .append(fragment)
        .append(fragment)
        .ret(total);
```

The two placements share the captured `input` and `total`, but each receives a fresh
binding and local slot for `temporary`. Final blocks and expressions have no parent
pointer or attached emission state, so the same logical fragment can be placed in a
method more than once or reused by compatible methods without corrupting another
placement.

The validator rejects out-of-scope captures, cycles, and incompatible attachments
before classfile emission.

## Expressions

The expression API deliberately preserves the strongest part of the legacy library.
Variables, parameters, fields, array elements, invocation results, and constructed
objects compose as expressions and render as concise source-like text.

Code such as this normally carries over with import changes only:

```java
positions.getElement(index)
        .add(offset)
        .cast(long.class)
        .multiply(scale)
```

The general placement rule is:

- use static `BytecodeExpressions` factories for values without a receiver, such as
  constants, `new`, static invocation, dynamic constants, class data, and bound
  runtime values;
- use fluent methods for operations on an existing value, such as arithmetic,
  comparison, cast, field access, array access, instance invocation, assignment,
  return, throw, and discard.

Typical rewrites are:

```java
// Legacy static forms
add(left, right)
lessThan(index, limit)
equal(actual, expected)

// Preferred classfile forms
left.add(right)
index.lessThan(limit)
actual.equal(expected)
```

Creation and static invocation remain static imports:

```java
BytecodeExpression values = newArray(int[].class, count);
BytecodeExpression result = invokeStatic(Math.class, "multiplyExact", int.class, left, right);
BytecodeExpression operator = boundConstant(runtimeOperator, Operator.class);
```

Keep `toString()` assertions when porting custom or particularly complex
expressions. Rendering is part of the debugging contract and is used in logical
model dumps and validation errors.

### Custom Expressions

Replace custom subclasses that emit instructions directly with a functional
`SyntheticExpression`. The extension lowers into ordinary expressions and an
optional setup block:

```java
private record AddThenDouble(BytecodeExpression input)
        implements SyntheticExpression
{
    @Override
    public ClassDesc type()
    {
        return ConstantDescs.CD_int;
    }

    @Override
    public ExpressionPlan expansion(ExpansionContext context)
    {
        CodeBlock.Builder setup = CodeBlock.blockBuilder();
        Variable temporary = setup.declare("temporary", input.add(constantInt(1)));
        return new ExpressionPlan(setup.build(), temporary.multiply(constantInt(2)));
    }

    @Override
    public String toString()
    {
        return "addThenDouble(" + input + ")";
    }
}
```

An extension must be deterministic and free of externally visible expansion side
effects. Validation, planning, and emission may request the expansion more than once.
This shape preserves domain-specific Trino expressions while allowing the compiler
to inspect and split the resulting standard model.

## Structured Control Flow

The new control-flow APIs use builders with named, set-once components. This is more
verbose than a positional constructor and easier to review when a loop has an
initializer, condition, update, body, and control targets.

### If/Else

```java
method.body().append(IfStatement.builder()
        .description("return the positive magnitude")
        .condition(value.lessThan(constantInt(0)))
        .then(value.negate().ret())
        .otherwise(value.ret())
        .build());
```

Use `then(...)` and `otherwise(...)` instead of `ifTrue(...)` and `ifFalse(...)`.
Either branch accepts a statement or a complete `CodeBlock`.

### For Loops

```java
ForLoop.Builder loop = ForLoop.builder();
method.body().append(loop
        .description("sum selected positions")
        .initialize(index.set(from))
        .condition(index.lessThan(to))
        .update(index.increment())
        .body(total.set(total.add(values.getElement(index))))
        .build());
```

Use `loop.breakLoop()` and `loop.continueLoop()` inside its body rather than exposing
the loop's physical labels. The loop object provides identity without committing the
logical DOM to a particular branch layout.

`WhileLoop` and `DoWhileLoop` follow the same builder style.

### Switch

The new switch is structured and does not model Java fall-through:

```java
method.body().append(SwitchStatement.builder()
        .description("decode channel")
        .expression(channel)
        .caseValue(0, result.set(left))
        .caseValue(1, result.set(right))
        .defaultCase(newInstance(IllegalArgumentException.class).throwObject())
        .build());
```

Every case is an independent block with an implicit exit. Rewrite a legacy switch
that intentionally falls through as explicit structured code or, when necessary, a
small symbolic-label region.

### Try/Catch/Finally

Classfile has first-class `finally` semantics, including non-local exits:

```java
method.body().append(TryCatch.builder()
        .description("read value")
        .tryBlock(result.set(reader.invoke("read", int.class)))
        .catching(IOException.class, "failure", (body, failure) -> body
                .append(result.set(constantInt(-1))))
        .finallyBlock(resource.invoke("close", void.class))
        .build());
```

Returns, throws, loop exits, and symbolic jumps leaving the try region execute the
applicable `finally` blocks.

### Labels

Prefer structured statements. For a generator pattern that still needs a label:

```java
CodeLabel end = method.body().label("end");
method.body()
        .jump(end)
        .mark(end);
```

Labels are symbolic, scoped, and bound to fresh JDK labels for each block placement.
Do not port ASM `Label`, `LabelNode`, or raw jump instructions into application code.

## Constructors

Replace the legacy stack-oriented superclass invocation:

```java
body.append(constructor.getThis())
        .invokeConstructor(Object.class);
```

with an explicit constructor statement:

```java
constructor.body()
        .invokeSuperConstructor()
        .append(constructor.thisVariable().setField(nameField, name))
        .ret();
```

Use `invokeThisConstructor(...)` for sibling delegation. Arguments may be calculated
before the constructor call, including through local variables and static methods.
The validator proves that every continuing path invokes exactly one superclass or
sibling constructor before ordinary receiver use or return.

For a trivial constructor:

```java
definition.defaultConstructor().access(PUBLIC);
```

## Compiling and Loading

Compilation is target-aware. The compiler must know the actual defining loader or
lookup so it can validate package access, modules, generated hierarchies, stack-map
types, and exact class identity before a class is loaded.

### Nominal Classes

The direct legacy equivalent is:

```java
Class<? extends Filter> generated = StandardClassDefiner.builder(parentLoader)
        .build()
        .defineClass(classModel, Filter.class);
```

For production generators, prefer the complete unit lifecycle because it enables
measurement, warnings, and companion-class sharding:

```java
StandardClassDefiner definer = StandardClassDefiner.builder(parentLoader)
        .build();

CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
        .compileUnit(classModel);

DefinedUnit defined = definer.defineUnit(unit);
Class<? extends Filter> generated = defined.primaryClass(Filter.class);
```

`DefinedUnit` replaces maps indexed by generated binary-name strings. Retrieve the
primary class directly or retrieve another generated class with its `ClassDesc` and
expected Java supertype:

```java
Class<? extends Strategy> strategy = defined.definedClass(strategyType, Strategy.class);
```

### Hidden Classes

```java
HiddenClassDefiner definer = HiddenClassDefiner.builder(hostLookup)
        .build();

CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
        .compileUnit(classModel);

DefinedUnit defined = definer.defineUnit(unit);
Class<? extends Filter> generated = defined.primaryClass(Filter.class);
MethodHandles.Lookup generatedLookup = defined.primaryLookup().orElseThrow();
```

The logical class must be in the host lookup's package. Add `NESTMATE` to the definer
when the generated implementation requires nestmate access.

Automatically generated companions are hidden too. They are linked through extracted
method handles and per-class runtime data, not through nominal generated names.

### Runtime Values and Class Data

Replace explicit binding maps and generated static holder fields with expression-level
bindings:

```java
BytecodeExpression operator = boundConstant(runtimeOperator, Operator.class);
```

The compiler collects bindings from the logical model. Hidden classes receive them
through JDK class data; nominal classes receive equivalent data from the generated
class loader. Unit-wide application data is configured explicitly:

```java
CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
        .classData(applicationData)
        .compileUnit(classModel);
```

Target-aware adaptation automatically erases inaccessible method-handle parameter or
return types to `Object` when necessary. This catches subtle class-loader and package
visibility problems during testing instead of after generated code reaches users.

## Replace Manual Code Splitting

Large Bytecode generators, including Trino's flat-hash generator, divide logical
work into helper methods and chunk classes using field-count estimates. Those
boundaries are difficult to tune: JVM
method limits, constant-pool limits, live locals, and HotSpot's huge-method threshold
do not scale uniformly with the number of logical fields.

The classfile compiler accepts the unsplit logical method:

```java
MethodDefinition hash = definition.method("hash", long.class, values)
        .access(PUBLIC);
Variable result = hash.body().declare("result", constantLong(0));

for (HashField field : fields) {
    hash.body().append(result.set(invokeStatic(
            HashSupport.class,
            "combineHash",
            long.class,
            result,
            field.hash(values.getElement(field.index())))));
}
hash.body().ret(result);
```

Compile it as a unit:

```java
CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
        .compileUnit(definition.build());
```

The physical planner can then:

- recursively decompose large ordered and short-circuit expressions;
- extract safe sequential statement regions into methods;
- carry zero or one live result across an extracted region;
- create boolean continuation helpers for repeated early returns;
- extract constructor argument expressions that do not use the uninitialized
  receiver;
- partition generated helpers into companion classes under method-count and
  constant-pool pressure;
- link companions by method handle for both nominal and hidden classes;
- preserve readable names derived from the logical method and statement
  descriptions.

The logical `ClassModel` is not mutated. Physical helper methods, companion classes,
slots, labels, and bindings are compilation results rather than authoring concerns.

This behavior is exercised with an unchunked FlatHash-shaped generator containing
2,001 fields under both nominal and hidden loading, a 10,000-field stress case, and a
25,000-constant class-sharding case. These tests validate resource limits directly
instead of relying on a hand-selected chunk constant.

### Migrating an Existing Chunked Generator

Use this sequence:

1. Port the existing chunk methods and classes without changing their boundaries.
2. Switch loading to `compileUnit(...)` and `defineUnit(...)`.
3. Add a stress test materially larger than the generator's supported production
   shape.
4. Merge one manual chunking layer back into a single logical method.
5. Inspect behavior, `CompilationReport`, generated helper names, and hidden-class
   execution.
6. Continue removing manual boundaries while tests remain readable and the planner
   can prove safe extraction.

Do not retain a manual chunk merely because the legacy implementation needed it.
Retain it when it expresses a meaningful domain boundary, improves logical
readability, or surrounds control flow the planner intentionally will not move.

### Reports and Warnings

Every `CompiledUnit` reports actual emitted resources:

```java
CompilationReport report = unit.report();
report.classes().forEach(classInfo -> classInfo.methods().forEach(method ->
        log.debug("{}.{}{}: {} bytes", classInfo.type().displayName(),
                method.name(), method.type().descriptorString(), method.codeBytes())));
```

The default policy distinguishes correctness from optimization:

- exceeding the configured hard method limit fails compilation;
- remaining above a desirable huge-method threshold produces a warning and runnable
  output;
- callers may reject selected warning categories when their environment requires a
  stricter policy.

This means a conservative optimization miss does not turn otherwise valid generated
code into an outage.

### Boundaries

Automatic splitting does not make every possible classfile valid. Important retained
limits are:

- method parameters are not packed to evade the JVM parameter-slot limit;
- a statement region with several live outputs is not automatically converted into
  a state carrier;
- complex exception regions, loops, non-local control transfers, receiver-sensitive
  constructor code, and caller-sensitive operations may pin a region;
- a single indivisible operation can still exceed a hard limit;
- the compiler does not synthesize arbitrary object graphs or cyclic mutable linkage
  protocols.

These boundaries are deliberately described in Java-level terms. If a real Trino
workload reaches one, keep or introduce a logical helper at that point and use the
report to drive a focused compiler improvement rather than restoring global chunk
constants.

## Records and Interfaces

Records and interfaces are not required for a mechanical migration, but they can
remove substantial generated boilerplate once the port is stable.

### Records

```java
ClassDefinition row = ClassDefinition.defineRecord(rowType)
        .access(PUBLIC);

RecordComponentDefinition position = row.recordComponent("position", int.class)
        .build();
RecordComponentDefinition value = row.recordComponent("value", String.class)
        .build();
```

The class model supplies the private final fields, accessors, canonical constructor,
and final `equals`, `hashCode`, and `toString` methods. Object methods use the JDK
`ObjectMethods` bootstrap and work for nominal and hidden records.

Use a compact constructor for validation or normalization without writing final-field
assignment boilerplate:

```java
CompactConstructorDefinition constructor = row.compactConstructor();
constructor.body().append(IfStatement.builder()
        .condition(constructor.parameter(position).lessThan(constantInt(0)))
        .then(newInstance(IllegalArgumentException.class).throwObject())
        .build());
constructor.initialize(
        value,
        constructor.parameter(value).invoke("strip", String.class));
```

Every implied member and constructor path is validated before emission.

### Interfaces

`defineInterface(...)` supports abstract, default, static, and private interface
methods. Invocation emission derives the interface-owner bit from the target
hierarchy, including generated and hidden interfaces, rather than requiring the
expression author to choose a special instruction form.

## Diagnostics

Start with logical rendering:

```java
System.out.println(expression);
System.out.println(codeBlock);
System.out.println(classModel);
```

For final emitted bytes:

```java
byte[] bytes = unit.classfile(unit.primaryType());
List<VerifyError> errors = ClassFileDiagnostics.verify(bytes);
String text = ClassFileDiagnostics.disassemble(bytes);
ClassFileDiagnostics.dump(outputDirectory, bytes);
```

Replace ASM trace visitors, fake line-number experiments, and instruction-tree dumps
with logical model rendering, compilation reports, JDK verification, and final-byte
disassembly. Statement descriptions also feed readable generated helper names, which
makes reports and production stack traces easier to connect to the generator.

## Testing a Port

A migrated generator should have coverage at four levels:

1. **Logical behavior:** compare public method results, thrown exceptions, side
   effects, and state changes with the legacy generator.
2. **Authoring behavior:** retain important expression and block `toString()`
   assertions, invalid-scope tests, and constructor-path validation.
3. **Runtime behavior:** execute through the intended nominal or hidden definer with
   actual runtime bindings and target visibility.
4. **Scale behavior:** generate a class materially larger than expected production
   input and inspect its `CompilationReport` and warnings.

Do not compare raw classfile bytes. Constant-pool order, stack maps, helper placement,
and physical class topology may legitimately differ. Parity means observable runtime
behavior and understandable authoring code.

## Migration Checklist

- [ ] Replace the dependency and package imports.
- [ ] Replace `Access` sets with JDK `AccessFlag` varargs.
- [ ] Replace `ParameterizedType` and ASM `Type` with `ClassDesc`; keep convenient
      `Class<?>` overloads where the class is loaded.
- [ ] Replace string descriptors with `MethodTypeDesc` and generic strings with JDK
      signature metadata.
- [ ] Convert class, field, method, and constructor declarations to builders.
- [ ] Convert `getBody()` and repeated appends to a chained `CodeBlock.Builder`.
- [ ] Declare locals on their lexical block and pass variable objects instead of
      looking them up by name.
- [ ] Remove temporary-slot allocation and release code.
- [ ] Preserve fluent expression chains and move receiver operations off static
      helper calls where appropriate.
- [ ] Replace custom instruction-emitting expressions with functional
      `SyntheticExpression` implementations.
- [ ] Replace mutable control objects and physical loop labels with structured
      builders and loop-specific break/continue values.
- [ ] Replace raw labels only where structured control flow cannot express the
      existing behavior.
- [ ] Replace stack-oriented constructor calls with `invokeSuperConstructor(...)` or
      `invokeThisConstructor(...)`.
- [ ] Build an immutable `ClassModel` and compile it for the exact definer target.
- [ ] Prefer `CompiledUnit` and `DefinedUnit` over direct class and name-map loading.
- [ ] Replace explicit binding maps with bound expressions and class data.
- [ ] Port behavior tests before changing manual helper boundaries.
- [ ] Add nominal and hidden execution coverage where both are supported.
- [ ] Add a large synthetic workload, inspect the compilation report, and then remove
      obsolete manual chunking.
- [ ] Consider records, compact constructors, and generated interfaces where they
      replace application boilerplate.
- [ ] Remove ASM, Guava, and legacy Bytecode dependencies after the last generator is
      migrated.

## Executable References

The most useful executable examples are:

- [`TestPageFunctionCompilerClassfile`](../src/test/java/io/airlift/classfile/TestPageFunctionCompilerClassfile.java)
  and [`TestFlatHashStrategyCompilerClassfile`](../src/test/java/io/airlift/classfile/TestFlatHashStrategyCompilerClassfile.java)
  for substantial Trino-shaped generators;
- [`TestExpressionParity`](../src/test/java/io/airlift/classfile/TestExpressionParity.java)
  and [`TestExpressionBehaviorMatrix`](../src/test/java/io/airlift/classfile/TestExpressionBehaviorMatrix.java);
- [`TestAutomaticFlatHashSplitting`](../src/test/java/io/airlift/classfile/TestAutomaticFlatHashSplitting.java)
  for the de-chunked acceptance workload;
- [`TestAutomaticRowConstructorSplitting`](../src/test/java/io/airlift/classfile/TestAutomaticRowConstructorSplitting.java)
  for conditionally initialized field blocks with scoped locals;
- [`TestRuntimeDefiners`](../src/test/java/io/airlift/classfile/TestRuntimeDefiners.java)
  and [`TestCompiledUnit`](../src/test/java/io/airlift/classfile/TestCompiledUnit.java)
  for target-aware compilation, reports, and loading;
- [`TestRecordGeneration`](../src/test/java/io/airlift/classfile/TestRecordGeneration.java)
  and [`TestCompactRecordConstructor`](../src/test/java/io/airlift/classfile/TestCompactRecordConstructor.java)
  for generated records.

See [`USER_GUIDE.md`](USER_GUIDE.md) for the complete authoring API and
[`AUTOMATIC_CODE_SPLITTING.md`](AUTOMATIC_CODE_SPLITTING.md) for splitting behavior,
admission rules, limits, and possible future extensions.
