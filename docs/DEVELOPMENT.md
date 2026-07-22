# Development

## Requirements

Development requires JDK 25 or newer. The Maven wrapper supplies the required Maven
version.

## Build

Run the complete local build with:

```shell
./mvnw clean install -P ci
```

Error Prone and compiler warnings run during normal compilation and fail the build.
The project does not maintain a relaxed compiler profile. Suppress a check only at
the narrowest location where the code deliberately requires the behavior, and
document why the suppression is necessary.

Format Java sources with:

```shell
./mvnw airstyle:format
```

## Benchmarks

Generation benchmarks separate compiler construction, classfile compilation, wrapper
definition, raw JVM hidden-class definition, and the complete compile-and-define path.
Compile them once and then rerun selected shapes without rebuilding:

```shell
./mvnw test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/benchmark-classpath.txt \
    -Dmdep.includeScope=test
java -cp "target/test-classes:target/classes:$(cat target/benchmark-classpath.txt)" \
    io.airlift.classfile.BenchmarkClassfileGeneration \
    -f 1 -wi 3 -i 5 -e '.*createCompiler' -p shape=STATE_1,STATE_5
```

Pass a benchmark-name regular expression as the first argument to run only part of
the class. The `createCompiler` benchmark intentionally has no shape parameter and can
be run separately. Use `-prof gc` to include allocation rates. Add representative
workload shapes to `BenchmarkClassfileGeneration.Shape` as more generators migrate to
Classfile.

Generated-execution benchmarks compile and define each workload once during trial
setup, then measure only the hot generated code. They retain controls for the hidden
lambda JIT boundary and invocation-density splitting, including common 32, 50, and
64-field hash shapes:

```shell
java -cp "target/test-classes:target/classes:$(cat target/benchmark-classpath.txt)" \
    io.airlift.classfile.BenchmarkGeneratedExecution \
    -f 1 -wi 3 -i 5 -w 500ms -r 500ms -prof gc
```

Treat throughput and allocation as a joint gate. A boundary that restores throughput
by preventing scalar replacement is still a regression. Compare the `DEFAULT` and
control policies, and retain both call-free and invocation-dense shapes so a planner
change does not improve one workload by penalizing the other.

Public API documentation uses Java 25 Markdown documentation comments (`///`).

## Dependency Boundary

Production code is JDK-only. Dependencies may be added for tests and build tooling,
but code under `src/main/java` must not import third-party APIs. The dependency and
public-API guardrail tests enforce this boundary.

## Commit Discipline

Every commit submitted for review must compile independently. Pull requests compile
each earlier commit with JDK 25 and run the complete build for the final tree with
JDK 25 and JDK 26.

Commit subjects must be imperative and capitalized, must not end with a period, and
should be no longer than 50 characters. They must not exceed 60 characters. Wrap
commit bodies at 72 characters. CI rejects ordinary body text over 79 characters
while allowing long URLs, trailers, quoted text, code blocks, and unwrappable tokens.

Install `pre-commit`, then enable the shared Airlift commit-message hook once in the
main checkout. The hook is inherited by linked worktrees:

```shell
pre-commit install --hook-type commit-msg --install-hooks
```

The hook applies the same subject, body-width, and attribution checks before Git
creates a commit.
