/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.classfile;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;

import static io.airlift.classfile.BytecodeExpressions.boundMethodHandle;
import static io.airlift.classfile.BytecodeExpressions.constantBoolean;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.invokeDynamic;
import static io.airlift.classfile.CodeBlock.block;
import static io.airlift.classfile.CodeBlock.blockBuilder;
import static io.airlift.classfile.DescriptorUtils.classDesc;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;

/// Retained hot-path benchmarks for JIT-sensitive compiler policy.
///
/// Each trial compiles and defines its generated class once. Benchmark methods measure only
/// generated execution. Run with `-prof gc` whenever changing lambda-boundary or statement-split
/// policy so throughput and scalar-replacement behavior are considered together.
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
public class BenchmarkGeneratedExecution
{
    private static final MethodHandle LONG_IDENTITY = MethodHandles.identity(long.class);
    private static final MethodHandle LONGS_EQUAL = findStatic("longsEqual", MethodType.methodType(boolean.class, long.class, long.class));
    private static final MethodHandle VARIABLE_LENGTH = findStatic("variableLength", MethodType.methodType(int.class, long.class));
    private static final int LAMBDA_CALLER_OPERATIONS = 32;
    private static final CompilationPolicy DEFAULT_POLICY = new CompilationPolicy(65_535, 7_200, 35, 325, false);
    private static final CompilationPolicy NO_LAMBDA_BOUNDARY_POLICY = new CompilationPolicy(65_535, 7_200, 35, 7_200, false);
    private static final CompilationPolicy NO_COMPLEXITY_SPLITTING_POLICY = new CompilationPolicy(65_535, 8_000, 35, 8_000, false);

    @Benchmark
    public long hiddenLambda(LambdaData data)
    {
        return data.workload.apply(data.builder, data.nextValue++);
    }

    @Benchmark
    public boolean flatHashLike(FlatHashData data)
    {
        int index = data.nextIndex++;
        if (data.nextIndex == data.left.length) {
            data.nextIndex = 0;
        }
        long value = data.nextValue++;
        data.left[index] = value;
        data.right[index] = value;
        return data.strategy.valueIdentical(data.left, data.right);
    }

    @State(Scope.Thread)
    public static class LambdaData
    {
        @Param
        LambdaShape lambdaShape;

        @Param
        LambdaBoundary lambdaBoundary;

        BufferedTransform workload;
        BufferedLongPairBuilder builder;
        long nextValue;

        @Setup(Level.Trial)
        public void setup()
                throws ReflectiveOperationException
        {
            ClassModel model = lambdaModel(lambdaShape);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            HiddenClassDefiner definer = HiddenClassDefiner.builder(lookup).build();

            CompilationPolicy measurementPolicy = new CompilationPolicy(65_535, 7_200, 35, 7_200, false);
            CompiledUnit unpadded = ClassCompiler.forTarget(definer.compilationTarget())
                    .policy(measurementPolicy)
                    .compileUnit(model);
            int unpaddedBytes = methodCodeBytes(unpadded, "transform");
            int boundaryTrigger = DEFAULT_POLICY.maxInlineSize() +
                    (DEFAULT_POLICY.frequentInlineSize() - DEFAULT_POLICY.maxInlineSize()) / 2;
            if (lambdaShape == LambdaShape.SMALL && unpaddedBytes > boundaryTrigger) {
                throw new IllegalStateException("Small lambda target is outside the inlineable range: " + unpaddedBytes);
            }
            if (lambdaShape == LambdaShape.MEDIUM && (unpaddedBytes <= boundaryTrigger || unpaddedBytes > DEFAULT_POLICY.frequentInlineSize())) {
                throw new IllegalStateException("Medium lambda target is outside the measured boundary range: " + unpaddedBytes);
            }

            CompilationPolicy policy = lambdaBoundary == LambdaBoundary.DEFAULT ? DEFAULT_POLICY : NO_LAMBDA_BOUNDARY_POLICY;
            CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                    .policy(policy)
                    .compileUnit(model);
            int emittedBytes = methodCodeBytes(unit, "transform");
            if (lambdaShape == LambdaShape.SMALL && emittedBytes != unpaddedBytes) {
                throw new IllegalStateException("Small lambda target was unexpectedly rewritten");
            }
            if (lambdaShape == LambdaShape.MEDIUM && lambdaBoundary == LambdaBoundary.DEFAULT && emittedBytes <= DEFAULT_POLICY.frequentInlineSize()) {
                throw new IllegalStateException("Medium lambda target did not retain a JIT boundary: " + emittedBytes);
            }
            if (lambdaBoundary == LambdaBoundary.NO_BOUNDARY && emittedBytes != unpaddedBytes) {
                throw new IllegalStateException("No-boundary target was unexpectedly rewritten");
            }

            workload = definer.defineUnit(unit)
                    .primaryClass(BufferedTransform.class)
                    .getConstructor()
                    .newInstance();
            if (!workload.getClass().isHidden()) {
                throw new IllegalStateException("Generated lambda workload is not hidden");
            }
            long input = 11;
            builder = new BufferedLongPairBuilder();
            long actual = workload.apply(builder, input);
            long expected = referenceTransform(lambdaShape, input);
            if (actual != expected) {
                throw new IllegalStateException("Generated lambda returned %s instead of %s".formatted(actual, expected));
            }
            nextValue = 1;
        }
    }

    @State(Scope.Thread)
    public static class FlatHashData
    {
        @Param
        FlatHashShape flatHashShape;

        @Param
        FlatHashPlanning planning;

        @Param({"32", "50", "64"})
        int fieldCount;

        LongArraysEqual strategy;
        long[] left;
        long[] right;
        int nextIndex;
        long nextValue;

        @Setup(Level.Trial)
        public void setup()
                throws ReflectiveOperationException
        {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            HiddenClassDefiner definer = HiddenClassDefiner.builder(lookup).build();
            ClassModel model = flatHashModel(flatHashShape, fieldCount);
            CompilationPolicy policy = planning == FlatHashPlanning.DEFAULT ? DEFAULT_POLICY : NO_COMPLEXITY_SPLITTING_POLICY;
            CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                    .policy(policy)
                    .compileUnit(model);
            long helpers = unit.report().classes().stream()
                    .flatMap(classInfo -> classInfo.methods().stream())
                    .filter(method -> method.name().startsWith("valueIdentical$blocks$"))
                    .count();
            if (flatHashShape == FlatHashShape.MIXED && planning == FlatHashPlanning.DEFAULT && helpers == 0) {
                ExpressionPlanner.Metrics metrics = ExpressionPlanner.metrics(model.methods().stream()
                        .filter(method -> method.name().equals("valueIdentical"))
                        .findFirst()
                        .orElseThrow()
                        .body());
                throw new IllegalStateException("Mixed invocation-dense shape did not split: " + metrics);
            }
            if (planning == FlatHashPlanning.NO_COMPLEXITY_SPLITTING && helpers != 0) {
                throw new IllegalStateException("No-complexity control split unexpectedly");
            }
            if (flatHashShape == FlatHashShape.FIXED && helpers != 0) {
                throw new IllegalStateException("Call-free fixed shape split unexpectedly");
            }

            strategy = definer.defineUnit(unit)
                    .primaryClass(LongArraysEqual.class)
                    .getConstructor()
                    .newInstance();
            if (!strategy.getClass().isHidden()) {
                throw new IllegalStateException("Generated flat-hash workload is not hidden");
            }
            left = new long[fieldCount];
            right = new long[fieldCount];
            for (int index = 0; index < fieldCount; index++) {
                left[index] = index * 17L;
                right[index] = left[index];
            }
            if (!strategy.valueIdentical(left, right)) {
                throw new IllegalStateException("Equal benchmark inputs do not compare equal");
            }
            right[fieldCount - 1]++;
            if (strategy.valueIdentical(left, right)) {
                throw new IllegalStateException("Different benchmark inputs compare equal");
            }
            right[fieldCount - 1] = left[fieldCount - 1];
            nextValue = 10_000;
        }
    }

    public enum LambdaShape
    {
        SMALL(3),
        MEDIUM(7);

        private final int operations;

        LambdaShape(int operations)
        {
            this.operations = operations;
        }

        int operations()
        {
            return operations;
        }
    }

    public enum LambdaBoundary
    {
        DEFAULT,
        NO_BOUNDARY,
    }

    public enum FlatHashShape
    {
        FIXED,
        MIXED,
    }

    public enum FlatHashPlanning
    {
        DEFAULT,
        NO_COMPLEXITY_SPLITTING,
    }

    public interface LongArraysEqual
    {
        boolean valueIdentical(long[] left, long[] right);
    }

    public interface BufferedTransform
    {
        long apply(BufferedLongPairBuilder builder, long input);
    }

    public interface LongPairWriter
    {
        void build(LongBuffer left, LongBuffer right);
    }

    public static final class BufferedLongPairBuilder
    {
        private final LongBuffer left = new LongBuffer();
        private final LongBuffer right = new LongBuffer();

        public long build(int entryCount, LongPairWriter writer)
        {
            if (entryCount != 8) {
                throw new IllegalArgumentException("entryCount must be 8");
            }
            if (left.size() != right.size()) {
                throw new IllegalStateException("buffer sizes do not match");
            }
            left.reset(entryCount);
            right.reset(entryCount);
            try {
                writer.build(left, right);
            }
            catch (RuntimeException e) {
                left.reset(0);
                right.reset(0);
                throw e;
            }
            if (left.size() != entryCount || right.size() != entryCount) {
                throw new IllegalStateException("writer produced the wrong number of entries");
            }
            return pair(0) + pair(1) + pair(2) + pair(3) +
                    pair(4) + pair(5) + pair(6) + pair(7);
        }

        private long pair(int index)
        {
            return left.get(index) * 31 + right.get(index);
        }
    }

    public static final class LongBuffer
    {
        private long[] values = new long[8];
        private int size;

        public void reset(int expectedSize)
        {
            if (values.length < expectedSize) {
                values = new long[expectedSize];
            }
            size = 0;
        }

        public void append(long value)
        {
            values[size++] = value;
        }

        public long get(int index)
        {
            return values[index];
        }

        public int size()
        {
            return size;
        }
    }

    private static ClassModel lambdaModel(LambdaShape shape)
    {
        ClassDefinition definition = ClassDefinition.define(ClassDesc.of(BenchmarkGeneratedExecution.class.getPackageName() + ".GeneratedBenchmarkLambda" + shape))
                .access(PUBLIC, FINAL)
                .addInterface(BufferedTransform.class);
        definition.defaultConstructor().access(PUBLIC);

        Parameter capture = arg("capture", long.class);
        Parameter input = arg("input", long.class);
        Parameter left = arg("left", LongBuffer.class);
        Parameter right = arg("right", LongBuffer.class);
        MethodDefinition transform = definition.method("transform", void.class, capture, input, left, right).access(PRIVATE, STATIC);
        ForLoop.Builder loop = ForLoop.builder();
        CodeBlock.Builder initializer = blockBuilder();
        Variable index = initializer.declare("index", constantInt(0));
        CodeBlock.Builder loopBody = blockBuilder();
        Variable result = loopBody.declare("result", input.add(capture).add(index.cast(long.class)));
        for (int operation = 0; operation < shape.operations(); operation++) {
            BytecodeExpression transformed = BytecodeExpressions.inlineIf(
                    result.bitwiseAnd(constantLong(1)).equal(constantLong(0)),
                    result.multiply(constantLong(31)).add(constantLong(operation + 1)),
                    result.multiply(constantLong(17)).subtract(constantLong(operation + 1)));
            if (shape == LambdaShape.MEDIUM) {
                transformed = boundMethodHandle(LONG_IDENTITY).invoke(transformed);
            }
            loopBody.append(result.set(transformed));
        }
        loopBody.append(left.invoke("append", void.class, input.add(index.cast(long.class))));
        loopBody.append(right.invoke("append", void.class, result));
        transform.body().append(loop
                .initialize(initializer.build())
                .condition(index.lessThan(constantInt(8)))
                .update(block(index.increment()))
                .body(loopBody.build())
                .build());
        transform.body().ret();

        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDesc(LambdaMetafactory.class),
                "metafactory",
                MethodTypeDesc.of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType, CD_MethodType, CD_MethodHandle, CD_MethodType));
        MethodHandleDesc implementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                definition.type(),
                transform.name(),
                transform.methodType());
        MethodTypeDesc samType = MethodTypeDesc.of(
                CD_void,
                classDesc(LongBuffer.class),
                classDesc(LongBuffer.class));
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(
                bootstrap,
                "build",
                MethodTypeDesc.of(classDesc(LongPairWriter.class), CD_long, CD_long),
                samType,
                implementation,
                samType);

        Parameter applyBuilder = arg("builder", BufferedLongPairBuilder.class);
        Parameter applyInput = arg("input", long.class);
        MethodDefinition apply = definition.method("apply", long.class, applyBuilder, applyInput).access(PUBLIC);
        Variable adjustedInput = apply.body().declare("adjustedInput", applyInput);
        for (int operation = 0; operation < LAMBDA_CALLER_OPERATIONS; operation++) {
            apply.body().append(adjustedInput.set(boundMethodHandle(LONG_IDENTITY).invoke(
                    adjustedInput.multiply(constantLong(31)).add(constantLong(operation + 1)))));
        }
        BytecodeExpression lambda = invokeDynamic(callSite, adjustedInput, adjustedInput);
        apply.body().ret(applyBuilder.invoke(
                "build",
                long.class,
                constantInt(8),
                lambda));
        return definition.build();
    }

    private static ClassModel flatHashModel(FlatHashShape shape, int fieldCount)
    {
        ClassDefinition definition = ClassDefinition.define(ClassDesc.of(BenchmarkGeneratedExecution.class.getPackageName() + ".GeneratedBenchmarkFlatHash" + shape + fieldCount))
                .access(PUBLIC, FINAL)
                .addInterface(LongArraysEqual.class);
        definition.defaultConstructor().access(PUBLIC);

        Parameter left = arg("left", long[].class);
        Parameter right = arg("right", long[].class);
        MethodDefinition method = definition.method("valueIdentical", boolean.class, left, right).access(PUBLIC);
        Variable currentVariableOffset = shape == FlatHashShape.MIXED ? method.body().declare("currentVariableOffset", constantInt(0)) : null;
        for (int field = 0; field < fieldCount; field++) {
            CodeBlock.Builder comparison = blockBuilder();
            if (shape == FlatHashShape.MIXED) {
                Variable leftValue = comparison.declare("left" + field, normalize(left.getElement(constantInt(field))));
                Variable rightValue = comparison.declare("right" + field, normalize(right.getElement(constantInt(field))));
                Variable leftIsNull = comparison.declare("leftIsNull" + field, leftValue.equal(constantLong(Long.MIN_VALUE)));
                Variable rightIsNull = comparison.declare("rightIsNull" + field, rightValue.equal(constantLong(Long.MIN_VALUE)));
                BytecodeExpression nextOffset = (field & 1) == 0
                        ? currentVariableOffset
                        : currentVariableOffset.add(boundMethodHandle(VARIABLE_LENGTH).invoke(leftValue));
                comparison.append(IfStatement.builder()
                        .condition(leftIsNull)
                        .then(block(currentVariableOffset.set(BytecodeExpressions.inlineIf(
                                rightIsNull,
                                currentVariableOffset,
                                constantInt(-1)))))
                        .otherwise(block(IfStatement.builder()
                                .condition(rightIsNull)
                                .then(block(currentVariableOffset.set(constantInt(-1))))
                                .otherwise(block(currentVariableOffset.set(BytecodeExpressions.inlineIf(
                                        boundMethodHandle(LONGS_EQUAL).invoke(leftValue, rightValue),
                                        nextOffset,
                                        constantInt(-1)))))
                                .build()))
                        .build());
                method.body().append(block(IfStatement.builder()
                        .condition(currentVariableOffset.greaterThanOrEqual(constantInt(0)))
                        .then(comparison.build())
                        .build()));
            }
            else {
                comparison.append(IfStatement.builder()
                        .condition(left.getElement(constantInt(field)).notEqual(right.getElement(constantInt(field))))
                        .then(block(constantBoolean(false).ret()))
                        .build());
                method.body().append(comparison.build());
            }
        }
        method.body().ret(shape == FlatHashShape.MIXED ? currentVariableOffset.greaterThanOrEqual(constantInt(0)) : constantBoolean(true));
        return definition.build();
    }

    private static int methodCodeBytes(CompiledUnit unit, String name)
    {
        return unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .filter(method -> method.name().equals(name))
                .mapToInt(CompilationReport.MethodInfo::codeBytes)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Method not emitted: " + name));
    }

    private static BytecodeExpression normalize(BytecodeExpression value)
    {
        for (int invocation = 0; invocation < 4; invocation++) {
            value = boundMethodHandle(LONG_IDENTITY).invoke(value);
        }
        return value;
    }

    private static long referenceTransform(LambdaShape shape, long input)
    {
        for (int operation = 0; operation < LAMBDA_CALLER_OPERATIONS; operation++) {
            input = input * 31 + operation + 1;
        }
        long total = 0;
        for (int index = 0; index < 8; index++) {
            long result = input + input + index;
            for (int operation = 0; operation < shape.operations(); operation++) {
                result = (result & 1) == 0
                        ? result * 31 + operation + 1
                        : result * 17 - operation - 1;
            }
            total += (input + index) * 31 + result;
        }
        return total;
    }

    @SuppressWarnings("UnusedMethod") // Resolved reflectively by LONGS_EQUAL.
    private static boolean longsEqual(long left, long right)
    {
        return left == right;
    }

    @SuppressWarnings("UnusedMethod") // Resolved reflectively by VARIABLE_LENGTH.
    private static int variableLength(long value)
    {
        return Long.numberOfLeadingZeros(value) & 1;
    }

    private static MethodHandle findStatic(String name, MethodType type)
    {
        try {
            return MethodHandles.lookup().findStatic(BenchmarkGeneratedExecution.class, name, type);
        }
        catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void main(String[] args)
            throws Exception
    {
        BenchmarkSupport.run(BenchmarkGeneratedExecution.class, args);
    }
}
