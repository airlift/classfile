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

import java.awt.Point;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.constantDouble;
import static io.airlift.classfile.BytecodeExpressions.constantFalse;
import static io.airlift.classfile.BytecodeExpressions.constantFloat;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
import static io.airlift.classfile.BytecodeExpressions.constantNumber;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.BytecodeExpressions.defaultValue;
import static io.airlift.classfile.BytecodeExpressions.getStatic;
import static io.airlift.classfile.BytecodeExpressions.inlineIf;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.ClassfileExpressionAssertions.assertExpression;
import static io.airlift.classfile.ClassfileExpressionAssertions.evaluate;
import static io.airlift.classfile.ClassfileTestUtils.defineHidden;
import static io.airlift.classfile.DescriptorUtils.classDesc;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_byte;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;

class TestExpressionBehaviorMatrix
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();

    public static final boolean BOOLEAN_VALUE = true;
    public static final byte BYTE_VALUE = 99;
    public static final char CHAR_VALUE = 11;
    public static final short SHORT_VALUE = 22;
    public static final int INT_VALUE = 33;
    public static final long LONG_VALUE = 44;
    public static final float FLOAT_VALUE = 3.3f;
    public static final double DOUBLE_VALUE = 4.4;

    private static int intCallCount;
    private static int longCallCount;

    @Test
    void testArithmeticMatrix()
            throws Exception
    {
        assertExpression(constantInt(3).add(constantInt(7)), 3 + 7, "(3 + 7)");
        assertExpression(constantLong(3).add(constantLong(7)), 3L + 7L, "(3L + 7L)");
        assertExpression(constantFloat(3.1f).add(constantFloat(7.5f)), 3.1f + 7.5f, "(3.1f + 7.5f)");
        assertExpression(constantDouble(3.1).add(constantDouble(7.5)), 3.1 + 7.5, "(3.1 + 7.5)");

        assertExpression(constantInt(3).subtract(constantInt(7)), 3 - 7, "(3 - 7)");
        assertExpression(constantLong(3).subtract(constantLong(7)), 3L - 7L, "(3L - 7L)");
        assertExpression(constantFloat(3.1f).subtract(constantFloat(7.5f)), 3.1f - 7.5f, "(3.1f - 7.5f)");
        assertExpression(constantDouble(3.1).subtract(constantDouble(7.5)), 3.1 - 7.5, "(3.1 - 7.5)");

        assertExpression(constantInt(3).multiply(constantInt(7)), 3 * 7, "(3 * 7)");
        assertExpression(constantLong(3).multiply(constantLong(7)), 3L * 7L, "(3L * 7L)");
        assertExpression(constantFloat(3.1f).multiply(constantFloat(7.5f)), 3.1f * 7.5f, "(3.1f * 7.5f)");
        assertExpression(constantDouble(3.1).multiply(constantDouble(7.5)), 3.1 * 7.5, "(3.1 * 7.5)");

        assertExpression(constantInt(7).divide(constantInt(3)), 7 / 3, "(7 / 3)");
        assertExpression(constantLong(7).divide(constantLong(3)), 7L / 3L, "(7L / 3L)");
        assertExpression(constantFloat(3.1f).divide(constantFloat(7.5f)), 3.1f / 7.5f, "(3.1f / 7.5f)");
        assertExpression(constantDouble(3.1).divide(constantDouble(7.5)), 3.1 / 7.5, "(3.1 / 7.5)");

        assertExpression(constantInt(7).remainder(constantInt(3)), 7 % 3, "(7 % 3)");
        assertExpression(constantLong(7).remainder(constantLong(3)), 7L % 3L, "(7L % 3L)");
        assertExpression(constantFloat(3.1f).remainder(constantFloat(7.5f)), 3.1f % 7.5f, "(3.1f % 7.5f)");
        assertExpression(constantDouble(3.1).remainder(constantDouble(7.5)), 3.1 % 7.5, "(3.1 % 7.5)");

        assertExpression(constantInt(7).shiftLeft(constantInt(3)), 7 << 3, "(7 << 3)");
        assertExpression(constantLong(7).shiftLeft(constantInt(3)), 7L << 3, "(7L << 3)");
        assertExpression(constantInt(-7).shiftRight(constantInt(3)), -7 >> 3, "(-7 >> 3)");
        assertExpression(constantLong(-7).shiftRight(constantInt(3)), -7L >> 3, "(-7L >> 3)");
        assertExpression(constantInt(-7).shiftRightUnsigned(constantInt(3)), -7 >>> 3, "(-7 >>> 3)");
        assertExpression(constantLong(-7).shiftRightUnsigned(constantInt(3)), -7L >>> 3, "(-7L >>> 3)");

        assertExpression(constantInt(101).bitwiseAnd(constantInt(37)), 101 & 37, "(101 & 37)");
        assertExpression(constantLong(101).bitwiseAnd(constantLong(37)), 101L & 37L, "(101L & 37L)");
        assertExpression(constantInt(101).bitwiseOr(constantInt(37)), 101 | 37, "(101 | 37)");
        assertExpression(constantLong(101).bitwiseOr(constantLong(37)), 101L | 37L, "(101L | 37L)");
        assertExpression(constantInt(101).bitwiseXor(constantInt(37)), 101 ^ 37, "(101 ^ 37)");
        assertExpression(constantLong(101).bitwiseXor(constantLong(37)), 101L ^ 37L, "(101L ^ 37L)");

        assertExpression(constantInt(3).negate(), -3, "-(3)");
        assertExpression(constantLong(3).negate(), -3L, "-(3L)");
        assertExpression(constantFloat(3.1f).negate(), -3.1f, "-(3.1f)");
        assertExpression(constantDouble(3.1).negate(), -3.1, "-(3.1)");
    }

    @Test
    void testNarrowPrimitiveOperationsUseJavaNumericPromotion()
            throws Exception
    {
        BytecodeExpression byteMaximum = constantInt(Byte.MAX_VALUE).cast(byte.class);
        BytecodeExpression byteOne = constantInt(1).cast(byte.class);
        BytecodeExpression byteOverflow = byteMaximum.add(byteOne);
        assertThat(byteOverflow.type()).isEqualTo(CD_int);
        assertExpression(byteOverflow.cast(Object.class), 128, "((Object) (((byte) 127) + ((byte) 1)))");
        assertExpression(byteOverflow.cast(byte.class).cast(Object.class), (byte) -128, "((Object) ((byte) (((byte) 127) + ((byte) 1))))");
        assertExpression(byteOverflow.add(byteOne), 129, "((((byte) 127) + ((byte) 1)) + ((byte) 1))");

        BytecodeExpression shortValue = constantInt(1).cast(short.class);
        BytecodeExpression charValue = constantInt(1).cast(char.class);
        assertThat(shortValue.negate().type()).isEqualTo(CD_int);
        assertThat(shortValue.bitwiseOr(shortValue).type()).isEqualTo(CD_int);
        assertThat(charValue.shiftLeft(constantInt(1)).type()).isEqualTo(CD_int);
    }

    @Test
    void testNarrowPrimitiveBoundariesNormalizeValues()
            throws Exception
    {
        ClassDesc type = generatedType("NarrowBoundaries");
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);

        MethodDefinition incrementByte = definition.method("incrementByte", Object.class).access(PUBLIC, STATIC);
        Variable byteValue = incrementByte.body().declare("value", constantInt(Byte.MAX_VALUE).cast(byte.class));
        incrementByte.body()
                .append(byteValue.increment())
                .ret(byteValue.cast(Object.class));

        MethodDefinition incrementShort = definition.method("incrementShort", Object.class).access(PUBLIC, STATIC);
        Variable shortValue = incrementShort.body().declare("value", constantInt(Short.MAX_VALUE).cast(short.class));
        incrementShort.body()
                .append(shortValue.increment())
                .ret(shortValue.cast(Object.class));

        MethodDefinition incrementChar = definition.method("incrementChar", Object.class).access(PUBLIC, STATIC);
        Variable charValue = incrementChar.body().declare("value", constantInt(Character.MAX_VALUE).cast(char.class));
        incrementChar.body()
                .append(charValue.increment())
                .ret(charValue.cast(Object.class));

        MethodDefinition assignByte = definition.method("assignByte", Object.class).access(PUBLIC, STATIC);
        Variable assigned = assignByte.body().declare("value", defaultValue(byte.class));
        assignByte.body()
                .append(assigned.set(constantInt(128)))
                .ret(assigned.cast(Object.class));

        MethodDefinition invokeByte = definition.method("invokeByte", Object.class).access(PUBLIC, STATIC);
        invokeByte.body().ret(invokeStatic(
                TestExpressionBehaviorMatrix.class,
                "boxByte",
                MethodTypeDesc.of(CD_Object, CD_byte),
                constantInt(128)));

        MethodDefinition returnedByte = definition.method("returnedByte", Object.class).access(PUBLIC, STATIC);
        returnedByte.body().ret(invokeStatic(
                TestExpressionBehaviorMatrix.class,
                "narrowByte",
                MethodTypeDesc.of(CD_byte, CD_int),
                constantInt(128)).cast(Object.class));

        Class<?> generated = define(definition.build());
        assertThat(generated.getMethod("incrementByte").invoke(null)).isEqualTo((byte) -128);
        assertThat(generated.getMethod("incrementShort").invoke(null)).isEqualTo((short) -32768);
        assertThat(generated.getMethod("incrementChar").invoke(null)).isEqualTo((char) 0);
        assertThat(generated.getMethod("assignByte").invoke(null)).isEqualTo((byte) -128);
        assertThat(generated.getMethod("invokeByte").invoke(null)).isEqualTo((byte) -128);
        assertThat(generated.getMethod("returnedByte").invoke(null)).isEqualTo((byte) -128);
    }

    @Test
    void testComparisonMatrixIncludingNanDirection()
            throws Exception
    {
        assertOrderedComparisons(constantInt(3), constantInt(7), true, false, true, false);
        assertOrderedComparisons(constantLong(3), constantLong(7), true, false, true, false);
        assertOrderedComparisons(constantFloat(3.3f), constantFloat(7.7f), true, false, true, false);
        assertOrderedComparisons(constantDouble(3.3), constantDouble(7.7), true, false, true, false);

        assertOrderedComparisons(constantFloat(Float.NaN), constantFloat(7.7f), false, false, false, false);
        assertOrderedComparisons(constantFloat(7.7f), constantFloat(Float.NaN), false, false, false, false);
        assertOrderedComparisons(constantDouble(Double.NaN), constantDouble(7.7), false, false, false, false);
        assertOrderedComparisons(constantDouble(7.7), constantDouble(Double.NaN), false, false, false, false);

        assertExpression(constantInt(7).equal(constantInt(7)), true, "(7 == 7)");
        assertExpression(constantLong(7).notEqual(constantLong(3)), true, "(7L != 3L)");
        assertExpression(constantFloat(Float.NaN).equal(constantFloat(Float.NaN)), false, "(NaNf == NaNf)");
        assertExpression(constantDouble(Double.NaN).notEqual(constantDouble(Double.NaN)), true, "(NaN != NaN)");
        assertExpression(constantString("same").equal(constantString("same")), true, "(\"same\" == \"same\")");
        assertExpression(constantString("left").notEqual(constantString("right")), true, "(\"left\" != \"right\")");
    }

    @Test
    void testLogicalAndConditionalMatrix()
            throws Exception
    {
        assertExpression(constantTrue().and(constantTrue()), true, "(true && true)");
        assertExpression(constantTrue().and(constantFalse()), false, "(true && false)");
        assertExpression(constantFalse().and(constantTrue()), false, "(false && true)");
        assertExpression(constantFalse().and(constantFalse()), false, "(false && false)");
        assertExpression(constantTrue().or(constantTrue()), true, "(true || true)");
        assertExpression(constantTrue().or(constantFalse()), true, "(true || false)");
        assertExpression(constantFalse().or(constantTrue()), true, "(false || true)");
        assertExpression(constantFalse().or(constantFalse()), false, "(false || false)");
        assertExpression(constantTrue().not(), false, "(!true)");
        assertExpression(constantFalse().not(), true, "(!false)");
        assertExpression(constantNull(Object.class).isNull(), true, "(null == null)");
        assertExpression(constantString("value").isNotNull(), true, "(\"value\" != null)");
        assertExpression(inlineIf(constantTrue(), constantString("T"), constantString("F")), "T", "(true ? \"T\" : \"F\")");
        assertExpression(inlineIf(constantFalse(), constantString("T"), constantString("F")), "F", "(false ? \"T\" : \"F\")");
    }

    @Test
    void testConstantAndDefaultMatrix()
            throws Exception
    {
        assertExpression(constantNumber((byte) 7), 7, "7");
        assertExpression(constantNumber((short) 8), 8, "8");
        assertExpression(constantNumber(9), 9, "9");
        assertExpression(constantNumber(10L), 10L, "10L");
        assertExpression(constantNumber(11.5f), 11.5f, "11.5f");
        assertExpression(constantNumber(12.5), 12.5, "12.5");

        assertExpression(defaultValue(boolean.class), false, "false");
        assertExpression(defaultValue(byte.class), (byte) 0, "0");
        assertExpression(defaultValue(char.class), (char) 0, "0");
        assertExpression(defaultValue(short.class), (short) 0, "0");
        assertExpression(defaultValue(int.class), 0, "0");
        assertExpression(defaultValue(long.class), 0L, "0L");
        assertExpression(defaultValue(float.class), 0.0f, "0.0f");
        assertExpression(defaultValue(double.class), 0.0, "0.0");
        assertExpression(defaultValue(Object.class), null, "null");
    }

    @Test
    void testPrimitiveCastMatrixIncludingBoxing()
            throws Exception
    {
        BytecodeExpression booleanValue = getStatic(TestExpressionBehaviorMatrix.class, "BOOLEAN_VALUE");
        assertCast(booleanValue, boolean.class, true);

        List<PrimitiveSource> sources = List.of(
                new PrimitiveSource("BYTE_VALUE", 99),
                new PrimitiveSource("CHAR_VALUE", 11),
                new PrimitiveSource("SHORT_VALUE", 22),
                new PrimitiveSource("INT_VALUE", 33),
                new PrimitiveSource("LONG_VALUE", 44),
                new PrimitiveSource("FLOAT_VALUE", 3.3f),
                new PrimitiveSource("DOUBLE_VALUE", 4.4));
        List<Class<?>> targets = List.of(byte.class, char.class, short.class, int.class, long.class, float.class, double.class);

        for (PrimitiveSource source : sources) {
            BytecodeExpression expression = getStatic(TestExpressionBehaviorMatrix.class, source.fieldName());
            for (Class<?> target : targets) {
                assertCast(expression, target, converted(source.value(), target));
            }
        }

        assertExpression(getStatic(TestExpressionBehaviorMatrix.class, "INT_VALUE").cast(Object.class).cast(int.class),
                33,
                "((int) ((Object) TestExpressionBehaviorMatrix.INT_VALUE))");
        assertExpression(getStatic(TestExpressionBehaviorMatrix.class, "INT_VALUE").cast(Integer.class),
                33,
                "((Integer) TestExpressionBehaviorMatrix.INT_VALUE)");
    }

    @Test
    void testArrayMatrixIncludingStores()
            throws Exception
    {
        List<ArrayCase> cases = List.of(
                new ArrayCase(boolean[].class, constantTrue(), true),
                new ArrayCase(byte[].class, constantInt(7).cast(byte.class), (byte) 7),
                new ArrayCase(char[].class, constantInt(8).cast(char.class), (char) 8),
                new ArrayCase(short[].class, constantInt(9).cast(short.class), (short) 9),
                new ArrayCase(int[].class, constantInt(10), 10),
                new ArrayCase(long[].class, constantLong(11), 11L),
                new ArrayCase(float[].class, constantFloat(12.5f), 12.5f),
                new ArrayCase(double[].class, constantDouble(13.5), 13.5),
                new ArrayCase(String[].class, constantString("value"), "value"));

        for (ArrayCase arrayCase : cases) {
            String component = arrayCase.arrayType().componentType().getSimpleName();
            assertExpression(newArray(arrayCase.arrayType(), 3).length(), 3, "new " + component + "[3].length");
            assertExpression(newArray(arrayCase.arrayType(), List.of(arrayCase.value())).getElement(0),
                    arrayCase.expected(),
                    "new " + component + "[] {" + arrayCase.value() + "}[0]");
            assertArrayStore(arrayCase);
        }
    }

    @Test
    void testInstanceFieldSetAndCategoryOneAndTwoPop()
            throws Exception
    {
        ClassDesc type = generatedType("Fields");
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition method = definition.method("value", Point.class).access(PUBLIC, STATIC);
        Variable point = method.body().declare("point", BytecodeExpressions.newInstance(Point.class, constantInt(3), constantInt(7)));
        method.body()
                .append(point.setField(Point.class, "x", constantInt(42)))
                .ret(point);

        Class<?> generated = define(definition.build());
        assertThat(generated.getMethod("value").invoke(null)).isEqualTo(new Point(42, 7));

        intCallCount = 0;
        assertThat(evaluate(invokeStatic(TestExpressionBehaviorMatrix.class, "incrementAndGetInt", int.class).pop())).isNull();
        assertThat(intCallCount).isEqualTo(1);

        longCallCount = 0;
        assertThat(evaluate(invokeStatic(TestExpressionBehaviorMatrix.class, "incrementAndGetLong", long.class).pop())).isNull();
        assertThat(longCallCount).isEqualTo(1);
    }

    @Test
    void testSymbolicAndReflectionExpressionOverloads()
            throws Exception
    {
        assertExpression(
                constantString("value").cast(CD_Object).instanceOf(CD_String),
                true,
                "(((Object) \"value\") instanceof String)");

        Method max = Math.class.getMethod("max", int.class, int.class);
        assertExpression(invokeStatic(max, constantInt(11), constantInt(7)), 11, "Math.max(11, 7)");
        assertExpression(
                invokeStatic(classDesc(Math.class), "max", CD_int, constantInt(3), constantInt(9)),
                9,
                "Math.max(3, 9)");

        ClassDefinition definition = ClassDefinition.define(generatedType("FieldOverloads")).access(PUBLIC, FINAL);
        MethodDefinition method = definition.method("value", int.class).access(PUBLIC, STATIC);
        Variable point = method.body().declare("point", BytecodeExpressions.newInstance(Point.class, constantInt(1), constantInt(2)));
        Field y = Point.class.getField("y");
        method.body()
                .append(point.setField("x", constantInt(40)))
                .append(point.setField(y, constantInt(2)))
                .ret(point.getField(classDesc(Point.class), "x", CD_int).add(point.getField("y", int.class)));

        assertThat(define(definition.build()).getMethod("value").invoke(null)).isEqualTo(42);
    }

    @Test
    void testInvokeSpecialCallsSuperclassImplementation()
            throws Exception
    {
        ClassDefinition definition = ClassDefinition.define(generatedType("InvokeSpecial"))
                .access(PUBLIC, FINAL)
                .superClass(SpecialBase.class);
        definition.defaultConstructor().access(PUBLIC);
        MethodDefinition method = definition.method("value", int.class).access(PUBLIC);
        method.body().ret(method.thisVariable().invokeSpecial(
                classDesc(SpecialBase.class),
                "value",
                MethodTypeDesc.of(CD_int, CD_int),
                constantInt(20)));

        Object instance = define(definition.build()).getConstructor().newInstance();
        assertThat(instance.getClass().getMethod("value").invoke(instance)).isEqualTo(42);
    }

    public static int incrementAndGetInt()
    {
        return ++intCallCount;
    }

    public static long incrementAndGetLong()
    {
        return ++longCallCount;
    }

    private static void assertOrderedComparisons(
            BytecodeExpression left,
            BytecodeExpression right,
            boolean lessThan,
            boolean greaterThan,
            boolean lessThanOrEqual,
            boolean greaterThanOrEqual)
            throws Exception
    {
        assertExpression(left.lessThan(right), lessThan, "(" + left + " < " + right + ")");
        assertExpression(left.greaterThan(right), greaterThan, "(" + left + " > " + right + ")");
        assertExpression(left.lessThanOrEqual(right), lessThanOrEqual, "(" + left + " <= " + right + ")");
        assertExpression(left.greaterThanOrEqual(right), greaterThanOrEqual, "(" + left + " >= " + right + ")");
    }

    private static void assertCast(BytecodeExpression source, Class<?> target, Object expected)
            throws Exception
    {
        BytecodeExpression cast = source.cast(target);
        assertExpression(cast, expected, castRendering(target, source.toString()));

        Class<?> boxedType = boxedType(target);
        BytecodeExpression boxed = cast.cast(boxedType);
        assertExpression(boxed, expected, castRendering(boxedType, cast.toString()));

        BytecodeExpression unboxed = boxed.cast(target);
        assertExpression(unboxed, expected, castRendering(target, boxed.toString()));
    }

    private static void assertArrayStore(ArrayCase arrayCase)
            throws Exception
    {
        ClassDesc type = generatedType("ArrayStore");
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition method = definition.method("value", arrayCase.arrayType().componentType()).access(PUBLIC, STATIC);
        Variable array = method.body().declare("array", newArray(arrayCase.arrayType(), 1));
        BytecodeExpression setElement = array.setElement(0, arrayCase.value());
        assertThat(setElement.toString()).isEqualTo("array[0] = " + arrayCase.value() + ";");
        method.body()
                .append(setElement)
                .ret(array.getElement(0));

        Class<?> generated = define(definition.build());
        assertThat(generated.getMethod("value").invoke(null)).isEqualTo(arrayCase.expected());
    }

    private static Class<?> define(ClassModel definition)
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompiledClass compiledClass = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup)).compileClass(definition);
        return defineHidden(lookup, compiledClass).lookupClass();
    }

    private static Object converted(Number value, Class<?> target)
    {
        if (target == byte.class) {
            return value.byteValue();
        }
        if (target == char.class) {
            return (char) value.intValue();
        }
        if (target == short.class) {
            return value.shortValue();
        }
        if (target == int.class) {
            return value.intValue();
        }
        if (target == long.class) {
            return value.longValue();
        }
        if (target == float.class) {
            return value.floatValue();
        }
        if (target == double.class) {
            return value.doubleValue();
        }
        throw new IllegalArgumentException("Unsupported primitive target: " + target);
    }

    private static Class<?> boxedType(Class<?> primitiveType)
    {
        if (primitiveType == boolean.class) {
            return Boolean.class;
        }
        if (primitiveType == byte.class) {
            return Byte.class;
        }
        if (primitiveType == char.class) {
            return Character.class;
        }
        if (primitiveType == short.class) {
            return Short.class;
        }
        if (primitiveType == int.class) {
            return Integer.class;
        }
        if (primitiveType == long.class) {
            return Long.class;
        }
        if (primitiveType == float.class) {
            return Float.class;
        }
        if (primitiveType == double.class) {
            return Double.class;
        }
        throw new IllegalArgumentException("Not a primitive value type: " + primitiveType);
    }

    private static String castRendering(Class<?> target, String value)
    {
        return "((" + target.getSimpleName() + ") " + value + ")";
    }

    private static ClassDesc generatedType(String suffix)
    {
        return ClassDesc.of(TestExpressionBehaviorMatrix.class.getPackageName() + ".Generated" + suffix + NEXT_CLASS_ID.incrementAndGet());
    }

    private record PrimitiveSource(String fieldName, Number value) {}

    private record ArrayCase(Class<?> arrayType, BytecodeExpression value, Object expected) {}

    public static Object boxByte(byte value)
    {
        return value;
    }

    public static byte narrowByte(int value)
    {
        return (byte) value;
    }

    public static class SpecialBase
    {
        public int value(int input)
        {
            return input + 22;
        }
    }
}
