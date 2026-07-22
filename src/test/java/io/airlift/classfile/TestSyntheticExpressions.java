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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.boundMethodHandle;
import static io.airlift.classfile.BytecodeExpressions.constantFalse;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
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
