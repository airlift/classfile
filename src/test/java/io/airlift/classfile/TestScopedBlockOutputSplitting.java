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
import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.IntUnaryOperator;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.CodeBlock.block;
import static io.airlift.classfile.CodeBlock.blockBuilder;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestScopedBlockOutputSplitting
{
    private static final CompilationPolicy SPLITTING_POLICY = CompilationPolicy.builder()
            .targetMethodCodeLimit(100)
            .hardMethodCodeLimit(1_000)
            .build();

    @Test
    void testScopedBlocksWithOneOutputSplit()
            throws ReflectiveOperationException
    {
        ClassDefinition definition = ClassDefinition.define(TestScopedBlockOutputSplitting.class)
                .access(PUBLIC, FINAL)
                .addInterface(IntUnaryOperator.class);
        definition.defaultConstructor().access(PUBLIC);
        FieldDefinition increment = definition.field("increment", int.class).access(PRIVATE).build();

        Parameter input = arg("input", int.class);
        MethodDefinition apply = definition.method("applyAsInt", int.class, input).access(PUBLIC);
        Variable result = apply.body().declare("result", input);
        for (int index = 0; index < 100; index++) {
            CodeBlock.Builder field = blockBuilder();
            Variable delta = field.declare("delta", apply.thisVariable().getField(increment).add(constantInt(1)));
            field.append(IfStatement.builder()
                    .condition(input.greaterThanOrEqual(constantInt(0)))
                    .then(block(result.set(result.add(delta))))
                    .build());
            apply.body().append(field.build());
        }
        apply.body().ret(result);

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(SPLITTING_POLICY)
                .compileUnit(definition.build());
        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .filter(method -> method.name().contains("$blocks$")))
                .isNotEmpty()
                .allSatisfy(method -> assertThat(method.type().returnType()).isEqualTo(CD_int));
        assertThat(ClassFile.of().parse(unit.classfile(unit.primaryType())).methods().stream()
                .filter(method -> method.methodName().stringValue().contains("$blocks$")))
                .isNotEmpty()
                .allSatisfy(method -> {
                    assertThat(method.flags().flags()).doesNotContain(STATIC);
                    assertThat(method.methodTypeSymbol().parameterList()).doesNotContain(unit.primaryType());
                });
        DefinedUnit definedUnit = definer.defineUnit(unit);
        IntUnaryOperator operator = definedUnit.primaryClass(IntUnaryOperator.class)
                .getConstructor()
                .newInstance();

        assertThat(operator.applyAsInt(0)).isEqualTo(100);
        assertThat(operator.applyAsInt(-1)).isEqualTo(-1);
    }

    @Test
    void testTrailingScopedBlocksWithMultipleOutputsSplit()
            throws ReflectiveOperationException
    {
        ClassDefinition definition = ClassDefinition.define(TestScopedBlockOutputSplitting.class)
                .access(PUBLIC, FINAL)
                .addInterface(IntUnaryOperator.class);
        definition.defaultConstructor().access(PUBLIC);
        FieldDefinition increment = definition.field("increment", int.class).access(PRIVATE).build();

        Parameter input = arg("input", int.class);
        MethodDefinition apply = definition.method("applyAsInt", int.class, input).access(PUBLIC);
        Variable first = apply.body().declare("first", input);
        Variable second = apply.body().declare("second", input);
        for (int index = 0; index < 100; index++) {
            CodeBlock.Builder field = blockBuilder();
            Variable delta = field.declare("delta", apply.thisVariable().getField(increment).add(constantInt(1)));
            field.append(IfStatement.builder()
                    .condition(input.greaterThanOrEqual(constantInt(0)))
                    .then(block(
                            first.set(first.add(delta)),
                            second.set(second.add(delta))))
                    .build());
            apply.body().append(field.build());
        }
        apply.body().ret(first.add(second));

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(SPLITTING_POLICY)
                .compileUnit(definition.build());
        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .filter(method -> method.name().contains("$continuation$")))
                .hasSizeGreaterThan(1)
                .allSatisfy(method -> assertThat(method.type().returnType()).isEqualTo(CD_int));
        assertThat(ClassFile.of().parse(unit.classfile(unit.primaryType())).methods().stream()
                .filter(method -> method.methodName().stringValue().contains("$continuation$")))
                .isNotEmpty()
                .allSatisfy(method -> {
                    assertThat(method.flags().flags()).doesNotContain(STATIC);
                    assertThat(method.methodTypeSymbol().parameterList()).doesNotContain(unit.primaryType());
                });
        DefinedUnit definedUnit = definer.defineUnit(unit);
        IntUnaryOperator operator = definedUnit.primaryClass(IntUnaryOperator.class)
                .getConstructor()
                .newInstance();

        assertThat(operator.applyAsInt(0)).isEqualTo(200);
        assertThat(operator.applyAsInt(-1)).isEqualTo(-2);
    }

    @Test
    void testScopedBlocksWithUninitializedOutputAreNotExtracted()
    {
        ClassDefinition definition = ClassDefinition.define(TestScopedBlockOutputSplitting.class).access(PUBLIC, FINAL);
        MethodDefinition evaluate = definition.method("evaluate", int.class).access(PUBLIC, STATIC);
        Variable result = evaluate.body().declare(int.class, "result");
        for (int index = 0; index < 100; index++) {
            evaluate.body().append(block(result.set(constantInt(index))));
        }
        evaluate.body().ret(result);

        StatementPlanner.Result planned = StatementPlanner.plan(definition.build(), SPLITTING_POLICY, Set.of(), true);

        assertThat(planned.generatedMethods()).isEmpty();
    }

    @Test
    void testNonTrailingScopedBlocksWithMultipleOutputsAreNotExtracted()
    {
        ClassDefinition definition = ClassDefinition.define(TestScopedBlockOutputSplitting.class).access(PUBLIC, FINAL);
        MethodDefinition evaluate = definition.method("evaluate", int.class).access(PUBLIC, STATIC);
        Variable first = evaluate.body().declare("first", constantInt(0));
        Variable second = evaluate.body().declare("second", constantInt(0));
        for (int index = 0; index < 100; index++) {
            evaluate.body().append(block(
                    first.set(first.add(constantInt(1))),
                    second.set(second.add(constantInt(1)))));
        }
        evaluate.body().append(first.add(second).pop());
        evaluate.body().ret(first.add(second));

        StatementPlanner.Result planned = StatementPlanner.plan(definition.build(), SPLITTING_POLICY, Set.of(), true);

        assertThat(planned.generatedMethods()).isEmpty();
    }

    @Test
    void testTrailingMultipleOutputsRequireInitializers()
    {
        ClassDefinition definition = ClassDefinition.define(TestScopedBlockOutputSplitting.class).access(PUBLIC, FINAL);
        MethodDefinition evaluate = definition.method("evaluate", int.class).access(PUBLIC, STATIC);
        Variable first = evaluate.body().declare(int.class, "first");
        Variable second = evaluate.body().declare("second", constantInt(0));
        for (int index = 0; index < 100; index++) {
            evaluate.body().append(block(
                    first.set(constantInt(index)),
                    second.set(second.add(constantInt(1)))));
        }
        evaluate.body().ret(first.add(second));

        StatementPlanner.Result planned = StatementPlanner.plan(definition.build(), SPLITTING_POLICY, Set.of(), true);

        assertThat(planned.generatedMethods()).isEmpty();
    }

    @Test
    void testMultiOutputContinuationRespectsParameterSlotLimit()
    {
        ClassDefinition definition = ClassDefinition.define(TestScopedBlockOutputSplitting.class).access(PUBLIC, FINAL);
        MethodDefinition evaluate = definition.method("evaluate", int.class).access(PUBLIC, STATIC);
        ArrayList<Variable> values = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            values.add(evaluate.body().declare("value" + index, constantInt(index)));
        }
        for (int index = 0; index < 2; index++) {
            CodeBlock.Builder region = blockBuilder();
            values.forEach(value -> region.append(value.pop()));
            region.append(values.get(0).set(values.get(0).add(constantInt(1))));
            region.append(values.get(1).set(values.get(1).add(constantInt(1))));
            evaluate.body().append(region.build());
        }
        evaluate.body().ret(values.get(0).add(values.get(1)));

        StatementPlanner.Result planned = StatementPlanner.plan(definition.build(), SPLITTING_POLICY, Set.of(), true);

        assertThat(planned.generatedMethods()).isEmpty();
    }

    @Test
    void testHiddenMultiOutputContinuationHelpersCanBeSharded()
            throws ReflectiveOperationException
    {
        CompilationPolicy policy = CompilationPolicy.builder()
                .targetMethodCodeLimit(10)
                .hardMethodCodeLimit(1_000)
                .build();
        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(multiOutputContinuationModel("HiddenBoundedContinuations"));
        assertBoundedContinuationUnit(unit);
        DefinedUnit definedUnit = definer.defineUnit(unit);
        assertThat(definedUnit.types())
                .allSatisfy(type -> assertThat(definedUnit.definedClass(type).isHidden()).isTrue());
        IntUnaryOperator operator = definedUnit.primaryClass(IntUnaryOperator.class)
                .getConstructor()
                .newInstance();

        assertThat(operator.applyAsInt(1)).isEqualTo(4_002);
    }

    @Test
    void testStandardMultiOutputContinuationHelpersCanBeSharded()
            throws ReflectiveOperationException
    {
        CompilationPolicy policy = CompilationPolicy.builder()
                .targetMethodCodeLimit(10)
                .hardMethodCodeLimit(1_000)
                .build();
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(multiOutputContinuationModel("StandardBoundedContinuations"));
        assertBoundedContinuationUnit(unit);
        DefinedUnit definedUnit = definer.defineUnit(unit);
        assertThat(definedUnit.types())
                .allSatisfy(type -> assertThat(definedUnit.definedClass(type).isHidden()).isFalse());
        IntUnaryOperator operator = definedUnit.primaryClass(IntUnaryOperator.class)
                .getConstructor()
                .newInstance();

        assertThat(operator.applyAsInt(1)).isEqualTo(4_002);
    }

    private static ClassModel multiOutputContinuationModel(String name)
    {
        ClassDesc type = ClassDesc.of(TestScopedBlockOutputSplitting.class.getPackageName() + "." + name);
        ClassDefinition definition = ClassDefinition.define(type)
                .access(PUBLIC, FINAL)
                .addInterface(IntUnaryOperator.class);
        definition.defaultConstructor().access(PUBLIC);

        Parameter input = arg("input", int.class);
        MethodDefinition apply = definition.method("applyAsInt", int.class, input).access(PUBLIC);
        Variable first = apply.body().declare("first", input);
        Variable second = apply.body().declare("second", input);
        for (int index = 0; index < 2_000; index++) {
            apply.body().append(block(
                    first.set(first.add(constantInt(1))),
                    second.set(second.add(constantInt(1)))));
        }
        apply.body().ret(first.add(second));
        return definition.build();
    }

    private static void assertBoundedContinuationUnit(CompiledUnit unit)
    {
        assertThat(unit.report().classes()).hasSizeGreaterThan(1);
        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .filter(method -> method.name().contains("$continuation$")))
                .hasSizeBetween(2, 256)
                .allSatisfy(method -> assertThat(method.codeBytes()).isLessThan(1_000));
    }

    @Test
    void testMultiOutputContinuationFailsWhenBoundedHelpersCannotFit()
    {
        ClassDefinition definition = ClassDefinition.define(TestScopedBlockOutputSplitting.class).access(PUBLIC, FINAL);
        MethodDefinition evaluate = definition.method("evaluate", int.class).access(PUBLIC, STATIC);
        Variable first = evaluate.body().declare("first", constantInt(0));
        Variable second = evaluate.body().declare("second", constantInt(0));
        for (int index = 0; index < 300; index++) {
            evaluate.body().append(block(
                    first.set(first.add(constantInt(1))),
                    second.set(second.add(constantInt(1)))));
        }
        evaluate.body().ret(first.add(second));

        CompilationPolicy policy = CompilationPolicy.builder()
                .targetMethodCodeLimit(10)
                .hardMethodCodeLimit(100)
                .maxInlineSize(10)
                .frequentInlineSize(20)
                .build();
        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();

        assertThatThrownBy(() -> ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(definition.build()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("requested target for method evaluate()I would create 300 continuation helpers")
                .hasMessageContaining("limits generated continuation depth to 256")
                .hasMessageContaining("cannot coarsen the helpers within the hard method limit");
    }
}
