# Automatic Code Splitting

Automatic code splitting converts a logical `ClassModel` into a loadable
`CompiledUnit` whose methods and constant pools fit JVM limits. Authors write the
natural expression and statement structure. They do not choose chunk sizes, declare
split helpers, or name companion classes.

Splitting is part of `ClassCompiler.compileUnit(...)`:

```java
CompilationTarget target = CompilationTarget.forLookup(MethodHandles.lookup());
CompiledUnit unit = ClassCompiler.forTarget(target)
        .compileUnit(logicalClass);

DefinedUnit defined = HiddenClassDefiner.builder(MethodHandles.lookup())
        .build()
        .defineUnit(unit);
Class<?> generatedClass = defined.primaryClass();
```

The compiler may add private helper methods and synthetic companion classes. These
physical artifacts are implementation details. The logical model is not modified,
and the generated code preserves Java evaluation order, exceptions, side effects,
short-circuiting, and lexical variable values.

This document explains the physical planning performed by `compileUnit(...)`.

## Why Splitting Is Automatic

Generated code often repeats a small operation hundreds or thousands of times. A
flat-hash strategy, for example, can read, write, compare, and hash every field in a
row. The natural logical method is easy to understand:

```java
Variable hash = method.body().declare("hash", constantLong(1));
for (int field = 0; field < fieldCount; field++) {
    method.body().append(hash.set(invokeStatic(
            TestAutomaticFlatHashSplitting.class,
            "combineHash",
            long.class,
            hash,
            values.getElement(field))));
}
method.body().ret(hash);
```

Without splitting, the generator author must discover JVM limits experimentally,
choose a fixed chunk size, write dispatch methods, carry local values between chunks,
and repeat the same design for every generated operation. A fixed chunk size is also
fragile: changing one field operation changes the emitted bytecode and can invalidate
the chosen boundary.

With automatic splitting, the compiler can turn the method into a physical shape
similar to:

```java
hash = hash$statements$1(values, hash);
hash = hash$statements$2(values, hash);
hash = hash$statements$3(values, hash);
return hash;
```

If the generated helpers put too much pressure on one class, they are moved into
synthetic companion classes. Calls to those companions are linked with method handles
rather than symbolic generated-class references.

## Compilation Pipeline

`compileUnit(...)` performs four physical-planning steps in a fixed order:

1. Split large expressions into static helper methods.
2. Split eligible top-level statement sequences into static helper methods.
3. Move generated helpers into companion classes when helper count or constant usage
   puts pressure on the primary class.
4. Emit every physical class, measure the exact classfiles, optionally re-emit a
   hidden-lambda owner to preserve a JIT boundary, enforce the configured hard method
   limit, and produce a `CompilationReport`.

The first two steps use conservative size estimates to choose candidates. The final
step parses the emitted classfiles with the JDK Class-File API and records exact code,
stack, local, constant-pool, bootstrap, field, and classfile sizes.

Planning is deterministic for a logical model, compilation policy, target, and JVM
configuration. A change to any of those inputs may produce a different physical plan.

## Logical And Physical Models

The authoring API produces a logical model:

- `ClassModel` describes a class;
- `MethodDefinition.Model` describes a method;
- `CodeBlock` and structured statements describe control flow;
- `BytecodeExpression` describes values and side effects.

The compiler derives physical models containing generated helpers. It creates new
objects when rewriting a model and never writes planner state into the logical DOM.
This matters because blocks and expressions form a DAG rather than an ownership tree.
The same block can be placed multiple times, even in different methods. Each placement
is expanded and planned independently with the locals and target visibility available
at that location.

The physical result is a `CompiledUnit`. It contains:

- the logical primary type;
- one classfile artifact for each physical class;
- runtime data and generated-method links for each artifact;
- dependency-safe class definition order;
- exact measurements and warnings in a `CompilationReport`.

Use `compileUnit(...)` when automatic class sharding, physical-plan inspection, or
hidden-class definition is required. The narrower `compileClass(...)` and
`compileClassBundle(...)` entry points perform expression and statement planning but
do not produce a `CompiledUnit`, class sharding, or a compilation report.

## Compilation Policy

`CompilationPolicy` separates a JVM correctness limit from optimization targets:

```java
CompilationPolicy policy = CompilationPolicy.builder()
        .hardMethodCodeLimit(65_535)
        .targetMethodCodeLimit(7_200)
        .build();

CompiledUnit unit = ClassCompiler.forTarget(target)
        .policy(policy)
        .compileUnit(logicalClass);
```

The builder is primarily useful for tests and runtime-specific tuning. Each builder
setting can be supplied once.

### Hard Method Limit

`hardMethodCodeLimit` is the largest emitted `Code` attribute accepted by the
application. Its default is 65,535 bytes, the classfile limit.

After emission, the compiler compares every exact method size with this limit. A
method above it causes `CompilationException`; it is never returned as a warning.
This check applies to `compileClass(...)`, `compileClassBundle(...)`, and
`compileUnit(...)`.
The JDK Class-File API and verifier remain the backstop for other bounded classfile
structures, including descriptors and constant pools.

Lower hard limits are useful in tests because they exercise splitting without
creating enormous fixtures.

### Target Method Limit

`targetMethodCodeLimit` is the desired upper bound used by the expression and
statement planners. Exceeding it is not a correctness failure.

The default policy reads HotSpot's `DontCompileHugeMethods` and `HugeMethodLimit`
options through the diagnostic MBean. When HotSpot declines to optimize huge methods,
the target is 90 percent of `HugeMethodLimit`. If the option cannot be read, the
policy uses an 8,000-byte fallback and records that fact. When huge-method compilation
is enabled, the target is the JVM hard method limit.

A method that remains above the target but below the hard limit is emitted and
reported with a `HUGE_METHOD` warning. Runnable code is preferred over failing because
an optimization could not be applied safely.

### Inline Sizes

The policy also records HotSpot's `MaxInlineSize` and `FreqInlineSize`, with defaults
of 35 and 325 bytes. These values are included in the effective policy for inspection
and experimentation. They do not currently cause additional splitting. Splitting
large generated methods into hundreds of tiny methods merely to meet an inline limit
would generally increase call overhead without guaranteeing that HotSpot will inline
them.

### Hidden Lambda JIT Boundaries

Hidden-safe LambdaMetafactory linkage adds an inlineable adapter between the consumer
and a same-owner implementation method. Small implementation methods must remain
inlineable so captured lambda instances can be scalar-replaced, but allowing a larger
target to inline through the adapter can inflate the consumer's C2 graph.

The compiler therefore emits and parses the class once to measure exact implementation
method sizes. A non-constructor target in the upper half of the range between
`MaxInlineSize` and `FreqInlineSize` receives harmless load/pop padding that moves it
one byte beyond `FreqInlineSize`; the class is then emitted, parsed, measured, and
verified again. Smaller targets and targets already above `FreqInlineSize` are not
re-emitted. This is a JIT-boundary adjustment, not statement splitting, and the final
measured classfile is the one recorded in `CompilationReport`.

The generated hidden type itself cannot be the functional interface returned by a
same-owner LambdaMetafactory site. Such a site is rejected during compilation; define
the functional interface nominally and use the hidden class only for its implementation.

## Expression Splitting

Expression planning walks every method body except class initializers. It recursively
rewrites expressions from their children upward. The expression budget is:

```text
max(32, targetMethodCodeLimit / 2)
```

When an eligible non-void expression exceeds that estimate, the planner extracts it
to a private static synthetic method. Locals referenced by the expression become
helper parameters, in stable encounter order, and the original expression is replaced
with a helper invocation.

For this logical expression:

```java
method.body().ret(input.add(constantInt(1))
        .add(constantInt(2))
        .add(constantInt(3)));
```

an intentionally small test policy may produce a physical shape like:

```java
private static int evaluate$expression$1(int input) { ... }

return evaluate$expression$1(input) + 3;
```

The exact boundary is not a public contract.

### Eligible Expressions

The planner can extract value-producing forms including:

- arithmetic, comparison, and boolean operators;
- unary operations and casts;
- `instanceof`;
- inline conditionals;
- array length, reads, writes, and construction;
- field reads and writes;
- method, method-handle, dynamic, and linked-method invocations;
- object construction.

Constants, dynamic constants, bound constants, assignments, increments, return/throw
adapters, and other void adapters are not extracted as roots. Their child expressions
are still visited and may be extracted.

### Evaluation Order And Short-Circuiting

Extraction does not reassociate operators. The planner retains the original tree and
replaces one subtree with a call at the same position. Receiver and method arguments
therefore remain left-to-right.

The right side of `&&` and `||`, and the true and false values of an inline
conditional, remain inside their original conditional position. A helper invocation
for one of those children executes only when the original child would have executed.
This preserves short-circuit behavior, side effects, and exceptions.

### Constructors

Expressions in constructors can be split, including calculations performed before
the superclass constructor invocation. The generated helper is static, so it is legal
before `super(...)`.

Completed-model validation rejects expressions that capture the receiver before
`super(...)` or `this(...)`. Expressions after receiver initialization may pass `this`
to a static helper without moving the helper call across the constructor invocation.
Constructor statement sequences are not extracted by the statement planner.

### Synthetic Expressions

A `SyntheticExpression` is expanded while planning. The planner verifies that the
expanded value has the declared type and snapshots the planned expansion in the
physical model.

Synthetic expressions must be functional: repeated expansion with the same context
must describe the same setup and value. Validation, planning, diagnostics, and
emission may request an expansion more than once. Recursive synthetic expansion is
rejected.

A synthetic expression with setup statements remains constrained by those statements.
The compiler does not move non-empty setup into a value helper when doing so would
separate setup from the value it prepares.

### Expression Limits

An expression is left in place when:

- its captured locals consume more than 255 JVM parameter slots;
- it is not an eligible value-producing root;
- no child or enclosing expression provides a safe extraction boundary.

`long` and `double` captures consume two slots. The 255-slot check applies to the
physical helper descriptor and is independent of lexical local-slot reuse.

## Statement Splitting

Statement planning runs after expression splitting. It considers an ordinary method
only when the estimated method body exceeds the statement-planning budget:

```text
max(64, targetMethodCodeLimit * 3)
```

The model estimate is intentionally conservative and normally larger than emitted
bytecode. The whole-method trigger uses three times the target; once splitting is
necessary, helper grouping uses the tighter two-times-target budget. The additional
trigger headroom avoids splitting an ordinary emitted method that is already below
the target, while the tighter grouping budget keeps generated helpers below it.
Constructors, class initializers, and generated expression helpers are skipped.

The planner scans the top-level statements in the method body. It recognizes
sequential expression regions, ordered early-boolean-return regions, and eligible nested
`CodeBlock` regions. Other structured statements remain in place, although their
nested expressions may already have been split by the expression pass.

### Sequential Expression Regions

A candidate region contains at least two consecutive expression statements. Return,
throw, declarations, labels, jumps, and structured control-flow statements terminate
the region.

All locals referenced by the region become helper parameters. A region is extracted
when it writes:

- no local variable; or
- one local variable whose final value can be returned to the caller.

For a single written local, the helper initializes a local copy from the incoming
parameter, executes the sequence, returns the final value, and the caller assigns the
result back to the original local:

```java
result = evaluate$statements$1(input, result);
```

Heap writes and calls do not need an explicit output because their effects are already
visible to the caller.

A candidate is not extracted when its captured locals exceed 255 parameter slots. A
sequence that writes more than one distinct local is divided at the first safe
boundary when possible; a remaining non-trailing multi-output region stays in the
original method.

The planner groups estimated regions up to:

```text
max(64, targetMethodCodeLimit * 2)
```

Expression splitting has already reduced large value trees before this grouping pass.
The estimate-to-code ratio leaves headroom for structured control flow and bound
runtime data, whose emitted instruction sequences can be larger than the logical
model suggests. The final emitted size, rather than the estimate, determines whether
the hard limit is satisfied.

### Scoped Block Regions

Consecutive top-level `CodeBlock` values may be extracted together when every block
completes normally. An eligible block may contain expressions, declarations,
comments, nested blocks, and `if/else` statements composed from the same forms.

Locals declared by the moved blocks remain ordinary scoped locals in the helper.
References to parameters and outer locals become helper parameters. A region may
assign several initialized outer locals when at most one incoming value is read after
the region before being overwritten. The helper returns that one live result to the
caller. Other written locals become helper-local scratch values because the caller
overwrites them before any read. Conditional writes and control transfers are treated
conservatively; a value is dead only when every path overwrites it before a read.
Regions that assign an uninitialized outer local or produce more than one live local
remain in place. Mutations visible through a captured reference, such as filling an
array or invoking a mutating method, do not need a returned value and remain eligible.

This form supports generators that construct one independently scoped block per
field. Authors can retain field-local declarations and conditional setup without
manually grouping fields into helper methods. Loops, exception regions, switches,
explicit jumps, and non-local exits terminate eligibility.

### Trailing Multi-Output Continuations

A trailing sequence of eligible scoped blocks may modify several initialized outer
locals when the sequence ends in the method's value return. The planner divides the
sequence into bounded helpers. Each helper receives the live values, updates local
copies, and returns the result of invoking the next helper. The final helper evaluates
the original return expression:

```text
method -> continuation3(hash, offset)
              -> continuation2(hash, offset)
                    -> continuation1(hash, offset)
                          -> final result
```

This carries multiple values without a tuple, array, record, or other allocation.
It preserves statement order and applies only to normally completing blocks directly
before a value return. A non-trailing region, a void method, an uninitialized output,
unsupported control flow, an owner-typed hidden-class local, or a descriptor above
255 slots remains in the logical method.

The compiler bounds the continuation chain to 256 helpers. If an unusually small
target would create a deeper chain, adjacent regions are coarsened while retaining a
conservative estimate below the hard method limit. Compilation fails with a planning
diagnostic when no bounded allocation-free plan fits. This bounds compiler-added
frames; it does not guarantee safety for every caller depth or Java stack size.

### Repeated Early Boolean Returns

Generated equality and filter methods commonly contain a sequence such as:

```java
if (firstMismatch) {
    return false;
}
if (secondMismatch) {
    return false;
}
if (thirdMismatch) {
    return false;
}
return true;
```

Two or more consecutive top-level statements with exactly this shape can be moved to
a boolean continuation helper. The same extraction applies to the symmetric
`if (condition) return true` form, and when each top-level statement is a scoped
`CodeBlock` containing local declarations and one or more early returns of the same
boolean value. This lets a generator retain single-evaluation locals around each
comparison without authoring physical helper boundaries.

Block-local declarations remain local to the helper. Parameters and read-only outer
locals become helper parameters. A scoped early-return region is not extracted when it
writes an outer local, mixes `true` and `false` returns, contains another return value,
or contains unsupported control flow. The helper evaluates complete blocks in order,
returns the authored boolean value on the first early return, and returns the opposite
value when all checks pass. The caller retains one matching early-return test:

```java
if (!identical$conditions$1(arguments)) {
    return false;
}
```

The transformation preserves declaration scope, evaluation and exception order, and
early return behavior.

### Structured Control Flow

The statement planner does not independently extract an arbitrary `if`, loop,
`switch`, `try`, catch, or `finally` region. An `if` may move as part of an eligible
normally completing scoped block or an ordered scoped early-boolean-return region. Explicit
labels, jumps, `break`, and `continue` remain in the logical method, while expression
planning can still reduce large conditions, selectors, updates, and values nested
inside them.

This conservative boundary avoids inventing a protocol for non-local exits or moving
code across exception handlers. A large method dominated by one indivisible control
structure may remain above the target and produce a warning, or may fail if it exceeds
the hard limit.

## Companion Classes

Generated helpers normally remain in the primary physical class. `ClassSharder` moves
them into synthetic companion classes when either of these planning thresholds is
exceeded:

- more than 96 generated helpers in one class;
- more than 20,000 estimated constant references across generated helpers.

Movable helpers are grouped into companions containing at most 96 helpers and at most
20,000 estimated constant references. Companion types use a readable source-derived
name such as `GeneratedStrategy$Generated1`.

These thresholds are conservative planning values, not JVM limits and not public
binary compatibility promises.

### Movability

A helper can move only when it is independent of caller identity and of private state
owned by the logical class. The sharder pins a generated helper that contains:

- a field access owned by the logical class;
- construction of the logical class;
- a dynamic constant or manually authored `invokedynamic` call site;
- an invocation of an ordinary method owned by the logical class;
- `invokespecial`;
- a JDK caller-sensitive operation, including operations owned by `MethodHandles` or
  `StackWalker`;
- a constructor invocation;
- a try/catch region;
- a label, jump, `break`, or `continue`.

Movability is checked recursively through nested statements and synthetic-expression
expansions.

For hidden-class compilation, a helper is also pinned when its descriptor, local
types, expression metadata, or constant descriptors refer symbolically to the logical
hidden class. Hidden classes cannot be resolved by their authored binary name from a
separately defined companion. Helpers with authored generic signatures, exceptions,
or annotations are conservatively pinned because those metadata structures can carry
the same kind of symbolic reference.

Dynamic linkage is pinned because the JVM supplies the physical instruction owner as
the bootstrap `Lookup`. Compiler-managed bound constants, bound method handles, and
generated linked-method calls remain movable: they are not manually bootstrapped
`invokedynamic` instructions, and their runtime data is attached to the physical class
that receives them. `classData()` is currently represented as a dynamic constant and
is therefore conservatively pinned as well.

Class sharding currently requires the complete generated-helper family to be movable.
If one helper is pinned, the family remains in the primary class. This avoids creating
a primary-to-companion-to-primary linkage cycle without a more complex mutable
linkage protocol.

### Name-Free Linkage

A call between generated physical classes is not emitted as a symbolic classfile
reference. Instead, the caller contains a runtime binding slot for a typed
`MethodHandle`.

`CompiledUnit` records each `LinkedMethod` dependency and computes a definition order
in which dependencies precede callers. A definer then:

1. defines a dependency;
2. resolves its generated method handles;
3. places those handles in the caller's runtime data;
4. defines the caller;
5. exposes the logical primary class through `DefinedUnit`.

This works for both nominal and hidden classes. Hidden companions do not need stable,
name-resolvable identities. The same physical linkage model is used by
`StandardClassDefiner` and `HiddenClassDefiner`.

A generated linkage cycle is rejected while constructing the `CompiledUnit`.

## Helper Names And Stack Traces

Generated method names are derived from the logical method:

- `evaluate$expression$1` for an expression helper;
- `evaluate$statements$1` for a sequential statement helper;
- `identical$conditions$1` for an early-boolean-return continuation helper.

Characters that are not Java identifier parts are replaced with underscores, so a
constructor helper begins with `_init_$expression$`.

Names are deterministic and intentionally readable because generated helpers appear
in stack traces and profiles. They identify the logical source method and kind of
split. Their numeric suffixes, class placement, and exact spelling remain physical
implementation details and can change when the model, policy, runtime, or compiler
changes.

Descriptions attached to logical blocks remain useful in logical dumps. They do not
currently participate in helper naming.

## Reports And Warnings

Every `CompiledUnit` has a `CompilationReport`:

```java
CompilationReport report = unit.report();

for (CompilationReport.ClassInfo classInfo : report.classes()) {
    System.out.printf("%s: %,d bytes, %,d constants%n",
            classInfo.type().displayName(),
            classInfo.classfileBytes(),
            classInfo.constantPoolCount());

    for (CompilationReport.MethodInfo method : classInfo.methods()) {
        System.out.printf("  %s%s: %,d code bytes%n",
                method.name(), method.type().descriptorString(), method.codeBytes());
    }
}
```

Class measurements include:

- total classfile bytes;
- constant-pool count;
- bootstrap-method count;
- field count;
- all emitted methods.

Method measurements include:

- name and descriptor;
- exact `Code` attribute length;
- maximum operand-stack depth;
- maximum local slots.

The compiler currently emits two warning categories:

- `JIT_THRESHOLD_FALLBACK` when HotSpot's huge-method limit is unavailable and the
  default policy uses the fallback;
- `HUGE_METHOD` when an emitted method exceeds `targetMethodCodeLimit` but remains
  within `hardMethodCodeLimit`.

Warnings do not prevent definition. Applications that require a stricter performance
policy can reject selected warnings before loading:

```java
unit.report().warnings().stream()
        .filter(warning -> warning.category() == CompilationWarning.Category.HUGE_METHOD)
        .findFirst()
        .ifPresent(warning -> {
            throw new IllegalStateException(
                    warning.location() + ": " + warning.message());
        });
```

## Failure Boundaries

Automatic splitting handles large generated Java-shaped code, but it does not make
all possible logical programs representable.

### Method Descriptor Limits

The compiler does not pack a method's declared parameters. A logical method whose
descriptor exceeds the JVM's 255-slot limit is invalid. Helper extraction is also
skipped when captured locals would create a helper descriptor above that limit.

### Non-Trailing Multiple Live Outputs

A sequential statement or non-trailing scoped-block helper carries at most one
modified local value back to its caller. A trailing scoped-block sequence ending in
the method's value return can instead use the allocation-free continuation chain
described above. Other regions that must return several independently modified locals
are not moved as one unit. The planner may still find smaller zero-output or
one-output regions around them.

### Indivisible Control Flow

Whole loops, exception regions, switches, and arbitrary jumps are not extracted.
An `if/else` may move only as part of an eligible normally completing scoped block or
an ordered scoped early-boolean-return region. Large expressions inside pinned structures can
still split, but the structure itself remains in the logical method.

### Class Sharding Constraints

A generated-helper family containing a caller-sensitive or owner-sensitive helper is
not sharded. Helpers with dependencies may be sharded when every generated call points
to an earlier helper, producing an acyclic physical-class definition order. Forward
or cyclic dependency shapes remain together. If that class then exceeds a classfile
structural limit, classfile emission fails rather than changing visibility or
inventing cyclic runtime linkage.

### Exact Size Is Known After Emission

Candidate selection uses estimates. Exact bytecode size depends on constant-pool
layout, local indexes, branch widths, and instruction forms selected during emission.
The compiler measures the final classfiles and enforces the hard method limit then.
It does not currently feed exact measurements into an iterative replanning pass.

A method above the configured hard limit fails with its physical class, method,
descriptor, actual size, and configured limit. Verification failures include a JDK
classfile disassembly to aid diagnosis.

## Inspection And Tests

Start with the logical rendering when authoring code:

```java
System.out.println(logicalClass);
System.out.println(method.body());
System.out.println(expression);
```

Use `CompiledUnit.report()` to inspect the physical topology and exact sizes. Use
`ClassFileDiagnostics` when raw classfile verification or disassembly is needed.

The main executable splitting examples are:

- `TestCompiledUnit.testLargeExpressionIsDecomposedWithoutChangingTheLogicalModel`;
- `TestCompiledUnit.testSequentialStatementsCarryOneLiveOutput`;
- `TestCompiledUnit.testLargeBooleanExpressionPreservesShortCircuiting`;
- `TestCompiledUnit.testConstructorPreSuperCalculationUsesStaticExpressionHelpers`;
- `TestCompiledUnit.testOptimizationWarningDoesNotPreventDefinition`;
- `TestCompiledUnit.testConstantPoolPressureCreatesCompanionClasses`;
- `TestAutomaticFlatHashSplitting.testTwoThousandFieldsThroughNominalAndHiddenClasses`;
- `TestAutomaticFlatHashSplitting.testTenThousandFieldHashStress`;
- `TestAutomaticRowConstructorSplitting.testBulkyFieldsAreSplitWithoutManualHelpers`;
- `TestScopedBlockOutputSplitting.testScopedBlocksWithOneOutputSplit`;
- `TestScopedBlockOutputSplitting.testTrailingScopedBlocksWithMultipleOutputsSplit`;
- `TestScopedBlockOutputSplitting.testMultiOutputContinuationHelpersCanBeSharded`;
- `TestScopedConditionSplitting.testScopedEarlyReturnConditionsSplit`;
- `TestHiddenClassReceiverSplitting.testScopedConditionHelperUsesHiddenReceiver`.

The FlatHash fixture builds the logical operations without manual chunks. It executes
a 2,001-field strategy through both nominal and hidden definers, stress-tests a
10,000-field hash method, and checks the physical classfiles against JVM hard limits.
A separate 25,000-distinct-string fixture exercises constant-pool-driven companion
creation.

The row-constructor fixture models independently scoped, conditionally initialized
fields without authored chunks. It verifies extraction of block-owned locals and
`if/else` code while retaining captured-reference mutations, and separately verifies
that one initialized captured outer local is returned to the caller.

## Future Considerations

The following extensions are possible if real workloads require them. They are not
part of the current splitting behavior.

### General Multiple-Output State Carriers

For non-trailing regions, the compiler could synthesize a record or another state
carrier when an otherwise useful region has several live outputs. This would extend
statement extraction but could add allocation, field traffic, or scalar-replacement
dependence. It should be introduced only with workload evidence and explicit
performance testing.

### General Control-Flow Results

A helper could return a structured result describing normal completion, return,
break, or continue. That would permit extraction across more control-flow boundaries,
but it would make generated Java semantics and stack traces harder to understand.

### Partial And Cyclic Class Sharding

The sharder accepts a generated family whose dependencies all point backward in
planner order, but it does not currently move only a proven acyclic subset of a more
complicated family. More aggressive designs could compute strongly connected
components or use mutable call sites or staged linkage for cycles. The current
all-or-nothing movability rule deliberately avoids that runtime complexity.

### Iterative Exact-Size Replanning

The compiler could use an emitted classfile as feedback, adjust split boundaries, and
emit again. This would reduce estimation error and support richer replanning
diagnostics at the cost of compilation time and planner complexity.

### Inline-Oriented Planning

A final optimization pass could use `MaxInlineSize`, `FreqInlineSize`, call frequency,
and caller size to preserve selected inline opportunities. Method size alone is not
enough to make this reliable, so the current planner targets loadability and
optimizing-compilation eligibility first.

### Richer Source-Level Diagnostics

Warnings and failures could identify the precise logical placement and source-like
region that prevented a split. The current report gives exact physical sizes and
readable helper names, while hard-limit failures identify the final physical method.

### Constant And Parameter Packing

Generated holder classes could pack large internal parameter sets or move difficult
constants out of a pressured class. These are recovery techniques for pathological
shapes. They do not solve an invalid public method descriptor and are intentionally
not automatic today.
