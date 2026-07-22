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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.boundConstant;
import static io.airlift.classfile.BytecodeExpressions.constantFalse;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;

/// A self-contained legacy fixture for an explicitly authored FlatHashStrategy and
/// static chunk class. TestAutomaticFlatHashSplitting covers the recommended natural
/// logical shape with compiler-owned physical splitting.
public class TestFlatHashStrategyCompilerClassfile
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();
    private static final int COLUMN_COUNT = 3;
    private static final int FIXED_SIZE = 5;
    private static final int NULL_SENTINEL = Integer.MIN_VALUE;
    private static final long INITIAL_HASH_VALUE = 1;

    @Test
    void testFlatRoundTripAndMetadata()
            throws Exception
    {
        FlatValueOperator operator = new FlatValueOperator(17);
        List<String> types = List.of("first", "second", "third");
        FlatHashStrategy strategy = compileFlatHashStrategy(operator, types);

        assertThat(strategy.types()).isEqualTo(types);
        assertThat(strategy.isAnyVariableWidth()).isTrue();
        assertThat(strategy.getTotalFlatFixedLength()).isEqualTo(COLUMN_COUNT * FIXED_SIZE);

        int[] values = {11, NULL_SENTINEL, -7};
        int fixedOffset = 2;
        int variableOffset = 3;
        byte[] fixed = new byte[fixedOffset + strategy.getTotalFlatFixedLength() + 2];
        byte[] variable = new byte[variableOffset + strategy.getTotalVariableWidth(values) + 2];

        strategy.writeFlat(values, fixed, fixedOffset, variable, variableOffset);

        assertThat(strategy.readFlat(fixed, fixedOffset, variable, variableOffset)).containsExactly(values);
        assertThat(strategy.valueIdentical(values, fixed, fixedOffset, variable, variableOffset)).isTrue();
        assertThat(strategy.hash(values)).isEqualTo(strategy.hashFlat(fixed, fixedOffset, variable, variableOffset));
        assertThat(variable).contains((byte) (11 + operator.salt()), (byte) (-7 + operator.salt()));
    }

    @Test
    void testNullAndMismatchPaths()
            throws Exception
    {
        FlatHashStrategy strategy = compileFlatHashStrategy(new FlatValueOperator(31), List.of("a", "b", "c"));
        int[] values = {NULL_SENTINEL, 4, 9};
        byte[] fixed = new byte[strategy.getTotalFlatFixedLength()];
        byte[] variable = new byte[strategy.getTotalVariableWidth(values)];
        strategy.writeFlat(values, fixed, 0, variable, 0);

        assertThat(strategy.readFlat(fixed, 0, variable, 0)).containsExactly(values);
        assertThat(strategy.valueIdentical(new int[] {NULL_SENTINEL, 4, 10}, fixed, 0, variable, 0)).isFalse();
        assertThat(strategy.hash(new int[] {NULL_SENTINEL, 4, 10}))
                .isNotEqualTo(strategy.hashFlat(fixed, 0, variable, 0));
    }

    private static FlatHashStrategy compileFlatHashStrategy(FlatValueOperator operator, List<String> types)
            throws ReflectiveOperationException
    {
        long classId = NEXT_CLASS_ID.incrementAndGet();
        ClassDesc strategyType = generatedType("GeneratedFlatHashStrategy" + classId);
        ClassDesc chunkType = generatedType("GeneratedFlatHashStrategyChunk" + classId);

        ClassDefinition chunkDefinition = ClassDefinition.define(chunkType)
                .access(PUBLIC, FINAL)
                .sourceFile("GeneratedFlatHashStrategyChunk.java");
        chunkDefinition.defaultConstructor().access(PRIVATE);

        MethodDefinition getTotalVariableWidthChunk = generateGetTotalVariableWidthChunk(chunkDefinition);
        MethodDefinition writeFlatChunk = generateWriteFlatChunk(chunkDefinition);
        MethodDefinition readFlatChunk = generateReadFlatChunk(chunkDefinition);
        MethodDefinition identicalChunk = generateIdenticalChunk(chunkDefinition);
        MethodDefinition hashBlockChunk = generateHashBlockChunk(chunkDefinition);
        MethodDefinition hashFlatChunk = generateHashFlatChunk(chunkDefinition);

        ClassDefinition strategyDefinition = ClassDefinition.define(strategyType)
                .access(PUBLIC, FINAL)
                .addInterface(FlatHashStrategy.class)
                .sourceFile("GeneratedFlatHashStrategy.java");
        FieldDefinition typesField = strategyDefinition.field("types", List.class).access(PRIVATE, FINAL).build();
        FieldDefinition operatorField = strategyDefinition.field("operator", FlatValueOperator.class).access(PRIVATE, FINAL).build();

        MethodDefinition constructor = strategyDefinition.constructor().access(PUBLIC);
        constructor.body()
                .invokeSuperConstructor()
                .append(constructor.thisVariable().setField(typesField, boundConstant(List.copyOf(types), List.class)))
                .append(constructor.thisVariable().setField(operatorField, boundConstant(operator, FlatValueOperator.class)))
                .ret();

        MethodDefinition typesMethod = strategyDefinition.method("types", List.class).access(PUBLIC);
        typesMethod.body().ret(typesMethod.thisVariable().getField(typesField));
        strategyDefinition.method("isAnyVariableWidth", boolean.class).access(PUBLIC)
                .body().ret(constantTrue());
        strategyDefinition.method("getTotalFlatFixedLength", int.class).access(PUBLIC)
                .body().ret(constantInt(COLUMN_COUNT * FIXED_SIZE));

        Parameter values = arg("values", int[].class);
        MethodDefinition getTotalVariableWidth = strategyDefinition.method("getTotalVariableWidth", int.class, values).access(PUBLIC);
        getTotalVariableWidth.body()
                .ret(invokeStatic(getTotalVariableWidthChunk, getTotalVariableWidth.thisVariable().getField(operatorField), values));

        Parameter writeValues = arg("values", int[].class);
        Parameter writeFixed = arg("fixed", byte[].class);
        Parameter writeFixedOffset = arg("fixedOffset", int.class);
        Parameter writeVariable = arg("variable", byte[].class);
        Parameter writeVariableOffset = arg("variableOffset", int.class);
        MethodDefinition writeFlat = strategyDefinition.method(
                "writeFlat",
                void.class,
                writeValues,
                writeFixed,
                writeFixedOffset,
                writeVariable,
                writeVariableOffset).access(PUBLIC);
        writeFlat.body()
                .append(invokeStatic(
                        writeFlatChunk,
                        writeFlat.thisVariable().getField(operatorField),
                        writeValues,
                        writeFixed,
                        writeFixedOffset,
                        writeVariable,
                        writeVariableOffset))
                .ret();

        Parameter readFixed = arg("fixed", byte[].class);
        Parameter readFixedOffset = arg("fixedOffset", int.class);
        Parameter readVariable = arg("variable", byte[].class);
        Parameter readVariableOffset = arg("variableOffset", int.class);
        MethodDefinition readFlat = strategyDefinition.method(
                "readFlat",
                int[].class,
                readFixed,
                readFixedOffset,
                readVariable,
                readVariableOffset).access(PUBLIC);
        readFlat.body().ret(invokeStatic(
                readFlatChunk,
                readFlat.thisVariable().getField(operatorField),
                readFixed,
                readFixedOffset,
                readVariable,
                readVariableOffset));

        Parameter identicalValues = arg("values", int[].class);
        Parameter identicalFixed = arg("fixed", byte[].class);
        Parameter identicalFixedOffset = arg("fixedOffset", int.class);
        Parameter identicalVariable = arg("variable", byte[].class);
        Parameter identicalVariableOffset = arg("variableOffset", int.class);
        MethodDefinition identical = strategyDefinition.method(
                "valueIdentical",
                boolean.class,
                identicalValues,
                identicalFixed,
                identicalFixedOffset,
                identicalVariable,
                identicalVariableOffset).access(PUBLIC);
        identical.body().ret(invokeStatic(
                identicalChunk,
                identical.thisVariable().getField(operatorField),
                identicalValues,
                identicalFixed,
                identicalFixedOffset,
                identicalVariable,
                identicalVariableOffset));

        Parameter hashValues = arg("values", int[].class);
        MethodDefinition hash = strategyDefinition.method("hash", long.class, hashValues).access(PUBLIC);
        hash.body().ret(invokeStatic(hashBlockChunk, hash.thisVariable().getField(operatorField), hashValues));

        Parameter hashFixed = arg("fixed", byte[].class);
        Parameter hashFixedOffset = arg("fixedOffset", int.class);
        Parameter hashVariable = arg("variable", byte[].class);
        Parameter hashVariableOffset = arg("variableOffset", int.class);
        MethodDefinition hashFlat = strategyDefinition.method(
                "hashFlat",
                long.class,
                hashFixed,
                hashFixedOffset,
                hashVariable,
                hashVariableOffset).access(PUBLIC);
        hashFlat.body().ret(invokeStatic(
                hashFlatChunk,
                hashFlat.thisVariable().getField(operatorField),
                hashFixed,
                hashFixedOffset,
                hashVariable,
                hashVariableOffset));

        ClassModel chunk = chunkDefinition.build();
        ClassModel strategy = strategyDefinition.build();
        DefinedClasses classes = StandardClassDefiner.builder(TestFlatHashStrategyCompilerClassfile.class.getClassLoader())
                .build()
                .defineClasses(List.of(chunk, strategy));
        return classes.definedClass(strategy, FlatHashStrategy.class).getConstructor().newInstance();
    }

    private static MethodDefinition generateGetTotalVariableWidthChunk(ClassDefinition definition)
    {
        Parameter operator = arg("operator", FlatValueOperator.class);
        Parameter values = arg("values", int[].class);
        MethodDefinition method = definition.method("getTotalVariableWidth", int.class, operator, values).access(PUBLIC, STATIC);
        Variable size = method.body().declare("size", constantInt(0));
        for (int field = 0; field < COLUMN_COUNT; field++) {
            method.body().append(size.set(size.add(operator.invoke("variableWidth", int.class, values.getElement(field)))));
        }
        method.body().ret(size);
        return method;
    }

    private static MethodDefinition generateWriteFlatChunk(ClassDefinition definition)
    {
        Parameter operator = arg("operator", FlatValueOperator.class);
        Parameter values = arg("values", int[].class);
        Parameter fixed = arg("fixed", byte[].class);
        Parameter fixedOffset = arg("fixedOffset", int.class);
        Parameter variable = arg("variable", byte[].class);
        Parameter variableOffset = arg("variableOffset", int.class);
        MethodDefinition method = definition.method(
                "writeFlat",
                void.class,
                operator,
                values,
                fixed,
                fixedOffset,
                variable,
                variableOffset).access(PUBLIC, STATIC);
        Variable currentVariableOffset = method.body().declare("currentVariableOffset", variableOffset);
        for (int field = 0; field < COLUMN_COUNT; field++) {
            BytecodeExpression value = values.getElement(field);
            method.body()
                    .append(operator.invoke(
                            "write",
                            void.class,
                            value,
                            fixed,
                            fixedOffset.add(constantInt(field * FIXED_SIZE)),
                            variable,
                            currentVariableOffset))
                    .append(currentVariableOffset.set(currentVariableOffset.add(operator.invoke("variableWidth", int.class, value))));
        }
        method.body().ret();
        return method;
    }

    private static MethodDefinition generateReadFlatChunk(ClassDefinition definition)
    {
        Parameter operator = arg("operator", FlatValueOperator.class);
        Parameter fixed = arg("fixed", byte[].class);
        Parameter fixedOffset = arg("fixedOffset", int.class);
        Parameter variable = arg("variable", byte[].class);
        Parameter variableOffset = arg("variableOffset", int.class);
        MethodDefinition method = definition.method(
                "readFlat",
                int[].class,
                operator,
                fixed,
                fixedOffset,
                variable,
                variableOffset).access(PUBLIC, STATIC);
        Variable result = method.body().declare("result", newArray(int[].class, constantInt(COLUMN_COUNT)));
        Variable currentVariableOffset = method.body().declare("currentVariableOffset", variableOffset);
        for (int field = 0; field < COLUMN_COUNT; field++) {
            BytecodeExpression value = operator.invoke(
                    "read",
                    int.class,
                    fixed,
                    fixedOffset.add(constantInt(field * FIXED_SIZE)),
                    variable,
                    currentVariableOffset);
            method.body()
                    .append(result.setElement(field, value))
                    .append(currentVariableOffset.set(currentVariableOffset.add(operator.invoke("variableWidth", int.class, value))));
        }
        method.body().ret(result);
        return method;
    }

    private static MethodDefinition generateIdenticalChunk(ClassDefinition definition)
    {
        Parameter operator = arg("operator", FlatValueOperator.class);
        Parameter values = arg("values", int[].class);
        Parameter fixed = arg("fixed", byte[].class);
        Parameter fixedOffset = arg("fixedOffset", int.class);
        Parameter variable = arg("variable", byte[].class);
        Parameter variableOffset = arg("variableOffset", int.class);
        MethodDefinition method = definition.method(
                "valueIdentical",
                boolean.class,
                operator,
                values,
                fixed,
                fixedOffset,
                variable,
                variableOffset).access(PUBLIC, STATIC);
        Variable currentVariableOffset = method.body().declare("currentVariableOffset", variableOffset);
        for (int field = 0; field < COLUMN_COUNT; field++) {
            BytecodeExpression flatValue = operator.invoke(
                    "read",
                    int.class,
                    fixed,
                    fixedOffset.add(constantInt(field * FIXED_SIZE)),
                    variable,
                    currentVariableOffset);
            method.body()
                    .append(IfStatement.builder()
                            .condition(operator.invoke("identical", boolean.class, values.getElement(field), flatValue).not())
                            .then(constantFalse().ret())
                            .build())
                    .append(currentVariableOffset.set(currentVariableOffset.add(operator.invoke("variableWidth", int.class, flatValue))));
        }
        method.body().ret(constantTrue());
        return method;
    }

    private static MethodDefinition generateHashBlockChunk(ClassDefinition definition)
    {
        Parameter operator = arg("operator", FlatValueOperator.class);
        Parameter values = arg("values", int[].class);
        MethodDefinition method = definition.method("hash", long.class, operator, values).access(PUBLIC, STATIC);
        Variable hash = method.body().declare("hash", constantLong(INITIAL_HASH_VALUE));
        for (int field = 0; field < COLUMN_COUNT; field++) {
            method.body().append(hash.set(invokeStatic(
                    TestFlatHashStrategyCompilerClassfile.class,
                    "combineHash",
                    long.class,
                    hash,
                    operator.invoke("hash", long.class, values.getElement(field)))));
        }
        method.body().ret(hash);
        return method;
    }

    private static MethodDefinition generateHashFlatChunk(ClassDefinition definition)
    {
        Parameter operator = arg("operator", FlatValueOperator.class);
        Parameter fixed = arg("fixed", byte[].class);
        Parameter fixedOffset = arg("fixedOffset", int.class);
        Parameter variable = arg("variable", byte[].class);
        Parameter variableOffset = arg("variableOffset", int.class);
        MethodDefinition method = definition.method(
                "hashFlat",
                long.class,
                operator,
                fixed,
                fixedOffset,
                variable,
                variableOffset).access(PUBLIC, STATIC);
        Variable hash = method.body().declare("hash", constantLong(INITIAL_HASH_VALUE));
        Variable currentVariableOffset = method.body().declare("currentVariableOffset", variableOffset);
        for (int field = 0; field < COLUMN_COUNT; field++) {
            BytecodeExpression value = operator.invoke(
                    "read",
                    int.class,
                    fixed,
                    fixedOffset.add(constantInt(field * FIXED_SIZE)),
                    variable,
                    currentVariableOffset);
            method.body()
                    .append(hash.set(invokeStatic(
                            TestFlatHashStrategyCompilerClassfile.class,
                            "combineHash",
                            long.class,
                            hash,
                            operator.invoke("hash", long.class, value))))
                    .append(currentVariableOffset.set(currentVariableOffset.add(operator.invoke("variableWidth", int.class, value))));
        }
        method.body().ret(hash);
        return method;
    }

    public static long combineHash(long currentHash, long valueHash)
    {
        return (currentHash * 31) + valueHash;
    }

    private static ClassDesc generatedType(String simpleName)
    {
        return ClassDesc.of(TestFlatHashStrategyCompilerClassfile.class.getPackageName() + "." + simpleName);
    }

    public interface FlatHashStrategy
    {
        List<String> types();

        boolean isAnyVariableWidth();

        int getTotalFlatFixedLength();

        int getTotalVariableWidth(int[] values);

        void writeFlat(int[] values, byte[] fixed, int fixedOffset, byte[] variable, int variableOffset);

        int[] readFlat(byte[] fixed, int fixedOffset, byte[] variable, int variableOffset);

        boolean valueIdentical(int[] values, byte[] fixed, int fixedOffset, byte[] variable, int variableOffset);

        long hash(int[] values);

        long hashFlat(byte[] fixed, int fixedOffset, byte[] variable, int variableOffset);
    }

    public record FlatValueOperator(int salt)
    {
        public int variableWidth(int value)
        {
            return value == NULL_SENTINEL ? 0 : value & 3;
        }

        public void write(int value, byte[] fixed, int fixedOffset, byte[] variable, int variableOffset)
        {
            fixed[fixedOffset] = (byte) (value == NULL_SENTINEL ? 1 : 0);
            writeInt(fixed, fixedOffset + 1, value);
            Arrays.fill(variable, variableOffset, variableOffset + variableWidth(value), (byte) (value + salt));
        }

        public int read(byte[] fixed, int fixedOffset, byte[] variable, int variableOffset)
        {
            return fixed[fixedOffset] == 1 ? NULL_SENTINEL : readInt(fixed, fixedOffset + 1);
        }

        public boolean identical(int left, int right)
        {
            return left == right;
        }

        public long hash(int value)
        {
            return value == NULL_SENTINEL ? 0x9E37_79B9L : (value * 37L) + salt;
        }

        private static void writeInt(byte[] target, int offset, int value)
        {
            target[offset] = (byte) value;
            target[offset + 1] = (byte) (value >>> 8);
            target[offset + 2] = (byte) (value >>> 16);
            target[offset + 3] = (byte) (value >>> 24);
        }

        private static int readInt(byte[] source, int offset)
        {
            return (source[offset] & 0xFF) |
                    ((source[offset + 1] & 0xFF) << 8) |
                    ((source[offset + 2] & 0xFF) << 16) |
                    ((source[offset + 3] & 0xFF) << 24);
        }
    }
}
