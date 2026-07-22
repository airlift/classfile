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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.constantDouble;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.CodeBlock.block;
import static io.airlift.classfile.CodeBlock.blockBuilder;
import static java.lang.constant.ConstantDescs.CD_double;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestStructuredControlFlow
{
    @Test
    void testExcessiveStructuredNestingFailsDeterministically()
    {
        Statement nested = constantInt(1).ret();
        for (int index = 0; index < 10_000; index++) {
            nested = block(nested);
        }
        Statement body = nested;

        ClassDefinition definition = generatedClass("DeepStructuredNesting");
        definition.method("value", int.class).access(PUBLIC, STATIC).body().append(body);
        assertThatThrownBy(definition::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Structured statement nesting exceeds the supported limit of " + StructuredDepth.MAX_NESTING);
    }

    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();

    @Test
    void testForLoopBreakContinueAndSlotReuse()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("ForLoop");
        Parameter values = Parameter.arg("values", int[].class);
        MethodDefinition method = classDefinition.method("sum", CD_int, values).access(PUBLIC, STATIC);
        Variable result = method.body().declare(CD_int, "result");
        method.body().append(result.set(constantInt(0)));

        ForLoop.Builder loop = ForLoop.builder();
        CodeBlock.Builder initializer = blockBuilder();
        Variable index = initializer.declare(CD_int, "index");
        initializer.append(index.set(constantInt(0)));
        CodeBlock update = block(index.increment());

        CodeBlock.Builder body = blockBuilder();
        Variable value = body.declare(CD_int, "value");
        body.append(value.set(values.getElement(index)));
        body.append(IfStatement.builder()
                .condition(value.lessThan(constantInt(0)))
                .then(loop.continueLoop())
                .build());
        body.append(result.set(result.add(value)));
        body.append(IfStatement.builder()
                .condition(result.greaterThan(constantInt(10)))
                .then(loop.breakLoop())
                .build());

        method.body().append(loop
                .initialize(initializer.build())
                .condition(index.lessThan(values.length()))
                .update(update)
                .body(body.build())
                .build());
        method.body().append(result.ret());

        Class<?> generated = define(classDefinition.build());
        assertThat(generated.getMethod("sum", int[].class).invoke(null, (Object) new int[] {3, -100, 4, 7, 100})).isEqualTo(14);
    }

    @Test
    void testDirectFloatingPointBranchesPreserveNaNSemantics()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("FloatingPointBranches");
        Parameter lessValue = Parameter.arg("value", CD_double);

        MethodDefinition less = classDefinition.method("less", CD_int, lessValue).access(PUBLIC, STATIC);
        less.body().append(IfStatement.builder()
                .condition(lessValue.lessThan(constantDouble(1)))
                .then(constantInt(1).ret())
                .build());
        less.body().ret(constantInt(0));

        Parameter greaterValue = Parameter.arg("value", CD_double);
        MethodDefinition greater = classDefinition.method("greater", CD_int, greaterValue).access(PUBLIC, STATIC);
        greater.body().append(IfStatement.builder()
                .condition(greaterValue.greaterThan(constantDouble(1)))
                .then(constantInt(1).ret())
                .build());
        greater.body().ret(constantInt(0));

        Class<?> generated = define(classDefinition.build());
        assertThat(generated.getMethod("less", double.class).invoke(null, Double.NaN)).isEqualTo(0);
        assertThat(generated.getMethod("greater", double.class).invoke(null, Double.NaN)).isEqualTo(0);
    }

    @Test
    void testConciseForLoop()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("ConciseForLoop");
        Parameter count = Parameter.arg("count", int.class);
        MethodDefinition method = classDefinition.method("sum", CD_int, count).access(PUBLIC, STATIC);
        Variable result = method.body().declare("result", constantInt(0));
        Variable index = method.body().declare(CD_int, "index");

        method.body().append(ForLoop.builder()
                .initialize(index.set(constantInt(0)))
                .condition(index.lessThan(count))
                .update(index.increment())
                .body(result.set(result.add(index)))
                .build());
        method.body().append(result.ret());

        assertThat(define(classDefinition.build()).getMethod("sum", int.class).invoke(null, 5)).isEqualTo(10);
    }

    @Test
    void testNestedBlockCanShadowVariableName()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("ShadowedLocal");
        MethodDefinition method = classDefinition.method("value", CD_int).access(PUBLIC, STATIC);
        Variable value = method.body().declare("value", constantInt(10));
        Variable result = method.body().declare("result", constantInt(0));

        CodeBlock.Builder nested = blockBuilder();
        Variable nestedValue = nested.declare("value", constantInt(7));
        nested.append(result.set(nestedValue));
        method.body().append(nested.build());
        method.body().append(result.add(value).ret());

        assertThat(define(classDefinition.build()).getMethod("value").invoke(null)).isEqualTo(17);
    }

    @Test
    void testWhileDoWhileAndSwitch()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("LoopsAndSwitch");
        Parameter input = Parameter.arg("input", int.class);
        MethodDefinition method = classDefinition.method("map", CD_int, input).access(PUBLIC, STATIC);
        Variable value = method.body().declare(CD_int, "value");
        Variable count = method.body().declare(CD_int, "count");
        method.body().append(value.set(input));
        method.body().append(count.set(constantInt(0)));

        WhileLoop.Builder whileLoop = WhileLoop.builder();
        assertThat(whileLoop.breakLoop()).hasToString("break;");
        assertThat(whileLoop.continueLoop()).hasToString("continue;");
        method.body().append(whileLoop
                .condition(value.greaterThan(constantInt(0)))
                .body(block(
                        count.set(count.add(constantInt(1))),
                        value.set(value.subtract(constantInt(1)))))
                .build());

        DoWhileLoop.Builder doWhileLoop = DoWhileLoop.builder();
        assertThat(doWhileLoop.breakLoop()).hasToString("break;");
        assertThat(doWhileLoop.continueLoop()).hasToString("continue;");
        method.body().append(doWhileLoop
                .condition(value.greaterThan(constantInt(-2)))
                .body(block(
                        count.set(count.add(constantInt(10))),
                        value.set(value.subtract(constantInt(1)))))
                .build());

        Variable result = method.body().declare(CD_int, "result");
        method.body().append(SwitchStatement.builder()
                .expression(count)
                .caseValue(20, block(result.set(constantInt(200))))
                .caseValue(23, block(result.set(constantInt(230))))
                .defaultCase(block(result.set(constantInt(-1))))
                .build());
        method.body().append(result.ret());

        Class<?> generated = define(classDefinition.build());
        assertThat(generated.getMethod("map", int.class).invoke(null, 0)).isEqualTo(200);
        assertThat(generated.getMethod("map", int.class).invoke(null, 3)).isEqualTo(230);
    }

    @Test
    void testWhileAndDoWhileBreakContinue()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("LoopJumps");
        MethodDefinition method = classDefinition.method("value", CD_int).access(PUBLIC, STATIC);
        Variable value = method.body().declare("value", constantInt(0));
        Variable result = method.body().declare("result", constantInt(0));

        WhileLoop.Builder whileLoop = WhileLoop.builder();
        method.body().append(whileLoop
                .condition(value.lessThan(constantInt(10)))
                .body(block(
                        value.increment(),
                        IfStatement.builder()
                                .condition(value.equal(constantInt(2)))
                                .then(whileLoop.continueLoop())
                                .build(),
                        result.set(result.add(value)),
                        IfStatement.builder()
                                .condition(value.equal(constantInt(4)))
                                .then(whileLoop.breakLoop())
                                .build()))
                .build());

        DoWhileLoop.Builder doWhileLoop = DoWhileLoop.builder();
        method.body().append(doWhileLoop
                .condition(value.lessThan(constantInt(10)))
                .body(block(
                        value.increment(),
                        IfStatement.builder()
                                .condition(value.equal(constantInt(6)))
                                .then(doWhileLoop.continueLoop())
                                .build(),
                        result.set(result.add(value)),
                        IfStatement.builder()
                                .condition(value.equal(constantInt(8)))
                                .then(doWhileLoop.breakLoop())
                                .build()))
                .build());
        method.body().ret(result);

        assertThat(define(classDefinition.build()).getMethod("value").invoke(null)).isEqualTo(28);
    }

    @Test
    void testTryCatchFinallyAndReturn()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("TryCatch");
        Parameter state = Parameter.arg("state", int[].class);
        MethodDefinition method = classDefinition.method("value", CD_int, state).access(PUBLIC, STATIC);

        TryCatch statement = TryCatch.builder()
                .tryBlock(block(BytecodeExpressions.newInstance(IllegalArgumentException.class, constantString("failure")).throwObject()))
                .catching(IllegalArgumentException.class, "failure", (body, failure) ->
                        body.append(failure.invoke("getMessage", String.class).invoke("length", int.class).ret()))
                .finallyBlock(block(state.setElement(0, constantInt(9))))
                .build();
        method.body().append(statement);
        method.body().append(constantInt(-1).ret());

        int[] stateValue = new int[1];
        Class<?> generated = define(classDefinition.build());
        assertThat(generated.getMethod("value", int[].class).invoke(null, (Object) stateValue)).isEqualTo(7);
        assertThat(stateValue).containsExactly(9);
    }

    @Test
    void testExceptionFromFinallyIsNotCaughtBySameTry()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("FinallyThrows");
        Parameter state = Parameter.arg("state", int[].class);
        MethodDefinition method = classDefinition.method("value", CD_int, state).access(PUBLIC, STATIC);
        method.body().append(TryCatch.builder()
                .tryBlock(block(constantInt(7).ret()))
                .catching(IllegalArgumentException.class, "failure", (body, _) ->
                        body.append(state.setElement(0, constantInt(1))))
                .finallyBlock(block(BytecodeExpressions.newInstance(IllegalArgumentException.class, constantString("finally")).throwObject()))
                .build());
        method.body().append(constantInt(-1).ret());

        int[] stateValue = new int[1];
        Method generatedMethod = define(classDefinition.build()).getMethod("value", int[].class);
        assertThatThrownBy(() -> generatedMethod.invoke(null, (Object) stateValue))
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("finally");
        assertThat(stateValue).containsExactly(0);
    }

    @Test
    void testBreakAndContinueRunFinally()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("FinallyLoop");
        MethodDefinition method = classDefinition.method("value", CD_int).access(PUBLIC, STATIC);
        Variable result = method.body().declare(CD_int, "result");
        method.body().append(result.set(constantInt(0)));

        ForLoop.Builder loop = ForLoop.builder();
        CodeBlock.Builder initializer = blockBuilder();
        Variable index = initializer.declare(CD_int, "index");
        initializer.append(index.set(constantInt(0)));

        CodeBlock tryBody = block(
                IfStatement.builder()
                        .condition(index.equal(constantInt(1)))
                        .then(loop.continueLoop())
                        .build(),
                IfStatement.builder()
                        .condition(index.equal(constantInt(3)))
                        .then(loop.breakLoop())
                        .build());
        loop.initialize(initializer.build())
                .condition(index.lessThan(constantInt(4)))
                .update(block(index.increment()))
                .body(block(TryCatch.builder()
                        .tryBlock(tryBody)
                        .finallyBlock(block(result.set(result.add(constantInt(10)))))
                        .build()));
        method.body().append(loop.build());
        method.body().append(result.ret());

        assertThat(define(classDefinition.build()).getMethod("value").invoke(null)).isEqualTo(40);
    }

    @Test
    void testSymbolicJumpRunsOnlyExitedFinalizers()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("FinallyJump");
        Parameter state = Parameter.arg("state", int[].class);
        MethodDefinition method = classDefinition.method("value", CD_int, state).access(PUBLIC, STATIC);
        CodeLabel end = method.body().label("end");

        CodeBlock.Builder tryBody = blockBuilder();
        CodeLabel internalEnd = tryBody.label("internalEnd");
        tryBody.append(state.setElement(0, constantInt(1)));
        tryBody.jump(internalEnd);
        tryBody.append(state.setElement(0, constantInt(99)));
        tryBody.mark(internalEnd);
        tryBody.jump(end);

        method.body().append(TryCatch.builder()
                .tryBlock(tryBody.build())
                .finallyBlock(block(state.setElement(0, state.getElement(0).add(constantInt(10)))))
                .build());
        method.body().append(state.setElement(0, constantInt(1000)));
        method.body().mark(end);
        method.body().append(state.getElement(0).ret());

        int[] stateValue = new int[1];
        assertThat(define(classDefinition.build()).getMethod("value", int[].class).invoke(null, (Object) stateValue)).isEqualTo(11);
        assertThat(stateValue).containsExactly(11);
    }

    @Test
    void testParentAndOwnedLabelsAcrossRepeatedPlacements()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("Labels");
        Parameter input = Parameter.arg("input", int.class);
        MethodDefinition method = classDefinition.method("match", CD_int, input).access(PUBLIC, STATIC);
        Variable result = method.body().declare(CD_int, "result");
        CodeLabel match = method.body().label("match");
        CodeLabel end = method.body().label("end");

        CodeBlock test = block(IfStatement.builder()
                .condition(input.equal(constantInt(7)))
                .then(blockBuilder().jump(match).build())
                .build());
        method.body().append(test);
        method.body().append(test);
        method.body().append(result.set(constantInt(0)));
        method.body().jump(end);
        method.body().mark(match);
        method.body().append(result.set(constantInt(1)));
        method.body().mark(end);

        CodeBlock.Builder ownedBuilder = blockBuilder();
        CodeLabel ownedEnd = ownedBuilder.label("ownedEnd");
        ownedBuilder.jump(ownedEnd);
        ownedBuilder.append(result.set(constantInt(99)));
        ownedBuilder.mark(ownedEnd);
        CodeBlock owned = ownedBuilder.build();
        method.body().append(owned);
        method.body().append(owned);
        method.body().append(result.ret());

        Class<?> generated = define(classDefinition.build());
        assertThat(generated.getMethod("match", int.class).invoke(null, 7)).isEqualTo(1);
        assertThat(generated.getMethod("match", int.class).invoke(null, 4)).isEqualTo(0);
    }

    @Test
    void testLabelValidation()
    {
        CodeBlock.Builder owner = blockBuilder();
        CodeLabel label = owner.label("target");
        owner.jump(label);
        owner.mark(label);
        assertThat(label).hasToString("target");
        assertThat(owner.build().statements().get(0)).hasToString("goto target;");
        assertThat(owner.build().statements().get(1)).hasToString("target:");
        assertThatThrownBy(() -> blockBuilder().mark(label))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Label is owned by another block: target");

        ClassDefinition classDefinition = generatedClass("InvalidLabel");
        MethodDefinition method = classDefinition.method("value", CD_int).access(PUBLIC, STATIC);
        method.body().append(blockBuilder().jump(label).build());
        method.body().append(constantInt(0).ret());
        assertThatThrownBy(classDefinition::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Label is not in scope: target");
    }

    @Test
    void testControlFlowBuilderComponentsAreSetOnce()
    {
        BytecodeExpression condition = constantInt(1).equal(constantInt(1));
        CodeBlock body = block(constantInt(1));

        IfStatement.Builder ifStatement = IfStatement.builder()
                .description("if")
                .condition(condition)
                .then(body)
                .otherwise(body);
        assertThatThrownBy(() -> ifStatement.condition(condition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("condition is already set");
        assertThatThrownBy(() -> ifStatement.then(body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("then statement is already set");
        assertThatThrownBy(() -> ifStatement.otherwise(body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("otherwise statement is already set");
        assertThatThrownBy(() -> ifStatement.description("other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("description is already set");

        ForLoop.Builder forLoop = ForLoop.builder()
                .description("for")
                .initialize(body)
                .condition(condition)
                .update(body)
                .body(body);
        assertThatThrownBy(() -> forLoop.initialize(body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("initializer is already set");
        assertThatThrownBy(() -> forLoop.condition(condition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("condition is already set");
        assertThatThrownBy(() -> forLoop.update(body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("update is already set");
        assertThatThrownBy(() -> forLoop.body(body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("body is already set");
        assertThatThrownBy(() -> forLoop.description("other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("description is already set");

        WhileLoop.Builder whileLoop = WhileLoop.builder()
                .description("while")
                .condition(condition)
                .body(body);
        assertThatThrownBy(() -> whileLoop.condition(condition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("condition is already set");
        assertThatThrownBy(() -> whileLoop.body(body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("body is already set");
        assertThatThrownBy(() -> whileLoop.description("other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("description is already set");

        DoWhileLoop.Builder doWhileLoop = DoWhileLoop.builder()
                .description("do")
                .condition(condition)
                .body(body);
        assertThatThrownBy(() -> doWhileLoop.condition(condition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("condition is already set");
        assertThatThrownBy(() -> doWhileLoop.body(body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("body is already set");
        assertThatThrownBy(() -> doWhileLoop.description("other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("description is already set");

        SwitchStatement.Builder switchStatement = SwitchStatement.builder()
                .description("switch")
                .expression(constantInt(1))
                .defaultCase(body);
        assertThatThrownBy(() -> switchStatement.expression(constantInt(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expression is already set");
        assertThatThrownBy(() -> switchStatement.defaultCase(body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("default case is already set");
        assertThatThrownBy(() -> switchStatement.description("other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("description is already set");

        TryCatch.Builder tryCatch = TryCatch.builder()
                .description("try")
                .tryBlock(body)
                .finallyBlock(body);
        assertThatThrownBy(() -> tryCatch.tryBlock(body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("try block is already set");
        assertThatThrownBy(() -> tryCatch.finallyBlock(body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("finally block is already set");
        assertThatThrownBy(() -> tryCatch.description("other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("description is already set");
    }

    @Test
    void testSingleStatementRegionsAndDescriptions()
    {
        BytecodeExpression condition = constantInt(1).equal(constantInt(1));
        BytecodeExpression statement = constantInt(11);

        WhileLoop whileLoop = WhileLoop.builder()
                .description("consume values")
                .condition(condition)
                .body(statement)
                .build();
        assertThat(whileLoop.body().statements()).hasSize(1);
        assertThat(whileLoop.description()).isEqualTo("consume values");
        assertThat(whileLoop.toString()).startsWith("// consume values\nwhile");

        DoWhileLoop doWhileLoop = DoWhileLoop.builder()
                .description("consume one value")
                .condition(condition)
                .body(statement)
                .build();
        assertThat(doWhileLoop.body().statements()).hasSize(1);
        assertThat(doWhileLoop.description()).isEqualTo("consume one value");
        assertThat(doWhileLoop.toString()).startsWith("// consume one value\ndo");

        SwitchStatement switchStatement = SwitchStatement.builder()
                .description("select value")
                .expression(constantInt(1))
                .caseValue(1, statement)
                .defaultCase(constantInt(0))
                .build();
        assertThat(switchStatement.cases().getFirst().body().statements()).hasSize(1);
        assertThat(switchStatement.description()).isEqualTo("select value");
        assertThat(switchStatement.toString()).startsWith("// select value\nswitch");

        TryCatch tryCatch = TryCatch.builder()
                .description("recover value")
                .tryBlock(statement)
                .finallyBlock(constantInt(0))
                .build();
        assertThat(tryCatch.tryBlock().statements()).hasSize(1);
        assertThat(tryCatch.description()).isEqualTo("recover value");
        assertThat(tryCatch.toString()).startsWith("// recover value\ntry");

        IfStatement ifStatement = IfStatement.builder()
                .description("choose value")
                .condition(condition)
                .then(statement)
                .build();
        assertThat(ifStatement.description()).isEqualTo("choose value");
        assertThat(ifStatement.toString()).startsWith("// choose value\nif");
        ForLoop forLoop = ForLoop.builder()
                .description("iterate values")
                .condition(condition)
                .body(statement)
                .build();
        assertThat(forLoop.description()).isEqualTo("iterate values");
        assertThat(forLoop.toString()).startsWith("// iterate values\nfor");
    }

    @Test
    void testControlFlowBuildersRequireCompleteStructure()
    {
        BytecodeExpression condition = constantInt(1).equal(constantInt(1));
        CodeBlock body = block(constantInt(1));

        assertThatThrownBy(IfStatement.builder()::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("condition is not set");
        assertThatThrownBy(() -> IfStatement.builder().condition(condition).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("then or otherwise statement is not set");

        assertThatThrownBy(() -> ForLoop.builder().body(body).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("condition is not set");
        assertThatThrownBy(() -> ForLoop.builder().condition(condition).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("body is not set");

        assertThatThrownBy(() -> WhileLoop.builder().body(body).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("condition is not set");
        assertThatThrownBy(() -> WhileLoop.builder().condition(condition).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("body is not set");

        assertThatThrownBy(() -> DoWhileLoop.builder().body(body).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("condition is not set");
        assertThatThrownBy(() -> DoWhileLoop.builder().condition(condition).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("body is not set");

        assertThatThrownBy(SwitchStatement.builder()::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expression is not set");
        assertThatThrownBy(() -> TryCatch.builder().finallyBlock(body).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("try block is not set");
    }

    @Test
    void testControlFlowConstructionIsBuilderOnly()
    {
        assertThat(IfStatement.class.getConstructors()).isEmpty();
        assertThat(ForLoop.class.getConstructors()).isEmpty();
        assertThat(WhileLoop.class.getConstructors()).isEmpty();
        assertThat(DoWhileLoop.class.getConstructors()).isEmpty();
        assertThat(SwitchStatement.class.getConstructors()).isEmpty();
        assertThat(TryCatch.class.getConstructors()).isEmpty();
    }

    private ClassDefinition generatedClass(String suffix)
    {
        ClassDesc type = ClassDesc.of(getClass().getPackageName() + ".Generated" + suffix + NEXT_CLASS_ID.incrementAndGet());
        return ClassDefinition.define(type).access(PUBLIC, FINAL);
    }

    private Class<?> define(ClassModel definition)
    {
        return StandardClassDefiner.builder(getClass().getClassLoader())
                .build()
                .defineClass(definition);
    }
}
