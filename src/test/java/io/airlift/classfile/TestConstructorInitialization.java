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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.ClassfileTestUtils.defineHidden;
import static io.airlift.classfile.CodeBlock.block;
import static io.airlift.classfile.CodeBlock.blockBuilder;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestConstructorInitialization
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();

    @Test
    void testProcessesArgumentsAndInitializesFieldBeforeSuperConstructor()
            throws Exception
    {
        ClassDefinition definition = generatedClass("FlexibleConstructor").superClass(StringBase.class);
        FieldDefinition lengthField = definition.field("length", int.class).access(PRIVATE, FINAL).build();
        Parameter input = Parameter.arg("input", String.class);
        MethodDefinition constructor = definition.constructor(input).access(PUBLIC);

        Variable normalized = constructor.body().declare("normalized", invokeStatic(
                TestConstructorInitialization.class,
                "normalize",
                String.class,
                input));
        constructor.body()
                .append(IfStatement.builder()
                        .description("reject a null constructor argument")
                        .condition(normalized.equal(constantNull(String.class)))
                        .then(BytecodeExpressions.newInstance(IllegalArgumentException.class, constantString("input is null")).throwObject())
                        .build())
                .append(constructor.thisVariable().setField(lengthField, normalized.invoke("length", int.class)))
                .invokeSuperConstructor(StringBase.class.getConstructor(String.class), normalized)
                .ret();

        MethodDefinition length = definition.method("length", int.class).access(PUBLIC);
        length.body().ret(length.thisVariable().getField(lengthField));

        Class<?> generated = define(definition.build());
        Object instance = generated.getConstructor(String.class).newInstance("  value  ");
        assertThat(generated.getMethod("value").invoke(instance)).isEqualTo("VALUE");
        assertThat(generated.getMethod("length").invoke(instance)).isEqualTo(5);
    }

    @Test
    void testDelegatesToAnotherGeneratedConstructor()
            throws Exception
    {
        ClassDefinition definition = generatedClass("ThisConstructor");
        FieldDefinition valueField = definition.field("value", String.class).access(PRIVATE, FINAL).build();

        Parameter value = Parameter.arg("value", String.class);
        MethodDefinition stringConstructor = definition.constructor(value).access(PUBLIC);
        stringConstructor.body()
                .invokeSuperConstructor()
                .append(stringConstructor.thisVariable().setField(valueField, value))
                .ret();

        Parameter number = Parameter.arg("number", int.class);
        MethodDefinition intConstructor = definition.constructor(number).access(PUBLIC);
        Variable text = intConstructor.body().declare("text", invokeStatic(Integer.class, "toString", String.class, number));
        intConstructor.body()
                .invokeThisConstructor(text)
                .ret();

        MethodDefinition valueMethod = definition.method("value", String.class).access(PUBLIC);
        valueMethod.body().ret(valueMethod.thisVariable().getField(valueField));

        Class<?> generated = define(definition.build());
        Object instance = generated.getConstructor(int.class).newInstance(91);
        assertThat(generated.getMethod("value").invoke(instance)).isEqualTo("91");
    }

    @Test
    void testSelectsConstructorWithStructuredControlFlow()
            throws Exception
    {
        ClassDefinition definition = generatedClass("BranchedConstructor").superClass(OverloadedBase.class);
        Parameter useText = Parameter.arg("useText", boolean.class);
        MethodDefinition constructor = definition.constructor(useText).access(PUBLIC);
        constructor.body()
                .append(IfStatement.builder()
                        .condition(useText)
                        .then(blockBuilder()
                                .invokeSuperConstructor(MethodTypeDesc.of(CD_void, CD_String), constantString("text"))
                                .build())
                        .otherwise(blockBuilder()
                                .invokeSuperConstructor(MethodTypeDesc.of(CD_void, CD_int), constantInt(37))
                                .build())
                        .build())
                .ret();

        Class<?> generated = define(definition.build());
        assertThat(generated.getMethod("value").invoke(generated.getConstructor(boolean.class).newInstance(true))).isEqualTo("text");
        assertThat(generated.getMethod("value").invoke(generated.getConstructor(boolean.class).newInstance(false))).isEqualTo("37");
    }

    @Test
    void testConstructorValidation()
    {
        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("JumpBeforeInitialization");
            MethodDefinition constructor = definition.constructor().access(PUBLIC);
            CodeLabel end = constructor.body().label("end");
            constructor.body().jump(end).mark(end).ret();
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("returns before");

        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("JumpAfterInitialization");
            MethodDefinition constructor = definition.constructor().access(PUBLIC);
            CodeLabel end = constructor.body().label("end");
            constructor.body()
                    .invokeSuperConstructor()
                    .jump(end)
                    .mark(end)
                    .invokeSuperConstructor()
                    .ret();
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");

        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("JumpStateJoin");
            Parameter initialize = Parameter.arg("initialize", boolean.class);
            MethodDefinition constructor = definition.constructor(initialize).access(PUBLIC);
            CodeLabel end = constructor.body().label("end");
            constructor.body()
                    .append(IfStatement.builder()
                            .condition(initialize)
                            .then(blockBuilder().invokeSuperConstructor().jump(end).build())
                            .otherwise(blockBuilder().jump(end).build())
                            .build())
                    .mark(end)
                    .ret();
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("returns before");

        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("InvocationInMethod");
            definition.method("value", void.class).access(PUBLIC)
                    .body()
                    .invokeSuperConstructor()
                    .ret();
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only valid in a constructor");

        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("ThisBeforeSuper");
            MethodDefinition constructor = definition.constructor().access(PUBLIC);
            constructor.body()
                    .append(constructor.thisVariable().invoke("hashCode", int.class))
                    .invokeSuperConstructor()
                    .ret();
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uses this before");

        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("DoubleInitialization");
            definition.constructor().access(PUBLIC)
                    .body()
                    .invokeSuperConstructor()
                    .invokeSuperConstructor()
                    .ret();
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");

        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("ReturnBeforeInitialization");
            definition.constructor().access(PUBLIC).body().ret();
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("returns before");

        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("FallthroughBeforeInitialization");
            definition.constructor().access(PUBLIC).body().comment("no constructor invocation");
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete without invoking");

        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("MissingSiblingConstructor");
            definition.constructor().access(PUBLIC)
                    .body()
                    .invokeThisConstructor(MethodTypeDesc.of(CD_void, CD_String), constantString("missing"))
                    .ret();
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Constructor does not exist");

        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("RecursiveSiblingConstructors");
            MethodDefinition first = definition.constructor().access(PUBLIC);
            MethodDefinition second = definition.constructor(Parameter.arg("value", String.class)).access(PUBLIC);
            first.body()
                    .invokeThisConstructor(second.methodType(), constantString("value"))
                    .ret();
            second.body()
                    .invokeThisConstructor(first.methodType())
                    .ret();
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Recursive constructor delegation");

        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("ProtectedInitialization");
            definition.constructor().access(PUBLIC)
                    .body()
                    .append(TryCatch.builder()
                            .tryBlock(blockBuilder().invokeSuperConstructor().build())
                            .finallyBlock(block(constantInt(1)))
                            .build())
                    .ret();
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protected region");

        assertThatThrownBy(() -> {
            ClassDefinition definition = generatedClass("WrongReflectiveOwner");
            definition.constructor().access(PUBLIC)
                    .body()
                    .invokeSuperConstructor(StringBase.class.getConstructor(String.class), constantString("value"))
                    .ret();
            definition.build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void testConstructorRendering()
    {
        CodeBlock body = blockBuilder()
                .invokeSuperConstructor(MethodTypeDesc.of(CD_void, CD_String), constantString("value"))
                .build();
        assertThat(body.toString()).isEqualTo("{\n    super(\"value\");\n}");
    }

    @Test
    void testConstructorInitializationAcrossStructuredControlFlow()
    {
        ClassDefinition switched = generatedClass("SwitchedInitialization");
        switched.constructor().access(PUBLIC)
                .body()
                .append(SwitchStatement.builder()
                        .expression(constantInt(1))
                        .caseValue(1, blockBuilder().invokeSuperConstructor().build())
                        .defaultCase(blockBuilder().invokeSuperConstructor().build())
                        .build())
                .ret();
        switched.build();

        assertConstructorInvocationRejected("ForLoopInitialization", ForLoop.builder()
                .condition(BytecodeExpressions.constantFalse())
                .body(blockBuilder().invokeSuperConstructor().build())
                .build());
        assertConstructorInvocationRejected("WhileInitialization", WhileLoop.builder()
                .condition(BytecodeExpressions.constantFalse())
                .body(blockBuilder().invokeSuperConstructor().build())
                .build());
        assertConstructorInvocationRejected("DoWhileInitialization", DoWhileLoop.builder()
                .condition(BytecodeExpressions.constantFalse())
                .body(blockBuilder().invokeSuperConstructor().build())
                .build());
        assertConstructorInvocationRejected("CatchInitialization", TryCatch.builder()
                .tryBlock(constantInt(1))
                .catching(RuntimeException.class, "failure", (body, _) -> body.invokeSuperConstructor())
                .build());

        ClassDefinition synthetic = generatedClass("SyntheticInitialization");
        synthetic.constructor().access(PUBLIC)
                .body()
                .append(new ConstructorInvocationExpression())
                .ret();
        assertThatThrownBy(synthetic::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loop or protected region");
    }

    @SuppressWarnings("ReturnMissingNullable") // This helper deliberately preserves null for generated constructor validation.
    public static String normalize(String value)
    {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static ClassDefinition generatedClass(String suffix)
    {
        ClassDesc type = ClassDesc.of(TestConstructorInitialization.class.getPackageName() + ".Generated" + suffix + NEXT_CLASS_ID.incrementAndGet());
        return ClassDefinition.define(type).access(PUBLIC, FINAL);
    }

    private static void assertConstructorInvocationRejected(String suffix, Statement statement)
    {
        ClassDefinition definition = generatedClass(suffix);
        definition.constructor().access(PUBLIC).body().append(statement).ret();
        assertThatThrownBy(definition::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loop or protected region");
    }

    private static Class<?> define(ClassModel definition)
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompiledClass compiledClass = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup)).compileClass(definition);
        return defineHidden(lookup, compiledClass).lookupClass();
    }

    public static class StringBase
    {
        private final String value;

        public StringBase(String value)
        {
            this.value = value;
        }

        public String value()
        {
            return value;
        }
    }

    public static class OverloadedBase
    {
        private final String value;

        public OverloadedBase(String value)
        {
            this.value = value;
        }

        public OverloadedBase(int value)
        {
            this.value = Integer.toString(value);
        }

        public String value()
        {
            return value;
        }
    }

    private static final class ConstructorInvocationExpression
            implements SyntheticExpression
    {
        @Override
        public ClassDesc type()
        {
            return CD_int;
        }

        @Override
        public ExpressionPlan expansion(ExpansionContext context)
        {
            return new ExpressionPlan(blockBuilder().invokeSuperConstructor().build(), constantInt(1));
        }

        @Override
        public String toString()
        {
            return "constructorInvocationExpression";
        }
    }
}
