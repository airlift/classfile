# Classfile API Examples

This page indexes executable examples for `io.airlift.classfile`. The examples are
JUnit tests so every shown authoring pattern is compiled, loaded, and executed rather
than maintained as an unchecked documentation snippet.

For a continuous authoring and runtime walkthrough, start with
[`USER_GUIDE.md`](USER_GUIDE.md). This page indexes the executable tests backing
that guide and the broader design contract.

## Complete Class

[`TestClassfileWalkingSkeleton`](../src/test/java/io/airlift/classfile/TestClassfileWalkingSkeleton.java)
contains the primary full-class examples. The basic shape is:

```java
ClassDesc type = ClassDesc.of("example.GeneratedCounter");
ClassDefinition classDefinition = ClassDefinition.define(type)
        .access(PUBLIC, FINAL)
        .sourceFile("GeneratedCounter.java");

FieldDefinition nameField = classDefinition.field("name", String.class).access(PRIVATE, FINAL).build();
FieldDefinition valueField = classDefinition.field("value", int.class).access(PRIVATE).build();

Parameter name = Parameter.arg("name", String.class);
MethodDefinition constructor = classDefinition.constructor(name).access(PUBLIC);
constructor.body()
        .invokeSuperConstructor()
        .append(constructor.thisVariable().setField(nameField, name))
        .ret();

Parameter delta = Parameter.arg("delta", int.class);
MethodDefinition add = classDefinition.method("add", int.class, delta).access(PUBLIC);
Variable current = add.body().declare("current", add.thisVariable().getField(valueField));
add.body().append(IfStatement.builder()
        .condition(delta.greaterThan(constantInt(0)))
        .then(current.set(current.add(delta)))
        .otherwise(current.set(current.subtract(delta.negate())))
        .build());
add.body().append(add.thisVariable().setField(valueField, current));
add.body().ret(current);

ClassModel classModel = classDefinition.build();
Class<?> generated = StandardClassDefiner.builder(parentLoader)
        .build()
        .defineClass(classModel);
```

This uses custom definitions only where a reusable declarative model is needed. The
backend emits directly through `java.lang.classfile`; no instruction DOM or visitor
API is exposed.

## Reusable DAG Fragment

A finalized block can capture declarations from a compatible parent and own local
declarations of its own:

```java
Variable result = method.body().declare(int.class, "result");

CodeBlock.Builder fragmentBuilder = CodeBlock.blockBuilder();
Variable temporary = fragmentBuilder.declare(int.class, "temporary");
fragmentBuilder.append(temporary.set(input.add(constantInt(1))));
fragmentBuilder.append(result.set(result.add(temporary)));
CodeBlock fragment = fragmentBuilder.build();

method.body().append(fragment);
method.body().append(fragment);
```

Both placements capture the same `input` and `result`. Each placement independently
binds `temporary`, and physical slots are allocated and released only during
emission. The same rule works through loops, catch handlers, and `finally` blocks.

## Named Loop

The builder form labels each part of a nontrivial loop without requiring one-statement
`CodeBlock` wrappers:

```java
Variable result = method.body().declare("result", constantInt(0));
Variable index = method.body().declare(int.class, "index");

method.body().append(ForLoop.builder()
        .initialize(index.set(constantInt(0)))
        .condition(index.lessThan(count))
        .update(index.increment())
        .body(result.set(result.add(index)))
        .build());
method.body().ret(result);
```

The builder also exposes `breakLoop()` and `continueLoop()` for loop bodies that need
explicit control targets.

## Executable Catalog

The suite contains more than twenty standalone scenarios:

| Area | Executable examples |
| --- | --- |
| Complete classes | fields, constructors, instance methods, interfaces, class initialization, source metadata |
| Records | components, generated members, custom canonical and delegating constructors, metadata, nominal and hidden definition |
| Expressions | constants, arithmetic, bitwise operations, shifts, comparisons, NaN, short-circuit logic, null checks |
| Arrays and casts | primitive/reference arrays, narrow stores, indexing, length, primitive conversions, boxing, unboxing |
| Linkage | reflective calls, generated members and hierarchies, exact descriptors and class identities, constructors, fields, `invokedynamic`, constant dynamic |
| Control flow | `if/else`, `for`, `while`, `do/while`, switch, break, continue, symbolic labels |
| Exceptions | typed catches, catch locals, `finally` on return/break/continue/jump, exceptions thrown by `finally` |
| Reuse and scope | repeated block placement, parent captures, owned locals, sibling slot reuse, invalid captures, cycles |
| Extensions | value-only synthetic expressions, structured synthetic expressions, repeated expansion, invalid recursion |
| Runtime | typed standard classes, multi-class bundles, hidden classes, shared loaders, class data, bound constants, target-adapted method handles |
| Trino-shaped generators | projection/filter work, flat strategy/chunk classes, fixed/variable storage, identity, hashing, runtime-bound operators |
| Metadata and tools | annotations, signatures, exceptions, constants, verification, readable dumps, dependency guardrails |

The source entry points are:

- [`TestClassfileWalkingSkeleton`](../src/test/java/io/airlift/classfile/TestClassfileWalkingSkeleton.java): complete generated classes and DAG/scoping behavior.
- [`TestConstructorInitialization`](../src/test/java/io/airlift/classfile/TestConstructorInitialization.java): positional superclass/sibling constructor invocation, pre-invocation processing, branch paths, and validation.
- [`TestExpressionParity`](../src/test/java/io/airlift/classfile/TestExpressionParity.java): fluent expression behavior and exact rendering.
- [`TestExpressionBehaviorMatrix`](../src/test/java/io/airlift/classfile/TestExpressionBehaviorMatrix.java): exhaustive arithmetic, comparison, logical, cast/boxing, array/store, field, and discard behavior.
- [`TestExpressionModel`](../src/test/java/io/airlift/classfile/TestExpressionModel.java): symbolic descriptors and authoring-time validation.
- [`TestStructuredControlFlow`](../src/test/java/io/airlift/classfile/TestStructuredControlFlow.java): all structured control forms, labels, and exception exits.
- [`TestSyntheticExpressions`](../src/test/java/io/airlift/classfile/TestSyntheticExpressions.java): the public extension boundary.
- [`TestRuntimeDefiners`](../src/test/java/io/airlift/classfile/TestRuntimeDefiners.java): named/hidden loading, runtime data, generated hierarchy merges, target visibility diagnostics, and exact-identity method-handle adaptation.
- [`TestClassMetadata`](../src/test/java/io/airlift/classfile/TestClassMetadata.java): JDK classfile metadata types.
- [`TestRecordGeneration`](../src/test/java/io/airlift/classfile/TestRecordGeneration.java): generated Java records, component references and metadata, JDK object methods, constructor rules, validation, and nominal/hidden definition.
- [`TestCompactRecordConstructor`](../src/test/java/io/airlift/classfile/TestCompactRecordConstructor.java): compact constructor validation and normalization with implicit component assignment under nominal and hidden definition.
- [`TestClassKindsAndInterfaces`](../src/test/java/io/airlift/classfile/TestClassKindsAndInterfaces.java): first-class interface declarations, default/static/private methods, class initialization, nominal implementation bundles, and invalid interface shapes.
- [`TestParameterMetadata`](../src/test/java/io/airlift/classfile/TestParameterMetadata.java): reflected parameter names and flags, visible/invisible annotations, classfile attributes, and immutable method snapshots.
- [`TestClassFileDiagnostics`](../src/test/java/io/airlift/classfile/TestClassFileDiagnostics.java): source-like model rendering, JDK verification, final-byte disassembly, and dumps.
- [`TestTrinoWorkloadFixtures`](../src/test/java/io/airlift/classfile/TestTrinoWorkloadFixtures.java): distilled shapes from six complex Trino generator families.
- [`TestPageFunctionCompilerClassfile`](../src/test/java/io/airlift/classfile/TestPageFunctionCompilerClassfile.java): substantial PageFunctionCompiler-shaped projection and filter classes using Classfile.
- [`TestFlatHashStrategyCompilerClassfile`](../src/test/java/io/airlift/classfile/TestFlatHashStrategyCompilerClassfile.java): a legacy manual strategy/chunk class pair retained to cover explicitly authored multi-class bundles.
- [`TestCompiledUnit`](../src/test/java/io/airlift/classfile/TestCompiledUnit.java): forced-budget examples for expression and statement splitting, readable helpers, constructor pre-super extraction, warnings, constant-pool companions, and name-free nominal/hidden linkage.
- [`TestAutomaticFlatHashSplitting`](../src/test/java/io/airlift/classfile/TestAutomaticFlatHashSplitting.java): the de-chunked 2,001-field FlatHash acceptance workload and 10,000-field stress path.
- [`TestAutomaticRowConstructorSplitting`](../src/test/java/io/airlift/classfile/TestAutomaticRowConstructorSplitting.java): de-chunked, conditionally initialized row fields with scoped locals, automatic block extraction, and captured-local safety.

The PageFunctionCompiler fixture preserves the real generator's class-level shape:
captured fields, constructor initialization, private scalar evaluation, public bulk
processing, list/range selected positions, reusable selection storage, and bound
operator objects. It is self-contained rather than linked to Trino so this repository
can execute it in isolation.

Class construction is intentionally inline rather than hidden behind a fixture helper,
and the fixture exercises the runtime-data binding path used by Trino-style generators.

The legacy FlatHashStrategyCompiler fixture retains the former manual split between a
public strategy and generated static chunk methods. It covers a runtime-bound value
operator and debugging metadata, multiple explicitly authored classes in one loader,
unrolled column operations, fixed and variable flat storage, nulls, identity checks,
and equivalent block/flat hashes. New generators should instead author the natural
logical shape demonstrated by `TestAutomaticFlatHashSplitting` and let the compiler
choose physical helper and companion boundaries.

Its loading path is the normal multi-class shape:

```java
DefinedClasses classes = StandardClassDefiner.builder(parentLoader)
        .build()
        .defineClasses(List.of(chunk, strategy));

Class<? extends FlatHashStrategy> generated =
        classes.definedClass(strategy, FlatHashStrategy.class);
```

Definitions in one call are compiled with a shared binding-index context and loaded
through one generated class loader. For a physical unit containing hidden companion
classes, `HiddenClassDefiner.defineUnit(...)` returns a `DefinedUnit` with the lookup
for every hidden class and the logical primary class.

The Trino-shaped fixtures are self-contained adaptations of generator patterns. They
exercise the API without introducing a Trino dependency into this artifact.
