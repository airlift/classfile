# Classfile User Guide

This guide describes the `io.airlift.classfile` API. It starts with
authoring because generated-code authors spend most of their time constructing the
logical class and method bodies. Compilation, automatic physical planning, and class
definition come afterward.

The library has two models:

- the **logical model** is the class, fields, methods, expressions, and control flow
  authored by the caller;
- the **physical unit** is the set of classfiles the compiler produces after it has
  measured and, when necessary, split that logical model.

Authors work only with the logical model. They do not choose local-variable slots,
JDK labels, helper methods, helper classes, or split sizes.

## Complete Example

The following example creates a nominal class implementing `IntUnaryOperator`. The
class stores a multiplier in a field and uses structured control flow in its method.

```java
import io.airlift.classfile.ClassCompiler;
import io.airlift.classfile.ClassDefinition;
import io.airlift.classfile.ClassModel;
import io.airlift.classfile.CompiledUnit;
import io.airlift.classfile.FieldDefinition;
import io.airlift.classfile.IfStatement;
import io.airlift.classfile.MethodDefinition;
import io.airlift.classfile.Parameter;
import io.airlift.classfile.DefinedUnit;
import io.airlift.classfile.StandardClassDefiner;

import java.lang.constant.ClassDesc;
import java.util.function.IntUnaryOperator;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;

ClassDesc generatedType = ClassDesc.of("example.GeneratedMultiplier");
ClassDefinition definition = ClassDefinition.define(generatedType)
        .access(PUBLIC, FINAL)
        .addInterface(IntUnaryOperator.class)
        .sourceFile("GeneratedMultiplier.java");

FieldDefinition multiplierField = definition.field("multiplier", int.class)
        .access(PRIVATE, FINAL)
        .build();

Parameter multiplier = Parameter.arg("multiplier", int.class);
MethodDefinition constructor = definition.constructor(multiplier).access(PUBLIC);
constructor.body()
        .invokeSuperConstructor()
        .append(constructor.thisVariable().setField(multiplierField, multiplier))
        .ret();

Parameter value = Parameter.arg("value", int.class);
MethodDefinition apply = definition.method("applyAsInt", int.class, value).access(PUBLIC);
apply.body()
        .append(IfStatement.builder()
                .description("if value is negative, return the positive version")
                .condition(value.lessThan(constantInt(0)))
                .then(value.negate().ret())
                .build())
        .ret(value.multiply(apply.thisVariable().getField(multiplierField)));

ClassModel logicalClass = definition.build();

StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader())
        .build();
CompiledUnit compiled = ClassCompiler.forTarget(definer.compilationTarget())
        .compileUnit(logicalClass);

// Applications may log or reject selected warnings before loading the class.
compiled.report().warnings().forEach(System.err::println);

DefinedUnit defined = definer.defineUnit(compiled);
Class<? extends IntUnaryOperator> generatedClass = defined.primaryClass(IntUnaryOperator.class);
IntUnaryOperator operator = generatedClass.getConstructor(int.class).newInstance(3);
```

`operator.applyAsInt(7)` returns `21`, and `operator.applyAsInt(-7)` returns `7`.
The same complete lifecycle is exercised by
[`TestClassfileWalkingSkeleton`](../src/test/java/io/airlift/classfile/TestClassfileWalkingSkeleton.java).

## Authoring A Class

### Types

The logical model uses JDK symbolic descriptors. Use the convenient `Class<?>`
overloads for already loaded types and `ClassDesc` for the generated class or any
other type that does not exist yet:

```java
ClassDesc generatedType = ClassDesc.of("com.example.GeneratedFilter");
ClassDefinition definition = ClassDefinition.define(generatedType)
        .access(PUBLIC, FINAL)
        .superClass(BaseFilter.class)
        .addInterface(Filter.class);
```

A `ClassDesc` describes a type; it does not load that type. This allows generated
classes to refer to themselves and to other classes in the same generated unit.

The definition factory selects a `ClassKind`, which is retained in the immutable
model and drives validation and classfile flags:

```java
ClassDefinition ordinary = ClassDefinition.define(classType);
ClassDefinition contract = ClassDefinition.defineInterface(interfaceType);
ClassDefinition data = ClassDefinition.defineRecord(recordType);
```

Do not model an interface by passing `INTERFACE` to `access(...)`. The factory makes
interfaces first-class and automatically supplies the classfile-level `INTERFACE`
and `ABSTRACT` flags. `ClassModel.kind()` reports `CLASS`, `INTERFACE`, or `RECORD`.

Class, field, method, and control-flow builders reject setting the same singular
property twice. For example, calling `access(...)`, `condition(...)`, or
`otherwise(...)` twice throws instead of silently replacing the earlier value.
Additive operations such as `addInterface(...)`, `addAnnotation(...)`, and
`caseValue(...)` may be called repeatedly.

### Fields

`field(...)` returns a builder. Calling `build()` validates and registers the field
with its class:

```java
FieldDefinition countField = definition.field("count", int.class)
        .access(PRIVATE)
        .build();

FieldDefinition nameField = definition.field("name", String.class)
        .access(PRIVATE, FINAL)
        .build();

FieldDefinition versionField = definition.field("VERSION", int.class)
        .access(PUBLIC, STATIC, FINAL)
        .constantValue(1)
        .build();
```

Constant values are limited to JVM constant-value attribute types and require a
`static final` field. Ordinary object initialization belongs in a constructor or the
class initializer.

### Interfaces

Use `defineInterface(...)` for a generated interface. Interface method intent stays
explicit: an abstract declaration is `public abstract`, a default method is `public`
with a body, and a static or private method declares those flags and has a body.

```java
ClassDefinition operation = ClassDefinition.defineInterface(operationType)
        .access(PUBLIC);

Parameter value = Parameter.arg("value", int.class);
operation.method("apply", int.class, value)
        .access(PUBLIC, ABSTRACT);

Parameter doubled = Parameter.arg("value", int.class);
operation.method("twice", int.class, doubled)
        .access(PUBLIC)
        .body()
        .ret(doubled.multiply(constantInt(2)));
```

Interface fields must be `public static final`. Constant-value fields and fields
initialized by `classInitializer()` are both supported. Interfaces cannot declare a
superclass, constructor, record component, instance field, protected method, or
final method. These rules are checked when the logical model is built. Static and
special calls determine the interface-owner bit from the compilation target, so
expression authors do not encode that classfile detail.

### Records

Use `defineRecord(...)` when the generated class should have Java record semantics.
Each component builder returns a stable `RecordComponentDefinition` that exposes the
generated field and accessor as well as expression helpers for using them:

```java
ClassDesc pointType = ClassDesc.of("com.example.GeneratedPoint");
ClassDefinition point = ClassDefinition.defineRecord(pointType)
        .access(PUBLIC);

RecordComponentDefinition x = point.recordComponent("x", int.class).build();
RecordComponentDefinition name = point.recordComponent("name", String.class).build();

MethodDefinition score = point.method("score", int.class).access(PUBLIC);
score.body().ret(x.get(score.thisVariable())
        .add(name.get(score.thisVariable()).invoke("length", int.class)));

ClassModel pointModel = point.build();
```

`build()` supplies the members implied by a Java record when they are not explicitly
declared:

- a private final field and public accessor for every component;
- the canonical constructor;
- final `equals(Object)`, `hashCode()`, and `toString()` methods using the JDK
  `java.lang.runtime.ObjectMethods` implementation.

`component.get(receiver)` invokes the public accessor. `component.read(receiver)`
reads the private field directly and is intended for code inside the record.
`component.field()` and `component.accessor()` expose symbolic generated-member
references for APIs that need them. There is deliberately no setter.

An explicitly declared accessor or object method with the exact implied descriptor
replaces the generated default. The complete record is validated at
`ClassDefinition.build()`: accessors must be public instance methods without declared
exceptions, records cannot have additional instance fields, and component fields
cannot be written outside the canonical constructor.

For the common normalization case, use a compact constructor. The component
parameters are expressions, while the `Record()` call, final field assignments, and
return are implicit:

```java
CompactConstructorDefinition constructor = point.compactConstructor();
constructor.body().append(IfStatement.builder()
        .condition(constructor.parameter(x).lessThan(constantInt(0)))
        .then(newInstance(IllegalArgumentException.class).throwObject())
        .build());
constructor.initialize(
        name,
        constructor.parameter(name).invoke("strip", String.class));
```

Every component defaults to its corresponding parameter. `initialize(component,
expression)` overrides that final value and can be specified once per component.
The constructor visibility defaults to the record visibility, or can be set once
with `access(...)`. Record components must be complete before
`compactConstructor()` is requested. A compact body may validate, calculate, and
throw, but cannot explicitly return or invoke a constructor.

For bytecode requiring complete control over component writes, declare the
canonical constructor explicitly. Its parameter names and order must match the
components, and every component must be initialized exactly once on an
unconditional path after `Record()` is invoked:

```java
Parameter xParameter = Parameter.arg("x", int.class);
Parameter nameParameter = Parameter.arg("name", String.class);
MethodDefinition constructor = point.constructor(xParameter, nameParameter).access(PUBLIC);
constructor.body()
        .invokeSuperConstructor()
        .append(x.initialize(constructor.thisVariable(), xParameter))
        .append(name.initialize(
                constructor.thisVariable(),
                nameParameter.invoke("strip", String.class)))
        .ret();
```

Additional constructors must delegate to the canonical constructor with
`invokeThisConstructor(...)`. Component generic signatures and runtime-visible or
runtime-invisible annotations can be added on the component builder. These describe
the record component attribute itself; field, accessor, and constructor-parameter
annotation propagation is not inferred.

The same model works for nominal and hidden classes. Generated object methods route
through a small bootstrap adapter that resolves the actual lookup class and then
delegates to the JDK `ObjectMethods.bootstrap`. This preserves JDK record behavior
without embedding a nominal self-class constant that would have the wrong identity
for a hidden class.

As with hidden classes generally, do not put the generated hidden type itself,
directly or inside an array, in a member descriptor. Resolving that descriptor
requires loader lookup by the nominal source name, but a hidden class is
intentionally not discoverable by name. Use a loaded interface or another nominal
carrier type at that boundary. Target-aware compilation rejects this shape before
emission.

### Methods And Parameters

Create each `Parameter` explicitly, then pass it to the method:

```java
Parameter left = Parameter.arg("left", int.class);
Parameter right = Parameter.arg("right", int.class);
MethodDefinition add = definition.method("add", int.class, left, right)
        .access(PUBLIC, STATIC);

add.body().ret(left.add(right));
```

Parameters are expressions, so no separate load operation is needed. A parameter is
owned by one method and cannot be reused as the parameter declaration of another
method.

Parameter names are emitted in the `MethodParameters` attribute. Parameter access
flags and annotations can be authored directly and are snapshotted with the method:

```java
Parameter value = Parameter.arg("value", int.class)
        .access(FINAL)
        .addAnnotation(runtimeVisibleAnnotation)
        .addInvisibleAnnotation(classFileOnlyAnnotation);
```

Only JVM method-parameter flags (`FINAL`, `SYNTHETIC`, and `MANDATED`) are accepted,
and `access(...)` follows the set-once builder rule. Visible and invisible parameter
annotations are emitted as separate attributes. Parameters implicitly declared by a
compact record constructor are automatically marked `MANDATED`, matching `javac`;
explicit and synthesized canonical-constructor parameters are named but not mandated.

Use `method.thisVariable()` for the receiver of an instance method. Static methods
do not have a receiver, and calling `thisVariable()` on one fails immediately.

The method definition is registered when `method(...)` is called. There is no
per-method `build()` call. `definition.build()` validates the complete class and
returns an immutable `ClassModel` snapshot.

### Constructors And Class Initialization

A constructor body must invoke exactly one superclass or sibling constructor on
every continuing path:

```java
Parameter name = Parameter.arg("name", String.class);
MethodDefinition constructor = definition.constructor(name).access(PUBLIC);
constructor.body()
        .invokeSuperConstructor()
        .append(constructor.thisVariable().setField(nameField, name))
        .ret();
```

Arguments may be computed before the constructor invocation. For example, given a
superclass constructor accepting a `String`, the argument can be normalized first.
The uninitialized receiver cannot be read or passed to ordinary code before
`super(...)` or `this(...)`:

```java
Variable normalized = constructor.body().declare(
        "normalized",
        name.invoke("strip", String.class));
constructor.body()
        .invokeSuperConstructor(normalized)
        .ret();
```

Use `invokeThisConstructor(...)` for sibling-constructor delegation. Use the
`MethodTypeDesc` or reflective `Constructor<?>` overload when argument types alone
do not identify the intended superclass constructor.

For a trivial constructor:

```java
definition.defaultConstructor().access(PUBLIC);
```

The class initializer is created lazily and is always static. For example, a
non-constant static field can be initialized as follows:

```java
FieldDefinition registryField = definition.field("registry", Registry.class)
        .access(PRIVATE, STATIC, FINAL)
        .build();
definition.classInitializer().body()
        .append(setStatic(registryField, newInstance(Registry.class)))
        .ret();
```

## Building Method Bodies

`method.body()` returns a `CodeBlock.Builder`. Its operations chain, so a method body
normally reads as one ordered sequence:

```java
method.body()
        .append(result.set(input.add(constantInt(1))))
        .append(result.set(result.multiply(constantInt(2))))
        .ret(result);
```

`append(...)` accepts any `Statement`. Expressions are statements too; when an
expression with a result is appended without using that result, the emitter discards
it correctly.

Use `CodeBlock.block(...)` for a short immutable block and `blockBuilder()` for a
block assembled incrementally:

```java
CodeBlock shortBlock = block(
        result.set(constantInt(1)),
        result.increment());

CodeBlock.Builder builder = blockBuilder().description("process selected positions");
builder.append(result.set(constantInt(0)));
builder.append(shortBlock);
CodeBlock reusableBlock = builder.build();
```

The `description(...)` is included in source-like rendering, which makes logical
dumps easier to understand. Automatically generated helpers are named from their
logical method and operation kind.

### Local Variables And Scope

Declare an uninitialized local with its type, or infer the type from an initializer:

```java
Variable result = method.body().declare(int.class, "result");
Variable limit = method.body().declare("limit", values.length());
```

Variables are expressions. Assignment, increment, array access, field access, method
calls, and arithmetic therefore compose directly:

```java
method.body()
        .append(result.set(values.getElement(index).add(constantInt(1))))
        .append(index.increment());
```

Each `CodeBlock` introduces a lexical scope. A nested block may shadow an outer
variable name because variables are referenced by identity rather than looked up by
name:

```java
Variable value = method.body().declare("value", constantInt(10));

CodeBlock.Builder nested = blockBuilder();
Variable nestedValue = nested.declare("value", constantInt(7));
nested.append(result.set(nestedValue));
method.body().append(nested.build());
```

No local-variable slot is assigned while authoring. Slots are assigned for each
placement during emission and reused after a lexical scope ends.

### Reusing Blocks And Expressions

`CodeBlock` and expression values are immutable and may be placed more than once:

```java
CodeBlock.Builder fragment = blockBuilder().description("accumulate one value");
Variable temporary = fragment.declare("temporary", input.add(constantInt(1)));
fragment.append(result.set(result.add(temporary)));
CodeBlock reusable = fragment.build();

method.body()
        .append(reusable)
        .append(reusable)
        .ret(result);
```

Each placement receives fresh slots for `temporary`; the captured `input` and
`result` still resolve to their enclosing method values. The same principle applies
inside branches, loops, and exception handlers.

The complete class is validated for scope consistency when `ClassDefinition.build()`
is called. A fragment that captures a variable from an unrelated method or sibling
scope is rejected with the variable name and placement instead of producing invalid
bytecode later.

## Expressions

The fluent expression API is intended to resemble ordinary Java evaluation:

```java
BytecodeExpression expression = values.getElement(index)
        .add(offset)
        .multiply(constantInt(31))
        .cast(long.class);
```

Use static factories for expressions that do not naturally start from a receiver:

```java
constantInt(42)
constantString("value")
constantNull(String.class)
newArray(int[].class, count)
newInstance(ArrayList.class)
invokeStatic(Integer.class, "compare", int.class, left, right)
```

Use fluent methods when an existing expression is the subject of the operation:

```java
array.getElement(index)
array.setElement(index, value)
array.length()
receiver.getField(field)
receiver.setField(field, value)
receiver.invoke("size", int.class)
value.cast(long.class)
value.instanceOf(String.class)
```

Arithmetic, bitwise operations, shifts, comparisons, null checks, and boolean
short-circuit operations are also fluent. `and(...)` and `or(...)` preserve Java
short-circuit behavior.

Arithmetic on `byte`, `short`, and `char` follows Java numeric promotion and produces
an `int` expression. Boxing therefore produces an `Integer` unless the result is
explicitly cast back to the narrow type. Variable assignment, increment, method
arguments and returns, and field and array stores normalize narrow values at the
declared type boundary.

`inlineIf(condition, ifTrue, ifFalse)` is the expression equivalent of Java's
conditional operator. Both result expressions must have the same type.

Use `ret()`, `throwObject()`, or `pop()` to turn an expression into the corresponding
terminal or discard operation. `CodeBlock.Builder` also supplies `ret(value)`,
`throwObject(value)`, and no-value `ret()` conveniences.

Expressions render themselves as concise source-like strings. This is part of the
debugging contract: descriptions, validation failures, and logical-model dumps use
the same rendering.

### Runtime Values And Extension Expressions

`boundConstant(value, type)` embeds an application object through runtime data rather
than the constant pool. The compiler validates and adapts the binding for the actual
definition target. `boundMethodHandle(handle)` provides the corresponding invocation
form for a method handle.

Applications may define a `SyntheticExpression` for domain operations. Its
`expansion(...)` returns an immutable `ExpressionPlan` containing optional setup and
a value expressed in the standard model. Expansion must be functional: it must be
deterministic, must not mutate external state, and must not depend on how many times
the compiler requests it.

## Structured Control Flow

Control-flow builders use named components instead of positional constructors. Each
component can be assigned once, and any single `Statement` is automatically wrapped
as a one-statement block.

### If/Else

```java
method.body().append(IfStatement.builder()
        .description("select sign")
        .condition(value.lessThan(constantInt(0)))
        .then(result.set(value.negate()))
        .otherwise(result.set(value))
        .build());
```

The condition must be boolean. Either branch may be omitted, but at least one branch
must contain a statement.

### For Loop

```java
Variable index = method.body().declare(int.class, "index");
Variable sum = method.body().declare("sum", constantInt(0));

method.body().append(ForLoop.builder()
        .description("sum values")
        .initialize(index.set(constantInt(0)))
        .condition(index.lessThan(values.length()))
        .update(index.increment())
        .body(sum.set(sum.add(values.getElement(index))))
        .build());
```

The initializer and update are optional. They also accept `CodeBlock` when more than
one statement is needed. A variable declared in an initializer block is visible to
the condition, update, and body, but not after the loop.

Request `break` and `continue` statements from the builder for the loop they target:

```java
ForLoop.Builder loop = ForLoop.builder();
CodeBlock body = block(
        IfStatement.builder()
                .condition(value.isNull())
                .then(loop.continueLoop())
                .build(),
        IfStatement.builder()
                .condition(limitReached)
                .then(loop.breakLoop())
                .build());

method.body().append(loop
        .condition(hasNext)
        .body(body)
        .build());
```

This identity makes nested-loop targets explicit without exposing labels.

### While And Do/While

```java
WhileLoop.Builder whileLoop = WhileLoop.builder();
method.body().append(whileLoop
        .condition(index.lessThan(limit))
        .body(block(
                consume.invoke("accept", void.class, values.getElement(index)),
                index.increment()))
        .build());

method.body().append(DoWhileLoop.builder()
        .condition(retry)
        .body(attempt.invoke("run", void.class))
        .build());
```

Both builders also provide loop-specific `breakLoop()` and `continueLoop()` values.

### Switch

```java
method.body().append(SwitchStatement.builder()
        .description("decode kind")
        .expression(kind)
        .caseValue(1, result.set(constantInt(10)))
        .caseValue(2, result.set(constantInt(20)))
        .defaultCase(result.set(constantInt(-1)))
        .build());
```

The switch expression must be int-like. Cases are unique and are sorted by key.
Every case is an independent block with an implicit exit; this API intentionally
does not model Java fall-through.

### Try/Catch/Finally

```java
method.body().append(TryCatch.builder()
        .description("read value")
        .tryBlock(result.set(reader.invoke("read", int.class)))
        .catching(IOException.class, "failure", (body, failure) -> body
                .append(result.set(failure.invoke("getMessage", String.class)
                        .invoke("length", int.class))))
        .finallyBlock(resource.invoke("close", void.class))
        .build());
```

The catch callback receives a builder for the catch scope and its typed exception
variable. Multiple exception types may be caught. A try statement requires at least
one catch or a finally block.

Returns, throws, loop exits, and symbolic jumps leaving the try region execute the
applicable `finally` blocks. An exception thrown by a `finally` block is not caught by
a catch belonging to that same try statement.

### Returns And Throws

```java
method.body().ret(result);
method.body().ret();
method.body().throwObject(newInstance(IllegalArgumentException.class, message));
```

The class validator checks that return values match the method return type and that
constructor paths satisfy initialization rules.

### Symbolic Labels

Labels are an escape hatch for generator patterns that cannot yet be represented by
structured control flow:

```java
CodeLabel end = method.body().label("end");
method.body()
        .jump(end)
        .mark(end)
        .ret();
```

Prefer structured statements. Labels are scoped, must be bound exactly once by their
owning block, and are validated when the model is assembled. A reusable block may
capture a visible parent label; block-owned labels receive a fresh JDK label for each
placement.

## Compiling And Defining Classes

The preferred lifecycle is:

1. Build an immutable `ClassModel`.
2. Create the definer for the intended runtime environment.
3. Compile with the definer's exact `compilationTarget()`.
4. Inspect the resulting `CompilationReport` if desired.
5. Define the complete `CompiledUnit`.
6. Retrieve the primary class through its expected Java supertype or interface.

Using the definer's target is important. Accessibility depends on the actual class
loader or lookup, runtime package, module exports, and class identity. The compiler
checks these before class definition. Compiled artifacts retain this target, and a
definer rejects an artifact compiled for a different loader or lookup environment.

### Nominal Classes

Nominal classes have ordinary binary names and are defined together in a dedicated
generated class loader:

```java
StandardClassDefiner definer = StandardClassDefiner.builder(parentClassLoader)
        .build();

CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
        .compileUnit(logicalClass);

DefinedUnit defined = definer.defineUnit(unit);
Class<? extends Filter> filterClass = defined.primaryClass(Filter.class);
```

Use nominal classes when code must refer to the generated class by name, when several
ordinary generated classes need symbolic relationships, or when an integration
requires normal class-loader identity. The generated class loader, not its parent,
owns these classes. Holding the resulting classes also keeps that loader alive.

When dependencies must retain class identity from a plugin or overlay environment,
configure `StandardClassDefiner.Builder.overrideLoader(...)`. The generated loader
first resolves its own pending generated classes, then asks the override loader for
non-generated dependencies, and finally uses normal parent delegation. The override
loader supplies dependency identity; it does not define or own generated classes or
their runtime data.

### Hidden Classes

Hidden classes are not discoverable through `Class.forName` and their runtime names
are not stable binary names. They are usually the better choice for generated
implementation details:

```java
MethodHandles.Lookup hostLookup = MethodHandles.lookup();
HiddenClassDefiner definer = HiddenClassDefiner.builder(hostLookup)
        .build();

CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
        .compileUnit(logicalClass);

DefinedUnit defined = definer.defineUnit(unit);
Class<? extends Filter> filterClass = defined.primaryClass(Filter.class);
MethodHandles.Lookup generatedLookup = defined.primaryLookup().orElseThrow();
```

The logical class must be in the host lookup's package, and the lookup must have full
privilege access. Both requirements are checked before classfile emission. Pass
`MethodHandles.Lookup.ClassOption.NESTMATE` to the definer builder when the generated
class must be a nestmate of the lookup class.

Every automatically generated companion is hidden as well. Companions are defined in
dependency order, their method handles are extracted, and callers are linked through
runtime data. Generated class names are not used to link them.

`DefinedUnit` remains keyed by the logical `ClassDesc`, even though a hidden runtime
class has a VM-assigned hidden name. `primaryLookup()` and `lookup(type)` are present
for hidden definitions and empty for nominal definitions.

### Class Data And Runtime Bindings

Set unit-wide class data on the compiler and retrieve it with the `classData(...)`
expression:

```java
CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
        .classData(applicationData)
        .compileUnit(logicalClass);
```

For hidden classes, this uses the JDK hidden-class class-data mechanism. For nominal
classes, equivalent per-class runtime data is supplied by the generated class loader.
`boundConstant(...)`, bound method handles, and generated companion links share this
runtime-data channel without requiring fields containing globally registered keys.
Compiled artifacts retain every declared `classData(T)` use. Definers validate the
effective class-data value after compiler and definer configuration are merged, before
installing classfiles. Missing data and values incompatible with any declared use fail
definition immediately rather than during bootstrap linkage.

Classfile is designed primarily to compile and define generated classes in the same
runtime. A unit that uses class data, bound constants, bound method handles, or
generated companion links is not a self-contained persistent classfile: retain the
`CompiledUnit` and define it through the corresponding definer. Callers that avoid
runtime bindings may use `CompiledUnit.classfile(...)` when they deliberately need
the emitted bytes.

### Compilation Reports

`CompiledUnit.report()` contains:

- the effective `CompilationPolicy`;
- every physical class and its classfile size, constant-pool count, bootstrap-method
  count, and field count;
- every physical method and its descriptor, code size, maximum stack, and maximum
  locals;
- deterministic compilation warnings.

Warnings do not prevent a valid unit from being defined. A strict application may
reject selected categories before definition:

```java
Set<CompilationWarning.Category> rejected = Set.of(
        CompilationWarning.Category.HUGE_METHOD);

unit.report().warnings().stream()
        .filter(warning -> rejected.contains(warning.category()))
        .findFirst()
        .ifPresent(warning -> {
            throw new IllegalStateException(warning.location() + ": " + warning.message());
        });
```

The planner emits `JIT_THRESHOLD_FALLBACK` and `HUGE_METHOD`. Applications should use
a `default` branch when switching over warning categories so future diagnostics remain
source compatible.

Use `CompilationPolicy.builder()` mainly for tests, experiments, or an application
with measured runtime-specific targets. Defaults retain the JVM hard method limit and
derive useful HotSpot optimization thresholds when the VM exposes them.

## Automatic Physical Planning

`compileUnit(...)` measures the emitted code and may create physical methods or
classes that were not present in the logical model. The compiler performs:

- recursive decomposition of large ordered expressions without reassociation;
- short-circuit-preserving decomposition of large boolean expressions;
- extraction of sequential statement regions with zero or one live output;
- extraction of normally completing scoped blocks containing local declarations and
  `if/else`, while preserving captured-reference mutations;
- boolean continuation helpers for repeated early-false returns;
- static extraction of constructor expressions both before receiver initialization
  and after it; completed-model validation prevents pre-initialization receiver capture;
- readable helper naming derived from the logical method and descriptions;
- companion-class partitioning under helper-count or constant-pool pressure;
- dependency-ordered, method-handle linkage of companions for both nominal and
  hidden definition.

The logical `ClassModel`, reusable blocks, and expressions are never mutated. A block
placed twice is analyzed and emitted twice, and physical bindings remain local to
each placement.

Structured statements may nest up to 512 lexical/control-flow levels. Deeper models
are rejected deterministically before recursive validation, planning, diagnostics, or
emission begins.

Small methods are not split merely to satisfy every inline threshold. The hard JVM
limit is a correctness boundary; the huge-method threshold is an optimization target.
If valid code remains above an optimization target, compilation succeeds and records
a warning.

## Boundaries And Failure Modes

Automatic planning is intentionally conservative. Users should understand these
boundaries:

- **Method parameters are not packed.** JVM parameter/local-slot limits are validated
  directly. A method taking 500 arguments is invalid and remains the author's problem.
- **Statement extraction carries at most one live local result.** A region producing
  several values needed later is left in place. Generated record or state carriers
  are deliberately not synthesized.
- **Complex control flow may pin a region.** Loops, exception regions, non-local
  transfers, receiver-sensitive constructor code, and caller-sensitive operations
  are moved only when the compiler can prove equivalent Java behavior.
- **One indivisible operation can still be too large.** If expression and statement
  decomposition find no legal boundary below the hard method limit, compilation
  fails with the physical method name, actual code size, and configured limit. The
  hard-limit error does not reconstruct a full Java-level explanation of the rejected
  region.
- **Class-level heroics are limited.** The compiler shards generated helpers and
  constants, but it does not invent arbitrary object graphs, reconstruct constants,
  or build cyclic mutable linkage protocols.
- **Synthetic expressions must be functional.** A stateful extension can be expanded
  during validation, planning, and emission and is invalid even if it appears to work
  in a small test.
- **Physical details are not an API.** Helper names are readable for diagnostics but
  helper count, placement, class topology, and stack-trace frames may change when the
  logical model, policy, VM, or compiler changes.
- **Authoring is not thread-safe.** Mutable definition and block builders are intended
  for single-threaded construction. Finalized models and blocks are immutable and
  reusable.
- **Runtime definers are reusable concurrently within a bounded scope.** Independent
  definition calls may safely share a definer across threads. A nominal definer owns one
  generated loader, so every class and runtime binding defined through it shares that
  loader's lifetime. Use a definer per bounded unit or batch rather than retaining one for
  an unbounded stream of generated classes. Hidden definers do not impose the same
  loader-granular unloading lifetime.

When a method satisfies the JVM's hard limits but cannot be improved safely, the
default is to emit runnable code and report the missed optimization. A final method
above the configured hard code-size limit produces `CompilationException`; the JDK
classfile API remains the backstop for other classfile-format limits.

## Diagnostics

Start with source-like rendering:

```java
System.out.println(logicalClass);
System.out.println(expression);
System.out.println(codeBlock);
```

For final physical bytes, `ClassFileDiagnostics` provides JDK verification,
disassembly, and paired `.class`/`.class.txt` dumps:

```java
byte[] bytes = unit.classfile(unit.primaryType());
List<VerifyError> errors = ClassFileDiagnostics.verify(bytes);
String disassembly = ClassFileDiagnostics.disassemble(bytes);
ClassFileDiagnostics.dump(outputDirectory, bytes);
```

Logical descriptions improve model dumps. Source-derived helper names make physical
reports, disassembly, and production stack traces easier to interpret.

## Further Reading

- [`MIGRATION_GUIDE.md`](MIGRATION_GUIDE.md) maps the legacy Bytecode API to this
  API and recommends a staged conversion, including removal of manual code splitting.
- [`DESIGN.md`](DESIGN.md) defines logical-model invariants and validation semantics.
- [`AUTOMATIC_CODE_SPLITTING.md`](AUTOMATIC_CODE_SPLITTING.md) explains how physical
  planning works, including its algorithms, reports, and operational limits.
- [`EXAMPLES.md`](EXAMPLES.md) links executable examples and Trino-shaped fixtures.
