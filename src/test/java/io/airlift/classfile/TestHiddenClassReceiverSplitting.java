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
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.CodeBlock.block;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;

class TestHiddenClassReceiverSplitting
{
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final CompilationPolicy SPLITTING_POLICY = CompilationPolicy.builder()
            .targetMethodCodeLimit(100)
            .build();

    @Test
    void testStatementHelperUsesHiddenReceiver()
            throws Throwable
    {
        ClassDefinition definition = newDefinition("Statements");
        FieldDefinition field = definition.field("field", long.class).access(PRIVATE).build();
        MethodDefinition method = definition.method("value", long.class).access(PUBLIC);
        for (int index = 0; index < 1_000; index++) {
            method.body().append(method.thisVariable().getField(field).add(constantLong(index)).pop());
        }
        method.body().ret(method.thisVariable().getField(field));

        MethodHandles.Lookup lookup = defineHidden(definition, "$statements$");

        MethodHandle value = lookup.findVirtual(lookup.lookupClass(), "value", MethodType.methodType(long.class));
        assertThat((long) value.invoke(lookup.lookupClass().getConstructor().newInstance())).isZero();
    }

    @Test
    void testExpressionHelperUsesHiddenReceiver()
            throws Throwable
    {
        ClassDefinition definition = newDefinition("Expression");
        FieldDefinition field = definition.field("field", long.class).access(PRIVATE).build();
        MethodDefinition method = definition.method("value", long.class).access(PUBLIC);
        BytecodeExpression value = method.thisVariable().getField(field);
        for (int index = 0; index < 1_000; index++) {
            value = value.add(constantLong(1));
        }
        method.body().ret(value);

        MethodHandles.Lookup lookup = defineHidden(definition, "$expression$");

        MethodHandle valueMethod = lookup.findVirtual(lookup.lookupClass(), "value", MethodType.methodType(long.class));
        assertThat((long) valueMethod.invoke(lookup.lookupClass().getConstructor().newInstance())).isEqualTo(1_000);
    }

    @Test
    void testConditionHelperUsesHiddenReceiver()
            throws Throwable
    {
        ClassDefinition definition = newDefinition("Conditions");
        FieldDefinition field = definition.field("field", long.class).access(PRIVATE).build();
        MethodDefinition method = definition.method("value", boolean.class).access(PUBLIC);
        for (int index = 0; index < 1_000; index++) {
            method.body().append(IfStatement.builder()
                    .condition(method.thisVariable().getField(field).notEqual(constantLong(0)))
                    .then(BytecodeExpressions.constantFalse().ret())
                    .build());
        }
        method.body().ret(constantTrue());

        MethodHandles.Lookup lookup = defineHidden(definition, "$conditions$");

        MethodHandle value = lookup.findVirtual(lookup.lookupClass(), "value", MethodType.methodType(boolean.class));
        assertThat((boolean) value.invoke(lookup.lookupClass().getConstructor().newInstance())).isTrue();
    }

    @Test
    void testBlockHelperUsesHiddenReceiver()
            throws Throwable
    {
        ClassDefinition definition = newDefinition("Blocks");
        FieldDefinition field = definition.field("field", long.class).access(PRIVATE).build();
        MethodDefinition method = definition.method("value", long.class).access(PUBLIC);
        for (int index = 0; index < 1_000; index++) {
            method.body().append(block(method.thisVariable().getField(field).add(constantLong(index)).pop()));
        }
        method.body().ret(method.thisVariable().getField(field));

        MethodHandles.Lookup lookup = defineHidden(definition, "$blocks$");

        MethodHandle value = lookup.findVirtual(lookup.lookupClass(), "value", MethodType.methodType(long.class));
        assertThat((long) value.invoke(lookup.lookupClass().getConstructor().newInstance())).isZero();
    }

    private static ClassDefinition newDefinition(String suffix)
    {
        ClassDesc type = ClassDesc.of(TestHiddenClassReceiverSplitting.class.getPackageName() + ".Generated" + suffix + NEXT_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        definition.defaultConstructor().access(PUBLIC);
        return definition;
    }

    private static MethodHandles.Lookup defineHidden(ClassDefinition definition, String helperCategory)
    {
        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledClass compiled = ClassCompiler.forTarget(definer.compilationTarget())
                .policy(SPLITTING_POLICY)
                .compileClass(definition.build());
        assertThat(ClassFile.of().parse(compiled.classfile()).methods().stream()
                .filter(method -> method.methodName().stringValue().contains(helperCategory)))
                .isNotEmpty()
                .allSatisfy(method -> {
                    assertThat(method.flags().flags()).doesNotContain(STATIC);
                    assertThat(method.methodTypeSymbol().parameterList()).doesNotContain(compiled.type());
                });

        MethodHandles.Lookup lookup = definer.defineCompiledClass(compiled);
        assertThat(lookup.lookupClass().isHidden()).isTrue();
        return lookup;
    }
}
