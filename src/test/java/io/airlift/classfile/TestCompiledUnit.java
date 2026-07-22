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
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.boundMethodHandle;
import static io.airlift.classfile.BytecodeExpressions.constantClass;
import static io.airlift.classfile.BytecodeExpressions.constantDynamic;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.invokeDynamic;
import static io.airlift.classfile.BytecodeExpressions.invokeLinked;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.DescriptorUtils.classDesc;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.lang.reflect.AccessFlag.SYNTHETIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCompiledUnit
{
    private static final AtomicLong NEXT_ID = new AtomicLong();

    @Test
    void testNameFreeGeneratedLinkageForNominalClasses()
            throws Exception
    {
        Models models = models();
        ClassLoader parent = getClass().getClassLoader();
        StandardClassDefiner definer = StandardClassDefiner.builder(parent).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(testPolicy())
                .compileUnit(models.primary(), List.of(models.helper()));

        assertNameFreeUnit(models, unit);
        DefinedUnit defined = definer.defineUnit(unit);

        assertThat(defined.primaryClass().getMethod("evaluate", int.class).invoke(null, 20)).isEqualTo(42);
        assertThat(defined.primaryClass(Object.class)).isSameAs(defined.primaryClass());
        assertThat(defined.primaryClass().isHidden()).isFalse();
        assertThat(defined.definedClass(models.helper().type()).isHidden()).isFalse();
        assertThat(defined.definedClass(models.helper().type(), Object.class)).isSameAs(defined.definedClass(models.helper().type()));
        assertThat(defined.lookup(models.primary().type())).isEmpty();
        assertThat(defined.primaryClass().getClassLoader()).isSameAs(defined.definedClass(models.helper().type()).getClassLoader());
    }

    @Test
    void testNameFreeGeneratedLinkageForHiddenClasses()
            throws Throwable
    {
        Models models = models();
        MethodHandles.Lookup hostLookup = MethodHandles.lookup();
        HiddenClassDefiner definer = HiddenClassDefiner.builder(hostLookup).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(testPolicy())
                .compileUnit(models.primary(), List.of(models.helper()));

        assertNameFreeUnit(models, unit);
        DefinedUnit defined = definer.defineUnit(unit);

        assertThat(defined.primaryClass().isHidden()).isTrue();
        assertThat(defined.primaryClass(Object.class)).isSameAs(defined.primaryClass());
        assertThat(defined.definedClass(models.helper().type()).isHidden()).isTrue();
        assertThat(defined.definedClass(models.helper().type(), Object.class)).isSameAs(defined.definedClass(models.helper().type()));
        assertThat(defined.primaryLookup()).isPresent();
        assertThat(defined.lookup(models.primary().type())).isEqualTo(defined.primaryLookup());
        assertThat(defined.lookup(models.helper().type())).isPresent();
        int result = (int) defined.primaryLookup().orElseThrow()
                .findStatic(defined.primaryClass(), "evaluate", MethodType.methodType(int.class, int.class))
                .invokeExact(20);
        assertThat(result).isEqualTo(42);
    }

    @Test
    void testLargeExpressionIsDecomposedWithoutChangingTheLogicalModel()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedExpression" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        Parameter input = arg("input", int.class);
        MethodDefinition evaluate = definition.method("evaluate", CD_int, input).access(PUBLIC, STATIC);
        BytecodeExpression expression = input;
        for (int index = 0; index < 400; index++) {
            expression = expression.add(constantInt(1));
        }
        evaluate.body().ret(expression);
        ClassModel logicalModel = definition.build();
        String logicalRendering = logicalModel.toString();

        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(500)
                .targetMethodCodeLimit(200)
                .build();
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(logicalModel);

        assertThat(logicalModel.toString()).isEqualTo(logicalRendering);
        CompilationReport.ClassInfo classInfo = unit.report().classes().getFirst();
        assertThat(classInfo.methods())
                .extracting(CompilationReport.MethodInfo::name)
                .anyMatch(name -> name.startsWith("evaluate$expression$"));
        assertThat(classInfo.methods())
                .allSatisfy(method -> assertThat(method.codeBytes()).isLessThanOrEqualTo(policy.hardMethodCodeLimit()));

        DefinedUnit defined = definer.defineUnit(unit);
        assertThat(defined.primaryClass().getMethod("evaluate", int.class).invoke(null, 42)).isEqualTo(442);
    }

    @Test
    void testVeryDeepExpressionIsValidatedAndSplitWithoutOverflow()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedDeepExpression" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        Parameter input = arg("input", int.class);
        MethodDefinition evaluate = definition.method("evaluate", int.class, input).access(PUBLIC, STATIC);
        BytecodeExpression expression = input;
        for (int index = 0; index < 10_000; index++) {
            expression = expression.add(constantInt(1));
        }
        evaluate.body().ret(expression);

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = compilerWithSmallMethodLimit(definer.compilationTarget()).compileUnit(definition.build());
        Class<?> generated = definer.defineUnit(unit).primaryClass();

        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .map(CompilationReport.MethodInfo::name))
                .anyMatch(name -> name.startsWith("evaluate$expression$"));
        assertThat(generated.getMethod("evaluate", int.class).invoke(null, 42)).isEqualTo(10_042);
    }

    @Test
    void testDeepUnsplitExpressionIsValidatedWithoutOverflow()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedDeepUnsplitExpression" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition evaluate = definition.method("evaluate", int.class).access(PUBLIC, STATIC);
        BytecodeExpression expression = constantInt(0);
        for (int index = 0; index < 1_900; index++) {
            expression = expression.add(constantInt(1));
        }
        evaluate.body().ret(expression);

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompilationPolicy policy = new CompilationPolicy(65_535, 65_535, 35, 325, false);
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(definition.build());
        Class<?> generated = definer.defineUnit(unit).primaryClass();

        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .map(CompilationReport.MethodInfo::name))
                .noneMatch(name -> name.startsWith("evaluate$expression$"));
        assertThat(generated.getMethod("evaluate").invoke(null)).isEqualTo(1_900);
    }

    @Test
    void testAuthoredSyntheticMethodsAreNotShardedByName()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedSyntheticApi" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        for (int index = 0; index < 97; index++) {
            definition.method("api$expression$" + index, int.class)
                    .access(PUBLIC, STATIC, SYNTHETIC)
                    .body()
                    .ret(constantInt(index));
        }
        MethodDefinition large = definition.method("api$expression$large", int.class).access(PUBLIC, STATIC, SYNTHETIC);
        appendLargeStatementRegion(large, constantInt(0));

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = compilerWithSmallMethodLimit(definer.compilationTarget()).compileUnit(definition.build());
        Class<?> generated = definer.defineUnit(unit).primaryClass();

        assertThat(unit.types()).containsExactly(type);
        assertThat(generated.getMethod("api$expression$0").invoke(null)).isEqualTo(0);
        assertThat(generated.getMethod("api$expression$96").invoke(null)).isEqualTo(96);
        assertThat(generated.getMethod("api$expression$large").invoke(null)).isEqualTo(400);
    }

    @Test
    void testExpressionHelperNamesAreUniqueAcrossOverloadsAndAuthoredMethods()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedOverloadedExpressions" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        definition.method("evaluate$expression$1", int.class).access(PRIVATE, STATIC).body().ret(constantInt(-1));

        Parameter intInput = arg("input", int.class);
        MethodDefinition intEvaluate = definition.method("evaluate", int.class, intInput).access(PUBLIC, STATIC);
        intEvaluate.body().ret(largeConstantExpression(1));

        Parameter longInput = arg("input", long.class);
        MethodDefinition longEvaluate = definition.method("evaluate", int.class, longInput).access(PUBLIC, STATIC);
        longEvaluate.body().ret(largeConstantExpression(2));

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = compilerWithSmallMethodLimit(definer.compilationTarget()).compileUnit(definition.build());
        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .map(CompilationReport.MethodInfo::name)
                .toList())
                .filteredOn(name -> name.startsWith("evaluate$expression$"))
                .doesNotHaveDuplicates()
                .contains("evaluate$expression$1", "evaluate$expression$2", "evaluate$expression$3");

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate", int.class).invoke(null, 0)).isEqualTo(401);
        assertThat(generated.getMethod("evaluate", long.class).invoke(null, 0L)).isEqualTo(402);
    }

    @Test
    void testStatementHelperNamesAreUniqueAcrossOverloadsAndAuthoredMethods()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedOverloadedStatements" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        Parameter authoredValue = arg("value", int.class);
        definition.method("evaluate$statements$1", int.class, authoredValue).access(PRIVATE, STATIC).body().ret(authoredValue);

        Parameter intInput = arg("input", int.class);
        MethodDefinition intEvaluate = definition.method("evaluate", int.class, intInput).access(PUBLIC, STATIC);
        appendLargeStatementRegion(intEvaluate, intInput);

        Parameter longInput = arg("input", long.class);
        MethodDefinition longEvaluate = definition.method("evaluate", int.class, longInput).access(PUBLIC, STATIC);
        appendLargeStatementRegion(longEvaluate, constantInt(2));

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = compilerWithSmallMethodLimit(definer.compilationTarget()).compileUnit(definition.build());
        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .map(CompilationReport.MethodInfo::name)
                .toList())
                .filteredOn(name -> name.startsWith("evaluate$statements$"))
                .doesNotHaveDuplicates()
                .contains("evaluate$statements$1", "evaluate$statements$2", "evaluate$statements$3");

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate", int.class).invoke(null, 1)).isEqualTo(401);
        assertThat(generated.getMethod("evaluate", long.class).invoke(null, 1L)).isEqualTo(402);
    }

    @Test
    void testSequentialStatementsCarryOneLiveOutput()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedStatements" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        Parameter input = arg("input", int.class);
        MethodDefinition evaluate = definition.method("evaluate", CD_int, input).access(PUBLIC, STATIC);
        Variable result = evaluate.body().declare("result", input);
        for (int index = 0; index < 400; index++) {
            evaluate.body().append(result.set(result.add(constantInt(1))));
        }
        evaluate.body().ret(result);
        ClassModel logicalModel = definition.build();

        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(500)
                .targetMethodCodeLimit(200)
                .build();
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(logicalModel);

        assertThat(unit.report().classes().getFirst().methods())
                .extracting(CompilationReport.MethodInfo::name)
                .anyMatch(name -> name.startsWith("evaluate$statements$"));
        assertThat(unit.report().classes().getFirst().methods())
                .allSatisfy(method -> assertThat(method.codeBytes()).isLessThanOrEqualTo(policy.hardMethodCodeLimit()));

        DefinedUnit defined = definer.defineUnit(unit);
        assertThat(defined.primaryClass().getMethod("evaluate", int.class).invoke(null, 42)).isEqualTo(442);
    }

    @Test
    void testSequentialStatementsInitializeOutputInsideHelper()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedInitializedStatements" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition evaluate = definition.method("evaluate", int.class).access(PUBLIC, STATIC);
        Variable result = evaluate.body().declare(int.class, "result");
        for (int index = 1; index <= 100; index++) {
            evaluate.body().append(result.set(constantInt(index)));
        }
        evaluate.body().ret(result);

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = compilerWithSmallMethodLimit(definer.compilationTarget()).compileUnit(definition.build());
        assertThat(unit.report().classes().getFirst().methods())
                .extracting(CompilationReport.MethodInfo::name)
                .anyMatch(name -> name.startsWith("evaluate$statements$"));

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate").invoke(null)).isEqualTo(100);
    }

    @Test
    @SuppressWarnings({"ExplicitArrayForVarargs", "PrimitiveArrayPassedToVarargsMethod"}) // MethodHandle.invokeExact is signature-polymorphic.
    void testLargeBooleanExpressionPreservesShortCircuiting()
            throws Throwable
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedShortCircuit" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        Parameter values = arg("values", int[].class);
        MethodDefinition evaluate = definition.method("evaluate", CD_boolean, values).access(PUBLIC, STATIC);
        BytecodeExpression expression = values.getElement(0).equal(constantInt(1));
        for (int index = 1; index <= 1_000; index++) {
            expression = expression.or(values.getElement(index).equal(constantInt(1)));
        }
        evaluate.body().ret(expression);
        ClassModel logicalModel = definition.build();

        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(500)
                .targetMethodCodeLimit(200)
                .build();
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(logicalModel);
        assertThat(unit.types()).hasSizeGreaterThan(1);
        String primaryClassfile = new String(unit.classfile(unit.primaryType()), StandardCharsets.ISO_8859_1);
        unit.types().stream()
                .filter(typeValue -> !typeValue.equals(unit.primaryType()))
                .forEach(typeValue -> assertThat(primaryClassfile).doesNotContain(typeValue.descriptorString()));
        DefinedUnit defined = definer.defineUnit(unit);

        assertThat(defined.primaryClass().getMethod("evaluate", int[].class).invoke(null, (Object) new int[] {1})).isEqualTo(true);
        assertThat(defined.primaryClass().getMethod("evaluate", int[].class).invoke(null, (Object) new int[1_001])).isEqualTo(false);

        MethodHandles.Lookup hostLookup = MethodHandles.lookup();
        CompiledUnit hiddenUnit = ClassCompiler.forTarget(CompilationTarget.forLookup(hostLookup))
                .policy(policy)
                .compileUnit(logicalModel);
        DefinedUnit hidden = HiddenClassDefiner.builder(hostLookup).build().defineUnit(hiddenUnit);
        assertThat(hidden.types()).allSatisfy(typeValue -> assertThat(hidden.definedClass(typeValue).isHidden()).isTrue());
        MethodHandle hiddenEvaluate = hidden.primaryLookup().orElseThrow()
                .findStatic(hidden.primaryClass(), "evaluate", MethodType.methodType(boolean.class, int[].class));
        assertThat((boolean) hiddenEvaluate.invokeExact(new int[] {1})).isTrue();
        assertThat((boolean) hiddenEvaluate.invokeExact(new int[1_001])).isFalse();
    }

    @Test
    void testConstructorPreSuperCalculationUsesStaticExpressionHelpers()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedConstructor" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        FieldDefinition stored = definition.field("stored", int.class).access(PRIVATE, FINAL).build();
        Parameter input = arg("input", int.class);
        MethodDefinition constructor = definition.constructor(input).access(PUBLIC);
        BytecodeExpression expression = input;
        for (int index = 0; index < 400; index++) {
            expression = expression.add(constantInt(1));
        }
        Variable computed = constructor.body().declare("computed", expression);
        constructor.body()
                .invokeSuperConstructor()
                .append(constructor.thisVariable().setField(stored, computed))
                .ret();
        MethodDefinition value = definition.method("value", int.class).access(PUBLIC);
        value.body().ret(value.thisVariable().getField(stored));

        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(500)
                .targetMethodCodeLimit(200)
                .build();
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(definition.build());
        assertThat(unit.report().classes().getFirst().methods())
                .extracting(CompilationReport.MethodInfo::name)
                .anyMatch(name -> name.startsWith("_init_$expression$"));

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        Object instance = generated.getConstructor(int.class).newInstance(42);
        assertThat(generated.getMethod("value").invoke(instance)).isEqualTo(442);
    }

    @Test
    void testConstructorPostSuperReceiverUsesStaticExpressionHelpers()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedPostSuperConstructor" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        FieldDefinition valueField = definition.field("value", int.class).access(PRIVATE).build();
        MethodDefinition constructor = definition.constructor().access(PUBLIC);
        constructor.body().invokeSuperConstructor();
        BytecodeExpression value = constructor.thisVariable().getField(valueField);
        for (int index = 0; index < 400; index++) {
            value = value.add(constantInt(1));
        }
        constructor.body().append(constructor.thisVariable().setField(valueField, value)).ret();
        MethodDefinition valueMethod = definition.method("value", int.class).access(PUBLIC);
        valueMethod.body().ret(valueMethod.thisVariable().getField(valueField));

        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(500)
                .targetMethodCodeLimit(200)
                .build();
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(definition.build());
        assertThat(unit.report().classes().getFirst().methods())
                .extracting(CompilationReport.MethodInfo::name)
                .anyMatch(name -> name.startsWith("_init_$expression$"));

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        Object instance = generated.getConstructor().newInstance();
        assertThat(generated.getMethod("value").invoke(instance)).isEqualTo(400);
    }

    @Test
    void testShardingPreservesExternalStaticInvocationOwner()
            throws Exception
    {
        ClassDesc externalType = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedCollisionTarget" + NEXT_ID.incrementAndGet());
        ClassDefinition external = ClassDefinition.define(externalType).access(PUBLIC, FINAL);
        external.method("evaluate$expression$1", int.class).access(PUBLIC, STATIC).body().ret(constantInt(42));

        ClassDesc primaryType = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedCollisionCaller" + NEXT_ID.incrementAndGet());
        ClassDefinition primary = ClassDefinition.define(primaryType).access(PUBLIC, FINAL);
        MethodDefinition evaluate = primary.method("evaluate", int.class).access(PUBLIC, STATIC);
        BytecodeExpression value = invokeStatic(externalType, "evaluate$expression$1", CD_int);
        for (int index = 0; index < 400; index++) {
            value = value.add(constantInt(1));
        }
        evaluate.body().ret(value);
        for (int method = 0; method < 96; method++) {
            primary.method("large" + method, int.class).access(PUBLIC, STATIC).body().ret(largeConstantExpression(method));
        }

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = compilerWithSmallMethodLimit(definer.compilationTarget())
                .compileUnit(primary.build(), List.of(external.build()));
        assertThat(unit.types()).hasSizeGreaterThan(2);

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate").invoke(null)).isEqualTo(442);
    }

    @Test
    void testConstructorInvocationArgumentsUseStaticExpressionHelpers()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedConstructorArgument" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type)
                .access(PUBLIC, FINAL)
                .superClass(ArrayList.class);
        MethodDefinition constructor = definition.constructor().access(PUBLIC);
        BytecodeExpression capacity = constantInt(1);
        for (int index = 0; index < 10_000; index++) {
            capacity = capacity.add(constantInt(1));
        }
        constructor.body()
                .invokeSuperConstructor(ArrayList.class.getConstructor(int.class), capacity)
                .ret();

        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(500)
                .targetMethodCodeLimit(200)
                .build();
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(definition.build());

        assertThat(unit.report().classes().getFirst().methods())
                .extracting(CompilationReport.MethodInfo::name)
                .anyMatch(name -> name.startsWith("_init_$expression$"));
        assertThat(definer.defineUnit(unit).primaryClass().getConstructor().newInstance())
                .isInstanceOf(ArrayList.class);
    }

    @Test
    void testOptimizationWarningDoesNotPreventDefinition()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedWarning" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition evaluate = definition.method("evaluate", int.class).access(PUBLIC, STATIC);
        CodeBlock.Builder loopBody = CodeBlock.blockBuilder();
        Variable value = loopBody.declare("value", constantInt(0));
        for (int index = 0; index < 100; index++) {
            loopBody.append(value.set(value.add(constantInt(1))));
        }
        evaluate.body()
                .append(WhileLoop.builder()
                        .condition(BytecodeExpressions.constantFalse())
                        .body(loopBody.build())
                        .build())
                .ret(constantInt(7));

        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(2_000)
                .targetMethodCodeLimit(100)
                .build();
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(definition.build());

        assertThat(unit.report().warnings())
                .extracting(CompilationWarning::category)
                .contains(CompilationWarning.Category.HUGE_METHOD);
        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate").invoke(null)).isEqualTo(7);
    }

    @Test
    void testHardMethodLimitAppliesToEveryCompilationForm()
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedHardLimit" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        definition.method("evaluate", int.class).access(PUBLIC, STATIC).body().ret(constantInt(7));
        ClassModel model = definition.build();
        ClassCompiler compiler = ClassCompiler.forTarget(CompilationTarget.forClassLoader(getClass().getClassLoader()))
                .policy(new CompilationPolicy(1, 1, 1, 1, false));

        assertThatThrownBy(() -> compiler.compileClass(model))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("above the configured hard limit of 1 bytes");
        assertThatThrownBy(() -> compiler.compileClassBundle(List.of(model)))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("above the configured hard limit of 1 bytes");
        assertThatThrownBy(() -> compiler.compileUnit(model))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("above the configured hard limit of 1 bytes");
    }

    @Test
    void testConstantPoolPressureCreatesCompanionClasses()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedConstants" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition touch = definition.method("touch", void.class).access(PUBLIC, STATIC);
        for (int index = 0; index < 25_000; index++) {
            touch.body().append(constantString("generated-constant-" + index).pop());
        }
        touch.body().ret();

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .compileUnit(definition.build());
        assertThat(unit.types()).hasSizeGreaterThan(1);
        assertThat(unit.report().classes())
                .allSatisfy(classInfo -> assertThat(classInfo.constantPoolCount()).isLessThan(65_535));

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        generated.getMethod("touch").invoke(null);
    }

    @Test
    void testBoundMethodHandlePressureCreatesCompanionClasses()
            throws Throwable
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedBoundHandles" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition[] helpers = new MethodDefinition[97];
        for (int helperIndex = 0; helperIndex < helpers.length; helperIndex++) {
            helpers[helperIndex] = definition.method("helper$expression$" + helperIndex, int.class).access(PRIVATE, STATIC, SYNTHETIC);
            for (int bindingIndex = 0; bindingIndex < 500; bindingIndex++) {
                MethodHandle handle = MethodHandles.constant(int.class, helperIndex * 500 + bindingIndex);
                helpers[helperIndex].body().append(boundMethodHandle(handle).invoke().pop());
            }
            helpers[helperIndex].body().ret(constantInt(helperIndex));
        }
        definition.method("evaluate", int.class).access(PUBLIC, STATIC).body().ret(invokeStatic(helpers[0]));

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        ClassModel model = definition.build();
        CompiledUnit unit = compileSharded(
                ClassCompiler.forTarget(definer.compilationTarget()),
                definer.compilationTarget(),
                model,
                helpers);

        assertThat(unit.report().classes())
                .hasSizeGreaterThan(2)
                .allSatisfy(classInfo -> assertThat(classInfo.constantPoolCount()).isLessThan(65_535));
        assertThat(definer.defineUnit(unit).primaryClass().getMethod("evaluate").invoke(null)).isEqualTo(0);
    }

    @Test
    void testGeneratedLinksAreRewrittenThroughoutStructuredCode()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedStructuredLinks" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition[] helpers = new MethodDefinition[97];
        for (int index = 0; index < helpers.length; index++) {
            helpers[index] = definition.method("helper$expression$" + index, int.class).access(PRIVATE, STATIC, SYNTHETIC);
            helpers[index].body().ret(constantInt(index));
        }

        Parameter input = arg("input", int.class);
        MethodDefinition evaluate = definition.method("evaluate", int.class, input).access(PUBLIC, STATIC);
        Variable result = evaluate.body().declare("result", constantInt(0));
        evaluate.body()
                .append(SwitchStatement.builder()
                        .expression(input)
                        .caseValue(0, result.set(invokeStatic(helpers[0])))
                        .defaultCase(result.set(invokeStatic(helpers[1])))
                        .build())
                .append(TryCatch.builder()
                        .tryBlock(result.set(result.add(invokeStatic(helpers[2]))))
                        .catching(RuntimeException.class, "failure", (body, _) -> body.append(result.set(invokeStatic(helpers[3]))))
                        .finallyBlock(result.set(result.add(invokeStatic(helpers[4]))))
                        .build())
                .ret(new LinkedSynthetic(result, helpers[5], helpers[6]));

        ClassModel logicalModel = definition.build();
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = compileSharded(
                ClassCompiler.forTarget(definer.compilationTarget()).policy(testPolicy()),
                definer.compilationTarget(),
                logicalModel,
                helpers);
        assertThat(unit.types()).hasSize(3);

        DefinedUnit defined = definer.defineUnit(unit);
        assertThat(defined.primaryClass().getMethod("evaluate", int.class).invoke(null, 0)).isEqualTo(17);
        assertThat(defined.primaryClass().getMethod("evaluate", int.class).invoke(null, 1)).isEqualTo(18);
    }

    @Test
    void testProtectedMembersPreventHelperSharding()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedProtectedAccess" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type)
                .access(PUBLIC, FINAL)
                .superClass(ProtectedBase.class);
        Method protectedMethod = ProtectedBase.class.getDeclaredMethod("protectedValue");
        Field protectedField = ProtectedBase.class.getDeclaredField("PROTECTED_FIELD");

        MethodDefinition[] helpers = new MethodDefinition[97];
        for (int index = 0; index < helpers.length; index++) {
            helpers[index] = definition.method("helper$expression$" + index, int.class).access(PRIVATE, STATIC, SYNTHETIC);
            helpers[index].body().ret(index % 2 == 0 ? invokeStatic(protectedMethod) : BytecodeExpressions.getStatic(protectedField));
        }
        MethodDefinition evaluate = definition.method("evaluate", int.class).access(PUBLIC, STATIC);
        evaluate.body().ret(invokeStatic(helpers[0]).add(invokeStatic(helpers[1])));

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        ClassModel model = definition.build();
        CompiledUnit unit = compileSharded(
                ClassCompiler.forTarget(definer.compilationTarget()),
                definer.compilationTarget(),
                model,
                helpers);
        assertThat(unit.types()).containsExactly(type);

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate").invoke(null)).isEqualTo(42);
    }

    @Test
    void testInvokeDynamicPreventsHelperSharding()
            throws Exception
    {
        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDesc(TestCompiledUnit.class),
                "lookupClassCallSite",
                MethodTypeDesc.of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType));
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(bootstrap, "lookupClass", MethodTypeDesc.of(CD_Class));

        assertLookupSensitiveExpression("InvokeDynamic", invokeDynamic(callSite));
    }

    @Test
    void testDynamicConstantPreventsHelperSharding()
            throws Exception
    {
        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDesc(TestCompiledUnit.class),
                "lookupClassConstant",
                MethodTypeDesc.of(CD_Object, CD_MethodHandles_Lookup, CD_String, CD_Class));
        DynamicConstantDesc<?> constant = DynamicConstantDesc.ofNamed(bootstrap, "lookupClass", CD_Class);

        assertLookupSensitiveExpression("DynamicConstant", constantDynamic(constant));
    }

    @Test
    void testHiddenLogicalClassLiteralPreventsHelperSharding()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedHiddenClassLiteral" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition[] helpers = new MethodDefinition[97];
        for (int index = 0; index < helpers.length; index++) {
            helpers[index] = definition.method("helper$expression$" + index, Class.class).access(PRIVATE, STATIC, SYNTHETIC);
            helpers[index].body().ret(index == 0 ? constantClass(type) : constantClass(Object.class));
        }
        definition.method("evaluate", Class.class).access(PUBLIC, STATIC).body().ret(invokeStatic(helpers[0]));

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        ClassModel model = definition.build();
        CompiledUnit unit = compileSharded(
                ClassCompiler.forTarget(definer.compilationTarget()),
                definer.compilationTarget(),
                model,
                helpers);
        assertThat(unit.types()).containsExactly(type);

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate").invoke(null)).isSameAs(generated);
    }

    @Test
    void testHiddenLogicalMethodDescriptorPreventsHelperSharding()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedHiddenDescriptor" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition[] helpers = new MethodDefinition[97];
        Parameter value = arg("value", type);
        helpers[0] = definition.method("helper$expression$0", int.class, value).access(PRIVATE, STATIC, SYNTHETIC);
        helpers[0].body().ret(constantInt(42));
        for (int index = 1; index < helpers.length; index++) {
            helpers[index] = definition.method("helper$expression$" + index, int.class).access(PRIVATE, STATIC, SYNTHETIC);
            helpers[index].body().ret(constantInt(index));
        }
        definition.method("evaluate", int.class).access(PUBLIC, STATIC).body().ret(invokeStatic(helpers[0], constantNull(type)));

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        ClassModel model = definition.build();
        CompiledUnit unit = compileSharded(
                ClassCompiler.forTarget(definer.compilationTarget()),
                definer.compilationTarget(),
                model,
                helpers);
        assertThat(unit.types()).containsExactly(type);

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate").invoke(null)).isEqualTo(42);
    }

    @Test
    void testCallerSensitiveMethodPreventsHelperSharding()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedCallerSensitive" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        definition.method("secret", int.class).access(PRIVATE, STATIC).body().ret(constantInt(42));

        BytecodeExpression reflectedMethod = constantClass(type).invoke(
                "getDeclaredMethod",
                Method.class,
                constantString("secret"),
                newArray(Class[].class, 0));
        BytecodeExpression reflectedValue = reflectedMethod.invoke(
                        "invoke",
                        Object.class,
                        constantNull(Object.class),
                        newArray(Object[].class, 0))
                .cast(Integer.class)
                .invoke("intValue", int.class);

        MethodDefinition[] helpers = new MethodDefinition[97];
        for (int index = 0; index < helpers.length; index++) {
            helpers[index] = definition.method("helper$expression$" + index, int.class).access(PRIVATE, STATIC, SYNTHETIC);
            helpers[index].body().ret(index == 0 ? reflectedValue : constantInt(index));
        }
        definition.method("evaluate", int.class).access(PUBLIC, STATIC).body().ret(invokeStatic(helpers[0]));

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        ClassModel model = definition.build();
        CompiledUnit unit = compileSharded(
                ClassCompiler.forTarget(definer.compilationTarget()),
                definer.compilationTarget(),
                model,
                helpers);
        assertThat(unit.types()).containsExactly(type);

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate").invoke(null)).isEqualTo(42);
    }

    @Test
    void testPublicMembersAllowHelperSharding()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedPublicAccess" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        Method absoluteValue = Math.class.getMethod("abs", int.class);

        MethodDefinition[] helpers = new MethodDefinition[97];
        for (int index = 0; index < helpers.length; index++) {
            helpers[index] = definition.method("helper$expression$" + index, int.class).access(PRIVATE, STATIC, SYNTHETIC);
            helpers[index].body().ret(invokeStatic(absoluteValue, constantInt(-index)));
        }
        MethodDefinition evaluate = definition.method("evaluate", int.class).access(PUBLIC, STATIC);
        evaluate.body().ret(invokeStatic(helpers[41]));

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        ClassModel model = definition.build();
        CompiledUnit unit = compileSharded(
                ClassCompiler.forTarget(definer.compilationTarget()),
                definer.compilationTarget(),
                model,
                helpers);
        assertThat(unit.types()).hasSizeGreaterThan(1);

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate").invoke(null)).isEqualTo(41);
    }

    @Test
    void testOrderedGeneratedDependenciesAllowHelperSharding()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedOrderedDependencies" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition[] helpers = new MethodDefinition[97];
        for (int index = 0; index < helpers.length; index++) {
            helpers[index] = definition.method("helper$expression$" + index, int.class).access(PRIVATE, STATIC, SYNTHETIC);
            helpers[index].body().ret(index == 0 ? constantInt(0) : invokeStatic(helpers[index - 1]).add(constantInt(1)));
        }
        definition.method("evaluate", int.class).access(PUBLIC, STATIC).body().ret(invokeStatic(helpers[96]));

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        ClassModel model = definition.build();
        CompiledUnit unit = compileSharded(
                ClassCompiler.forTarget(definer.compilationTarget()),
                definer.compilationTarget(),
                model,
                helpers);
        assertThat(unit.types()).hasSizeGreaterThan(1);

        DefinedUnit definedUnit = definer.defineUnit(unit);
        assertThat(definedUnit.types())
                .allSatisfy(generatedType -> assertThat(definedUnit.definedClass(generatedType).isHidden()).isTrue());
        assertThat(definedUnit.primaryClass().getMethod("evaluate").invoke(null)).isEqualTo(96);
    }

    @Test
    void testForwardGeneratedDependencyPreventsHelperSharding()
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedForwardDependency" + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition[] helpers = new MethodDefinition[97];
        for (int index = 0; index < helpers.length; index++) {
            helpers[index] = definition.method("helper$expression$" + index, int.class).access(PRIVATE, STATIC, SYNTHETIC);
        }
        helpers[0].body().ret(invokeStatic(helpers[96]));
        for (int index = 1; index < helpers.length; index++) {
            helpers[index].body().ret(constantInt(index));
        }
        definition.method("evaluate", int.class).access(PUBLIC, STATIC).body().ret(invokeStatic(helpers[0]));

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        ClassModel model = definition.build();
        CompiledUnit unit = compileSharded(
                ClassCompiler.forTarget(definer.compilationTarget()),
                definer.compilationTarget(),
                model,
                helpers);
        assertThat(unit.types()).containsExactly(type);

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate").invoke(null)).isEqualTo(96);
    }

    private static BytecodeExpression largeConstantExpression(int initialValue)
    {
        BytecodeExpression expression = constantInt(initialValue);
        for (int index = 0; index < 400; index++) {
            expression = expression.add(constantInt(1));
        }
        return expression;
    }

    private static void assertLookupSensitiveExpression(String name, BytecodeExpression expression)
            throws Exception
    {
        ClassDesc type = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".Generated" + name + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition[] helpers = new MethodDefinition[97];
        for (int index = 0; index < helpers.length; index++) {
            helpers[index] = definition.method("helper$expression$" + index, Class.class).access(PRIVATE, STATIC, SYNTHETIC);
            helpers[index].body().ret(index == 0 ? expression : constantClass(Object.class));
        }
        MethodDefinition evaluate = definition.method("evaluate", Class.class).access(PUBLIC, STATIC);
        evaluate.body().ret(invokeStatic(helpers[0]));

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        ClassModel model = definition.build();
        CompiledUnit unit = compileSharded(
                ClassCompiler.forTarget(definer.compilationTarget()),
                definer.compilationTarget(),
                model,
                helpers);
        assertThat(unit.types()).containsExactly(type);

        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("evaluate").invoke(null)).isSameAs(generated);
    }

    private static CompiledUnit compileSharded(
            ClassCompiler compiler,
            CompilationTarget target,
            ClassModel model,
            MethodDefinition[] helpers)
    {
        Set<String> generatedMethods = Set.copyOf(Arrays.stream(helpers)
                .map(MethodDefinition::name)
                .toList());
        ClassSharder.Result sharded = ClassSharder.shard(
                model,
                generatedMethods,
                new LinkageContext(target, List.of(model)));
        return compiler.compileUnit(sharded.primary(), sharded.auxiliaries());
    }

    private static void appendLargeStatementRegion(MethodDefinition method, BytecodeExpression initialValue)
    {
        Variable result = method.body().declare("result", initialValue);
        for (int index = 0; index < 400; index++) {
            method.body().append(result.set(result.add(constantInt(1))));
        }
        method.body().ret(result);
    }

    private static ClassCompiler compilerWithSmallMethodLimit(CompilationTarget target)
    {
        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(500)
                .targetMethodCodeLimit(200)
                .build();
        return ClassCompiler.forTarget(target)
                .policy(policy);
    }

    private static void assertNameFreeUnit(Models models, CompiledUnit unit)
    {
        assertThat(unit.primaryType()).isEqualTo(models.primary().type());
        assertThat(unit.definitionOrder()).containsExactly(models.helper().type(), models.primary().type());
        assertThat(unit.report().warnings()).isEmpty();
        assertThat(unit.report().classes()).hasSize(2);
        assertThat(unit.report().classes())
                .allSatisfy(classInfo -> {
                    assertThat(classInfo.classfileBytes()).isPositive();
                    assertThat(classInfo.constantPoolCount()).isPositive();
                    assertThat(classInfo.methods()).isNotEmpty();
                    assertThat(classInfo.methods()).allSatisfy(method -> {
                        assertThat(method.codeBytes()).isPositive();
                        assertThat(method.maxStack()).isPositive();
                        assertThat(method.maxLocals()).isPositive();
                    });
                });

        // A generated link is a runtime-data MethodHandle, never a symbolic classfile reference.
        String primaryClassfile = new String(unit.classfile(models.primary().type()), StandardCharsets.ISO_8859_1);
        assertThat(primaryClassfile)
                .doesNotContain(models.helper().type().descriptorString())
                .doesNotContain(models.helper().type().displayName().replace('.', '/'));
    }

    private static Models models()
    {
        long id = NEXT_ID.incrementAndGet();
        ClassDesc helperType = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedUnitHelper" + id);
        ClassDefinition helperDefinition = ClassDefinition.define(helperType).access(PUBLIC, FINAL);
        Parameter helperValue = arg("value", int.class);
        MethodDefinition increment = helperDefinition.method("increment", CD_int, helperValue).access(PUBLIC, STATIC);
        increment.body().ret(helperValue.add(constantInt(1)));

        ClassDesc primaryType = ClassDesc.of(TestCompiledUnit.class.getPackageName() + ".GeneratedUnitPrimary" + id);
        ClassDefinition primaryDefinition = ClassDefinition.define(primaryType).access(PUBLIC, FINAL);
        Parameter primaryValue = arg("value", int.class);
        MethodDefinition evaluate = primaryDefinition.method("evaluate", CD_int, primaryValue).access(PUBLIC, STATIC);
        evaluate.body().ret(invokeLinked(increment, primaryValue).multiply(constantInt(2)));

        return new Models(primaryDefinition.build(), helperDefinition.build());
    }

    private static CompilationPolicy testPolicy()
    {
        return CompilationPolicy.builder()
                .targetMethodCodeLimit(8_000)
                .build();
    }

    private record Models(ClassModel primary, ClassModel helper) {}

    private record LinkedSynthetic(Variable result, MethodDefinition valueHelper, MethodDefinition setupHelper)
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
            return new ExpressionPlan(
                    CodeBlock.block(result.set(result.add(invokeStatic(setupHelper)))),
                    result.add(invokeStatic(valueHelper)));
        }

        @Override
        public String toString()
        {
            return "linkedSynthetic(" + result + ")";
        }
    }

    public static CallSite lookupClassCallSite(MethodHandles.Lookup lookup, String name, MethodType type)
    {
        return new ConstantCallSite(MethodHandles.constant(Class.class, lookup.lookupClass()).asType(type));
    }

    public static Object lookupClassConstant(MethodHandles.Lookup lookup, String name, Class<?> type)
    {
        return lookup.lookupClass();
    }

    public static class ProtectedBase
    {
        protected static final int PROTECTED_FIELD = 1;

        protected ProtectedBase() {}

        protected static int protectedValue()
        {
            return 41;
        }
    }
}
