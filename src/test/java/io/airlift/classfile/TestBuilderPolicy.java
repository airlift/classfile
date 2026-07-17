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

import java.lang.classfile.Annotation;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestBuilderPolicy
{
    @Test
    void testMethodParameterSlotLimitCountsWideParametersAndReceiver()
    {
        Parameter[] maximumStaticParameters = Stream.concat(
                        IntStream.range(0, 127).mapToObj(index -> Parameter.arg("long" + index, long.class)),
                        Stream.of(Parameter.arg("last", int.class)))
                .toArray(Parameter[]::new);
        ClassDefinition validDefinition = ClassDefinition.define(ClassDesc.of("test.MaximumStaticParameters"));
        validDefinition.method("valid", void.class, maximumStaticParameters)
                .access(STATIC)
                .body()
                .ret();
        validDefinition.build();

        Parameter[] tooManyWideParameters = IntStream.range(0, 128)
                .mapToObj(index -> Parameter.arg("long" + index, long.class))
                .toArray(Parameter[]::new);
        ClassDefinition staticDefinition = ClassDefinition.define(ClassDesc.of("test.TooManyStaticParameters"));
        MethodDefinition staticMethod = staticDefinition.method("invalid", void.class, tooManyWideParameters);
        assertThatThrownBy(() -> staticMethod.access(STATIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Method parameters use 256 local-variable slots; the JVM limit is 255");

        Parameter[] maximumInstanceParameters = Stream.concat(
                        IntStream.range(0, 127).mapToObj(index -> Parameter.arg("long" + index, long.class)),
                        Stream.of(Parameter.arg("last", int.class)))
                .toArray(Parameter[]::new);
        ClassDefinition instanceDefinition = ClassDefinition.define(ClassDesc.of("test.TooManyInstanceParameters"));
        MethodDefinition instanceMethod = instanceDefinition.method("invalid", void.class, maximumInstanceParameters);
        assertThatThrownBy(() -> instanceMethod.access(PUBLIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Method parameters use 256 local-variable slots; the JVM limit is 255 including the receiver");
        instanceMethod.access(STATIC);
    }

    @Test
    void testImmutableMethodModelValidatesJvmConstraints()
    {
        assertThatThrownBy(() -> staticMethodModel("bad/name", MethodTypeDesc.of(CD_void), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid method name");
        assertThatThrownBy(() -> staticMethodModel("<clinit>", MethodTypeDesc.of(CD_int), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Class initializer must have descriptor ()V");
        assertThatThrownBy(() -> instanceMethodModel("<clinit>", MethodTypeDesc.of(CD_void), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Class initializer must be static with no parameters");
        assertThatThrownBy(() -> methodModel("<clinit>", MethodTypeDesc.of(CD_void), List.of(), Set.of(PUBLIC, STATIC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Class initializer must be static with no parameters");
        Parameter unexpectedParameter = Parameter.arg("unexpected", int.class);
        assertThatThrownBy(() -> staticMethodModel("<clinit>", MethodTypeDesc.of(CD_void), List.of(unexpectedParameter)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Class initializer must be static with no parameters");
        assertThatThrownBy(() -> instanceMethodModel("<init>", MethodTypeDesc.of(CD_int), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Constructor must return void");

        List<Parameter> maximumParameters = Stream.concat(
                        IntStream.range(0, 127).mapToObj(index -> Parameter.arg("long" + index, long.class)),
                        Stream.of(Parameter.arg("last", int.class)))
                .toList();
        staticMethodModel("valid", MethodTypeDesc.of(CD_void, maximumParameters.stream().map(Parameter::type).toList()), maximumParameters);

        List<Parameter> tooManyParameters = IntStream.range(0, 128)
                .mapToObj(index -> Parameter.arg("long" + index, long.class))
                .toList();
        assertThatThrownBy(() -> staticMethodModel(
                "invalid",
                MethodTypeDesc.of(CD_void, tooManyParameters.stream().map(Parameter::type).toList()),
                tooManyParameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Method parameters use 256 local-variable slots; the JVM limit is 255");
    }

    @Test
    void testDeclarationNamesAndLocalTypesAreValidated()
    {
        for (String name : new String[] {"bad/name", "bad.name", "bad;name", "bad[name"}) {
            ClassDefinition fields = ClassDefinition.define(ClassDesc.of("test.InvalidField"));
            assertThatThrownBy(() -> fields.field(name, int.class).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid field name");

            ClassDefinition methods = ClassDefinition.define(ClassDesc.of("test.InvalidMethod"));
            assertThatThrownBy(() -> methods.method(name, void.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid method name");
        }

        ClassDefinition methods = ClassDefinition.define(ClassDesc.of("test.InvalidSpecialMethod"));
        assertThatThrownBy(() -> methods.method("<bad>", void.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid method name");

        ClassDefinition fields = ClassDefinition.define(ClassDesc.of("test.LegalField"));
        assertThat(fields.field("<bad>", int.class).build().name()).isEqualTo("<bad>");

        assertThatThrownBy(() -> CodeBlock.blockBuilder().declare(void.class, "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type is void");
        assertThatThrownBy(() -> CodeBlock.blockBuilder().declare(CD_void, "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type is void");
    }

    @Test
    void testFailedMethodConstructionDoesNotBindFreshParameters()
    {
        Parameter alreadyBound = Parameter.arg("bound", int.class);
        ClassDefinition first = ClassDefinition.define(ClassDesc.of("test.ParameterOwner"));
        first.method("first", void.class, alreadyBound);

        Parameter fresh = Parameter.arg("fresh", int.class);
        ClassDefinition failed = ClassDefinition.define(ClassDesc.of("test.FailedParameterOwner"));
        assertThatThrownBy(() -> failed.method("failed", void.class, fresh, alreadyBound))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already attached");

        ClassDefinition retry = ClassDefinition.define(ClassDesc.of("test.RetryParameterOwner"));
        retry.method("retry", void.class, fresh);
        assertThat(retry.build().methods().getFirst().parameters()).containsExactly(fresh);
    }

    @Test
    void testDefinitionPropertiesAreSetOnce()
    {
        ClassSignature classSignature = ClassSignature.parseFrom("Ljava/lang/Object;");
        ClassDefinition classDefinition = ClassDefinition.define(ClassDesc.of("test.BuilderPolicy"))
                .access(PUBLIC)
                .superClass(CD_Object)
                .signature(classSignature)
                .sourceFile("BuilderPolicy.java");

        assertAlreadySet(() -> classDefinition.access(FINAL), "access is already set");
        assertAlreadySet(() -> classDefinition.superClass(Object.class), "super class is already set");
        assertAlreadySet(() -> classDefinition.signature(classSignature), "signature is already set");
        assertAlreadySet(() -> classDefinition.sourceFile("Other.java"), "source file is already set");

        MethodSignature methodSignature = MethodSignature.of(MethodTypeDesc.of(CD_void));
        MethodDefinition method = classDefinition.method("run", CD_void)
                .access(PUBLIC)
                .signature(methodSignature)
                .comment("run");

        assertAlreadySet(() -> method.access(FINAL), "access is already set");
        assertAlreadySet(() -> method.signature(methodSignature), "signature is already set");
        assertAlreadySet(() -> method.comment("other"), "comment is already set");

        CodeBlock.Builder block = CodeBlock.blockBuilder().description("first");
        assertAlreadySet(() -> block.description("second"), "description is already set");
        assertThat(block.build().description()).isEqualTo("first");

        MethodDefinition packagePrivateMethod = classDefinition.method("packagePrivate", CD_void);
        assertThat(packagePrivateMethod.thisVariable().type()).isEqualTo(classDefinition.build().type());
    }

    @Test
    void testFieldBuilderPropertiesAreSetOnceAndBuildRegistersField()
    {
        ClassDefinition classDefinition = ClassDefinition.define(ClassDesc.of("test.FieldBuilderPolicy"));
        Signature signature = Signature.of(CD_String);
        Annotation visible = Annotation.of(ClassDesc.of("test.Visible"));
        Annotation invisible = Annotation.of(ClassDesc.of("test.Invisible"));
        FieldDefinition.Builder fieldBuilder = classDefinition.field("value", CD_String)
                .access(PUBLIC, FINAL)
                .signature(signature)
                .addAnnotation(visible)
                .addAnnotation(visible)
                .addInvisibleAnnotation(invisible);

        assertThat(classDefinition.build().fields()).isEmpty();
        assertAlreadySet(() -> fieldBuilder.access(PUBLIC), "access is already set");
        assertAlreadySet(() -> fieldBuilder.signature(signature), "signature is already set");

        FieldDefinition field = fieldBuilder.build();
        assertThat(classDefinition.build().fields()).containsExactly(field);
        assertThat(field.visibleAnnotations()).containsExactly(visible, visible);
        assertThat(field.invisibleAnnotations()).containsExactly(invisible);
        assertAlreadySet(fieldBuilder::build, "field is already built");
        assertAlreadySet(() -> fieldBuilder.addAnnotation(null), "field is already built");
    }

    @Test
    void testFieldConstantValueIsSetOnce()
    {
        FieldDefinition.Builder field = ClassDefinition.define(ClassDesc.of("test.ConstantFieldBuilderPolicy"))
                .field("value", CD_int)
                .access(PUBLIC, STATIC, FINAL)
                .constantValue(1);

        assertAlreadySet(() -> field.constantValue(2), "constant value is already set");
    }

    @Test
    void testClassBuildCreatesImmutableSnapshot()
    {
        ClassDefinition classDefinition = ClassDefinition.define(ClassDesc.of("test.Snapshot"));
        MethodDefinition method = classDefinition.method("run", void.class).access(STATIC);

        ClassModel first = classDefinition.build();
        method.body().append(constantInt(1).pop());
        classDefinition.method("other", void.class).access(STATIC);
        ClassModel second = classDefinition.build();

        assertThat(first.methods()).hasSize(1);
        assertThat(first.methods().getFirst().body().isEmpty()).isTrue();
        assertThat(second.methods()).hasSize(2);
        assertThat(second.methods().getFirst().body().toString()).contains("1;");
    }

    @Test
    void testDefaultConstructorFixesSuperclassLinkage()
    {
        ClassDefinition classDefinition = ClassDefinition.define(ClassDesc.of("test.DefaultConstructorPolicy"));
        classDefinition.defaultConstructor();

        assertAlreadySet(
                () -> classDefinition.superClass(Object.class),
                "super class cannot be set after the default constructor is declared");
    }

    private static void assertAlreadySet(Runnable action, String message)
    {
        assertThatThrownBy(action::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
    }

    private static MethodDefinition.Model staticMethodModel(String name, MethodTypeDesc methodType, List<Parameter> parameters)
    {
        return methodModel(name, methodType, parameters, Set.of(STATIC));
    }

    private static MethodDefinition.Model instanceMethodModel(String name, MethodTypeDesc methodType, List<Parameter> parameters)
    {
        return methodModel(name, methodType, parameters, Set.of());
    }

    private static MethodDefinition.Model methodModel(String name, MethodTypeDesc methodType, List<Parameter> parameters, Set<AccessFlag> access)
    {
        ClassDesc declaringType = ClassDesc.of("test.ImmutableMethodModel");
        return new MethodDefinition.Model(
                declaringType,
                access,
                name,
                methodType,
                parameters,
                parameters.stream().map(Parameter::metadata).toList(),
                access.contains(STATIC) ? Optional.empty() : Optional.of(new Variable("this", declaringType, new Object())),
                Optional.of(CodeBlock.block()),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }
}
