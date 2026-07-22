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
import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import static io.airlift.classfile.BytecodeExpressions.constantFalse;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.CodeBlock.block;
import static io.airlift.classfile.CodeBlock.blockBuilder;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;

class TestScopedConditionSplitting
{
    private static final AtomicInteger CALLS = new AtomicInteger();

    @Test
    void testScopedEarlyReturnConditionsSplit()
            throws ReflectiveOperationException
    {
        ClassDefinition definition = ClassDefinition.define(TestScopedConditionSplitting.class)
                .access(PUBLIC, FINAL)
                .addInterface(IntPredicate.class);
        definition.defaultConstructor().access(PUBLIC);

        Parameter mismatchIndex = arg("mismatchIndex", int.class);
        MethodDefinition test = definition.method("test", boolean.class, mismatchIndex).access(PUBLIC);
        for (int index = 0; index < 100; index++) {
            CodeBlock.Builder condition = blockBuilder();
            Variable left = condition.declare("left", constantInt(index));
            Variable right = condition.declare("right", invokeStatic(
                    Observer.class,
                    "observe",
                    int.class,
                    mismatchIndex,
                    constantInt(index)));
            condition.append(IfStatement.builder()
                    .condition(left.notEqual(right))
                    .then(block(constantFalse().ret()))
                    .build());
            test.body().append(condition.build());
        }
        test.body().ret(mismatchIndex.greaterThanOrEqual(constantInt(-1)));

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(CompilationPolicy.builder()
                        .targetMethodCodeLimit(100)
                        .hardMethodCodeLimit(1_000)
                        .build())
                .compileUnit(definition.build());
        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .filter(method -> method.name().contains("$conditions$")))
                .isNotEmpty();
        IntPredicate predicate = definer.defineUnit(unit)
                .primaryClass(IntPredicate.class)
                .getConstructor()
                .newInstance();

        CALLS.set(0);
        assertThat(predicate.test(-1)).isTrue();
        assertThat(CALLS).hasValue(100);

        CALLS.set(0);
        assertThat(predicate.test(37)).isFalse();
        assertThat(CALLS).hasValue(38);

        CALLS.set(0);
        assertThat(predicate.test(-2)).isFalse();
        assertThat(CALLS).hasValue(100);
    }

    @Test
    void testScopedEarlyTrueConditionsSplit()
            throws ReflectiveOperationException
    {
        ClassDefinition definition = ClassDefinition.define(TestScopedConditionSplitting.class)
                .access(PUBLIC, FINAL)
                .addInterface(IntPredicate.class);
        definition.defaultConstructor().access(PUBLIC);

        Parameter mismatchIndex = arg("mismatchIndex", int.class);
        MethodDefinition test = definition.method("test", boolean.class, mismatchIndex).access(PUBLIC);
        for (int index = 0; index < 100; index++) {
            CodeBlock.Builder condition = blockBuilder();
            Variable left = condition.declare("left", constantInt(index));
            Variable right = condition.declare("right", invokeStatic(
                    Observer.class,
                    "observe",
                    int.class,
                    mismatchIndex,
                    constantInt(index)));
            condition.append(IfStatement.builder()
                    .condition(left.notEqual(right))
                    .then(block(constantTrue().ret()))
                    .build());
            test.body().append(condition.build());
        }
        test.body().ret(mismatchIndex.lessThan(constantInt(-1)));

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(CompilationPolicy.builder()
                        .targetMethodCodeLimit(100)
                        .hardMethodCodeLimit(1_000)
                        .build())
                .compileUnit(definition.build());
        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .filter(method -> method.name().contains("$conditions$")))
                .isNotEmpty();
        IntPredicate predicate = definer.defineUnit(unit)
                .primaryClass(IntPredicate.class)
                .getConstructor()
                .newInstance();

        CALLS.set(0);
        assertThat(predicate.test(-1)).isFalse();
        assertThat(CALLS).hasValue(100);

        CALLS.set(0);
        assertThat(predicate.test(37)).isTrue();
        assertThat(CALLS).hasValue(38);

        CALLS.set(0);
        assertThat(predicate.test(-2)).isTrue();
        assertThat(CALLS).hasValue(100);
    }

    @Test
    void testConditionWithOuterJumpRemainsInCaller()
            throws ReflectiveOperationException
    {
        assertOuterJumpConditionCompiles(100, CompilationPolicy.builder()
                .targetMethodCodeLimit(100)
                .hardMethodCodeLimit(1_000)
                .build());
        assertOuterJumpConditionCompiles(600, CompilationPolicy.defaults());
    }

    private static void assertOuterJumpConditionCompiles(int safeBlockCount, CompilationPolicy policy)
            throws ReflectiveOperationException
    {
        ClassDefinition definition = ClassDefinition.define(TestScopedConditionSplitting.class)
                .access(PUBLIC, FINAL)
                .addInterface(IntPredicate.class);
        definition.defaultConstructor().access(PUBLIC);

        Parameter input = arg("input", int.class);
        MethodDefinition test = definition.method("test", boolean.class, input).access(PUBLIC);
        for (int index = 0; index < safeBlockCount; index++) {
            test.body().append(block(IfStatement.builder()
                    .condition(input.equal(constantInt(index)))
                    .then(block(constantInt(index).pop()))
                    .build()));
        }
        CodeLabel exit = test.body().label("exit");
        test.body().append(block(IfStatement.builder()
                .condition(new OuterJumpCondition(exit))
                .then(block(constantInt(1).pop()))
                .build()));
        test.body().mark(exit).ret(constantTrue());

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(policy)
                .compileUnit(definition.build());
        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .filter(method -> method.name().contains("$blocks$")))
                .isNotEmpty();
        IntPredicate predicate = definer.defineUnit(unit)
                .primaryClass(IntPredicate.class)
                .getConstructor()
                .newInstance();
        assertThat(predicate.test(-1)).isTrue();
    }

    private record OuterJumpCondition(CodeLabel exit)
            implements SyntheticExpression
    {
        @Override
        public ClassDesc type()
        {
            return CD_boolean;
        }

        @Override
        public ExpressionPlan expansion(ExpansionContext context)
        {
            return new ExpressionPlan(blockBuilder().jump(exit).build(), constantFalse());
        }
    }

    public static final class Observer
    {
        private Observer() {}

        public static int observe(int mismatchIndex, int index)
        {
            CALLS.incrementAndGet();
            return mismatchIndex == index ? index + 1 : index;
        }
    }
}
