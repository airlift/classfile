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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.invokeLinked;
import static io.airlift.classfile.Parameter.arg;
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

    private static BytecodeExpression largeConstantExpression(int initialValue)
    {
        BytecodeExpression expression = constantInt(initialValue);
        for (int index = 0; index < 400; index++) {
            expression = expression.add(constantInt(1));
        }
        return expression;
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
}
