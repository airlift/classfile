# Java 25 Classfile Library Design

## Overview

Classfile is a modern Java 25 library for declaratively defining JVM classes and emitting
them through `java.lang.classfile`.

The library provides fluent expression ergonomics and reusable declarative code.
Class, member, scope, and control-flow APIs use modern Java records, sealed
hierarchies, pattern matching, and immutable finalized models where those constructs
fit their semantics. Source compatibility with `io.airlift.bytecode` is not a design
constraint.

The JDK classfile API remains the only emission backend. The custom model supplies
the reusable declarative structure, lexical binding, expression language, and
diagnostics that callback-oriented classfile builders do not provide.

The package is `io.airlift.classfile` and the Maven artifact is
`io.airlift:classfile`.

## Scope

The library provides:

- symbolic class, field, method, constructor, and class-initializer definitions;
- Java record definitions with symbolic component members and generated Java record
  behavior;
- fluent typed expressions;
- immutable finalized code blocks and high-level control structures;
- lexical variables, captures, scopes, and emission-time local slots;
- reusable DAG-shaped expressions and blocks;
- standard and hidden class definition;
- runtime data and bound constants;
- verification, rendering, and readable classfile dumps;
- JDK-only production code;
- substantial standalone examples and behavior tests.

The library does not provide:

- source compatibility with `io.airlift.bytecode`;
- a public model for every JVM instruction;
- ASM types, visitors, labels, or handles in the public API;
- byte-for-byte equivalence with ASM output;
- thread-safe builders or concurrent compilation guarantees;
- support for Java releases before 25.

## Design Standard

1. Preserve expression ergonomics, not historical method signatures.
2. Build a modern structural API rather than placing old Java patterns over the JDK
   classfile API.
3. Model source-level semantics, not stack operations or opcodes.
4. Close semantic categories by default and provide deliberate open extension edges.
5. Use records for immutable values and sealed types for closed semantic variants.
6. Preserve identity semantics for declarations and symbolic control targets.
7. Separate mutable construction from immutable finalized definitions.
8. Treat reuse as a first-class invariant, not an emitter optimization.
9. Assign slots, JDK labels, binding indexes, and constant-pool state only during
   emission.
10. Validate the complete declarative shape before emitting a classfile.
11. Prefer structured control flow while retaining a narrow symbolic-label escape
    hatch.
12. Use JDK symbolic descriptors directly and never require generated classes to be
    loaded during construction.

## Model Lifecycle

The lifecycle is:

```text
construction -> definition -> placement -> binding -> validation -> emission
```

### Construction

`ClassDefinition` and `MethodDefinition`, along with the remaining explicit builders,
are mutable, single-threaded assembly tools. They provide concise ordered construction
and reject locally provable errors. Mutable assembly objects are not DOM nodes;
generated member expressions capture their stable symbolic identity rather than
placing the assembly object in the tree.

### Definition

Calling `ClassDefinition.build()` produces an immutable declarative `ClassModel` and
immutable method snapshots. Expressions are immutable from creation. Finalized
blocks, control structures, and class models contain immutable collections and no
compiler state.

### Placement

Each parent-to-child edge is a placement. The same definition may have multiple
placements under one parent or under different compatible parents. The definitions
form a DAG; cycles are invalid.

### Binding

Each placement resolves lexical captures and independently binds declarations and
control targets owned by the placed definition. Binding state is external to the
definition.

### Validation

The completed method and class shape is validated once all lexical context is
available. Validation walks placements, not only unique node identities.

### Emission

The compiler translates each validated placement through fresh JDK builders. It owns
all slots, labels, exception ranges, constant-pool entries, runtime-binding indexes,
and temporary emission state.

## Immutable DAG Invariant

Definitions are reusable immutable DAG nodes. Placing or emitting a definition never
mutates it.

A reusable definition never stores:

- a parent pointer that grants exclusive ownership;
- an attached or emitted flag;
- a local-variable slot;
- a JDK label or label binding;
- a `ClassBuilder`, `MethodBuilder`, or `CodeBuilder`;
- a constant-pool entry or index;
- a runtime-binding index;
- a resolved capture environment;
- mutable compiler traversal state.

Repeated placement creates independent bindings for everything declared inside the
fragment. References captured from a lexical parent intentionally resolve to that
parent's binding.

## Symbolic Types

Use the JDK constant API as the canonical type and linkage model:

- `ClassDesc` for classes, arrays, fields, parameters, locals, and expression types;
- `MethodTypeDesc` for exact method, constructor, and dynamic call-site linkage;
- `MethodHandleDesc` and `DirectMethodHandleDesc` for symbolic handles;
- `DynamicConstantDesc<?>` for constant dynamic;
- `DynamicCallSiteDesc` for invokedynamic;
- JDK signature types for optional generic metadata;
- `AccessFlag` for access flags.

`Class<?>`, reflective `Field`, `Method`, and `Constructor<?>` overloads are useful
conveniences. They immediately derive authoritative symbolic descriptors and are
never the canonical stored representation.

Authoring code should use class literals for every loaded Java type. For example,
method returns, fields, parameters, and locals should normally use `void.class`,
`long.class`, and `String.class`, not `CD_void`, `CD_long`, and `CD_String`.
`ClassDesc` is exposed for generated or otherwise unloaded types and for code that
is intentionally constructing JDK linkage descriptors.

Invocation definitions carry the exact target `MethodTypeDesc`. Common loaded-type
forms infer that descriptor once from the return type and argument expressions.
Uncommon linkage, overload disambiguation, and generated/unloaded signatures use the
explicit `MethodTypeDesc` form instead of collection-shaped overloads.

## Builder And Overload Policy

### Builder mutation

A singular mutable authoring property is set-once, including properties with defaults. A
second call fails immediately rather than replacing the first value. This applies to
definition metadata, control-flow components, block descriptions, and runtime class
definition options.

Repeatable operations must be additive by contract. Names should make accumulation
clear, such as `append(...)`, `addInterface(...)`, `addException(...)`,
`addAnnotation(...)`, `caseValue(...)`, and `catching(...)`. Accessors and `build()`
snapshot operations may be called repeatedly because they do not alter builder configuration.
Registration operations are different: `FieldDefinition.Builder.build()` commits a
member to its owning class and therefore succeeds exactly once.

### Overloads

Overloads are admitted for demonstrated authoring value, not symmetry or anticipated
convenience. The default rules are:

- keep one canonical operation whose stored model uses JDK symbolic descriptors;
- retain `Class<?>` conveniences only at high-frequency authoring points where
  spelling `ClassDesc` conversion materially obscures ordinary code;
- retain reflective `Method`, `Field`, and `Constructor<?>` forms where consumers
  already possess those authoritative objects;
- retain generated-member forms where a definition must be referenced before its
  class exists;
- do not offer both varargs and collection forms without observed use of both;
- prefer distinct names or a builder when overloads represent different semantics,
  rather than different representations of the same value;
- remove speculative overloads that are not exercised by representative fixtures or
  a concrete Trino generator.

Declaration APIs retain `Class<?>` and `ClassDesc` pairs where they represent the
same operation. The `Class<?>` form is the normal authoring convenience; the
`ClassDesc` form supports the class currently being generated and other unloaded
types.

Field metadata is deliberately not represented by overloads. The two
`field(name, type)` entrypoints return a field builder. Access, generic signature,
constant value, and annotations are configured independently before `build()`
registers and returns the immutable field definition. This prevents combinatorial
`constantField(...)`, `annotatedField(...)`, and signature overloads while allowing
those features to be combined.

### Records

`ClassDefinition.defineRecord(...)` models a Java record rather than treating the
`Record` attribute as unrelated metadata. A built `RecordComponentDefinition`
registers the private final backing field and retains symbolic references to that
field and the public accessor. Component types use `ClassDesc`, so nominal generated
records can refer to unloaded generated types without loading them during authoring.

Finalizing a record synthesizes only the members that have not been declared with
their exact implied descriptors: component accessors, the canonical constructor,
and `equals`, `hashCode`, and `toString`. The object methods use private component
getters and delegate to `java.lang.runtime.ObjectMethods.bootstrap`, preserving the
same state semantics as Java source records even when an accessor is overridden.

The canonical constructor, whether synthesized or explicit, is validated as one
semantic unit. Its parameters match component order and names; it invokes
`Record()` once; and it initializes each component field exactly once, after the
super invocation, on an unconditional path, and on `this`. Other constructors
delegate directly to the canonical descriptor. No component-field setter is exposed;
the narrowly named `initialize(...)` expression exists only for canonical-constructor
authoring and is rejected elsewhere by completed-model validation.

Hidden records use the same logical model and JDK object-method implementation. A
bootstrap adapter substitutes the actual lookup class as the JDK bootstrap receiver,
avoiding a nominal self-class constant with the wrong hidden identity. A generated
type cannot appear as a hidden record component type because hidden classes are not
discoverable by symbolic name; target-aware linkage validation rejects that shape
before emission.

### Class kinds and interfaces

`ClassModel` carries a `ClassKind` rather than deriving source semantics from a
boolean or arbitrary access-flag combination. `CLASS`, `INTERFACE`, and `RECORD`
are therefore exhaustive choices for validation, rendering, planning, and
emission. The corresponding `ClassDefinition` factories establish invariant
superclass and access behavior; callers cannot create an interface by smuggling an
`INTERFACE` flag through an ordinary class definition.

Interfaces retain explicit method intent. Builders do not guess whether a method
with omitted flags was intended to be abstract or default. Completed-model
validation enforces interface field, method, constructor, and superclass rules. The
target-aware hierarchy resolver also supplies the interface-owner bit for static and
special invocation instructions, including generated interfaces in the same unit.

### Parameter metadata

`Parameter` remains the identity-bearing expression symbol used by the logical code
DOM. Its flags and visible/invisible annotations are mutable authoring metadata, but
`MethodDefinition.Model` captures an immutable metadata snapshot aligned with its
parameter list. This keeps previously built class models stable while allowing the
authoring definition to produce a later snapshot after more metadata is added.

Every non-empty method parameter list emits `MethodParameters`; annotation
attributes are emitted only when the corresponding visibility list contains an
annotation. Compact-constructor parameters gain `MANDATED` in the finalized method
model rather than by mutating their authoring symbols. Explicit and synthesized
canonical-constructor parameters retain ordinary parameter flags, matching `javac`.

Expression overloads are a separate ergonomics problem. They should be evaluated
against representative Trino call sites without forcing declaration API decisions
to apply mechanically to the expression language.

## Expression Model

### Ergonomic contract

Every expression:

- has a symbolic `ClassDesc` result type;
- composes as a child of another expression;
- supports the fluent operations appropriate for its type;
- can be assigned, evaluated, returned, or discarded as appropriate;
- renders deterministically in concise source-like form;
- participates in capture and type validation;
- can reference generated classes and members before they exist.

Variables and parameters are expressions. Typical code should remain as direct as:

```java
positions.getElement(index)
        .add(constantInt(7))
        .cast(CD_long)
        .multiply(constantLong(3))
```

API shapes are selected for clarity, safety, and consistency rather than migration
compatibility with `io.airlift.bytecode`.

### Fluent and static boundary

An operation belongs on `BytecodeExpression` when one existing expression is its
natural subject. This includes operators, casts and tests, instance fields and
methods, arrays, assignments, and adapters such as return, throw, and discard.
These operations are not duplicated as public static factories.

`BytecodeExpressions` is the public namespace for roots and operations without a
natural receiver: constants and runtime data, allocations, static fields and
methods, dynamic linkage, and balanced constructs such as `inlineIf`. Package-private
factory methods construct the core nodes used by fluent default methods; their
implementation role does not make them a second public authoring style.

Static methods must be invoked through `invokeStatic`. Calling a generated static
method through `receiver.invoke(...)` is invalid and never discards the receiver.
Constructor chaining is a method-body operation exposed by
`CodeBlock.Builder.invokeSuperConstructor(...)` and
`CodeBlock.Builder.invokeThisConstructor(...)`; object allocation remains the static
`newInstance(...)` expression root. The distinct names make the positional
constructor-initialization semantics visible instead of presenting chaining as a
general object invocation.

### Closed standard model and open extension edge

The intended hierarchy is conceptually:

```java
public sealed interface BytecodeExpression
        extends Statement
        permits CoreExpression, LocalValue, SyntheticExpression
{
    ClassDesc type();

    // Common fluent operations are default methods.
}

public non-sealed interface SyntheticExpression
        extends BytecodeExpression
{
    ExpressionPlan expansion(ExpansionContext context);
}
```

The extension boundary is:

- compiler-recognized core forms are package-private records in a sealed
  `ExpressionNode` hierarchy, held by a package-private final `CoreExpression`;
- parameters and variables form a package-private sealed `LocalValue` branch because
  they have identity semantics rather than structural record equality;
- application expressions implement one explicit non-sealed extension category;
- synthetic expressions lower into standard expressions and structured code;
- a synthetic expression never emits directly into a `CodeBuilder`;
- adding a synthetic expression does not require changing the compiler's exhaustive
  core-expression switch.

`ExpressionPlan` represents structured evaluation:

```java
public record ExpressionPlan(CodeBlock setup, BytecodeExpression value) {}
```

This single mechanism supports both important extension shapes:

- a domain expression with custom rendering and fluent helpers that lowers to a
  standard value expression;
- a macro expression that owns declarations, loops, branches, and setup before
  producing a value.

Expansion is functional: for the same expression and context it is deterministic,
has no externally visible side effects, and returns an immutable plan with the
declared result type. It must not depend on how many validation, diagnostic, sizing,
or emission passes request an expansion. Each placement of a synthetic expression
independently binds the plan's owned declarations and targets. Recursive or self
expansion is invalid.

## Declarations, Captures, And Scopes

Parameters, variables, and symbolic control targets are identity-bearing
declarations. They are not ordinary structural records: two declarations with the
same name and type in different scopes are different symbols.

Lexical scopes are structural:

- a method receiver and parameters form the root method scope;
- every block owns a child lexical scope;
- branches are sibling scopes;
- loop initializer declarations are visible to the condition, body, and update;
- loop-body declarations do not escape to the update or parent;
- every catch owns its exception declaration and child scope;
- a finally block is a child of the enclosing scope, not the try or catch body;
- switch cases are independent scopes; fallthrough is not modeled.

### Parent captures

A reusable block may reference variables or targets supplied by its lexical parent.
If placed repeatedly under that parent, every placement resolves those references to
the same parent declarations.

### Owned declarations

A reusable block may declare internal locals. Every placement receives an independent
binding and lifetime for those locals. Slots can be reused between non-overlapping
placements and sibling scopes.

### Cross-context reuse

A fragment capturing a declaration from method A cannot be placed in method B.
Classfile does not silently rebind captures by variable name and does not provide a
formal cross-method fragment-template API. Cross-method reuse is expressed with
ordinary Java factory methods that receive the required expressions and declarations.

## Code Blocks And Builders

`CodeBlock` is an immutable ordered definition. `CodeBlock.Builder` is its mutable
construction tool.

The intended distinction is:

```java
import static io.airlift.classfile.CodeBlock.block;
import static io.airlift.classfile.CodeBlock.blockBuilder;

CodeBlock.Builder builder = blockBuilder();
Variable index = builder.declare("index", constantInt(0));
builder.append(index.increment());
CodeBlock block = builder.build();
```

After `build()`:

- the block cannot change;
- changing or reusing the builder cannot affect the block;
- the block can be placed repeatedly;
- every declaration owned by the block is rebound for every placement;
- parent captures remain symbolic until placement validation and emission.

`block(statement...)` creates an immutable inline block when declarations are not
needed. `blockBuilder()` creates the mutable assembly tool. Both names are deliberately
safe and useful as static imports; a generic `builder()` factory is intentionally not
used for this high-frequency type. The design does not use copy-on-every-append
persistent lists, and ignoring an `append()` return value must not silently lose code.

`declare(name, initialValue)` infers the symbolic type and stores one initialized
declaration in the owning block. Explicit-type overloads declare uninitialized locals.
The initialized form renders as one source-like declaration, including in a loop
initializer, while emission still allocates and stores the local at that placement. When the
desired type differs from an initializer's exact type, declaration and assignment
remain two explicit operations rather than adding another overload family.

High-frequency terminal operations remain available on the builder:
`ret()`, `ret(value)`, `invokeSuperConstructor(...)`, and
`invokeThisConstructor(...)`. Constructor invocation is positional within a
constructor body, not an expression and not a general receiver operation. Code may
compute arguments, declare locals, branch, throw, and initialize fields declared by
the current class before chaining. Every reachable continuing path must invoke
exactly one superclass or sibling constructor before using the receiver normally or
returning.

The descriptor-explicit forms support generated and unloaded constructors. The
reflective superclass form additionally validates that the selected constructor's
owner is the generated class's direct superclass. Calls in loop bodies or protected
try/catch/finally regions are rejected because their exactly-once path semantics are
not represented safely by the structured validator. Raw-label paths remain a
JDK-verifier backstop rather than expanding this API into a bytecode data-flow DSL.

`ClassDefinition.defaultConstructor()` covers the common no-argument
constructor that invokes the configured superclass constructor and returns. It
returns the normal `MethodDefinition` so access remains a set-once property. The
superclass must be configured first because constructor linkage is exact.

## Structural Model

Classes and methods are represented by mutable authoring contexts because members are
created incrementally and referenced before the enclosing class is complete. They get
the concise domain names because these are the objects generator code uses throughout
authoring; they are not hidden behind nested `Builder` types.

The structural model uses:

- `ClassDefinition.define(type)` producing a mutable `ClassDefinition`;
- `ClassDefinition.build()` producing an immutable, validated `ClassModel` record;
- mutable `MethodDefinition` values returned by class member declarations;
- immutable `MethodDefinition.Model` snapshots owned by the completed `ClassModel`;
- immutable `FieldDefinition` symbols returned when fields are declared;
- parameter symbols with immutable identity, name, and type plus authoring metadata
  that is copied into immutable method snapshots;
- `CodeBlock.Builder` producing immutable `CodeBlock`.

The explicit class `build()` boundary snapshots the entire DOM and performs complete
validation before compilation or loading. A method snapshot is not a competing
top-level authoring abstraction: generated invocations continue to refer directly to
the mutable `MethodDefinition` identity established during assembly.

## Statements And Control Flow

Statements form a sealed hierarchy. Expressions are statements when evaluated for
side effects or discarded. Standard variants are immutable records where identity is
not required:

```java
sealed interface Statement
        permits BytecodeExpression, CodeBlock, IfStatement, ForLoop,
                WhileLoop, DoWhileLoop, SwitchStatement, TryCatch /* ... */
{
}
```

Walkers over the sealed `Statement`, `BytecodeExpression`, and `ExpressionNode`
hierarchies normally use exhaustive pattern switches with no `default` branch. This is
a compile-time maintenance boundary: adding a built-in variant must break every
emitter, validator, or renderer that has not made an explicit decision about that
variant. `StructuredDepth` is the deliberate exception: its allocation-light
recursive traversal and iterative overflow queue share one heterogeneous `Object`
dispatcher, which rejects an unknown value with `AssertionError`. Defaults otherwise
remain appropriate only when dispatching over genuinely open-world values such as
arbitrary constants or textual operators.

The ordinary authoring API emphasizes:

- expression evaluation and assignment;
- `if` and `if/else`;
- `while`, `do/while`, and `for`;
- structured switch without implicit fallthrough;
- try/catch/finally;
- returns and throws;
- loop break and continue;
- named structured exits where useful.

Control-flow builders name every structural component and finalize to immutable
values:

```java
import static io.airlift.classfile.CodeBlock.block;

IfStatement.builder().condition(condition).then(trueBlock).otherwise(falseBlock).build()
WhileLoop.builder().condition(condition).body(body).build()
ForLoop.builder().initialize(initializer).condition(condition).update(update).body(body).build()
```

Control flow deliberately has one public construction style. Positional factories
and constructors are not exposed because their unlabeled structural arguments are
difficult to scan and create a competing authoring model. Each component accepts a
single statement directly; callers use `block(...)` only for multiple statements or
owned declarations. A loop builder also establishes the identity used by its break
and continue targets before the immutable loop is built.

Control-flow builders follow the global set-once and additive-operation policy above.
Each builder also accepts one optional literal `description(String)`. Descriptions
render as source-like comments for model inspection and are never emitted as fake
line-number or debugger metadata.

Construction callbacks may be offered only as immediate builder conveniences. The
stored definition is always declarative data, never an emission callback.

## Symbolic Labels

Labels and jumps are supported as a discouraged advanced escape hatch because some
existing generators use shared merge and exit targets that are impractical to rewrite
immediately.

The public model exposes symbolic `CodeLabel` values, not JDK labels. A block builder
can create a label, append jumps that capture it, and bind it exactly once:

```java
CodeLabel end = body.label("end");
body.jump(end);
body.mark(end);
```

Rules:

- structured control flow is preferred in documentation and examples;
- no raw conditional branch opcode is public;
- label names are diagnostic only and do not establish identity;
- a label owned by a reusable block receives a fresh JDK label per placement;
- a fragment may capture a label owned by an enclosing compatible block;
- a captured parent label resolves to the same parent target from every placement;
- missing, duplicate, ambiguous, and cross-scope-invalid bindings fail DOM
  validation;
- stack-shape manipulation is not exposed merely because a label is available.

Labels remain in the root authoring package because they are occasionally necessary,
but structured statements are the normal control-flow API.

## Validation

### Construction-time validation

Builders and expression factories reject errors that need no parent context:

- invalid or void operand types;
- incompatible assignments;
- malformed invocation descriptors;
- duplicate declarations in one block;
- invalid control-object construction;
- obvious use before declaration within one builder;
- duplicate local label bindings.

### Completed DOM validation

Finalizing or compiling a method validates the complete placement graph:

- every variable capture resolves to a visible declaration;
- every control-target capture resolves to a valid target;
- child declarations do not escape their scope;
- declarations are not used before their placement;
- break and continue target appropriate enclosing structures;
- labels have valid and unambiguous binding points;
- return values match the method return type;
- constructors invoke exactly one valid `super(...)` or `this(...)` on every
  reachable continuing path before ordinary receiver use or return;
- every `this(...)` target exists in the completed class and constructor delegation
  is acyclic;
- constructor chaining does not occur in a repeated or protected region;
- class initializers satisfy their structural rules;
- record fields, accessors, canonical construction, secondary construction, and
  object methods satisfy Java record invariants;
- synthetic expansions satisfy the same capture and scope rules;
- repeated placements are validated independently;
- the definition graph has no cycles.

Compilation then performs target-aware linkage validation. Class headers, fields,
method descriptors, catches, constructor calls, locals, ordinary expression owners,
and dynamic linkage descriptors must resolve to accessible classes with the identity
selected by the target loader or lookup. Failures identify the generated class and
model path before class definition. Runtime-bound method handles are the deliberate
exception: their original signatures are input to target adaptation rather than
ordinary symbolic references.

The validator may memoize context-free properties, but it must not use a global
identity-based visited set for context-sensitive placement validation.

### Classfile validation

After emission, `ClassFile.verify` validates JVM structure, stack maps, exception
edges, and linkage constraints. Errors include the declarative model path, generated
class and method, and a readable JDK class-model disassembly when available.

## Rendering And Diagnostics

Diagnostics have two deliberately separate views:

- `ClassModel.toString()` renders the immutable declarative model in concise,
  source-like form, including record components, fields, methods, control
  descriptions, and expressions;
- `ClassFileDiagnostics.disassemble(bytes)` parses the emitted bytes with the JDK
  classfile API and renders classfile metadata and instructions;
- `ClassFileDiagnostics.verify(bytes)` exposes the JDK verifier result;
- `ClassFileDiagnostics.dump(root, bytes)` writes both the exact `.class` file and a
  neighboring `.class.txt` disassembly.

Classfile does not provide a second structural dump format, visitor-based debug
tools, synthetic source lines, or debugger-source emulation. Source-like DOM
rendering explains the authored program, while final-byte disassembly explains the
actual classfile without pretending generated bytecode is Java source.

## Emission

One internal compiler pattern-matches over the sealed standard model and emits
directly through JDK classfile builders. There is no public visitor and no second
instruction DOM.

Each method emission owns:

- a lexical environment stack;
- placement-specific variable bindings;
- one-slot and two-slot local allocators;
- placement-specific control-target bindings;
- loop and finally control stacks;
- exception ranges and handler labels;
- runtime-binding collection;
- diagnostic model paths.

Slots released at lexical scope exit may be reused. A parent-captured variable keeps
its parent lifetime. Internal declarations in repeated fragment placements receive
independent lifetimes even though they share symbolic definition identities.

## Physical Planning

`compileUnit(...)` derives a physical program from the validated logical model. The
physical program may contain expression helpers, statement helpers, and synthetic
companion classes that do not appear in the authoring DOM.

Physical planning obeys the same immutability and placement rules as emission:

- it never mutates a `ClassModel`, `CodeBlock`, expression, declaration, or control
  target;
- it expands and analyzes every placement independently;
- it carries captured locals through generated helper parameters rather than storing
  physical bindings in logical nodes;
- it preserves Java evaluation order, short-circuiting, exceptions, side effects,
  constructor initialization rules, and lexical values;
- it treats hard classfile limits as correctness requirements and JIT thresholds as
  optimization targets;
- it reports the exact emitted physical topology and resource sizes in the resulting
  `CompiledUnit`.

Generated companion classes are linked through runtime-bound method handles. Their
symbolic names are diagnostic identities, not runtime linkage requirements. This
allows the same physical plan to be defined as nominal classes or as independently
named hidden classes.

The splitting algorithms, admission rules, limits, reports, and failure modes are
documented in [`AUTOMATIC_CODE_SPLITTING.md`](AUTOMATIC_CODE_SPLITTING.md).

## Runtime And Loading

The runtime layer supports:

- standard named classes through a dedicated defining loader;
- multiple mutually visible generated classes reserved as one batch and then defined in
  dependency order; already-defined JVM classes cannot be rolled back after a later
  definition failure;
- hidden classes through `Lookup.defineHiddenClass`;
- hidden classes with class data through `Lookup.defineHiddenClassWithClassData`;
- immutable runtime data containing optional class data and numbered bindings;
- loader-backed runtime data for standard classes, since the JVM has no equivalent
  standard-class class-data API;
- runtime-bound object constants and dynamic call sites;
- runtime-bound method-handle invocation with automatic target-specific signature
  adaptation;
- generated-bundle hierarchy resolution for JDK stack-map generation;
- pre-definition accessibility and exact class-identity validation;
- explicit initialization and superclass validation;
- readable verification and definition failures.

The direct standard-class path accepts finalized class models. A single model returns
`Class<?>` or a supertype-checked `Class<? extends T>`. Multiple models return
`DefinedClasses`, which supports fail-fast lookup by `ClassModel` or canonical
`ClassDesc` and optional supertype validation. It does not expose the defining
loader's internal byte-array map.

Bundle compilation produces `CompiledClassBundle`. All definitions in the bundle
share one binding collector and one `RuntimeData`, so binding indexes remain valid
when several generated classes use runtime constants. The bundle retains caller list
order, and the standard definer uses that order when eagerly initializing independent
classes.

Automatic physical planning produces a `CompiledUnit`. Both standard and hidden
definers accept this result and return a `DefinedUnit` containing the primary class,
all physical classes, and hidden-class lookups when applicable. The unit records
dependency order, so generated companion methods are resolved before callers that
receive their method handles.

Explicit precompiled entrypoints remain available, but
`ClassCompiler.forTarget(...)` is the only compiler entrypoint. There is no
system-loader default: callers identify the loader or lookup that will define the
bytes before compilation.

Compilation is parameterized by a `CompilationTarget` created from the actual
defining class loader or hidden-class host lookup. The target supplies the fallback
JDK hierarchy resolver and accessibility rules. Bundle-defined interfaces and
superclasses are merged ahead of that resolver, so sibling branch merges work before
any generated class has been loaded.

Every compiled artifact retains its target. Runtime definers reject artifacts from a
different defining loader or lookup environment before installing bytes or runtime
data. Hidden targets additionally require a full-privilege lookup and generated types
in the lookup class's package before emission.

`boundMethodHandle(handle).invoke(arguments...)` retains the original logical handle
in the immutable expression. At emission, each inaccessible reference type, or a
reference type that resolves to a different `Class<?>` identity in the target, is
replaced by `Object`; argument-side reference types are also erased when the authored
expression does not carry the exact handle parameter type. The handle is adapted with
`MethodHandle.asType`, identity-deduplicated for the target signature, and invoked
with the adapted exact descriptor. The same expression can therefore compile to
different physical linkage in named and hidden classes without mutation.

Named classes obtain bindings from their dedicated defining loader. Hidden classes
obtain the same immutable runtime-data shape through class data. This distinction is
selected by the definer rather than exposed in expression authoring.

Hidden-class definition is deliberately not represented by `DefinedClasses`. Hidden
classes are not discoverable by loader name, repeated definitions with the same
nominal descriptor have distinct VM identities, and a `MethodHandles.Lookup` is
required for privileged access. Direct single-class definition therefore returns a
lookup, while unit definition returns `DefinedUnit` with a lookup for every physical
hidden class.

Builders and compilers are not required to be thread-safe. A fully built runtime
definer may be reused concurrently. Each definition call keeps its linkage state
local, while the nominal definer protects its pending classfiles and runtime bindings
with the generated class loader monitor. A failed definition releases only classfiles
from that operation that remain pending; JVM classes already loaded or initialized
cannot be rolled back.

## Dependencies

The implementation is JDK-only and the artifact has no production dependencies.

Guardrails must reject:

- third-party imports in main sources;
- ASM or `io.airlift.bytecode` types in public signatures;
- `Class.forName` for symbolic type or local-slot modeling;
- public low-level instruction wrappers or visitor APIs.

## Executable Specification

The test suite is the executable specification for this design. Every documented
model is compiled, loaded, and executed rather than represented only by unchecked
snippets.

The principal suites are:

- `TestClassfileWalkingSkeleton` for complete classes, members, lexical scopes, and
  repeated placement;
- `TestConstructorInitialization` for superclass and sibling constructor invocation,
  pre-invocation processing, and path-sensitive validation;
- `TestExpressionParity`, `TestExpressionBehaviorMatrix`, and `TestExpressionModel`
  for fluent authoring, runtime behavior, and exact rendering;
- `TestStructuredControlFlow` for structured statements, symbolic labels, and
  non-local `finally` exits;
- `TestSyntheticExpressions` for the open expression extension boundary;
- `TestRuntimeDefiners` and `TestCompiledUnit` for target-aware compilation, nominal
  and hidden definition, runtime data, physical planning, and generated linkage;
- `TestRecordGeneration`, `TestCompactRecordConstructor`, and
  `TestClassKindsAndInterfaces` for records and interface semantics;
- `TestAutomaticFlatHashSplitting` for large-method and companion-class behavior;
- `TestAutomaticRowConstructorSplitting` for scoped control-flow block extraction and
  captured-local safety.

[`EXAMPLES.md`](EXAMPLES.md) provides links and a complete catalog.
