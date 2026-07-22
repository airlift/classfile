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

import org.junit.jupiter.api.Test;

import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.boundMethodHandle;
import static io.airlift.classfile.BytecodeExpressions.constantFalse;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.BytecodeExpressions.inlineIf;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.CodeBlock.block;
import static io.airlift.classfile.CodeBlock.blockBuilder;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;

/// A de-chunked, test-local port of the repeated logical work in Trino's FlatHashStrategyCompiler.
public class TestAutomaticFlatHashSplitting
{
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final int NULL_VALUE = Integer.MIN_VALUE;

    @Test
    void testTwoThousandFieldsThroughNominalAndHiddenClasses()
            throws Throwable
    {
        int fieldCount = 2_001;
        ClassModel logicalModel = flatHashModel(fieldCount);
        int[] values = values(fieldCount);
        int variableSize = Arrays.stream(values).map(TestAutomaticFlatHashSplitting::variableWidth).sum();

        StandardClassDefiner nominalDefiner = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit nominalUnit = ClassCompiler.forTarget(nominalDefiner.compilationTarget())
                .compileUnit(logicalModel);
        assertWithinHardLimits(nominalUnit);
        DefinedUnit nominal = nominalDefiner.defineUnit(nominalUnit);
        assertFlatHashBehavior(nominal.primaryClass(), values, variableSize);

        MethodHandles.Lookup hostLookup = MethodHandles.lookup();
        CompiledUnit hiddenUnit = ClassCompiler.forTarget(CompilationTarget.forLookup(hostLookup)).compileUnit(logicalModel);
        assertWithinHardLimits(hiddenUnit);
        DefinedUnit hidden = HiddenClassDefiner.builder(hostLookup).build().defineUnit(hiddenUnit);
        assertThat(hidden.primaryClass().isHidden()).isTrue();
        assertFlatHashBehavior(hidden.primaryLookup().orElseThrow(), hidden.primaryClass(), values, variableSize);
    }

    @Test
    @SuppressWarnings("PrimitiveArrayPassedToVarargsMethod") // MethodHandle.invokeExact is signature-polymorphic.
    void testTenThousandFieldHashStress()
            throws Throwable
    {
        int fieldCount = 10_000;
        ClassModel logicalModel = hashOnlyModel(fieldCount);
        CompiledUnit unit = ClassCompiler.forTarget(CompilationTarget.forLookup(MethodHandles.lookup())).compileUnit(logicalModel);
        assertWithinHardLimits(unit);

        DefinedUnit hidden = HiddenClassDefiner.builder(MethodHandles.lookup()).build().defineUnit(unit);
        int[] values = values(fieldCount);
        MethodHandle hash = hidden.primaryLookup().orElseThrow()
                .findStatic(hidden.primaryClass(), "hash", MethodType.methodType(long.class, int[].class));
        assertThat((long) hash.invokeExact(values)).isEqualTo(expectedHash(values));
    }

    @Test
    void testBoundHandleScopedHelpersStayWithinJitTarget()
            throws Throwable
    {
        int fieldCount = 512;
        ClassDefinition definition = ClassDefinition.define(generatedType("FlatHashIdentical", fieldCount)).access(PUBLIC, FINAL);
        Parameter left = arg("left", byte[].class);
        Parameter blocks = arg("blocks", ValueBlock[].class);
        Parameter position = arg("position", int.class);
        MethodDefinition method = definition.method("valueIdentical", boolean.class, left, blocks, position).access(PUBLIC, STATIC);
        Variable state = method.body().declare("state", constantInt(0));
        MethodHandle identical = MethodHandles.lookup().findStatic(
                TestAutomaticFlatHashSplitting.class,
                "identical",
                MethodType.methodType(boolean.class, byte[].class, int.class, ValueBlock.class, int.class));
        for (int field = 0; field < fieldCount; field++) {
            CodeBlock.Builder compare = blockBuilder();
            Variable block = compare.declare("block", blocks.getElement(field));
            Variable leftIsNull = compare.declare("leftIsNull", left.getElement(field).cast(int.class).equal(constantInt(NULL_VALUE)));
            Variable rightIsNull = compare.declare("rightIsNull", block.invoke("isNull", boolean.class, position));
            compare.append(IfStatement.builder()
                    .condition(leftIsNull)
                    .then(block(state.set(inlineIf(rightIsNull, state, constantInt(-1)))))
                    .otherwise(block(IfStatement.builder()
                            .condition(rightIsNull)
                            .then(block(state.set(constantInt(-1))))
                            .otherwise(block(state.set(inlineIf(
                                    boundMethodHandle(identical).invoke(left, constantInt(field), block, position),
                                    state,
                                    constantInt(-1)))))
                            .build()))
                    .build());
            method.body().append(block(IfStatement.builder()
                    .condition(state.greaterThanOrEqual(constantInt(0)))
                    .then(compare.build())
                    .build()));
        }
        method.body().ret(state.greaterThanOrEqual(constantInt(0)));

        CompilationPolicy policy = new CompilationPolicy(65_535, 7_200, 35, 325, true);
        MethodHandles.Lookup hostLookup = MethodHandles.lookup();
        CompiledUnit unit = ClassCompiler.forTarget(CompilationTarget.forLookup(hostLookup))
                .policy(policy)
                .compileUnit(definition.build());

        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .filter(methodInfo -> methodInfo.name().startsWith("valueIdentical$blocks$")))
                .isNotEmpty()
                .allSatisfy(methodInfo -> assertThat(methodInfo.codeBytes()).isLessThanOrEqualTo(policy.targetMethodCodeLimit()));

        DefinedUnit hidden = HiddenClassDefiner.builder(hostLookup).build().defineUnit(unit);
        MethodHandle valueIdentical = hidden.primaryLookup().orElseThrow().findStatic(
                hidden.primaryClass(),
                "valueIdentical",
                MethodType.methodType(boolean.class, byte[].class, ValueBlock[].class, int.class));
        byte[] leftValues = new byte[fieldCount];
        ValueBlock[] rightValues = new ValueBlock[fieldCount];
        Arrays.fill(rightValues, new IntValueBlock(0));
        assertThat((boolean) valueIdentical.invokeExact(leftValues, rightValues, 0)).isTrue();
        rightValues[fieldCount - 1] = new IntValueBlock(1);
        assertThat((boolean) valueIdentical.invokeExact(leftValues, rightValues, 0)).isFalse();
    }

    private static ClassModel flatHashModel(int fieldCount)
    {
        ClassDefinition definition = ClassDefinition.define(generatedType("FlatHash", fieldCount)).access(PUBLIC, FINAL);
        generateVariableWidth(definition, fieldCount);
        generateWrite(definition, fieldCount);
        generateRead(definition, fieldCount);
        generateIdentical(definition, fieldCount);
        generateHash(definition, fieldCount);
        generateHashFlat(definition, fieldCount);
        return definition.build();
    }

    private static ClassModel hashOnlyModel(int fieldCount)
    {
        ClassDefinition definition = ClassDefinition.define(generatedType("FlatHashStress", fieldCount)).access(PUBLIC, FINAL);
        generateHash(definition, fieldCount);
        return definition.build();
    }

    private static void generateVariableWidth(ClassDefinition definition, int fieldCount)
    {
        Parameter values = arg("values", int[].class);
        MethodDefinition method = definition.method("variableWidth", int.class, values).access(PUBLIC, STATIC);
        Variable size = method.body().declare("size", constantInt(0));
        for (int field = 0; field < fieldCount; field++) {
            method.body().append(size.set(size.add(invokeStatic(
                    TestAutomaticFlatHashSplitting.class,
                    "variableWidth",
                    int.class,
                    values.getElement(field)))));
        }
        method.body().ret(size);
    }

    private static void generateWrite(ClassDefinition definition, int fieldCount)
    {
        Parameter values = arg("values", int[].class);
        Parameter fixed = arg("fixed", int[].class);
        Parameter variable = arg("variable", byte[].class);
        MethodDefinition method = definition.method("writeFlat", void.class, values, fixed, variable).access(PUBLIC, STATIC);
        Variable variableOffset = method.body().declare("variableOffset", constantInt(0));
        for (int field = 0; field < fieldCount; field++) {
            BytecodeExpression value = values.getElement(field);
            method.body()
                    .append(invokeStatic(
                            TestAutomaticFlatHashSplitting.class,
                            "writeValue",
                            void.class,
                            value,
                            fixed,
                            constantInt(field),
                            variable,
                            variableOffset))
                    .append(variableOffset.set(variableOffset.add(invokeStatic(
                            TestAutomaticFlatHashSplitting.class,
                            "variableWidth",
                            int.class,
                            value))));
        }
        method.body().ret();
    }

    private static void generateRead(ClassDefinition definition, int fieldCount)
    {
        Parameter fixed = arg("fixed", int[].class);
        Parameter variable = arg("variable", byte[].class);
        MethodDefinition method = definition.method("readFlat", int[].class, fixed, variable).access(PUBLIC, STATIC);
        Variable result = method.body().declare("result", newArray(int[].class, constantInt(fieldCount)));
        Variable variableOffset = method.body().declare("variableOffset", constantInt(0));
        for (int field = 0; field < fieldCount; field++) {
            BytecodeExpression value = invokeStatic(
                    TestAutomaticFlatHashSplitting.class,
                    "readValue",
                    int.class,
                    fixed,
                    constantInt(field),
                    variable,
                    variableOffset);
            method.body()
                    .append(result.setElement(field, value))
                    .append(variableOffset.set(variableOffset.add(invokeStatic(
                            TestAutomaticFlatHashSplitting.class,
                            "variableWidth",
                            int.class,
                            result.getElement(field)))));
        }
        method.body().ret(result);
    }

    private static void generateIdentical(ClassDefinition definition, int fieldCount)
    {
        Parameter values = arg("values", int[].class);
        Parameter fixed = arg("fixed", int[].class);
        MethodDefinition method = definition.method("identical", boolean.class, values, fixed).access(PUBLIC, STATIC);
        for (int field = 0; field < fieldCount; field++) {
            method.body().append(IfStatement.builder()
                    .condition(values.getElement(field).notEqual(fixed.getElement(field)))
                    .then(constantFalse().ret())
                    .build());
        }
        method.body().ret(constantTrue());
    }

    private static void generateHash(ClassDefinition definition, int fieldCount)
    {
        Parameter values = arg("values", int[].class);
        MethodDefinition method = definition.method("hash", long.class, values).access(PUBLIC, STATIC);
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
    }

    private static void generateHashFlat(ClassDefinition definition, int fieldCount)
    {
        Parameter fixed = arg("fixed", int[].class);
        Parameter variable = arg("variable", byte[].class);
        MethodDefinition method = definition.method("hashFlat", long.class, fixed, variable).access(PUBLIC, STATIC);
        Variable offsets = method.body().declare("offsets", newArray(int[].class, constantInt(fieldCount + 1)));
        Variable variableOffset = method.body().declare("variableOffset", constantInt(0));
        for (int field = 0; field < fieldCount; field++) {
            method.body()
                    .append(offsets.setElement(field, variableOffset))
                    .append(variableOffset.set(variableOffset.add(invokeStatic(
                            TestAutomaticFlatHashSplitting.class,
                            "variableWidth",
                            int.class,
                            fixed.getElement(field)))));
        }

        Variable hash = method.body().declare("hash", constantLong(1));
        for (int field = 0; field < fieldCount; field++) {
            method.body().append(hash.set(invokeStatic(
                    TestAutomaticFlatHashSplitting.class,
                    "combineHash",
                    long.class,
                    hash,
                    invokeStatic(
                            TestAutomaticFlatHashSplitting.class,
                            "readValue",
                            int.class,
                            fixed,
                            constantInt(field),
                            variable,
                            offsets.getElement(field)))));
        }
        method.body().ret(hash);
    }

    private static void assertFlatHashBehavior(Class<?> generated, int[] values, int variableSize)
            throws ReflectiveOperationException
    {
        int[] fixed = new int[values.length];
        byte[] variable = new byte[variableSize];
        assertThat(generated.getMethod("variableWidth", int[].class).invoke(null, (Object) values)).isEqualTo(variableSize);
        generated.getMethod("writeFlat", int[].class, int[].class, byte[].class).invoke(null, values, fixed, variable);
        assertThat((int[]) generated.getMethod("readFlat", int[].class, byte[].class).invoke(null, fixed, variable)).containsExactly(values);
        assertThat(generated.getMethod("identical", int[].class, int[].class).invoke(null, values, fixed)).isEqualTo(true);
        assertThat(generated.getMethod("hash", int[].class).invoke(null, (Object) values)).isEqualTo(expectedHash(values));
        assertThat(generated.getMethod("hashFlat", int[].class, byte[].class).invoke(null, fixed, variable)).isEqualTo(expectedHash(values));
    }

    @SuppressWarnings("PrimitiveArrayPassedToVarargsMethod") // MethodHandle.invokeExact is signature-polymorphic.
    private static void assertFlatHashBehavior(MethodHandles.Lookup lookup, Class<?> generated, int[] values, int variableSize)
            throws Throwable
    {
        int[] fixed = new int[values.length];
        byte[] variable = new byte[variableSize];
        MethodHandle width = lookup.findStatic(generated, "variableWidth", MethodType.methodType(int.class, int[].class));
        MethodHandle write = lookup.findStatic(generated, "writeFlat", MethodType.methodType(void.class, int[].class, int[].class, byte[].class));
        MethodHandle read = lookup.findStatic(generated, "readFlat", MethodType.methodType(int[].class, int[].class, byte[].class));
        MethodHandle identical = lookup.findStatic(generated, "identical", MethodType.methodType(boolean.class, int[].class, int[].class));
        MethodHandle hash = lookup.findStatic(generated, "hash", MethodType.methodType(long.class, int[].class));
        MethodHandle hashFlat = lookup.findStatic(generated, "hashFlat", MethodType.methodType(long.class, int[].class, byte[].class));

        assertThat((int) width.invokeExact(values)).isEqualTo(variableSize);
        write.invokeExact(values, fixed, variable);
        assertThat((int[]) read.invokeExact(fixed, variable)).containsExactly(values);
        assertThat((boolean) identical.invokeExact(values, fixed)).isTrue();
        assertThat((long) hash.invokeExact(values)).isEqualTo(expectedHash(values));
        assertThat((long) hashFlat.invokeExact(fixed, variable)).isEqualTo(expectedHash(values));
    }

    private static void assertWithinHardLimits(CompiledUnit unit)
    {
        assertThat(unit.report().classes()).isNotEmpty();
        unit.report().classes().forEach(classInfo -> {
            assertThat(classInfo.constantPoolCount()).isLessThan(65_535);
            classInfo.methods().forEach(method -> assertThat(method.codeBytes()).isLessThanOrEqualTo(65_535));
        });
    }

    private static int[] values(int fieldCount)
    {
        int[] values = new int[fieldCount];
        for (int field = 0; field < fieldCount; field++) {
            values[field] = field % 17 == 0 ? NULL_VALUE : (field * 31) - 7;
        }
        return values;
    }

    public static int variableWidth(int value)
    {
        return value == NULL_VALUE ? 0 : (value & 1) + 1;
    }

    public static void writeValue(int value, int[] fixed, int field, byte[] variable, int variableOffset)
    {
        fixed[field] = value;
        Arrays.fill(variable, variableOffset, variableOffset + variableWidth(value), (byte) value);
    }

    public static int readValue(int[] fixed, int field, byte[] variable, int variableOffset)
    {
        int value = fixed[field];
        if (variableWidth(value) != 0 && variable[variableOffset] != (byte) value) {
            throw new IllegalArgumentException("Corrupt variable data at field " + field);
        }
        return value;
    }

    public static long combineHash(long hash, int value)
    {
        return (hash * 31) + (value == NULL_VALUE ? 0x9E37_79B9L : value * 37L);
    }

    public static boolean identical(byte[] left, int offset, ValueBlock right, int position)
    {
        return left[offset] == right.getInt(position);
    }

    public interface ValueBlock
    {
        boolean isNull(int position);

        int getInt(int position);
    }

    private record IntValueBlock(int value)
            implements ValueBlock
    {
        @Override
        public boolean isNull(int position)
        {
            return false;
        }

        @Override
        public int getInt(int position)
        {
            return value;
        }
    }

    private static long expectedHash(int[] values)
    {
        long hash = 1;
        for (int value : values) {
            hash = combineHash(hash, value);
        }
        return hash;
    }

    private static ClassDesc generatedType(String name, int fieldCount)
    {
        return ClassDesc.of(TestAutomaticFlatHashSplitting.class.getPackageName() + ".Generated" + name + fieldCount + "_" + NEXT_ID.incrementAndGet());
    }
}
