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

import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static io.airlift.classfile.BytecodeExpressions.boundMethodHandle;
import static io.airlift.classfile.BytecodeExpressions.constantFalse;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.ClassfileTestUtils.defineHidden;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSyntheticExpressions
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();
    private static final MethodHandle ADD = methodHandle("add", methodType(long.class, long.class, long.class));

    @Test
    void testStructuredExpressionIsFluentAndReusable()
            throws Exception
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("Synthetic")).access(PUBLIC, FINAL);
        Parameter input = Parameter.arg("input", int.class);
        MethodDefinition method = classDefinition.method("value", CD_int, input).access(PUBLIC, STATIC);
        SyntheticExpression expression = new AddThenDouble(input);

        assertThat(expression.toString()).isEqualTo("addThenDouble(input)");
        assertThat(expression.add(constantInt(3)).toString()).isEqualTo("(addThenDouble(input) + 3)");
        SyntheticExpression offset = new Offset(input);
        assertThat(offset.toString()).isEqualTo("offset(input)");
        method.body().append(expression.add(expression).add(offset).ret());

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompiledClass compiledClass = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup)).compileClass(classDefinition.build());
        Class<?> generated = defineHidden(lookup, compiledClass).lookupClass();
        assertThat(generated.getMethod("value", int.class).invoke(null, 5)).isEqualTo(30);
    }

    @Test
    void testInvalidExpansionFailsDuringDefinitionAssembly()
    {
        ClassDefinition wrongTypeClass = ClassDefinition.define(generatedClass("WrongType"));
        MethodDefinition wrongType = wrongTypeClass.method("value", CD_int).access(PUBLIC, STATIC);
        wrongType.body().append(new WrongTypeExpression().ret());
        assertThatThrownBy(wrongTypeClass::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expansion value type long does not match expression type int");

        ClassDefinition recursiveClass = ClassDefinition.define(generatedClass("Recursive"));
        MethodDefinition recursive = recursiveClass.method("value", CD_int).access(PUBLIC, STATIC);
        recursive.body().append(new RecursiveExpression().ret());
        assertThatThrownBy(recursiveClass::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Synthetic expression expands to itself: recursive");
    }

    @Test
    void testExpansionContextCreatesRuntimeBoundConstants()
            throws Exception
    {
        Object classTyped = new Object();
        Object descriptorTyped = new Object();
        ClassDefinition definition = ClassDefinition.define(generatedClass("BoundContext")).access(PUBLIC, FINAL);
        MethodDefinition method = definition.method("values", Object[].class).access(PUBLIC, STATIC);
        method.body().ret(newArray(Object[].class, List.of(
                new ContextBoundConstant(classTyped, false),
                new ContextBoundConstant(descriptorTyped, true))));

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompiledClass compiledClass = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup)).compileClass(definition.build());
        Class<?> generated = defineHidden(lookup, compiledClass).lookupClass();
        assertThat((Object[]) generated.getMethod("values").invoke(null)).containsExactly(classTyped, descriptorTyped);
    }

    @Test
    void testSetupIsPreservedWhenReusableBlockIsMoved()
            throws Exception
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("MovedSetup")).access(PUBLIC, FINAL);
        Parameter input = Parameter.arg("input", int.class);
        MethodDefinition method = definition.method("value", int.class, input).access(PUBLIC, STATIC);
        Variable result = method.body().declare("result", newArray(int[].class, constantInt(1)));
        CodeBlock reusable = CodeBlock.blockBuilder()
                .append(result.setElement(0, new AddThenDouble(input)))
                .build();
        for (int index = 0; index < 80; index++) {
            method.body().append(reusable);
        }
        method.body().ret(result.getElement(0));

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(4_000)
                .targetMethodCodeLimit(64)
                .build();
        CompiledUnit unit = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup))
                .policy(policy)
                .compileUnit(definition.build());
        assertThat(unit.report().classes().getFirst().methods())
                .extracting(CompilationReport.MethodInfo::name)
                .anyMatch(name -> name.startsWith("value$blocks$"));

        Class<?> generated = HiddenClassDefiner.builder(lookup).build().defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("value", int.class).invoke(null, 5)).isEqualTo(12);
    }

    @Test
    void testSetupExpressionRemainsInPlaceDuringValueSplitting()
            throws Exception
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("ConstrainedSetup")).access(PUBLIC, FINAL);
        Parameter input = Parameter.arg("input", int.class);
        MethodDefinition method = definition.method("value", int.class, input).access(PUBLIC, STATIC);
        method.body().ret(new AddThenDouble(input).add(constantInt(1)));

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(4_000)
                .targetMethodCodeLimit(64)
                .build();
        CompiledUnit unit = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup))
                .policy(policy)
                .compileUnit(definition.build());

        Class<?> generated = HiddenClassDefiner.builder(lookup).build().defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("value", int.class).invoke(null, 5)).isEqualTo(13);
    }

    @Test
    void testSetupExpressionsRemainInPlaceDuringStatementSplitting()
            throws Exception
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("StatementSetup")).access(PUBLIC, FINAL);
        MethodDefinition statements = definition.method("statements", int.class).access(PUBLIC, STATIC);
        for (int index = 0; index < 20; index++) {
            statements.body().append(new SetupValue());
        }
        statements.body().ret(constantInt(42));

        Parameter input = Parameter.arg("input", int.class);
        MethodDefinition conditions = definition.method("conditions", boolean.class, input).access(PUBLIC, STATIC);
        for (int index = 0; index < 20; index++) {
            conditions.body().append(IfStatement.builder()
                    .condition(new SetupCondition(input))
                    .then(BytecodeExpressions.constantFalse().ret())
                    .build());
        }
        conditions.body().ret(BytecodeExpressions.constantTrue());

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(4_000)
                .targetMethodCodeLimit(64)
                .build();
        CompiledUnit unit = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup))
                .policy(policy)
                .compileUnit(definition.build());
        Class<?> generated = HiddenClassDefiner.builder(lookup).build().defineUnit(unit).primaryClass();

        assertThat(generated.getMethod("statements").invoke(null)).isEqualTo(42);
        assertThat(generated.getMethod("conditions", int.class).invoke(null, 0)).isEqualTo(false);
        assertThat(generated.getMethod("conditions", int.class).invoke(null, 1)).isEqualTo(true);
    }

    @Test
    void testSequentialSyntheticSetupSplits()
            throws Exception
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("SequentialSetup")).access(PUBLIC, FINAL);
        MethodDefinition method = definition.method("value", int.class).access(PUBLIC, STATIC);
        Variable result = method.body().declare("result", new SequentialSetup(256));
        method.body().ret(result);

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(1_000)
                .targetMethodCodeLimit(100)
                .build();
        CompiledUnit unit = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup))
                .policy(policy)
                .compileUnit(definition.build());
        assertThat(unit.report().classes().getFirst().methods())
                .extracting(CompilationReport.MethodInfo::name)
                .anyMatch(name -> name.startsWith("value$blocks$"));

        Class<?> generated = HiddenClassDefiner.builder(lookup).build().defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("value").invoke(null)).isEqualTo(256);
    }

    @Test
    void testBoundHandleDenseSyntheticSetupsUseCompactCallSites()
            throws Exception
    {
        ClassModel compactModel = denseBoundHandleSetup(48);
        int compactEstimate = ExpressionPlanner.estimate(compactModel.methods().getFirst().body());
        int targetMethodCodeLimit = (compactEstimate * 2 + 4) / 5;
        assertThat(compactEstimate).isGreaterThan(targetMethodCodeLimit * 2);
        assertThat(compactEstimate).isLessThanOrEqualTo(targetMethodCodeLimit * 3);

        CompilationPolicy policy = CompilationPolicy.builder()
                .targetMethodCodeLimit(targetMethodCodeLimit)
                .build();
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompiledUnit unit = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup))
                .policy(policy)
                .compileUnit(compactModel);
        List<CompilationReport.MethodInfo> methods = unit.report().classes().getFirst().methods();
        CompilationReport.MethodInfo valueMethod = methods.getFirst();
        assertThat(valueMethod.name()).isEqualTo("value");
        assertThat(methods).hasSize(1);
        assertThat(valueMethod.codeBytes()).isLessThan(targetMethodCodeLimit);
        assertThat(ClassFileDiagnostics.disassemble(unit.classfile(unit.primaryType())))
                .contains("opcode: INVOKEDYNAMIC")
                .doesNotContain("owner: java/lang/invoke/MethodHandle, method name: invokeExact");

        Class<?> generated = HiddenClassDefiner.builder(lookup).build().defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("value").invoke(null)).isEqualTo(48L);

        ClassModel wideModel = denseBoundHandleSetup(64);
        assertThat(ExpressionPlanner.estimate(wideModel.methods().getFirst().body())).isGreaterThan(targetMethodCodeLimit * 3);
        CompiledUnit wideUnit = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup))
                .policy(policy)
                .compileUnit(wideModel);
        assertThat(wideUnit.report().classes().getFirst().methods())
                .anySatisfy(method -> assertThat(method.name()).startsWith("value$continuation$"))
                .allSatisfy(method -> assertThat(method.codeBytes()).isLessThan(targetMethodCodeLimit));
        Class<?> wideGenerated = HiddenClassDefiner.builder(lookup).build().defineUnit(wideUnit).primaryClass();
        assertThat(wideGenerated.getMethod("value").invoke(null)).isEqualTo(64L);
    }

    private static ClassModel denseBoundHandleSetup(int statements)
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("BoundHandleDenseSetup")).access(PUBLIC, FINAL);
        MethodDefinition method = definition.method("value", Long.class).access(PUBLIC, STATIC);
        Variable wasNull = method.body().declare("wasNull", constantFalse());
        Variable value = method.body().declare("value", invokeStatic(Long.class, "valueOf", Long.class, constantLong(0)));
        for (int index = 0; index < statements; index++) {
            method.body().append(CodeBlock.block(value.set(new BoxedAdd(value, wasNull))));
        }
        method.body().ret(value);
        return definition.build();
    }

    @Test
    void testSyntheticResultForwardingPreservesExceptionalAssignment()
            throws Exception
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("ExceptionalForwarding")).access(PUBLIC, FINAL);
        MethodDefinition method = definition.method("value", int.class).access(PUBLIC, STATIC);
        Variable value = method.body().declare("value", constantInt(7));
        method.body().append(TryCatch.builder()
                .tryBlock(value.set(new AssignThenFail()))
                .catching(IllegalStateException.class, "ignored", (_, _) -> {})
                .build());
        method.body().ret(value);

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> generated = HiddenClassDefiner.builder(lookup).build().defineUnit(
                ClassCompiler.forTarget(CompilationTarget.forLookup(lookup)).compileUnit(definition.build())).primaryClass();
        assertThat(generated.getMethod("value").invoke(null)).isEqualTo(7);
    }

    @Test
    void testSetupWithOperandsOnStackStaysWithinItself()
            throws Exception
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("StackedSetup")).access(PUBLIC, FINAL);
        Parameter input = Parameter.arg("input", String.class);
        Parameter count = Parameter.arg("count", int.class);
        MethodDefinition method = definition.method("value", int.class, input, count).access(PUBLIC, STATIC);

        // The first operand is evaluated on an empty operand stack, so its setup may catch exceptions.
        CodeBlock.Builder parseSetup = CodeBlock.blockBuilder();
        Variable parsed = parseSetup.declare(CD_int, "parsed");
        parseSetup.append(TryCatch.builder()
                .tryBlock(parsed.set(invokeStatic(Integer.class, "parseInt", int.class, input)))
                .catching(NumberFormatException.class, "e", (body, _) -> body.append(parsed.set(constantInt(-1))))
                .build());

        // The second operand's setup runs above the first operand: it may branch within itself and return.
        CodeBlock.Builder sumSetup = CodeBlock.blockBuilder();
        Variable sum = sumSetup.declare("sum", constantInt(0));
        Variable index = sumSetup.declare("index", constantInt(0));
        WhileLoop.Builder loop = WhileLoop.builder();
        sumSetup.append(IfStatement.builder().condition(count.lessThan(constantInt(0))).then(constantInt(-100).ret()).build())
                .append(loop.condition(constantTrue())
                        .body(CodeBlock.block(
                                IfStatement.builder().condition(index.greaterThanOrEqual(count)).then(loop.breakLoop()).build(),
                                sum.set(sum.add(index)),
                                index.increment()))
                        .build());

        // A branch on a null comparison evaluates only the other operand.
        method.body().append(IfStatement.builder()
                .condition(count.equal(constantInt(-2)).or(constantNull(String.class).equal(new SetupThen(CodeBlock.block(catchingParse()), input))))
                .then(constantInt(-200).ret())
                .build());
        method.body().ret(new SetupThen(parseSetup.build(), parsed).add(new SetupThen(sumSetup.build(), sum)));

        // A handler that cannot complete normally never resumes the code above the operands.
        BytecodeExpression bad = BytecodeExpressions.newInstance(IllegalArgumentException.class, constantString("bad")).throwObject();
        Parameter throwingInput = Parameter.arg("input", String.class);
        definition.method("throwing", int.class, throwingInput).access(PUBLIC, STATIC).body()
                .ret(invokeStatic(Math.class, "addExact", int.class, constantInt(1), parseOr(throwingInput, bad)));
        Parameter returningInput = Parameter.arg("input", String.class);
        definition.method("returning", int.class, returningInput).access(PUBLIC, STATIC).body()
                .ret(invokeStatic(Math.class, "addExact", int.class, constantInt(1), parseOr(returningInput, constantInt(-1).ret())));

        // A return runs the finally block above the operands, where its handler throws.
        Parameter finallyInput = Parameter.arg("input", String.class);
        definition.method("finallyThrowing", int.class, finallyInput).access(PUBLIC, STATIC).body()
                .append(TryCatch.builder()
                        .tryBlock(invokeStatic(Math.class, "addExact", int.class, constantInt(1), new SetupThen(CodeBlock.block(constantInt(2).ret()), constantInt(3))).ret())
                        .finallyBlock(TryCatch.builder()
                                .tryBlock(invokeStatic(Integer.class, "parseInt", int.class, finallyInput).pop())
                                .catching(NumberFormatException.class, "e", (body, _) -> body.append(bad))
                                .build())
                        .build());

        // A catch block starts on an empty operand stack, so it may catch exceptions and return
        // through a finally block that catches them.
        Parameter catchingInput = Parameter.arg("input", String.class);
        CodeBlock.Builder catchingHandler = CodeBlock.blockBuilder();
        catchingHandler.append(TryCatch.builder()
                        .tryBlock(invokeStatic(Integer.class, "parseInt", int.class, catchingInput).pop())
                        .catching(NumberFormatException.class, "inner", (_, _) -> {})
                        .build())
                .append(constantInt(-1).ret());
        definition.method("handlerCatching", int.class, catchingInput).access(PUBLIC, STATIC).body()
                .ret(constantInt(1).add(new SetupThen(CodeBlock.block(TryCatch.builder()
                        .tryBlock(invokeStatic(Integer.class, "parseInt", int.class, catchingInput).pop())
                        .catching(NumberFormatException.class, "e", (body, _) -> body.append(catchingHandler.build()))
                        .build()), constantInt(2))));
        Parameter handlerFinallyInput = Parameter.arg("input", String.class);
        definition.method("handlerFinally", int.class, handlerFinallyInput).access(PUBLIC, STATIC).body()
                .append(TryCatch.builder()
                        .tryBlock(constantInt(1).add(new SetupThen(CodeBlock.block(TryCatch.builder()
                                .tryBlock(invokeStatic(Integer.class, "parseInt", int.class, handlerFinallyInput).pop())
                                .catching(NumberFormatException.class, "e", (body, _) -> body.append(constantInt(-1).ret()))
                                .build()), constantInt(2))).ret())
                        .finallyBlock(catchingParse())
                        .build());

        // An earlier snapshot of a builder, placed in a setup inside a later snapshot of the same builder,
        // binds its own copy of the builder's labels.
        CodeBlock.Builder nestedSnapshot = definition.method("nestedSnapshot", int.class).access(PUBLIC, STATIC).body();
        Variable snapshotResult = nestedSnapshot.declare(CD_int, "snapshotResult");
        CodeBlock.Builder snapshotBuilder = CodeBlock.blockBuilder();
        CodeLabel snapshotDone = snapshotBuilder.label("done");
        snapshotBuilder.jump(snapshotDone).mark(snapshotDone);
        snapshotBuilder.append(snapshotResult.set(constantInt(1).add(new SetupThen(snapshotBuilder.build(), constantInt(2)))));
        nestedSnapshot.append(snapshotBuilder.build()).ret(snapshotResult);

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompiledClass compiledClass = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup)).compileClass(definition.build());
        Class<?> generated = defineHidden(lookup, compiledClass).lookupClass();
        assertThat(generated.getMethod("value", String.class, int.class).invoke(null, "12", 4)).isEqualTo(18);
        assertThat(generated.getMethod("value", String.class, int.class).invoke(null, "x", 3)).isEqualTo(2);
        assertThat(generated.getMethod("value", String.class, int.class).invoke(null, "12", -1)).isEqualTo(-100);
        assertThat(generated.getMethod("value", String.class, int.class).invoke(null, null, 1)).isEqualTo(-200);
        assertThat(generated.getMethod("throwing", String.class).invoke(null, "12")).isEqualTo(13);
        assertThatThrownBy(() -> generated.getMethod("throwing", String.class).invoke(null, "x"))
                .cause().isInstanceOf(IllegalArgumentException.class).hasMessage("bad");
        assertThat(generated.getMethod("returning", String.class).invoke(null, "12")).isEqualTo(13);
        assertThat(generated.getMethod("returning", String.class).invoke(null, "x")).isEqualTo(-1);
        assertThat(generated.getMethod("finallyThrowing", String.class).invoke(null, "12")).isEqualTo(2);
        assertThatThrownBy(() -> generated.getMethod("finallyThrowing", String.class).invoke(null, "x"))
                .cause().isInstanceOf(IllegalArgumentException.class).hasMessage("bad");
        assertThat(generated.getMethod("handlerCatching", String.class).invoke(null, "12")).isEqualTo(3);
        assertThat(generated.getMethod("handlerCatching", String.class).invoke(null, "x")).isEqualTo(-1);
        assertThat(generated.getMethod("handlerFinally", String.class).invoke(null, "12")).isEqualTo(3);
        assertThat(generated.getMethod("handlerFinally", String.class).invoke(null, "x")).isEqualTo(-1);
        assertThat(generated.getMethod("nestedSnapshot").invoke(null)).isEqualTo(3);
    }

    private static SetupThen parseOr(Parameter input, BytecodeExpression handler)
    {
        CodeBlock.Builder setup = CodeBlock.blockBuilder();
        Variable parsed = setup.declare(CD_int, "parsed");
        setup.append(TryCatch.builder()
                .tryBlock(parsed.set(invokeStatic(Integer.class, "parseInt", int.class, input)))
                .catching(NumberFormatException.class, "e", (body, _) -> body.append(handler))
                .build());
        return new SetupThen(setup.build(), parsed);
    }

    @Test
    void testSetupWithOperandsOnStackCannotLeaveItsStackDepth()
    {
        // A handler discards the operands already pushed.
        SetupThen catching = new SetupThen(CodeBlock.block(catchingParse()), constantInt(2));
        String catchBlock = "have a catch block that may complete normally";
        assertStackedSetupRejected(catchBlock, (body, _) -> body.ret(constantInt(1).add(catching)));
        assertStackedSetupRejected(catchBlock, (body, _) -> body.ret(BytecodeExpressions.newInstance(StringBuilder.class, catching).invoke("length", int.class)));
        // Compared as a value, a null constant is pushed before the other operand.
        SetupThen catchingString = new SetupThen(CodeBlock.block(catchingParse()), constantString("x"));
        assertStackedSetupRejected(catchBlock, (body, _) -> body.append(constantNull(String.class).equal(catchingString).pop())
                .ret(constantInt(0)));
        // A constructor invocation evaluates its arguments above the receiver.
        ClassDesc type = generatedClass("StackedSetup");
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL).superClass(ArrayList.class);
        definition.constructor().access(PUBLIC).body()
                .invokeSuperConstructor(catching)
                .ret();
        assertThatThrownBy(definition::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Synthetic expression setup cannot " + catchBlock + " while operands are on the stack: setupThen in public " + type.displayName() + "()");

        // A handler that cannot complete normally still may not jump out of its catch block.
        assertStackedSetupRejected("jump out of a catch block", (body, condition) -> {
            WhileLoop.Builder loop = WhileLoop.builder();
            SetupThen breaking = new SetupThen(CodeBlock.block(TryCatch.builder()
                    .tryBlock(invokeStatic(Integer.class, "parseInt", int.class, constantString("x")).pop())
                    .catching(NumberFormatException.class, "e", (handler, _) -> handler.append(loop.breakLoop()))
                    .build()), constantInt(2));
            body.append(loop.condition(condition).body(constantInt(1).add(breaking).pop()).build())
                    .ret(constantInt(0));
        });
        assertStackedSetupRejected("jump out of a catch block to label end", (body, _) -> {
            CodeBlock.Builder setup = CodeBlock.blockBuilder();
            CodeLabel end = setup.label("end");
            setup.append(TryCatch.builder()
                            .tryBlock(invokeStatic(Integer.class, "parseInt", int.class, constantString("x")).pop())
                            .catching(NumberFormatException.class, "e", (handler, _) -> handler.jump(end))
                            .build())
                    .mark(end);
            body.ret(constantInt(1).add(new SetupThen(setup.build(), constantInt(2))));
        });

        // Code outside the setup runs below the operands.
        assertStackedSetupRejected("jump outside itself", (body, condition) -> {
            WhileLoop.Builder loop = WhileLoop.builder();
            body.append(loop.condition(condition)
                            .body(constantInt(1).add(new SetupThen(CodeBlock.block(loop.breakLoop()), constantInt(2))).pop())
                            .build())
                    .ret(constantInt(0));
        });
        assertStackedSetupRejected("jump outside itself to label end", (body, _) -> {
            CodeLabel end = body.label("end");
            CodeBlock.Builder jump = CodeBlock.blockBuilder();
            jump.jump(end);
            body.append(constantInt(1).add(new SetupThen(jump.build(), constantInt(2))).pop())
                    .mark(end)
                    .ret(constantInt(0));
        });
        // The exception handler's copy of a finally block runs below the operands.
        assertStackedSetupRejected("jump out of a finally block to label end", (body, _) -> {
            CodeBlock.Builder setup = CodeBlock.blockBuilder();
            CodeLabel end = setup.label("end");
            CodeBlock.Builder jump = CodeBlock.blockBuilder();
            jump.jump(end);
            setup.append(TryCatch.builder()
                            .tryBlock(invokeStatic(Integer.class, "parseInt", int.class, constantString("1")).pop())
                            .finallyBlock(jump.build())
                            .build())
                    .mark(end);
            body.ret(constantInt(1).add(new SetupThen(setup.build(), constantInt(2))));
        });

        // A return runs a copy of each enclosing finally block above the operands.
        String finallyCatchBlock = "return through a finally block with a catch block that may complete normally";
        assertStackedSetupRejected(finallyCatchBlock, (body, _) -> body.append(TryCatch.builder()
                        .tryBlock(constantInt(1).add(new SetupThen(CodeBlock.block(constantInt(2).ret()), constantInt(3))).ret())
                        .finallyBlock(catchingParse())
                        .build())
                .ret(constantInt(0)));
        // A setup stacked again in a catch block runs its return's finally copies above its own operands.
        assertStackedSetupRejected(finallyCatchBlock, (body, _) -> body.append(TryCatch.builder()
                        .tryBlock(constantInt(1).add(new SetupThen(CodeBlock.block(TryCatch.builder()
                                .tryBlock(invokeStatic(Integer.class, "parseInt", int.class, constantString("x")).pop())
                                .catching(NumberFormatException.class, "e", (handler, _) -> handler.append(constantInt(4)
                                        .add(new SetupThen(CodeBlock.block(constantInt(2).ret()), constantInt(3)))
                                        .ret()))
                                .build()), constantInt(2))).ret())
                        .finallyBlock(catchingParse())
                        .build())
                .ret(constantInt(0)));
        assertStackedSetupRejected("return through a finally block that jumps out of itself", (body, condition) -> {
            WhileLoop.Builder loop = WhileLoop.builder();
            body.append(loop.condition(condition)
                            .body(TryCatch.builder()
                                    .tryBlock(constantInt(1).add(new SetupThen(CodeBlock.block(constantInt(2).ret()), constantInt(3))).ret())
                                    .finallyBlock(CodeBlock.block(loop.breakLoop()))
                                    .build())
                            .build())
                    .ret(constantInt(0));
        });
        // The exception handler of a catch block in the finally copy runs below the operands.
        assertStackedSetupRejected("return through a finally block that jumps out of a catch block in it to label end", (body, _) -> {
            CodeBlock.Builder finallyBlock = CodeBlock.blockBuilder();
            CodeLabel end = finallyBlock.label("end");
            finallyBlock.append(TryCatch.builder()
                            .tryBlock(invokeStatic(Integer.class, "parseInt", int.class, constantString("x")).pop())
                            .catching(NumberFormatException.class, "e", (handler, _) -> handler.jump(end))
                            .build())
                    .mark(end);
            body.append(TryCatch.builder()
                            .tryBlock(constantInt(1).add(new SetupThen(CodeBlock.block(constantInt(2).ret()), constantInt(3))).ret())
                            .finallyBlock(finallyBlock.build())
                            .build())
                    .ret(constantInt(0));
        });
    }

    private static void assertStackedSetupRejected(String reason, BiConsumer<CodeBlock.Builder, Parameter> body)
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("StackedSetup")).access(PUBLIC, FINAL);
        Parameter condition = Parameter.arg("condition", boolean.class);
        MethodDefinition method = definition.method("value", int.class, condition).access(PUBLIC, STATIC);
        body.accept(method.body(), condition);
        assertThatThrownBy(definition::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Synthetic expression setup cannot " + reason + " while operands are on the stack: setupThen in public static int value(boolean condition)");
    }

    private static TryCatch catchingParse()
    {
        return TryCatch.builder()
                .tryBlock(invokeStatic(Integer.class, "parseInt", int.class, constantString("1")).pop())
                .catching(NumberFormatException.class, "e", (_, _) -> {})
                .build();
    }

    private static ClassDesc generatedClass(String suffix)
    {
        return ClassDesc.of(TestSyntheticExpressions.class.getPackageName() + ".Generated" + suffix + NEXT_CLASS_ID.incrementAndGet());
    }

    private record AddThenDouble(BytecodeExpression input)
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
            CodeBlock.Builder setup = CodeBlock.blockBuilder();
            Variable temporary = setup.declare(CD_int, "temporary");
            setup.append(temporary.set(input.add(constantInt(1))));
            return new ExpressionPlan(setup.build(), temporary.multiply(constantInt(2)));
        }

        @Override
        public String toString()
        {
            return "addThenDouble(" + input + ")";
        }
    }

    private record SetupThen(CodeBlock setup, BytecodeExpression value)
            implements SyntheticExpression
    {
        @Override
        public ClassDesc type()
        {
            return value.type();
        }

        @Override
        public ExpressionPlan expansion(ExpansionContext context)
        {
            return new ExpressionPlan(setup, value);
        }

        @Override
        public String toString()
        {
            return "setupThen";
        }
    }

    private record SequentialSetup(int statements)
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
            CodeBlock.Builder setup = CodeBlock.blockBuilder();
            Variable state = setup.declare("state", constantInt(0));
            for (int index = 0; index < statements; index++) {
                setup.append(CodeBlock.block(state.set(state.add(constantInt(1)))));
            }
            return new ExpressionPlan(setup.build(), state);
        }
    }

    private record Offset(BytecodeExpression input)
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
            return ExpressionPlan.value(input.add(constantInt(1)));
        }

        @Override
        public String toString()
        {
            return "offset(" + input + ")";
        }
    }

    private record BoxedAdd(BytecodeExpression input, Variable wasNull)
            implements SyntheticExpression
    {
        @Override
        public ClassDesc type()
        {
            return ClassDesc.of(Long.class.getName());
        }

        @Override
        public ExpressionPlan expansion(ExpansionContext context)
        {
            CodeBlock.Builder setup = CodeBlock.blockBuilder();
            setup.append(wasNull.set(constantFalse()));
            Variable result = setup.declare("expressionResult", new NullPropagatingAdd(input, wasNull));
            return new ExpressionPlan(setup.build(), new BoxIfNecessary(result, wasNull));
        }
    }

    private static final class AssignThenFail
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
            CodeBlock.Builder setup = CodeBlock.blockBuilder();
            Variable result = setup.declare(CD_int, "result");
            setup.append(result.set(constantInt(1)));
            setup.append(invokeStatic(TestSyntheticExpressions.class, "fail", int.class));
            return new ExpressionPlan(setup.build(), result);
        }
    }

    private record NullPropagatingAdd(BytecodeExpression input, Variable wasNull)
            implements SyntheticExpression
    {
        @Override
        public ClassDesc type()
        {
            return CD_long;
        }

        @Override
        public ExpressionPlan expansion(ExpansionContext context)
        {
            CodeBlock.Builder setup = CodeBlock.blockBuilder();
            Variable result = setup.declare(CD_long, "result");
            Variable argument = setup.declare("argument", new UnboxIfNecessary(input, wasNull));
            setup.append(IfStatement.builder()
                    .condition(wasNull)
                    .then(CodeBlock.block(result.set(constantLong(0))))
                    .otherwise(CodeBlock.block(result.set(boundMethodHandle(ADD).invoke(argument, constantLong(1)))))
                    .build());
            return new ExpressionPlan(setup.build(), result);
        }
    }

    private record UnboxIfNecessary(BytecodeExpression input, Variable wasNull)
            implements SyntheticExpression
    {
        @Override
        public ClassDesc type()
        {
            return CD_long;
        }

        @Override
        public ExpressionPlan expansion(ExpansionContext context)
        {
            CodeBlock.Builder setup = CodeBlock.blockBuilder();
            Variable boxed = setup.declare("boxedResult", input);
            Variable result = setup.declare(CD_long, "unboxedResult");
            setup.append(IfStatement.builder()
                    .condition(boxed.isNull())
                    .then(CodeBlock.block(wasNull.set(constantTrue()), result.set(constantLong(0))))
                    .otherwise(CodeBlock.block(result.set(boxed.invoke("longValue", long.class))))
                    .build());
            return new ExpressionPlan(setup.build(), result);
        }
    }

    private record BoxIfNecessary(BytecodeExpression input, Variable wasNull)
            implements SyntheticExpression
    {
        @Override
        public ClassDesc type()
        {
            return ClassDesc.of(Long.class.getName());
        }

        @Override
        public ExpressionPlan expansion(ExpansionContext context)
        {
            CodeBlock.Builder setup = CodeBlock.blockBuilder();
            Variable result = setup.declare(Long.class, "boxed");
            setup.append(IfStatement.builder()
                    .condition(wasNull)
                    .then(CodeBlock.block(result.set(constantNull(Long.class))))
                    .otherwise(CodeBlock.block(result.set(invokeStatic(Long.class, "valueOf", Long.class, input))))
                    .build());
            return new ExpressionPlan(setup.build(), result);
        }
    }

    private static MethodHandle methodHandle(String name, MethodType type)
    {
        try {
            return MethodHandles.lookup().findStatic(TestSyntheticExpressions.class, name, type);
        }
        catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("UnusedMethod")
    private static long add(long left, long right)
    {
        return left + right;
    }

    public static int fail()
    {
        throw new IllegalStateException("expected");
    }

    private static final class SetupValue
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
            return new ExpressionPlan(CodeBlock.block(constantInt(0).pop()), constantInt(1));
        }
    }

    private record SetupCondition(BytecodeExpression input)
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
            return new ExpressionPlan(CodeBlock.block(constantInt(0).pop()), input.equal(constantInt(0)));
        }
    }

    private static final class WrongTypeExpression
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
            return ExpressionPlan.value(constantLong(1));
        }

        @Override
        public String toString()
        {
            return "wrongType";
        }
    }

    private static final class RecursiveExpression
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
            return ExpressionPlan.value(this);
        }

        @Override
        public String toString()
        {
            return "recursive";
        }
    }

    private record ContextBoundConstant(Object value, boolean symbolicType)
            implements SyntheticExpression
    {
        @Override
        public ClassDesc type()
        {
            return CD_Object;
        }

        @Override
        public ExpressionPlan expansion(ExpansionContext context)
        {
            return ExpressionPlan.value(symbolicType
                    ? context.boundConstant(value, CD_Object)
                    : context.boundConstant(value, Object.class));
        }

        @Override
        public String toString()
        {
            return "contextBoundConstant";
        }
    }
}
