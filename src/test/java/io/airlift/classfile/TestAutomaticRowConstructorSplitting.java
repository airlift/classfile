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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.CodeBlock.blockBuilder;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;

/// A de-chunked port of the bulky field packing in Trino's RowConstructorCodeGenerator.
public class TestAutomaticRowConstructorSplitting
{
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final int FIELD_COUNT = 120;
    private static final int VALUES_PER_FIELD = 8;
    private static final int HARD_METHOD_CODE_LIMIT = 4_000;

    @Test
    void testBulkyFieldsAreSplitWithoutManualHelpers()
            throws Throwable
    {
        MethodHandles.Lookup hostLookup = MethodHandles.lookup();
        ClassModel logicalModel = rowConstructorModel();
        assertThat(logicalModel.methods())
                .extracting(MethodDefinition.Model::name)
                .containsExactly("build");
        CompilationPolicy policy = CompilationPolicy.builder()
                .hardMethodCodeLimit(HARD_METHOD_CODE_LIMIT)
                .targetMethodCodeLimit(512)
                .build();

        CompiledUnit unit = ClassCompiler.forTarget(CompilationTarget.forLookup(hostLookup))
                .policy(policy)
                .compileUnit(logicalModel);

        assertThat(unit.report().classes()).isNotEmpty();
        List<CompilationReport.MethodInfo> methods = unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .toList();
        assertThat(methods)
                .extracting(CompilationReport.MethodInfo::name)
                .anyMatch(name -> name.startsWith("build$blocks$"));
        assertThat(methods).allSatisfy(method -> {
            assertThat(method.codeBytes()).isLessThanOrEqualTo(policy.targetMethodCodeLimit());
            assertThat(method.codeBytes()).isLessThanOrEqualTo(HARD_METHOD_CODE_LIMIT);
        });

        DefinedUnit defined = HiddenClassDefiner.builder(hostLookup).build().defineUnit(unit);
        MethodHandle build = defined.primaryLookup().orElseThrow()
                .findStatic(defined.primaryClass(), "build", MethodType.methodType(String[][].class, String.class));

        String[][] row = (String[][]) build.invokeExact("value");
        assertThat(row.length).isEqualTo(FIELD_COUNT);
        assertThat(row[0]).containsExactly(expectedField(0));
        assertThat(row[FIELD_COUNT - 1]).containsExactly(expectedField(FIELD_COUNT - 1));

        String[][] nullRow = (String[][]) build.invokeExact((String) null);
        assertThat(Arrays.asList(nullRow)).containsOnlyNulls();
    }

    @Test
    void testBlockWritingOneCapturedLocalIsMoved()
            throws Throwable
    {
        MethodHandles.Lookup hostLookup = MethodHandles.lookup();
        ClassDefinition definition = ClassDefinition.define(generatedType()).access(PUBLIC, FINAL);
        MethodDefinition method = definition.method("count", int.class).access(PUBLIC, STATIC);
        Variable count = method.body().declare("count", constantInt(0));
        for (int index = 0; index < 50; index++) {
            method.body().append(blockBuilder()
                    .append(count.set(count.add(constantInt(1))))
                    .build());
        }
        method.body().ret(count);

        CompiledUnit unit = ClassCompiler.forTarget(CompilationTarget.forLookup(hostLookup))
                .policy(CompilationPolicy.builder()
                        .hardMethodCodeLimit(HARD_METHOD_CODE_LIMIT)
                        .targetMethodCodeLimit(64)
                        .build())
                .compileUnit(definition.build());

        assertThat(unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .map(CompilationReport.MethodInfo::name))
                .anyMatch(name -> name.startsWith("count$blocks$"));

        DefinedUnit defined = HiddenClassDefiner.builder(hostLookup).build().defineUnit(unit);
        MethodHandle countMethod = defined.primaryLookup().orElseThrow()
                .findStatic(defined.primaryClass(), "count", MethodType.methodType(int.class));
        assertThat((int) countMethod.invokeExact()).isEqualTo(50);
    }

    private static ClassModel rowConstructorModel()
    {
        ClassDefinition definition = ClassDefinition.define(generatedType()).access(PUBLIC, FINAL);
        Parameter input = arg("input", String.class);
        MethodDefinition method = definition.method("build", String[][].class, input).access(PUBLIC, STATIC);
        Variable row = method.body().declare("row", newArray(String[][].class, constantInt(FIELD_COUNT)));

        for (int field = 0; field < FIELD_COUNT; field++) {
            CodeBlock.Builder fieldBlock = blockBuilder().description("initialize row field " + field);
            Variable values = fieldBlock.declare("values", newArray(String[].class, constantInt(VALUES_PER_FIELD)));

            CodeBlock.Builder present = blockBuilder();
            for (int value = 0; value < VALUES_PER_FIELD; value++) {
                present.append(values.setElement(value, input.invoke("concat", String.class, constantString(suffix(field, value)))));
            }
            present.append(row.setElement(field, values));

            fieldBlock.append(IfStatement.builder()
                    .condition(input.isNull())
                    .then(row.setElement(field, constantNull(String[].class)))
                    .otherwise(present.build())
                    .build());
            method.body().append(fieldBlock.build());
        }
        method.body().ret(row);
        return definition.build();
    }

    private static String[] expectedField(int field)
    {
        String[] expected = new String[VALUES_PER_FIELD];
        for (int value = 0; value < VALUES_PER_FIELD; value++) {
            expected[value] = "value" + suffix(field, value);
        }
        return expected;
    }

    private static String suffix(int field, int value)
    {
        return "-" + field + "-" + value;
    }

    private static ClassDesc generatedType()
    {
        return ClassDesc.of(TestAutomaticRowConstructorSplitting.class.getPackageName() + ".GeneratedRowConstructor_" + NEXT_ID.incrementAndGet());
    }
}
