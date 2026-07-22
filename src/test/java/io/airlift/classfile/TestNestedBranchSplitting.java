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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.CodeBlock.block;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestNestedBranchSplitting
{
    private static final int CHANNELS = 7;
    private static final CompilationPolicy SMALL_SPLITTING_POLICY = CompilationPolicy.builder()
            .targetMethodCodeLimit(100)
            .hardMethodCodeLimit(1_000)
            .build();

    @Test
    void testNestedTernaryDispatcherSplits()
            throws Throwable
    {
        ClassDesc type = ClassDesc.of(TestNestedBranchSplitting.class.getPackageName() + ".GeneratedNestedBranchDispatcher");
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);

        Parameter result = arg("result", int[].class);
        ArrayList<Parameter> channels = new ArrayList<>();
        for (int channel = 0; channel < CHANNELS; channel++) {
            channels.add(arg("channel" + channel, Object.class));
        }
        ArrayList<Parameter> parameters = new ArrayList<>();
        parameters.add(result);
        parameters.addAll(channels);
        MethodDefinition invoke = definition.method("invoke", void.class, parameters.toArray(Parameter[]::new)).access(PUBLIC, STATIC);
        AtomicInteger nextLeaf = new AtomicInteger();
        invoke.body().append(dispatcher(definition, result, channels, 0, nextLeaf)).ret();
        assertThat(nextLeaf).hasValue(2_187);

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget()).compileUnit(definition.build());
        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .filter(method -> method.name().contains("$branches$")))
                .isNotEmpty()
                .allSatisfy(method -> assertThat(method.codeBytes()).isLessThanOrEqualTo(unit.report().policy().targetMethodCodeLimit()));

        DefinedUnit defined = definer.defineUnit(unit);
        assertThat(defined.primaryClass().isHidden()).isTrue();
        MethodType methodType = MethodType.methodType(void.class, int[].class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class);
        MethodHandle dispatcher = defined.primaryLookup().orElseThrow().findStatic(defined.primaryClass(), "invoke", methodType);

        int[] selected = new int[1];
        dispatcher.invokeExact(selected, (Object) "value", (Object) 1, (Object) 2L, (Object) "value", (Object) 1, (Object) 2L, (Object) "value");
        assertThat(selected[0]).isEqualTo(baseThreeIndex(0, 1, 2, 0, 1, 2, 0));

        assertThatThrownBy(() -> dispatcher.invokeWithArguments(selected, new Object(), 1, 2L, "value", 1, 2L, "value"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("unsupported value");
    }

    @Test
    void testNestedBranchHelperUsesHiddenReceiver()
            throws Throwable
    {
        ClassDesc type = ClassDesc.of(TestNestedBranchSplitting.class.getPackageName() + ".GeneratedNestedReceiverDispatcher");
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        definition.defaultConstructor().access(PUBLIC);
        FieldDefinition selected = definition.field("selected", int.class).access(PRIVATE).build();

        Parameter selector = arg("selector", int.class);
        MethodDefinition select = definition.method("select", int.class, selector).access(PUBLIC);
        AtomicInteger nextLeaf = new AtomicInteger();
        select.body()
                .append(fieldDispatcher(select.thisVariable(), selected, selector, 0, 5, nextLeaf))
                .ret(select.thisVariable().getField(selected));
        assertThat(nextLeaf).hasValue(243);

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(SMALL_SPLITTING_POLICY)
                .compileUnit(definition.build());
        assertThat(ClassFile.of().parse(unit.classfile(unit.primaryType())).methods().stream()
                .filter(method -> method.methodName().stringValue().contains("$branches$")))
                .isNotEmpty()
                .allSatisfy(method -> {
                    assertThat(method.flags().flags()).doesNotContain(STATIC);
                    assertThat(method.methodTypeSymbol().parameterList()).doesNotContain(unit.primaryType());
                });

        DefinedUnit defined = definer.defineUnit(unit);
        Object instance = defined.primaryClass().getConstructor().newInstance();
        MethodHandle selectMethod = defined.primaryLookup().orElseThrow()
                .findVirtual(defined.primaryClass(), "select", MethodType.methodType(int.class, int.class));
        assertThat((int) selectMethod.invoke(instance, 0)).isEqualTo(baseThreeIndex(0, 2, 2, 2, 2));
    }

    @Test
    void testNestedBranchesWithOuterWritesAreNotDirectlyExtracted()
    {
        ClassDefinition definition = ClassDefinition.define(TestNestedBranchSplitting.class).access(PUBLIC, FINAL);
        Parameter selector = arg("selector", int.class);
        MethodDefinition select = definition.method("select", int.class, selector).access(PUBLIC, STATIC);
        Variable selected = select.body().declare("selected", constantInt(0));
        AtomicInteger nextLeaf = new AtomicInteger();
        select.body()
                .append(localDispatcher(selected, selector, 0, 5, nextLeaf))
                .ret(selected);

        StatementPlanner.Result planned = StatementPlanner.plan(definition.build(), SMALL_SPLITTING_POLICY, Set.of(), true);

        assertThat(planned.generatedMethods()).noneMatch(name -> name.contains("$branches$"));
    }

    private static CodeBlock dispatcher(
            ClassDefinition definition,
            Parameter result,
            List<Parameter> channels,
            int channel,
            AtomicInteger nextLeaf)
    {
        if (channel == channels.size()) {
            int leafIndex = nextLeaf.getAndIncrement();
            Parameter leafResult = arg("result", int[].class);
            ArrayList<Parameter> leafChannels = new ArrayList<>();
            for (int leafChannel = 0; leafChannel < channels.size(); leafChannel++) {
                leafChannels.add(arg("channel" + leafChannel, Object.class));
            }
            ArrayList<Parameter> leafParameters = new ArrayList<>();
            leafParameters.add(leafResult);
            leafParameters.addAll(leafChannels);
            MethodDefinition leaf = definition.method("leaf" + leafIndex, void.class, leafParameters.toArray(Parameter[]::new)).access(PRIVATE, STATIC);
            leaf.body().append(leafResult.setElement(0, constantInt(leafIndex))).ret();
            ArrayList<BytecodeExpression> arguments = new ArrayList<>();
            arguments.add(result);
            arguments.addAll(channels);
            return block(invokeStatic(leaf, arguments.toArray(BytecodeExpression[]::new)));
        }

        Parameter value = channels.get(channel);
        return block(IfStatement.builder()
                .condition(value.instanceOf(String.class))
                .then(dispatcher(definition, result, channels, channel + 1, nextLeaf))
                .otherwise(block(IfStatement.builder()
                        .condition(value.instanceOf(Integer.class))
                        .then(dispatcher(definition, result, channels, channel + 1, nextLeaf))
                        .otherwise(block(IfStatement.builder()
                                .condition(value.instanceOf(Long.class))
                                .then(dispatcher(definition, result, channels, channel + 1, nextLeaf))
                                .otherwise(BytecodeExpressions.newInstance(UnsupportedOperationException.class, constantString("unsupported value")).throwObject())
                                .build()))
                        .build()))
                .build());
    }

    private static CodeBlock fieldDispatcher(
            Variable receiver,
            FieldDefinition selected,
            Parameter selector,
            int depth,
            int maxDepth,
            AtomicInteger nextLeaf)
    {
        if (depth == maxDepth) {
            return block(receiver.setField(selected, constantInt(nextLeaf.getAndIncrement())));
        }
        return block(IfStatement.builder()
                .condition(selector.equal(constantInt(depth)))
                .then(fieldDispatcher(receiver, selected, selector, depth + 1, maxDepth, nextLeaf))
                .otherwise(block(IfStatement.builder()
                        .condition(selector.equal(constantInt(depth + 10)))
                        .then(fieldDispatcher(receiver, selected, selector, depth + 1, maxDepth, nextLeaf))
                        .otherwise(fieldDispatcher(receiver, selected, selector, depth + 1, maxDepth, nextLeaf))
                        .build()))
                .build());
    }

    private static CodeBlock localDispatcher(
            Variable selected,
            Parameter selector,
            int depth,
            int maxDepth,
            AtomicInteger nextLeaf)
    {
        if (depth == maxDepth) {
            return block(selected.set(constantInt(nextLeaf.getAndIncrement())));
        }
        return block(IfStatement.builder()
                .condition(selector.equal(constantInt(depth)))
                .then(localDispatcher(selected, selector, depth + 1, maxDepth, nextLeaf))
                .otherwise(block(IfStatement.builder()
                        .condition(selector.equal(constantInt(depth + 10)))
                        .then(localDispatcher(selected, selector, depth + 1, maxDepth, nextLeaf))
                        .otherwise(localDispatcher(selected, selector, depth + 1, maxDepth, nextLeaf))
                        .build()))
                .build());
    }

    private static int baseThreeIndex(int... choices)
    {
        int result = 0;
        for (int choice : choices) {
            result = result * 3 + choice;
        }
        return result;
    }
}
