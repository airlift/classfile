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

import io.airlift.classfile.tool.ClassFileDiagnostics;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.constantBoolean;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.inlineIf;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.ABSTRACT;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRecordGeneration
{
    private static final AtomicLong NEXT_ID = new AtomicLong();

    @Test
    void testStandardRecord()
            throws Exception
    {
        RecordModel record = pointRecord();
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget()).compileUnit(record.model());
        DefinedUnit defined = definer.defineUnit(unit);

        Class<?> recordClass = defined.primaryClass();
        assertRecord(record, recordClass);
        assertThat(recordClass.isHidden()).isFalse();
        assertThat(ClassFile.of().parse(unit.classfile(unit.primaryType())).findAttribute(Attributes.record()))
                .isPresent();
        assertThat(ClassFileDiagnostics.disassemble(unit.classfile(unit.primaryType())))
                .contains("record component int x", "record component String name");
        assertThat(record.model().toString())
                .startsWith("public record " + record.model().type().displayName() + "(int x, String name) {")
                .doesNotContain("private final int x;")
                .doesNotContain("private final String name;");
    }

    @Test
    void testHiddenRecord()
            throws Exception
    {
        RecordModel record = pointRecord();
        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget()).compileUnit(record.model());
        DefinedUnit defined = definer.defineUnit(unit);

        Class<?> recordClass = defined.primaryClass();
        assertRecord(record, recordClass);
        assertThat(recordClass.isHidden()).isTrue();
        assertThat(defined.primaryLookup()).isPresent();
    }

    @Test
    void testExplicitAccessorsAndObjectMethodsOverrideDefaults()
            throws Exception
    {
        ClassDefinition definition = ClassDefinition.defineRecord(generatedType("Overrides")).access(PUBLIC);
        RecordComponentDefinition value = definition.recordComponent("value", int.class).build();

        MethodDefinition accessor = definition.method("value", int.class).access(PUBLIC);
        accessor.body().ret(constantInt(99));
        MethodDefinition raw = definition.method("raw", int.class).access(PUBLIC);
        raw.body().ret(value.read(raw.thisVariable()));
        MethodDefinition throughAccessor = definition.method("throughAccessor", int.class).access(PUBLIC);
        throughAccessor.body().ret(value.get(throughAccessor.thisVariable()));

        Parameter other = arg("other", Object.class);
        definition.method("equals", boolean.class, other).access(PUBLIC).body().ret(constantBoolean(false));
        definition.method("hashCode", int.class).access(PUBLIC).body().ret(constantInt(123));
        definition.method("toString", String.class).access(PUBLIC).body().ret(constantString("custom"));

        Class<?> recordClass = StandardClassDefiner.builder(getClass().getClassLoader()).build().defineClass(definition.build());
        Object instance = recordClass.getConstructor(int.class).newInstance(7);
        assertThat(recordClass.isRecord()).isTrue();
        assertThat(recordClass.getMethod("value").invoke(instance)).isEqualTo(99);
        assertThat(recordClass.getMethod("raw").invoke(instance)).isEqualTo(7);
        assertThat(recordClass.getMethod("throughAccessor").invoke(instance)).isEqualTo(99);
        assertThat(instance).hasToString("custom").hasSameHashCodeAs(123);
        assertThat(instance.equals(instance)).isFalse();
    }

    @Test
    void testDefaultObjectMethodsUseComponentFieldsNotOverriddenAccessor()
            throws Exception
    {
        ClassDefinition definition = ClassDefinition.defineRecord(generatedType("AccessorState")).access(PUBLIC);
        definition.recordComponent("value", int.class).build();
        definition.method("value", int.class).access(PUBLIC).body().ret(constantInt(99));

        Class<?> recordClass = StandardClassDefiner.builder(getClass().getClassLoader()).build().defineClass(definition.build());
        Object first = recordClass.getConstructor(int.class).newInstance(7);
        Object equal = recordClass.getConstructor(int.class).newInstance(7);

        assertThat(recordClass.getMethod("value").invoke(first)).isEqualTo(99);
        assertThat(first).isEqualTo(equal);
        assertThat(first.toString()).contains("value=7");
    }

    @Test
    void testZeroComponentRecord()
            throws Exception
    {
        ClassDefinition definition = ClassDefinition.defineRecord(generatedType("Empty")).access(PUBLIC);
        Class<?> recordClass = StandardClassDefiner.builder(getClass().getClassLoader()).build().defineClass(definition.build());
        Object first = recordClass.getConstructor().newInstance();
        Object second = recordClass.getConstructor().newInstance();

        assertThat(recordClass.isRecord()).isTrue();
        assertThat(recordClass.getRecordComponents()).isEmpty();
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.toString()).isEqualTo(recordClass.getSimpleName() + "[]");
    }

    @Test
    void testComponentMetadata()
    {
        ClassDefinition definition = ClassDefinition.defineRecord(generatedType("Metadata")).access(PUBLIC);
        Annotation marker = Annotation.of(
                DescriptorUtils.classDesc(ComponentMarker.class),
                AnnotationElement.ofString("value", "items"));
        Annotation invisible = Annotation.of(DescriptorUtils.classDesc(InvisibleComponentMarker.class));
        RecordComponentDefinition component = definition.recordComponent("items", List.class)
                .signature(Signature.parseFrom("Ljava/util/List<Ljava/lang/String;>;"))
                .addAnnotation(marker)
                .addInvisibleAnnotation(invisible)
                .build();

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget()).compileUnit(definition.build());
        Class<?> recordClass = definer.defineUnit(unit).primaryClass();
        RecordComponent reflected = recordClass.getRecordComponents()[0];
        assertThat(reflected.getName()).isEqualTo("items");
        assertThat(reflected.getGenericType().getTypeName()).isEqualTo("java.util.List<java.lang.String>");
        assertThat(reflected.getAnnotation(ComponentMarker.class).value()).isEqualTo("items");

        RecordAttribute attribute = ClassFile.of().parse(unit.classfile(unit.primaryType()))
                .findAttribute(Attributes.record())
                .orElseThrow();
        assertThat(attribute.components()).hasSize(1);
        assertThat(attribute.components().getFirst().findAttribute(Attributes.signature())).isPresent();
        assertThat(attribute.components().getFirst().findAttribute(Attributes.runtimeVisibleAnnotations())).isPresent();
        assertThat(attribute.components().getFirst().findAttribute(Attributes.runtimeInvisibleAnnotations())).isPresent();
        assertThat(component.field().visibleAnnotations()).isEmpty();
        assertThat(component.invisibleAnnotations()).containsExactly(invisible);
        assertThat(component.accessor()).hasToString(component.declaringType().displayName() + ".items()Ljava/util/List;");
    }

    @Test
    void testCustomCanonicalAndDelegatingConstructor()
            throws Exception
    {
        ClassDefinition definition = ClassDefinition.defineRecord(generatedType("Constructors")).access(PUBLIC);
        RecordComponentDefinition value = definition.recordComponent("value", int.class).build();
        RecordComponentDefinition name = definition.recordComponent("name", String.class).build();

        Parameter valueParameter = arg("value", int.class);
        Parameter nameParameter = arg("name", String.class);
        MethodDefinition canonical = definition.constructor(valueParameter, nameParameter).access(PUBLIC);
        canonical.body()
                .invokeSuperConstructor()
                .append(value.initialize(canonical.thisVariable(), valueParameter.add(constantInt(1))))
                .append(name.initialize(canonical.thisVariable(), nameParameter.invoke("strip", String.class)))
                .ret();

        Parameter valueText = arg("valueText", String.class);
        MethodDefinition convenience = definition.constructor(valueText).access(PUBLIC);
        convenience.body()
                .invokeThisConstructor(
                        MethodTypeDesc.of(CD_void, DescriptorUtils.classDesc(int.class), DescriptorUtils.classDesc(String.class)),
                        constantInt(10),
                        valueText)
                .ret();

        Class<?> recordClass = StandardClassDefiner.builder(getClass().getClassLoader()).build().defineClass(definition.build());
        Object instance = recordClass.getConstructor(String.class).newInstance(" value ");
        assertThat(recordClass.getConstructor(int.class, String.class).getParameters())
                .noneMatch(java.lang.reflect.Parameter::isImplicit);
        assertThat(recordClass.getMethod("value").invoke(instance)).isEqualTo(11);
        assertThat(recordClass.getMethod("name").invoke(instance)).isEqualTo("value");
    }

    @Test
    void testRecordValidation()
    {
        assertThatThrownBy(() -> ClassDefinition.define(generatedType("NotRecord")).recordComponent("value", int.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Record components can only be declared on a record");

        assertThatThrownBy(() -> ClassDefinition.defineRecord(generatedType("Reserved")).recordComponent("hashCode", int.class).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record component name conflicts with Object method: hashCode");

        ClassDefinition duplicate = ClassDefinition.defineRecord(generatedType("Duplicate"));
        duplicate.recordComponent("value", int.class).build();
        assertThatThrownBy(() -> duplicate.recordComponent("value", int.class).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record component already declared: value");

        ClassDefinition extraField = ClassDefinition.defineRecord(generatedType("ExtraField"));
        extraField.recordComponent("value", int.class).build();
        extraField.field("other", int.class).access(PRIVATE).build();
        assertThatThrownBy(extraField::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record cannot declare additional instance field: other");

        ClassDefinition abstractRecord = ClassDefinition.defineRecord(generatedType("Abstract")).access(PUBLIC, ABSTRACT);
        assertThatThrownBy(abstractRecord::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record cannot be abstract: " + abstractRecord.type().displayName());

        ClassDefinition privateAccessor = ClassDefinition.defineRecord(generatedType("Accessor")).access(PUBLIC);
        privateAccessor.recordComponent("value", int.class).build();
        privateAccessor.method("value", int.class).access(PRIVATE).body().ret(constantInt(1));
        assertThatThrownBy(privateAccessor::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record component accessor must be public, non-static, and declare no exceptions: value");

        ClassDefinition privateObjectMethod = ClassDefinition.defineRecord(generatedType("ObjectMethod")).access(PUBLIC);
        privateObjectMethod.method("hashCode", int.class).access(PRIVATE).body().ret(constantInt(1));
        assertThatThrownBy(privateObjectMethod::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record method must be public and non-static: hashCode");
    }

    @Test
    void testHiddenRecordRejectsGeneratedComponentType()
    {
        ClassDesc type = generatedType("Recursive");
        ClassDefinition definition = ClassDefinition.defineRecord(type).access(PUBLIC);
        definition.recordComponent("parent", type).build();

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        assertThatThrownBy(() -> ClassCompiler.forTarget(definer.compilationTarget()).compileClass(definition.build()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("record component parent")
                .hasMessageContaining("cannot appear in a hidden record component descriptor");
    }

    @Test
    void testRecordInitializationValidation()
    {
        ClassDefinition wrongNames = ClassDefinition.defineRecord(generatedType("WrongNames")).access(PUBLIC);
        RecordComponentDefinition wrongNamesValue = wrongNames.recordComponent("value", int.class).build();
        Parameter wrong = arg("wrong", int.class);
        MethodDefinition wrongNamesConstructor = wrongNames.constructor(wrong).access(PUBLIC);
        wrongNamesConstructor.body()
                .invokeSuperConstructor()
                .append(wrongNamesValue.initialize(wrongNamesConstructor.thisVariable(), wrong))
                .ret();
        assertThatThrownBy(wrongNames::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Canonical constructor parameter names must match record components: [value]");

        ClassDefinition missing = ClassDefinition.defineRecord(generatedType("Missing")).access(PUBLIC);
        missing.recordComponent("value", int.class).build();
        Parameter missingValue = arg("value", int.class);
        missing.constructor(missingValue).access(PUBLIC).body().invokeSuperConstructor().ret();
        assertThatThrownBy(missing::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record canonical constructor must initialize component exactly once: value");

        ClassDefinition duplicate = ClassDefinition.defineRecord(generatedType("DuplicateWrite")).access(PUBLIC);
        RecordComponentDefinition duplicateValue = duplicate.recordComponent("value", int.class).build();
        Parameter duplicateParameter = arg("value", int.class);
        MethodDefinition duplicateConstructor = duplicate.constructor(duplicateParameter).access(PUBLIC);
        duplicateConstructor.body()
                .invokeSuperConstructor()
                .append(duplicateValue.initialize(duplicateConstructor.thisVariable(), duplicateParameter))
                .append(duplicateValue.initialize(duplicateConstructor.thisVariable(), duplicateParameter))
                .ret();
        assertThatThrownBy(duplicate::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record canonical constructor must initialize component exactly once: value");

        ClassDefinition conditional = ClassDefinition.defineRecord(generatedType("Conditional")).access(PUBLIC);
        RecordComponentDefinition conditionalValue = conditional.recordComponent("value", int.class).build();
        Parameter conditionalParameter = arg("value", int.class);
        MethodDefinition conditionalConstructor = conditional.constructor(conditionalParameter).access(PUBLIC);
        conditionalConstructor.body()
                .invokeSuperConstructor()
                .append(IfStatement.builder()
                        .condition(constantBoolean(true))
                        .then(conditionalValue.initialize(conditionalConstructor.thisVariable(), conditionalParameter))
                        .build())
                .ret();
        assertThatThrownBy(conditional::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record component initialization cannot be conditional: value");

        ClassDefinition inlineConditional = ClassDefinition.defineRecord(generatedType("InlineConditional")).access(PUBLIC);
        RecordComponentDefinition inlineConditionalValue = inlineConditional.recordComponent("value", int.class).build();
        Parameter inlineConditionalParameter = arg("value", int.class);
        MethodDefinition inlineConditionalConstructor = inlineConditional.constructor(inlineConditionalParameter).access(PUBLIC);
        inlineConditionalConstructor.body()
                .invokeSuperConstructor()
                .append(inlineIf(
                        constantBoolean(false),
                        inlineConditionalValue.initialize(inlineConditionalConstructor.thisVariable(), inlineConditionalParameter),
                        constantInt(0).pop()))
                .ret();
        assertThatThrownBy(inlineConditional::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record component initialization cannot be conditional: value");

        ClassDefinition earlyReturn = ClassDefinition.defineRecord(generatedType("EarlyReturn")).access(PUBLIC);
        RecordComponentDefinition earlyReturnValue = earlyReturn.recordComponent("value", int.class).build();
        Parameter earlyReturnParameter = arg("value", int.class);
        MethodDefinition earlyReturnConstructor = earlyReturn.constructor(earlyReturnParameter).access(PUBLIC);
        earlyReturnConstructor.body()
                .invokeSuperConstructor()
                .ret()
                .append(earlyReturnValue.initialize(earlyReturnConstructor.thisVariable(), earlyReturnParameter));
        assertThatThrownBy(earlyReturn::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record canonical constructor can return before initializing component: value");

        ClassDefinition conditionalReturn = ClassDefinition.defineRecord(generatedType("ConditionalReturn")).access(PUBLIC);
        RecordComponentDefinition conditionalReturnValue = conditionalReturn.recordComponent("value", int.class).build();
        Parameter conditionalReturnParameter = arg("value", int.class);
        MethodDefinition conditionalReturnConstructor = conditionalReturn.constructor(conditionalReturnParameter).access(PUBLIC);
        conditionalReturnConstructor.body()
                .invokeSuperConstructor()
                .append(IfStatement.builder()
                        .condition(constantBoolean(true))
                        .then(CodeBlock.blockBuilder().ret().build())
                        .build())
                .append(conditionalReturnValue.initialize(conditionalReturnConstructor.thisVariable(), conditionalReturnParameter))
                .ret();
        assertThatThrownBy(conditionalReturn::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record canonical constructor can return before initializing component: value");

        ClassDefinition rawJump = ClassDefinition.defineRecord(generatedType("RawJump")).access(PUBLIC);
        RecordComponentDefinition rawJumpValue = rawJump.recordComponent("value", int.class).build();
        Parameter rawJumpParameter = arg("value", int.class);
        MethodDefinition rawJumpConstructor = rawJump.constructor(rawJumpParameter).access(PUBLIC);
        CodeLabel afterWrite = rawJumpConstructor.body().label("afterWrite");
        rawJumpConstructor.body()
                .invokeSuperConstructor()
                .jump(afterWrite)
                .append(rawJumpValue.initialize(rawJumpConstructor.thisVariable(), rawJumpParameter))
                .mark(afterWrite)
                .ret();
        assertThatThrownBy(rawJump::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record canonical constructor cannot use raw jumps because component initialization cannot be proven");

        ClassDefinition throwOnly = ClassDefinition.defineRecord(generatedType("ThrowOnly")).access(PUBLIC);
        throwOnly.recordComponent("value", int.class).build();
        throwOnly.recordComponent("name", String.class).build();
        Parameter throwOnlyValue = arg("value", int.class);
        Parameter throwOnlyName = arg("name", String.class);
        throwOnly.constructor(throwOnlyValue, throwOnlyName).access(PUBLIC).body()
                .invokeSuperConstructor()
                .append(BytecodeExpressions.newInstance(IllegalStateException.class).throwObject());
        assertThat(throwOnly.build()).isNotNull();

        ClassDefinition returnThroughThrow = ClassDefinition.defineRecord(generatedType("ReturnThroughThrow")).access(PUBLIC);
        returnThroughThrow.recordComponent("value", int.class).build();
        Parameter returnThroughThrowValue = arg("value", int.class);
        returnThroughThrow.constructor(returnThroughThrowValue).access(PUBLIC).body()
                .invokeSuperConstructor()
                .append(new ReturnFromSetup().throwObject());
        assertThatThrownBy(returnThroughThrow::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record canonical constructor must initialize component exactly once: value");

        ClassDefinition breakBeforeWrite = ClassDefinition.defineRecord(generatedType("BreakBeforeWrite")).access(PUBLIC);
        breakBeforeWrite.recordComponent("value", int.class).build();
        Parameter breakBeforeWriteValue = arg("value", int.class);
        MethodDefinition breakBeforeWriteConstructor = breakBeforeWrite.constructor(breakBeforeWriteValue).access(PUBLIC);
        DoWhileLoop.Builder loop = DoWhileLoop.builder();
        loop.body(loop.breakLoop()).condition(constantBoolean(true));
        breakBeforeWriteConstructor.body()
                .invokeSuperConstructor()
                .append(loop.build())
                .ret();
        assertThatThrownBy(breakBeforeWrite::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record canonical constructor must initialize component exactly once: value");

        ClassDefinition externalWrite = ClassDefinition.defineRecord(generatedType("ExternalWrite")).access(PUBLIC);
        RecordComponentDefinition externalValue = externalWrite.recordComponent("value", int.class).build();
        MethodDefinition mutate = externalWrite.method("mutate", void.class).access(PRIVATE);
        mutate.body().append(externalValue.initialize(mutate.thisVariable(), constantInt(1))).ret();
        assertThatThrownBy(externalWrite::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record component field can only be initialized in the canonical constructor: value");

        ClassDefinition nonCanonical = ClassDefinition.defineRecord(generatedType("NonCanonical")).access(PUBLIC);
        nonCanonical.recordComponent("value", int.class).build();
        Parameter text = arg("text", String.class);
        nonCanonical.constructor(text).access(PUBLIC).body().invokeSuperConstructor().ret();
        assertThatThrownBy(nonCanonical::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Non-canonical record constructor must delegate directly to the canonical constructor");

        ClassDefinition otherTarget = ClassDefinition.defineRecord(generatedType("OtherTarget")).access(PUBLIC);
        RecordComponentDefinition otherTargetValue = otherTarget.recordComponent("value", int.class).build();
        Parameter value = arg("value", int.class);
        Parameter other = arg("other", otherTarget.type());
        MethodDefinition otherTargetConstructor = otherTarget.constructor(value, other).access(PUBLIC);
        otherTargetConstructor.body()
                .invokeSuperConstructor()
                .append(otherTargetValue.initialize(other, value))
                .ret();
        assertThatThrownBy(otherTarget::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record component initialization must target this: value");
    }

    private static void assertRecord(RecordModel record, Class<?> recordClass)
            throws Exception
    {
        assertThat(recordClass.isRecord()).isTrue();
        assertThat(recordClass.getSuperclass()).isEqualTo(Record.class);
        assertThat(Arrays.stream(recordClass.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("x", "name");
        assertThat(Arrays.stream(recordClass.getRecordComponents()).map(RecordComponent::getType))
                .containsExactly(int.class, String.class);

        Constructor<?> constructor = recordClass.getConstructor(int.class, String.class);
        assertThat(constructor.getParameters()).noneMatch(java.lang.reflect.Parameter::isImplicit);
        Object first = constructor.newInstance(7, "alice");
        Object equal = constructor.newInstance(7, "alice");
        Object different = constructor.newInstance(8, "alice");
        assertThat(recordClass.getMethod("x").invoke(first)).isEqualTo(7);
        assertThat(recordClass.getMethod("name").invoke(first)).isEqualTo("alice");
        assertThat(recordClass.getMethod("score").invoke(first)).isEqualTo(12);
        assertThat(first).isEqualTo(equal).isNotEqualTo(different);
        assertThat(first.hashCode()).isEqualTo(equal.hashCode());
        assertThat(first.toString())
                .startsWith(recordClass.getSimpleName() + "[")
                .contains("x=7", "name=alice");
        assertThat(record.x().field().name()).isEqualTo("x");
        assertThat(record.x().accessor().name()).isEqualTo("x");
    }

    private static RecordModel pointRecord()
    {
        ClassDefinition definition = ClassDefinition.defineRecord(generatedType("Point")).access(PUBLIC);
        RecordComponentDefinition x = definition.recordComponent("x", int.class).build();
        RecordComponentDefinition name = definition.recordComponent("name", String.class).build();
        MethodDefinition score = definition.method("score", int.class).access(PUBLIC);
        score.body().ret(x.get(score.thisVariable()).add(name.get(score.thisVariable()).invoke("length", int.class)));
        return new RecordModel(definition.build(), x);
    }

    private static ClassDesc generatedType(String name)
    {
        return ClassDesc.of(TestRecordGeneration.class.getPackageName() + ".Generated" + name + NEXT_ID.incrementAndGet());
    }

    private record RecordModel(ClassModel model, RecordComponentDefinition x) {}

    private static final class ReturnFromSetup
            implements SyntheticExpression
    {
        @Override
        public ClassDesc type()
        {
            return DescriptorUtils.classDesc(IllegalStateException.class);
        }

        @Override
        public ExpressionPlan expansion(ExpansionContext context)
        {
            CodeBlock setup = CodeBlock.blockBuilder()
                    .append(IfStatement.builder()
                            .condition(constantBoolean(true))
                            .then(CodeBlock.blockBuilder().ret().build())
                            .build())
                    .build();
            return new ExpressionPlan(setup, BytecodeExpressions.newInstance(IllegalStateException.class));
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface ComponentMarker
    {
        String value();
    }

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface InvisibleComponentMarker {}
}
