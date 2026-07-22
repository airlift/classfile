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

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.constant.ClassDesc;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.getStatic;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.setStatic;
import static java.lang.reflect.AccessFlag.ABSTRACT;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestClassfileWalkingSkeleton
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();

    @Test
    void testCompleteGeneratedClass()
            throws Exception
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("Counter"))
                .access(PUBLIC, FINAL)
                .sourceFile("GeneratedCounter.java");
        FieldDefinition nameField = classDefinition.field("name", String.class).access(PRIVATE, FINAL).build();
        FieldDefinition valueField = classDefinition.field("value", int.class).access(PRIVATE).build();

        Parameter name = Parameter.arg("name", String.class);
        MethodDefinition constructor = classDefinition.constructor(name).access(PUBLIC);
        constructor.body()
                .invokeSuperConstructor()
                .append(constructor.thisVariable().setField(nameField, name))
                .ret();

        Parameter delta = Parameter.arg("delta", int.class);
        MethodDefinition add = classDefinition.method("add", int.class, delta).access(PUBLIC);
        Variable current = add.body().declare(int.class, "current");
        add.body().append(current.set(add.thisVariable().getField(valueField)));

        CodeBlock positive = CodeBlock.block(current.set(current.add(delta)));
        CodeBlock negative = CodeBlock.block(current.set(current.subtract(delta.negate())));
        add.body().append(IfStatement.builder()
                .condition(delta.greaterThan(constantInt(0)))
                .then(positive)
                .otherwise(negative)
                .build());
        add.body().append(add.thisVariable().setField(valueField, current));
        add.body().append(current.ret());

        MethodDefinition description = classDefinition.method("description", String.class).access(PUBLIC);
        description.body().append(description.thisVariable().getField(nameField)
                .invoke("concat", String.class, constantString(":"))
                .ret());

        ClassModel classModel = classDefinition.build();
        Class<?> generated = StandardClassDefiner.builder(getClass().getClassLoader()).build().defineClass(classModel);

        Object instance = generated.getConstructor(String.class).newInstance("count");
        assertThat(generated.getMethod("add", int.class).invoke(instance, 7)).isEqualTo(7);
        assertThat(generated.getMethod("add", int.class).invoke(instance, -2)).isEqualTo(5);
        assertThat(generated.getMethod("description").invoke(instance)).isEqualTo("count:");
    }

    @Test
    void testRepeatedFragmentPlacementBindsOwnedLocalsIndependently()
            throws Exception
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("Dag"))
                .access(PUBLIC, FINAL);
        Parameter input = Parameter.arg("input", int.class);
        MethodDefinition method = classDefinition.method("twice", int.class, input).access(PUBLIC, STATIC);
        Variable result = method.body().declare(int.class, "result");
        method.body().append(result.set(constantInt(0)));

        CodeBlock.Builder fragmentBuilder = CodeBlock.blockBuilder().description("shared fragment");
        Variable temporary = fragmentBuilder.declare(int.class, "temporary");
        fragmentBuilder.append(temporary.set(input.add(constantInt(1))));
        fragmentBuilder.append(result.set(result.add(temporary)));
        CodeBlock fragment = fragmentBuilder.build();

        method.body().append(fragment);
        method.body().append(fragment);
        method.body().append(result.ret());

        ClassModel model = classDefinition.build();
        CompiledClass compiledClass = ClassCompiler.forTarget(CompilationTarget.forClassLoader(getClass().getClassLoader()))
                .compileClass(model);
        CodeAttribute code = (CodeAttribute) ClassFile.of().parse(compiledClass.classfile()).methods().getFirst().code().orElseThrow();
        assertThat(code.maxLocals()).isEqualTo(3);

        Class<?> generated = StandardClassDefiner.builder(getClass().getClassLoader())
                .build()
                .defineClass(model);
        assertThat(generated.getMethod("twice", int.class).invoke(null, 10)).isEqualTo(22);
    }

    @Test
    void testInterfaceConstructorAndClassInitializer()
            throws Exception
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("Lifecycle"))
                .access(PUBLIC, FINAL)
                .addInterface(IntSupplier.class);
        FieldDefinition initialized = classDefinition.field("initialized", long.class).access(PUBLIC, STATIC).build();
        classDefinition.classInitializer().body().append(setStatic(initialized, constantLong(17)));

        MethodDefinition constructor = classDefinition.constructor().access(PUBLIC);
        constructor.body()
                .invokeSuperConstructor()
                .ret();

        MethodDefinition getAsInt = classDefinition.method("getAsInt", int.class).access(PUBLIC);
        getAsInt.body().append(constantInt(42).ret());
        MethodDefinition initializedMethod = classDefinition.method("initialized", long.class).access(PUBLIC, STATIC);
        initializedMethod.body().append(getStatic(initialized).ret());

        Class<?> generated = define(classDefinition.build());
        assertThat(((IntSupplier) generated.getConstructor().newInstance()).getAsInt()).isEqualTo(42);
        assertThat(generated.getMethod("initialized").invoke(null)).isEqualTo(17L);
    }

    @Test
    void testDefaultConstructorHelper()
            throws Exception
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("DefaultConstructor"))
                .access(PUBLIC, FINAL)
                .superClass(DefaultConstructorBase.class);
        classDefinition.defaultConstructor().access(PUBLIC);

        Class<?> generated = define(classDefinition.build());
        assertThat(generated.getConstructor().newInstance()).isInstanceOf(DefaultConstructorBase.class);
    }

    @Test
    void testGeneratedMethodReferenceBeforeClassLoading()
            throws Exception
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("GeneratedLinkage")).access(PUBLIC, FINAL);
        MethodDefinition helper = classDefinition.method("helper", int.class).access(PRIVATE, STATIC);
        helper.body().append(constantInt(9).ret());
        MethodDefinition caller = classDefinition.method("caller", int.class).access(PUBLIC, STATIC);
        caller.body().append(invokeStatic(helper).add(constantInt(1)).ret());

        assertThat(define(classDefinition.build()).getMethod("caller").invoke(null)).isEqualTo(10);
    }

    @Test
    void testRepeatedFragmentPlacementAcrossLoopAndExceptionScopes()
            throws Exception
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("DagContexts")).access(PUBLIC, FINAL);
        MethodDefinition method = classDefinition.method("value", int.class).access(PUBLIC, STATIC);
        Variable result = method.body().declare(int.class, "result");
        method.body().append(result.set(constantInt(0)));

        CodeBlock.Builder fragmentBuilder = CodeBlock.blockBuilder();
        Variable next = fragmentBuilder.declare(int.class, "next");
        fragmentBuilder.append(next.set(result.add(constantInt(1))));
        fragmentBuilder.append(result.set(next));
        CodeBlock fragment = fragmentBuilder.build();

        ForLoop.Builder loop = ForLoop.builder();
        CodeBlock.Builder initializer = CodeBlock.blockBuilder();
        Variable index = initializer.declare(int.class, "index");
        initializer.append(index.set(constantInt(0)));
        method.body().append(loop
                .initialize(initializer.build())
                .condition(index.lessThan(constantInt(2)))
                .update(CodeBlock.block(index.increment()))
                .body(CodeBlock.block(fragment))
                .build());

        CodeBlock failureBlock = CodeBlock.blockBuilder()
                .append(fragment)
                .throwObject(BytecodeExpressions.newInstance(IllegalStateException.class, constantString("failure")))
                .build();
        method.body().append(TryCatch.builder()
                .tryBlock(failureBlock)
                .catching(IllegalStateException.class, "failure", (body, _) -> body.append(fragment))
                .finallyBlock(CodeBlock.block(fragment))
                .build());
        method.body().append(result.ret());

        assertThat(define(classDefinition.build()).getMethod("value").invoke(null)).isEqualTo(5);
    }

    @Test
    void testBuilderSnapshotsAndInvalidCapture()
    {
        CodeBlock.Builder blockBuilder = CodeBlock.blockBuilder();
        blockBuilder.append(constantInt(1).pop());
        CodeBlock first = blockBuilder.build();
        blockBuilder.append(constantInt(2).pop());
        CodeBlock second = blockBuilder.build();
        assertThat(first.toString()).doesNotContain("2;");
        assertThat(second.toString()).contains("2;");

        CodeBlock.Builder owner = CodeBlock.blockBuilder();
        Variable privateVariable = owner.declare(int.class, "privateVariable");
        CodeBlock invalid = CodeBlock.block(privateVariable.ret());

        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("Invalid"));
        MethodDefinition method = classDefinition.method("value", int.class).access(PUBLIC, STATIC);
        method.body().append(invalid);
        assertThatThrownBy(classDefinition::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Variable is not in scope: privateVariable");

        Parameter parameter = Parameter.arg("value", int.class);
        ClassDefinition parameterOwner = ClassDefinition.define(generatedClass("ParameterOwner"));
        parameterOwner.method("first", int.class, parameter);
        assertThatThrownBy(() -> parameterOwner.method("second", int.class, parameter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Parameter is already attached to another method: value");
    }

    @Test
    void testInvalidReturnsFailDuringDefinitionAssembly()
    {
        ClassDefinition valueClass = ClassDefinition.define(generatedClass("VoidReturn"));
        valueClass.method("value", int.class).access(PUBLIC, STATIC).body().ret();
        assertThatThrownBy(valueClass::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Void return in method returning int");

        ClassDefinition voidClass = ClassDefinition.define(generatedClass("ValueReturn"));
        voidClass.method("run", void.class).access(PUBLIC, STATIC).body().append(constantInt(1).ret());
        assertThatThrownBy(voidClass::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Value return in void method: int");

        ClassDefinition wrongPrimitiveClass = ClassDefinition.define(generatedClass("WrongPrimitiveReturn"));
        wrongPrimitiveClass.method("value", long.class).access(PUBLIC, STATIC).body().append(constantInt(1).ret());
        assertThatThrownBy(wrongPrimitiveClass::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Return type int is not assignable to long");
    }

    @Test
    void testInvalidReservedMethodShapesFailDuringDefinitionAssembly()
    {
        ClassDefinition constructorClass = ClassDefinition.define(generatedClass("StaticConstructor"));
        assertThatThrownBy(() -> constructorClass.constructor().access(PUBLIC, STATIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Constructor has incompatible access flags");

        ClassDefinition initializerClass = ClassDefinition.define(generatedClass("InitializerFlags"));
        initializerClass.method("<clinit>", void.class).access(PUBLIC, STATIC);
        assertThatThrownBy(initializerClass::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Class initializer must be static with no parameters");

        ClassDefinition abstractClass = ClassDefinition.define(generatedClass("AbstractBody"));
        MethodDefinition abstractMethod = abstractClass.method("other", int.class).access(PUBLIC, ABSTRACT);
        assertThatThrownBy(abstractMethod::body)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Method does not have a body: other");
    }

    private static ClassDesc generatedClass(String suffix)
    {
        return ClassDesc.of(TestClassfileWalkingSkeleton.class.getPackageName() + ".Generated" + suffix + NEXT_CLASS_ID.incrementAndGet());
    }

    private Class<?> define(ClassModel classModel)
    {
        return StandardClassDefiner.builder(getClass().getClassLoader())
                .build()
                .defineClass(classModel);
    }

    public static class DefaultConstructorBase {}
}
