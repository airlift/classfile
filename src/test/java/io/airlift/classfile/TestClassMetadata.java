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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.getStatic;
import static io.airlift.classfile.ClassfileTestUtils.defineHidden;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;

class TestClassMetadata
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();

    @Test
    void testAnnotationsConstantsSignaturesAndExceptions()
            throws Exception
    {
        ClassDesc type = generatedClass();
        Annotation classAnnotation = annotation("class");
        Annotation fieldAnnotation = annotation("field");
        Annotation methodAnnotation = annotation("method");
        Annotation compoundAnnotation = Annotation.of(
                DescriptorUtils.classDesc(CompoundMarker.class),
                AnnotationElement.of("mode", AnnotationValue.ofEnum(DescriptorUtils.classDesc(Mode.class), Mode.SECOND.name())),
                AnnotationElement.ofClass("type", DescriptorUtils.classDesc(List.class)),
                AnnotationElement.ofAnnotation("nested", annotation("nested")),
                AnnotationElement.ofArray("numbers", AnnotationValue.ofInt(3), AnnotationValue.ofInt(5), AnnotationValue.ofInt(8)));
        Annotation invisible = Annotation.of(ClassDesc.of("java.lang.Deprecated"));

        ClassDefinition classDefinition = ClassDefinition.define(type)
                .access(PUBLIC, FINAL)
                .sourceFile("GeneratedMetadata.java")
                .signature(ClassSignature.parseFrom("Ljava/lang/Object;"))
                .addAnnotation(classAnnotation)
                .addAnnotation(compoundAnnotation)
                .addInvisibleAnnotation(invisible);
        FieldDefinition constant = classDefinition.field("VALUE", CD_int)
                .access(PUBLIC, STATIC, FINAL)
                .constantValue(37)
                .build();
        classDefinition.field("annotated", List.class)
                .access(PUBLIC)
                .signature(Signature.parseFrom("Ljava/util/List<Ljava/lang/String;>;"))
                .addAnnotation(fieldAnnotation)
                .addInvisibleAnnotation(invisible)
                .build();

        MethodDefinition method = classDefinition.method("value", CD_int)
                .access(PUBLIC, STATIC)
                .signature(MethodSignature.of(MethodTypeDesc.of(CD_int)))
                .addException(IllegalStateException.class)
                .addAnnotation(methodAnnotation)
                .addInvisibleAnnotation(invisible);
        method.body().append(getStatic(constant).ret());

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompiledClass compiledClass = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup)).compileClass(classDefinition.build());
        byte[] classfile = compiledClass.classfile();
        Class<?> generated = defineHidden(lookup, compiledClass).lookupClass();

        assertThat(generated.getAnnotation(Marker.class).value()).isEqualTo("class");
        CompoundMarker compoundMarker = generated.getAnnotation(CompoundMarker.class);
        assertThat(compoundMarker.mode()).isEqualTo(Mode.SECOND);
        assertThat(compoundMarker.type()).isEqualTo(List.class);
        assertThat(compoundMarker.nested().value()).isEqualTo("nested");
        assertThat(compoundMarker.numbers()).containsExactly(3, 5, 8);
        assertThat(generated.getField("annotated").getAnnotation(Marker.class).value()).isEqualTo("field");
        assertThat(generated.getMethod("value").getAnnotation(Marker.class).value()).isEqualTo("method");
        assertThat(generated.getMethod("value").getExceptionTypes()).containsExactly(IllegalStateException.class);
        assertThat(generated.getMethod("value").invoke(null)).isEqualTo(37);
        var classModel = ClassFile.of().parse(classfile);
        assertThat(classModel.findAttribute(Attributes.runtimeInvisibleAnnotations())).isPresent();
        var annotatedField = classModel.fields().stream()
                .filter(field -> field.fieldName().stringValue().equals("annotated"))
                .findFirst()
                .orElseThrow();
        assertThat(annotatedField.findAttribute(Attributes.signature())).isPresent();
        assertThat(annotatedField.findAttribute(Attributes.runtimeInvisibleAnnotations())).isPresent();
        var annotatedMethod = classModel.methods().stream()
                .filter(candidate -> candidate.methodName().equalsString("value"))
                .findFirst()
                .orElseThrow();
        assertThat(annotatedMethod.findAttribute(Attributes.runtimeInvisibleAnnotations())).isPresent();
    }

    private static Annotation annotation(String value)
    {
        return Annotation.of(DescriptorUtils.classDesc(Marker.class), AnnotationElement.ofString("value", value));
    }

    private static ClassDesc generatedClass()
    {
        return ClassDesc.of(TestClassMetadata.class.getPackageName() + ".GeneratedMetadata" + NEXT_CLASS_ID.incrementAndGet());
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marker
    {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface CompoundMarker
    {
        Mode mode();

        Class<?> type();

        Marker nested();

        int[] numbers();
    }

    public enum Mode
    {
        FIRST,
        SECOND,
    }
}
