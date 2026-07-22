# Classfile Documentation

Classfile is a high-level declarative Java 25 library for generating, inspecting,
and defining JVM class files. The library builds a reusable logical model and emits
it through the JDK Class-File API without exposing an instruction DOM or visitor API.

## Start Here

Choose the document that matches the task:

| Document | Use it for |
| --- | --- |
| [`USER_GUIDE.md`](USER_GUIDE.md) | Authoring classes, fields, methods, expressions, control flow, and loading generated code |
| [`MIGRATION_GUIDE.md`](MIGRATION_GUIDE.md) | Moving a generator from `io.airlift.bytecode` to `io.airlift.classfile` |
| [`AUTOMATIC_CODE_SPLITTING.md`](AUTOMATIC_CODE_SPLITTING.md) | Understanding physical planning, method splitting, companion classes, reports, and limits |
| [`DESIGN.md`](DESIGN.md) | Understanding the semantic model, scope and reuse rules, validation, emission, and runtime architecture |
| [`EXAMPLES.md`](EXAMPLES.md) | Finding executable examples and the tests that specify each feature |
| [`DEVELOPMENT.md`](DEVELOPMENT.md) | Building, formatting, and contributing to the project |

For a first use of the API, read the user guide and keep the examples index open. For
an existing Bytecode generator, start with the migration guide. Physical helper
methods and classes are normally automatic; consult the splitting guide when limits,
warnings, or generated stack frames matter.

## Model And Runtime

Classfile separates the logical program from its physical representation:

```text
ClassDefinition and MethodDefinition
                 |
                 v
        immutable ClassModel
                 |
                 v
   target-aware compilation and planning
                 |
                 v
           CompiledUnit
                 |
                 v
 nominal or hidden class definition
                 |
                 v
            DefinedUnit
```

The logical model contains source-like classes, members, expressions, lexical
variables, and structured control flow. It is reusable: the same block or expression
can be placed more than once without acquiring parent, slot, label, or constant-pool
state.

Compilation is parameterized by the class loader or hidden-class lookup that will
define the result. This allows accessibility and class identity to be validated
before loading. Physical planning can introduce helper methods and companion classes
without mutating the logical model.

The runtime supports both nominal and hidden classes. Runtime-bound constants and
method handles use loader data for nominal classes and JDK class data for hidden
classes. A `CompiledUnit` records the complete physical topology and definition order
so callers normally retrieve the primary class without managing generated helpers.

## Core Guarantees

The architectural guarantees are:

- production code is JDK-only and requires Java 25 or newer;
- generated and unloaded types use JDK symbolic descriptors rather than `Class<?>`;
- variables and parameters are fluent bytecode expressions;
- finalized expressions, blocks, statements, and class models are reusable values;
- local slots, JDK labels, runtime binding indexes, and constant-pool state are
  assigned during compilation or emission, never authoring;
- structured control flow is preferred, with symbolic labels retained as an advanced
  escape hatch;
- complete models are validated before emission and emitted classfiles are verified
  with the JDK Class-File API;
- nominal and hidden definitions use the same logical and physical planning model;
- automatic splitting favors runnable valid code and reports missed optimization
  targets without turning them into correctness failures.

The detailed semantics and invariants are defined in [`DESIGN.md`](DESIGN.md).

## Executable Specification

The examples are JUnit tests that compile, load, and execute generated classes.
[`EXAMPLES.md`](EXAMPLES.md) indexes the complete suite, including:

- full classes, interfaces, records, constructors, and metadata;
- fluent expressions and exact source-like rendering;
- lexical scope, slot reuse, and repeated DAG placement;
- every structured control-flow form and exception exit;
- nominal and hidden loading, class data, and runtime-bound handles;
- Trino-shaped projection, filter, state, join, lambda, input-reference, and flat-hash
  generators;
- automatic expression and statement splitting, constant-pool sharding, and large
  FlatHash stress cases.
