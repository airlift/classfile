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
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Objects;

import static io.airlift.classfile.BytecodeExpressions.boundMethodHandle;
import static io.airlift.classfile.BytecodeExpressions.constantBoolean;
import static io.airlift.classfile.BytecodeExpressions.constantClass;
import static io.airlift.classfile.BytecodeExpressions.constantDouble;
import static io.airlift.classfile.BytecodeExpressions.constantFloat;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.getStatic;
import static io.airlift.classfile.BytecodeExpressions.inlineIf;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.BytecodeExpressions.setStatic;
import static io.airlift.classfile.DescriptorUtils.classDesc;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestExpressionModel
{
    @Test
    void testConstantRendering()
    {
        assertThat(constantBoolean(true).toString()).isEqualTo("true");
        assertThat(constantInt(-17).toString()).isEqualTo("-17");
        assertThat(constantLong(19).toString()).isEqualTo("19L");
        assertThat(constantFloat(Float.NaN).toString()).isEqualTo("NaNf");
        assertThat(constantDouble(Double.NaN).toString()).isEqualTo("NaN");
        assertThat(constantString("a\n\"b").toString()).isEqualTo("\"a\\n\\\"b\"");
        assertThat(constantNull(List.class).toString()).isEqualTo("null");
        assertThat(constantClass(int[].class).toString()).isEqualTo("int[].class");
    }

    @Test
    void testFluentRendering()
    {
        CodeBlock.Builder block = CodeBlock.blockBuilder();
        Variable positions = block.declare(int[].class, "positions");
        Variable index = block.declare(int.class, "index");
        Variable position = block.declare(int.class, "position");

        assertThat(positions.getElement(index).toString()).isEqualTo("positions[index]");
        assertThat(position.set(positions.getElement(index)).toString()).isEqualTo("position = positions[index];");
        assertThat(position.add(constantInt(7)).cast(long.class).multiply(constantLong(3)).toString())
                .isEqualTo("(((long) (position + 7)) * 3L)");
        assertThat(position.greaterThan(constantInt(0)).and(index.lessThan(positions.length())).toString())
                .isEqualTo("((position > 0) && (index < positions.length))");
        assertThat(inlineIf(index.equal(constantInt(0)), constantString("zero"), constantString("many")).toString())
                .isEqualTo("((index == 0) ? \"zero\" : \"many\")");
        assertThat(position.ret().toString()).isEqualTo("return position;");
    }

    @Test
    void testArrayRendering()
    {
        assertThat(newArray(int[].class, constantInt(5)).length().toString()).isEqualTo("new int[5].length");
        assertThat(newArray(classDesc(String[].class), List.of(constantString("a"), constantString("b"))).toString())
                .isEqualTo("new String[] {\"a\", \"b\"}");
    }

    @Test
    void testExactInvocationDescriptorIsPreserved()
    {
        MethodTypeDesc descriptor = MethodTypeDesc.of(CD_Object, CD_Object);
        BytecodeExpression expression = invokeStatic(
                Objects.class,
                "requireNonNull",
                descriptor,
                constantString("value"));

        assertThat(expression.toString()).isEqualTo("Objects." + "requireNonNull(\"value\")");
        ExpressionNode.Invoke invocation = (ExpressionNode.Invoke) ((CoreExpression) expression).node();
        assertThat(invocation.methodType()).isEqualTo(descriptor);
        assertThat(invocation.methodType()).isNotEqualTo(MethodTypeDesc.of(CD_String, CD_String));
    }

    @Test
    void testGeneratedMembersDoNotRequireLoadedClass()
    {
        ClassDesc generatedType = ClassDesc.of("io.airlift.classfile.generated.Example");
        ClassDefinition definition = ClassDefinition.define(generatedType).access(PUBLIC, FINAL);
        FieldDefinition field = definition.field("count", CD_int).access(PRIVATE, STATIC).build();
        MethodDefinition method = definition.method("size", CD_int).access(PUBLIC, STATIC);

        assertThat(getStatic(field).toString()).isEqualTo("Example.count");
        assertThat(invokeStatic(method).toString()).isEqualTo("Example.size()");
        assertThatThrownBy(() -> constantNull(generatedType).invoke(method))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("method is static: int size()");
        assertThatThrownBy(() -> constantNull(generatedType).setField(field, constantInt(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("field is static: private static int count");
        assertThatThrownBy(() -> constantNull(generatedType).invokeSpecial(generatedType, "<init>", MethodTypeDesc.of(CD_void)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("constructor invocation is only valid as a CodeBlock operation");
        assertThat(field.declaringType()).isEqualTo(generatedType);
        assertThat(method.declaringType()).isEqualTo(generatedType);
    }

    @Test
    void testBlockOwnsDeclarations()
    {
        CodeBlock.Builder first = CodeBlock.blockBuilder();
        Variable firstValue = first.declare(int.class, "value");
        CodeBlock.Builder second = CodeBlock.blockBuilder();
        Variable secondValue = second.declare(int.class, "value");

        assertThat(firstValue).isNotSameAs(secondValue);
        assertThatThrownBy(() -> first.declare(int.class, "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Variable already declared in this block: value");
    }

    @Test
    void testDeclareAndInitialize()
    {
        CodeBlock.Builder block = CodeBlock.blockBuilder();
        Variable inferred = block.declare("inferred", constantInt(11));
        Variable explicit = block.declare(long.class, "explicit");
        block.append(explicit.set(inferred.cast(long.class)));

        assertThat(inferred.type()).isEqualTo(CD_int);
        assertThat(explicit.type()).isEqualTo(classDesc(long.class));
        assertThat(block.build().toString()).isEqualTo(
                """
                {
                    int inferred = 11;
                    long explicit;
                    explicit = ((long) inferred);
                }""");
    }

    @Test
    void testBooleanIsNotNarrowIntegerAssignable()
    {
        ClassDefinition definition = ClassDefinition.define(ClassDesc.of("test.BooleanReturn"));
        definition.method("value", boolean.class)
                .access(STATIC)
                .body()
                .ret(constantInt(2));
        assertThatThrownBy(definition::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Return type int is not assignable to boolean");

        CodeBlock.Builder block = CodeBlock.blockBuilder();
        Variable flag = block.declare(boolean.class, "flag");
        assertThatThrownBy(() -> flag.set(constantInt(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("variable value type int is not assignable to boolean");

        ClassDefinition fieldOwner = ClassDefinition.define(ClassDesc.of("test.BooleanField"));
        FieldDefinition field = fieldOwner.field("flag", boolean.class).access(STATIC).build();
        assertThatThrownBy(() -> setStatic(field, constantInt(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("field value type int is not assignable to boolean");

        assertThatThrownBy(() -> invokeStatic(
                Boolean.class,
                "valueOf",
                MethodTypeDesc.of(classDesc(Boolean.class), CD_boolean),
                constantInt(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("argument 0 type int is not assignable to boolean");
        assertThatThrownBy(() -> newArray(boolean[].class, List.of(constantInt(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("array element type int is not assignable to boolean");
        assertThatThrownBy(() -> BytecodeExpressions.newInstance(BooleanConstructor.class, constantInt(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No compatible public constructor");
        assertThatThrownBy(() -> boundMethodHandle(MethodHandles.identity(boolean.class)).invoke(constantInt(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Argument 0 has type int; expected boolean");
    }

    @Test
    void testSourceLikeForLoopRendering()
    {
        CodeBlock.Builder initializer = CodeBlock.blockBuilder();
        Variable index = initializer.declare("index", constantInt(0));
        ForLoop loop = ForLoop.builder()
                .initialize(initializer.build())
                .condition(index.lessThan(constantInt(3)))
                .update(index.increment())
                .body(index.set(index.add(constantInt(1))))
                .build();

        assertThat(loop.toString()).isEqualTo(
                """
                for (int index = 0; (index < 3); index++) {
                    index = (index + 1);
                }""");
    }

    @Test
    void testClassDefinitionRejectsDuplicateMembers()
    {
        ClassDefinition definition = ClassDefinition.define(ClassDesc.of("test.Duplicates")).access(PUBLIC);
        definition.field("value", CD_int).access(PRIVATE).build();
        assertThatThrownBy(() -> definition.field("value", CD_int).access(PRIVATE).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Field already declared: value");

        definition.method("run", CD_void).access(PUBLIC);
        assertThatThrownBy(() -> definition.method("run", CD_void).access(PUBLIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Method already declared: run()V");
    }

    public static final class BooleanConstructor
    {
        public BooleanConstructor(boolean value) {}
    }
}
