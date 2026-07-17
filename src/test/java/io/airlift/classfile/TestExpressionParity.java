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
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.boundConstant;
import static io.airlift.classfile.BytecodeExpressions.constantBoolean;
import static io.airlift.classfile.BytecodeExpressions.constantClass;
import static io.airlift.classfile.BytecodeExpressions.constantDouble;
import static io.airlift.classfile.BytecodeExpressions.constantDynamic;
import static io.airlift.classfile.BytecodeExpressions.constantFalse;
import static io.airlift.classfile.BytecodeExpressions.constantFloat;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.BytecodeExpressions.defaultValue;
import static io.airlift.classfile.BytecodeExpressions.getStatic;
import static io.airlift.classfile.BytecodeExpressions.inlineIf;
import static io.airlift.classfile.BytecodeExpressions.invokeDynamic;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.BytecodeExpressions.setStatic;
import static io.airlift.classfile.ClassfileExpressionAssertions.assertExpression;
import static io.airlift.classfile.ClassfileExpressionAssertions.evaluate;
import static io.airlift.classfile.ClassfileTestUtils.defineHidden;
import static io.airlift.classfile.DescriptorUtils.classDesc;
import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.DirectMethodHandleDesc.Kind.STATIC;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestExpressionParity
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();

    public static final Object OBJECT_FIELD = "object";
    public static final int INT_FIELD = 33;
    public static final long LONG_FIELD = 44;
    public static final float FLOAT_FIELD = 3.3f;
    public static final double DOUBLE_FIELD = 4.4;
    public static String mutableField;
    private static int callCount;

    @Test
    void testConstantsAndDefaults()
            throws Exception
    {
        assertExpression(constantNull(List.class), null, "null");
        assertExpression(constantTrue(), true, "true");
        assertExpression(constantFalse(), false, "false");
        assertExpression(constantInt(Integer.MIN_VALUE), Integer.MIN_VALUE, Integer.toString(Integer.MIN_VALUE));
        assertExpression(constantLong(Long.MAX_VALUE), Long.MAX_VALUE, Long.MAX_VALUE + "L");
        assertExpression(constantFloat(Float.NaN), Float.NaN, "NaNf");
        assertExpression(constantDouble(Double.NaN), Double.NaN, "NaN");
        assertExpression(constantString("a\n\"b"), "a\n\"b", "\"a\\n\\\"b\"");
        assertExpression(constantClass(int[].class), int[].class, "int[].class");
        assertExpression(defaultValue(byte.class), (byte) 0, "0");
        assertExpression(defaultValue(Object.class), null, "null");

        DynamicConstantDesc<?> nullConstant = DynamicConstantDesc.ofNamed(
                ConstantDescs.BSM_NULL_CONSTANT,
                "nullValue",
                CD_Object);
        assertExpression(constantDynamic(nullConstant), null, "condy(nullValue)");
    }

    @Test
    void testArithmeticAndBitwise()
            throws Exception
    {
        assertExpression(constantInt(7).add(constantInt(3)), 10, "(7 + 3)");
        assertExpression(constantLong(7).subtract(constantLong(3)), 4L, "(7L - 3L)");
        assertExpression(constantFloat(7.5f).multiply(constantFloat(2)), 15.0f, "(7.5f * 2.0f)");
        assertExpression(constantDouble(7.5).divide(constantDouble(2)), 3.75, "(7.5 / 2.0)");
        assertExpression(constantInt(7).remainder(constantInt(3)), 1, "(7 % 3)");
        assertExpression(constantInt(101).bitwiseAnd(constantInt(37)), 101 & 37, "(101 & 37)");
        assertExpression(constantLong(101).bitwiseOr(constantLong(37)), 101L | 37L, "(101L | 37L)");
        assertExpression(constantInt(101).bitwiseXor(constantInt(37)), 101 ^ 37, "(101 ^ 37)");
        assertExpression(constantInt(-7).shiftRight(constantInt(3)), -7 >> 3, "(-7 >> 3)");
        assertExpression(constantLong(-7).shiftRightUnsigned(constantInt(3)), -7L >>> 3, "(-7L >>> 3)");
        assertExpression(constantInt(3).negate(), -3, "-(3)");
    }

    @Test
    void testComparisonsIncludingNan()
            throws Exception
    {
        assertExpression(constantInt(3).lessThan(constantInt(7)), true, "(3 < 7)");
        assertExpression(constantLong(7).greaterThanOrEqual(constantLong(7)), true, "(7L >= 7L)");
        assertExpression(constantFloat(Float.NaN).lessThan(constantFloat(7)), false, "(NaNf < 7.0f)");
        assertExpression(constantDouble(7).greaterThan(constantDouble(Double.NaN)), false, "(7.0 > NaN)");
        assertExpression(constantDouble(Double.NaN).notEqual(constantDouble(Double.NaN)), true, "(NaN != NaN)");
        assertExpression(constantString("same").equal(constantString("same")), true, "(\"same\" == \"same\")");
    }

    @Test
    void testLogicalOperationsShortCircuit()
            throws Exception
    {
        callCount = 0;
        BytecodeExpression sideEffect = invokeStatic(TestExpressionParity.class, "countAndReturnTrue", boolean.class);
        assertExpression(constantFalse().and(sideEffect), false, "(false && TestExpressionParity.countAndReturnTrue())");
        assertThat(callCount).isZero();
        assertExpression(constantTrue().or(sideEffect), true, "(true || TestExpressionParity.countAndReturnTrue())");
        assertThat(callCount).isZero();
        assertExpression(constantBoolean(true).not(), false, "(!true)");
        assertExpression(constantNull(Object.class).isNull(), true, "(null == null)");
    }

    @Test
    void testArraysAndConditional()
            throws Exception
    {
        assertExpression(newArray(int[].class, List.of(constantInt(3), constantInt(7))), new int[] {3, 7}, "new int[] {3, 7}");
        assertExpression(newArray(boolean[].class, List.of(constantTrue())).getElement(0), true, "new boolean[] {true}[0]");
        assertExpression(newArray(byte[].class, List.of(constantInt(11).cast(byte.class))).getElement(0), (byte) 11, "new byte[] {((byte) 11)}[0]");
        assertExpression(newArray(char[].class, List.of(constantInt(12).cast(char.class))).getElement(0), (char) 12, "new char[] {((char) 12)}[0]");
        assertExpression(newArray(short[].class, List.of(constantInt(13).cast(short.class))).getElement(0), (short) 13, "new short[] {((short) 13)}[0]");
        assertExpression(newArray(String[].class, List.of(constantString("a"), constantString("b"))).getElement(1), "b", "new String[] {\"a\", \"b\"}[1]");
        assertExpression(newArray(int[].class, constantInt(4)).length(), 4, "new int[4].length");
        assertExpression(newArray(long[].class, 5).length(), 5, "new long[5].length");
        assertExpression(inlineIf(constantTrue(), constantString("T"), constantString("F")), "T", "(true ? \"T\" : \"F\")");
        assertThatThrownBy(() -> newArray(int[].class, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("length is negative: -1");
    }

    @Test
    void testPrimitiveReferenceAndBoxedCasts()
            throws Exception
    {
        BytecodeExpression intValue = getStatic(field("INT_FIELD"));
        assertExpression(intValue.cast(long.class), 33L, "((long) TestExpressionParity.INT_FIELD)");
        assertExpression(getStatic(field("DOUBLE_FIELD")).cast(byte.class), (byte) 4, "((byte) TestExpressionParity.DOUBLE_FIELD)");
        assertExpression(intValue.cast(Integer.class), 33, "((Integer) TestExpressionParity.INT_FIELD)");
        assertExpression(intValue.cast(Object.class).cast(int.class), 33, "((int) ((Object) TestExpressionParity.INT_FIELD))");
        assertExpression(getStatic(field("OBJECT_FIELD")).cast(String.class).invoke("length", int.class),
                6,
                "((String) TestExpressionParity.OBJECT_FIELD).length()");
        assertExpression(constantString("value").instanceOf(CharSequence.class), true, "(\"value\" instanceof CharSequence)");
        assertExpression(constantString("value").invoke(CharSequence.class.getMethod("length")), 5, "\"value\".length()");

        assertThatThrownBy(() -> intValue.cast(Double.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> intValue.cast(String.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> constantBoolean(true).cast(int.class)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testFieldsConstructorsAndInvocations()
            throws Exception
    {
        assertExpression(BytecodeExpressions.newInstance(UUID.class, constantLong(3), constantLong(7)), new UUID(3, 7), "new UUID(3L, 7L)");
        assertExpression(BytecodeExpressions.newInstance(Point.class, constantInt(3), constantInt(7)).getField(Point.class.getField("x")), 3, "new Point(3, 7).x");
        assertExpression(BytecodeExpressions.newInstance(Point.class, constantInt(3), constantInt(7)).getField(Point.class, "x"), 3, "new Point(3, 7).x");
        assertExpression(getStatic(Long.class, "MIN_VALUE"), Long.MIN_VALUE, "Long.MIN_VALUE");
        assertExpression(constantString("foo").invoke("concat", String.class, constantString("bar")), "foobar", "\"foo\".concat(\"bar\")");
        assertExpression(boundConstant(List.of(1, 2, 3), List.class).invoke("size", int.class), 3, "bound(List).size()");
        assertExpression(invokeStatic(Math.class, "cos", double.class, constantDouble(3.3)), Math.cos(3.3), "Math.cos(3.3)");
        assertExpression(constantString("foo").invoke(String.class.getMethod("length")), 3, "\"foo\".length()");
        assertExpression(
                constantString("foo").invokeInterface(classDesc(CharSequence.class), "length", MethodTypeDesc.of(CD_int)),
                3,
                "\"foo\".length()");

        mutableField = "before";
        assertExpression(setStatic(TestExpressionParity.class, "mutableField", constantString("after")), null, "TestExpressionParity.mutableField = \"after\";");
        assertThat(mutableField).isEqualTo("after");
    }

    @Test
    void testDynamicInvocation()
            throws Exception
    {
        MethodTypeDesc bootstrapType = MethodTypeDesc.of(
                CD_CallSite,
                CD_MethodHandles_Lookup,
                CD_String,
                CD_MethodType,
                CD_String);
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(
                MethodHandleDesc.ofMethod(STATIC, classDesc(TestExpressionParity.class), "bootstrap", bootstrapType),
                "foo",
                MethodTypeDesc.of(CD_String, CD_String),
                "bar");
        assertExpression(invokeDynamic(callSite, constantString("baz")), "foo-bar-baz", "invokedynamic foo(\"baz\")");
    }

    @Test
    void testVariableAssignmentIncrementAndPop()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(getClass().getPackageName() + ".GeneratedVariables" + NEXT_CLASS_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition method = definition.method("value", CD_int).access(PUBLIC, AccessFlag.STATIC);
        Variable value = method.body().declare(int.class, "value");
        method.body().append(value.set(constantInt(40)));
        assertThat(value.increment().toString()).isEqualTo("value++;");
        method.body().append(value.increment());
        method.body().append(constantLong(9).pop());
        method.body().append(value.ret());

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompiledClass compiledClass = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup)).compileClass(definition.build());
        Class<?> generated = defineHidden(lookup, compiledClass).lookupClass();
        assertThat(generated.getMethod("value").invoke(null)).isEqualTo(41);
        assertThat(evaluate(constantInt(1).pop())).isNull();
    }

    public static boolean countAndReturnTrue()
    {
        callCount++;
        return true;
    }

    public static CallSite bootstrap(MethodHandles.Lookup callerLookup, String name, MethodType type, String prefix)
            throws ReflectiveOperationException
    {
        MethodHandle methodHandle = callerLookup.findVirtual(String.class, "concat", MethodType.methodType(String.class, String.class));
        methodHandle = methodHandle.bindTo(name + "-" + prefix + "-");
        return new ConstantCallSite(methodHandle);
    }

    private static Field field(String name)
    {
        try {
            return TestExpressionParity.class.getField(name);
        }
        catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }
}
