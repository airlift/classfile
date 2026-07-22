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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.Parameter.arg;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.lang.reflect.AccessFlag.SYNTHETIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestParameterMetadata
{
    private static final AtomicLong NEXT_ID = new AtomicLong();

    @Test
    void testParameterNamesFlagsAndAnnotations()
            throws Exception
    {
        Annotation visible = Annotation.of(
                DescriptorUtils.classDesc(VisibleMarker.class),
                AnnotationElement.ofString("value", "visible"));
        Annotation invisible = Annotation.of(DescriptorUtils.classDesc(InvisibleMarker.class));
        Parameter value = arg("value", int.class)
                .access(FINAL, SYNTHETIC)
                .addAnnotation(visible)
                .addInvisibleAnnotation(invisible);
        assertThat(value.access()).containsExactlyInAnyOrder(FINAL, SYNTHETIC);
        assertThat(value.visibleAnnotations()).containsExactly(visible);
        assertThat(value.invisibleAnnotations()).containsExactly(invisible);
        ClassDefinition definition = ClassDefinition.define(generatedType("Parameters")).access(PUBLIC);
        definition.method("identity", int.class, value)
                .access(PUBLIC, STATIC)
                .body()
                .ret(value);

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledClass compiled = ClassCompiler.forTarget(definer.compilationTarget()).compileClass(definition.build());
        Class<?> generatedClass = definer.defineCompiledClass(compiled);
        Method method = generatedClass.getMethod("identity", int.class);
        java.lang.reflect.Parameter reflected = method.getParameters()[0];

        assertThat(reflected.getName()).isEqualTo("value");
        assertThat(reflected.isNamePresent()).isTrue();
        assertThat(reflected.isSynthetic()).isTrue();
        assertThat(Modifier.isFinal(reflected.getModifiers())).isTrue();
        assertThat(reflected.getAnnotation(VisibleMarker.class).value()).isEqualTo("visible");
        assertThat(reflected.getAnnotation(InvisibleMarker.class)).isNull();

        MethodModel methodModel = ClassFile.of().parse(compiled.classfile()).methods().stream()
                .filter(candidate -> candidate.methodName().equalsString("identity"))
                .findFirst()
                .orElseThrow();
        assertThat(methodModel.findAttribute(Attributes.methodParameters())).isPresent();
        assertThat(methodModel.findAttribute(Attributes.runtimeVisibleParameterAnnotations())).isPresent();
        assertThat(methodModel.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())).isPresent();
    }

    @Test
    void testMethodSnapshotOwnsParameterMetadata()
    {
        Parameter value = arg("value", int.class);
        ClassDefinition definition = ClassDefinition.define(generatedType("Snapshot"));
        definition.method("identity", int.class, value).access(STATIC).body().ret(value);

        ClassModel first = definition.build();
        value.addAnnotation(Annotation.of(DescriptorUtils.classDesc(InvisibleMarker.class)));
        ClassModel second = definition.build();

        assertThat(first.methods().getFirst().parameterMetadata().getFirst().visibleAnnotations()).isEmpty();
        assertThat(second.methods().getFirst().parameterMetadata().getFirst().visibleAnnotations()).hasSize(1);
    }

    @Test
    void testParameterAccessPolicy()
    {
        Parameter parameter = arg("value", int.class).access(FINAL);
        assertThatThrownBy(() -> parameter.access(SYNTHETIC))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("access is already set");
        assertThatThrownBy(() -> arg("value", int.class).access(PUBLIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Access flag is not valid for a method parameter: PUBLIC");
    }

    @Test
    void testImmutableMethodModelValidatesAccess()
    {
        assertThatThrownBy(() -> new MethodDefinition.Model(
                generatedType("InvalidModel"),
                Set.of(PUBLIC, AccessFlag.PRIVATE),
                "run",
                MethodTypeDesc.ofDescriptor("()V"),
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.of(CodeBlock.block()),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Method has conflicting visibility: run");
    }

    private static ClassDesc generatedType(String name)
    {
        return ClassDesc.of(TestParameterMetadata.class.getPackageName() + ".Generated" + name + NEXT_ID.incrementAndGet());
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface VisibleMarker
    {
        String value();
    }

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.PARAMETER)
    public @interface InvisibleMarker {}
}
