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
import java.lang.classfile.MethodSignature;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCompactRecordConstructor
{
    private static final AtomicLong NEXT_ID = new AtomicLong();

    @Test
    void testCompactConstructorNominalAndHidden()
            throws Exception
    {
        ClassModel model = normalizedRecord();

        Class<?> nominal = StandardClassDefiner.builder(getClass().getClassLoader()).build().defineClass(model);
        assertNormalizedRecord(nominal);

        Class<?> hidden = HiddenClassDefiner.builder(MethodHandles.lookup()).build().defineClass(model).lookupClass();
        assertThat(hidden.isHidden()).isTrue();
        assertNormalizedRecord(hidden);
    }

    @Test
    void testCompactConstructorValidation()
    {
        assertThatThrownBy(() -> ClassDefinition.define(generatedType("NotRecord")).compactConstructor())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Compact constructor can only be declared on a record");

        ClassDefinition lateComponent = ClassDefinition.defineRecord(generatedType("LateComponent"));
        lateComponent.compactConstructor();
        assertThatThrownBy(() -> lateComponent.recordComponent("value", int.class).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Record component cannot be declared after the compact constructor");

        ClassDefinition duplicate = ClassDefinition.defineRecord(generatedType("Duplicate"));
        RecordComponentDefinition value = duplicate.recordComponent("value", int.class).build();
        CompactConstructorDefinition constructor = duplicate.compactConstructor();
        constructor.initialize(value, constantInt(1));
        assertThatThrownBy(() -> constructor.initialize(value, constantInt(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Record component value is already set: value");
        assertThatThrownBy(duplicate::compactConstructor)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Compact constructor is already declared");

        ClassDefinition returning = ClassDefinition.defineRecord(generatedType("Returning"));
        returning.recordComponent("value", int.class).build();
        returning.compactConstructor().body().ret();
        assertThatThrownBy(returning::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Compact constructor body cannot return");
    }

    @Test
    void testCompactConstructorMetadataAndAuthoringContext()
    {
        ClassDefinition definition = ClassDefinition.defineRecord(generatedType("Metadata")).access(PUBLIC);
        definition.recordComponent("value", int.class).build();
        Annotation visible = Annotation.of(ClassDesc.of("io.airlift.classfile.VisibleConstructorMarker"));
        Annotation invisible = Annotation.of(ClassDesc.of("io.airlift.classfile.InvisibleConstructorMarker"));
        MethodTypeDesc methodType = MethodTypeDesc.of(CD_void, CD_int);
        CompactConstructorDefinition constructor = definition.compactConstructor()
                .access(PUBLIC)
                .signature(MethodSignature.of(methodType))
                .addAnnotation(visible)
                .addInvisibleAnnotation(invisible);

        assertThat(constructor.thisVariable().type()).isEqualTo(definition.type());
        assertThat(constructor.methodType()).isEqualTo(methodType);

        MethodDefinition.Model model = definition.build().methods().stream()
                .filter(method -> method.name().equals("<init>"))
                .findFirst()
                .orElseThrow();
        assertThat(model.access()).contains(PUBLIC);
        assertThat(model.signature()).contains(MethodSignature.of(methodType));
        assertThat(model.visibleAnnotations()).containsExactly(visible);
        assertThat(model.invisibleAnnotations()).containsExactly(invisible);
    }

    private static ClassModel normalizedRecord()
    {
        ClassDefinition definition = ClassDefinition.defineRecord(generatedType("Normalized")).access(PUBLIC);
        RecordComponentDefinition value = definition.recordComponent("value", int.class).build();
        RecordComponentDefinition name = definition.recordComponent("name", String.class).build();
        definition.recordComponent("enabled", boolean.class).build();
        CompactConstructorDefinition constructor = definition.compactConstructor().comment("validate and normalize components");
        constructor.parameter(value).access(FINAL);
        constructor.body().append(IfStatement.builder()
                .condition(constructor.parameter(value).lessThan(constantInt(0)))
                .then(BytecodeExpressions.newInstance(IllegalArgumentException.class, constantString("value is negative")).throwObject())
                .build());
        Variable normalizedName = constructor.body().declare("normalizedName", constructor.parameter(name).invoke("strip", String.class));
        constructor.initialize(value, constructor.parameter(value).add(constantInt(1)));
        constructor.initialize(name, normalizedName);
        return definition.build();
    }

    private static void assertNormalizedRecord(Class<?> recordClass)
            throws Exception
    {
        Constructor<?> constructor = recordClass.getConstructor(int.class, String.class, boolean.class);
        Object value = constructor.newInstance(4, " name ", true);
        assertThat(recordClass.getMethod("value").invoke(value)).isEqualTo(5);
        assertThat(recordClass.getMethod("name").invoke(value)).isEqualTo("name");
        assertThat(recordClass.getMethod("enabled").invoke(value)).isEqualTo(true);
        assertThat(constructor.getParameters()).extracting(java.lang.reflect.Parameter::getName)
                .containsExactly("value", "name", "enabled");
        assertThat(constructor.getParameters()).allMatch(java.lang.reflect.Parameter::isImplicit);
        assertThat(Modifier.isFinal(constructor.getParameters()[0].getModifiers())).isTrue();
        assertThatThrownBy(() -> constructor.newInstance(-1, "name", true))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private static ClassDesc generatedType(String name)
    {
        return ClassDesc.of(TestCompactRecordConstructor.class.getPackageName() + ".Generated" + name + NEXT_ID.incrementAndGet());
    }
}
