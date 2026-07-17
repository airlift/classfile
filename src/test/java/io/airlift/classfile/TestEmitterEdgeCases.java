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
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.ClassfileTestUtils.defineHidden;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;

class TestEmitterEdgeCases
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();

    @Test
    void testNarrowPrimitiveArrayStores()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("PrimitiveArrays");
        Parameter booleans = Parameter.arg("booleans", boolean[].class);
        Parameter bytes = Parameter.arg("bytes", byte[].class);
        Parameter chars = Parameter.arg("chars", char[].class);
        Parameter shorts = Parameter.arg("shorts", short[].class);
        MethodDefinition method = classDefinition.method("update", CD_void, booleans, bytes, chars, shorts).access(PUBLIC, STATIC);
        method.body()
                .append(booleans.setElement(0, constantTrue()))
                .append(bytes.setElement(0, constantInt(11).cast(byte.class)))
                .append(chars.setElement(0, constantInt(12).cast(char.class)))
                .append(shorts.setElement(0, constantInt(13).cast(short.class)))
                .ret();

        Class<?> generated = define(classDefinition.build());
        boolean[] booleanValues = new boolean[1];
        byte[] byteValues = new byte[1];
        char[] charValues = new char[1];
        short[] shortValues = new short[1];
        generated.getMethod("update", boolean[].class, byte[].class, char[].class, short[].class)
                .invoke(null, booleanValues, byteValues, charValues, shortValues);

        assertThat(booleanValues).containsExactly(true);
        assertThat(byteValues).containsExactly((byte) 11);
        assertThat(charValues).containsExactly((char) 12);
        assertThat(shortValues).containsExactly((short) 13);
    }

    @Test
    void testReflectiveConstructorUsesDeclaredDescriptor()
            throws Exception
    {
        ClassDefinition classDefinition = generatedClass("ConstructorDescriptor");
        MethodDefinition method = classDefinition.method("length", CD_int).access(PUBLIC, STATIC);
        method.body().append(BytecodeExpressions.newInstance(WidenedConstructor.class, constantString("value"))
                .invoke("value", Object.class)
                .cast(String.class)
                .invoke("length", int.class)
                .ret());

        assertThat(define(classDefinition.build()).getMethod("length").invoke(null)).isEqualTo(5);
    }

    private ClassDefinition generatedClass(String suffix)
    {
        ClassDesc type = ClassDesc.of(getClass().getPackageName() + ".Generated" + suffix + NEXT_CLASS_ID.incrementAndGet());
        return ClassDefinition.define(type).access(PUBLIC, FINAL);
    }

    private static Class<?> define(ClassModel definition)
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        CompiledClass compiledClass = ClassCompiler.forTarget(CompilationTarget.forLookup(lookup)).compileClass(definition);
        return defineHidden(lookup, compiledClass).lookupClass();
    }

    public static final class WidenedConstructor
    {
        private final Object value;

        public WidenedConstructor(Object value)
        {
            this.value = value;
        }

        public Object value()
        {
            return value;
        }
    }
}
