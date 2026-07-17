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

import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.ClassfileTestUtils.defineHidden;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;

final class ClassfileExpressionAssertions
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();

    private ClassfileExpressionAssertions() {}

    static void assertExpression(BytecodeExpression expression, Object expected, String rendering)
            throws Exception
    {
        assertThat(expression.toString()).isEqualTo(rendering);
        assertThat(evaluate(expression)).isEqualTo(expected);
    }

    static Object evaluate(BytecodeExpression expression)
            throws Exception
    {
        ClassDesc type = ClassDesc.of(ClassfileExpressionAssertions.class.getPackageName() + ".GeneratedParityExpression" + NEXT_CLASS_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        MethodDefinition method = definition.method("value", expression.type()).access(PUBLIC, STATIC);
        if (expression.type().equals(CD_void)) {
            method.body()
                    .append(expression)
                    .ret();
        }
        else {
            method.body().append(expression.ret());
        }
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompiledClass compiledClass = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup))
                .compileClass(definition.build());
        Class<?> generated = defineHidden(lookup, compiledClass).lookupClass();
        return generated.getMethod("value").invoke(null);
    }
}
