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
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.constantFalse;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.BytecodeExpressions.invokeDynamic;
import static io.airlift.classfile.DescriptorUtils.classDesc;
import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;

class TestTrinoWorkloadFixtures
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();

    @Test
    void testStateCompilerShape()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("State");
        FieldDefinition valueField = classDefinition.field("value", CD_long).access(PRIVATE).build();

        MethodDefinition constructor = classDefinition.constructor().access(PUBLIC);
        constructor.body()
                .invokeSuperConstructor()
                .ret();

        Parameter delta = Parameter.arg("delta", long.class);
        MethodDefinition add = classDefinition.method("add", CD_long, delta).access(PUBLIC);
        BytecodeExpression value = add.thisVariable().getField(valueField).add(delta);
        add.body().append(add.thisVariable().setField(valueField, value));
        add.body().append(add.thisVariable().getField(valueField).ret());

        Class<?> generated = define(classDefinition.build());
        Object state = generated.getConstructor().newInstance();
        assertThat(generated.getMethod("add", long.class).invoke(state, 11L)).isEqualTo(11L);
        assertThat(generated.getMethod("add", long.class).invoke(state, 7L)).isEqualTo(18L);
    }

    @Test
    void testJoinCompilerShape()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("Join");
        Parameter left = Parameter.arg("left", int[].class);
        Parameter right = Parameter.arg("right", int[].class);
        MethodDefinition method = classDefinition.method("matches", CD_boolean, left, right).access(PUBLIC, STATIC);
        Variable position = method.body().declare(CD_int, "position");
        CodeLabel mismatch = method.body().label("mismatch");

        CodeBlock test = CodeBlock.block(IfStatement.builder()
                .condition(left.getElement(position).notEqual(right.getElement(position)))
                .then(CodeBlock.blockBuilder().jump(mismatch).build())
                .build());
        method.body().append(position.set(constantInt(0)));
        method.body().append(test);
        method.body().append(position.set(constantInt(1)));
        method.body().append(test);
        method.body().append(constantTrue().ret());
        method.body().mark(mismatch);
        method.body().append(constantFalse().ret());

        Class<?> generated = define(classDefinition.build());
        assertThat(generated.getMethod("matches", int[].class, int[].class)
                .invoke(null, new int[] {1, 2}, new int[] {1, 2})).isEqualTo(true);
        assertThat(generated.getMethod("matches", int[].class, int[].class)
                .invoke(null, new int[] {1, 2}, new int[] {1, 3})).isEqualTo(false);
    }

    @Test
    void testFlatHashStrategyCompilerShape()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("FlatHash");
        Parameter values = Parameter.arg("values", int[].class);
        MethodDefinition method = classDefinition.method("hash", CD_long, values).access(PUBLIC, STATIC);
        Variable hash = method.body().declare(CD_long, "hash");
        method.body().append(hash.set(constantLong(1)));

        ForLoop.Builder loop = ForLoop.builder();
        CodeBlock.Builder initializer = CodeBlock.blockBuilder();
        Variable index = initializer.declare(CD_int, "index");
        initializer.append(index.set(constantInt(0)));
        loop.initialize(initializer.build())
                .condition(index.lessThan(values.length()))
                .update(CodeBlock.block(index.increment()))
                .body(CodeBlock.block(hash.set(hash.multiply(constantLong(31)).add(values.getElement(index).cast(long.class)))));
        method.body().append(loop.build());
        method.body().append(hash.ret());

        Class<?> generated = define(classDefinition.build());
        assertThat(generated.getMethod("hash", int[].class).invoke(null, (Object) new int[] {3, 7})).isEqualTo(1061L);
    }

    @Test
    void testPageFunctionCompilerShape()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("PageFunction");
        Parameter values = Parameter.arg("values", int[].class);
        Parameter position = Parameter.arg("position", int.class);
        MethodDefinition method = classDefinition.method("project", CD_int, values, position).access(PUBLIC, STATIC);
        Variable value = method.body().declare(CD_int, "value");
        method.body().append(value.set(values.getElement(position)));
        method.body().append(IfStatement.builder()
                .condition(value.lessThan(constantInt(0)))
                .then(constantInt(0).ret())
                .otherwise(value.multiply(constantInt(2)).ret())
                .build());

        Class<?> generated = define(classDefinition.build());
        assertThat(generated.getMethod("project", int[].class, int.class).invoke(null, new int[] {4, -1}, 0)).isEqualTo(8);
        assertThat(generated.getMethod("project", int[].class, int.class).invoke(null, new int[] {4, -1}, 1)).isEqualTo(0);
    }

    @Test
    void testLambdaBytecodeGeneratorShape()
            throws Exception
    {
        MethodTypeDesc bootstrapType = MethodTypeDesc.of(
                CD_CallSite,
                CD_MethodHandles_Lookup,
                CD_String,
                CD_MethodType,
                CD_String);
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(
                MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, classDesc(TestTrinoWorkloadFixtures.class), "bootstrap", bootstrapType),
                "lambda",
                MethodTypeDesc.of(CD_String, CD_String),
                "bound");

        ClassDefinition classDefinition = generatedClass("Lambda");
        MethodDefinition method = classDefinition.method("apply", CD_String).access(PUBLIC, STATIC);
        method.body().append(invokeDynamic(callSite, constantString("value")).ret());

        Class<?> generated = define(classDefinition.build());
        assertThat(generated.getMethod("apply").invoke(null)).isEqualTo("lambda-bound-value");
    }

    @Test
    void testInputReferenceCompilerShape()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("InputReference");
        Parameter row = Parameter.arg("row", Object[].class);
        Parameter field = Parameter.arg("field", int.class);
        MethodDefinition method = classDefinition.method("length", CD_int, row, field).access(PUBLIC, STATIC);
        method.body().append(row.getElement(field).cast(String.class).invoke("length", int.class).ret());

        Class<?> generated = define(classDefinition.build());
        assertThat(generated.getMethod("length", Object[].class, int.class)
                .invoke(null, (Object) new Object[] {"ignored", "airlift"}, 1)).isEqualTo(7);
    }

    public static CallSite bootstrap(MethodHandles.Lookup callerLookup, String name, MethodType type, String prefix)
            throws ReflectiveOperationException
    {
        MethodHandle methodHandle = callerLookup.findVirtual(String.class, "concat", MethodType.methodType(String.class, String.class));
        methodHandle = methodHandle.bindTo(name + "-" + prefix + "-");
        return new ConstantCallSite(methodHandle);
    }

    private ClassDefinition generatedClass(String suffix)
    {
        ClassDesc type = ClassDesc.of(getClass().getPackageName() + ".Generated" + suffix + NEXT_CLASS_ID.incrementAndGet());
        return ClassDefinition.define(type).access(PUBLIC, FINAL);
    }

    private static Class<?> define(ClassModel definition)
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompiledClass compiledClass = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup)).compileClass(definition);
        return HiddenClassDefiner.builder(lookup).build().defineCompiledClass(compiledClass).lookupClass();
    }
}
