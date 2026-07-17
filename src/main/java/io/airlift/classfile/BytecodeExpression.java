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
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static io.airlift.classfile.DescriptorUtils.classDesc;
import static io.airlift.classfile.DescriptorUtils.methodType;
import static java.util.Objects.requireNonNull;

/// A typed, composable expression in a generated method body.
///
/// Expressions form reusable logical values rather than JVM instruction sequences. Fluent methods
/// model operations on an existing value; operations without a receiver are provided by
/// [BytecodeExpressions]. Expressions are immutable and may be attached at multiple locations.
/// Arithmetic on `byte`, `short`, and `char` follows Java numeric promotion and produces `int`;
/// declared narrow-variable, argument, field, array, and return boundaries normalize values to
/// their declared type.
public sealed interface BytecodeExpression
        extends Statement
        permits CoreExpression,
                LocalValue,
                SyntheticExpression
{
    ClassDesc type();

    default BytecodeExpression add(BytecodeExpression right)
    {
        return BytecodeExpressions.add(this, right);
    }

    default BytecodeExpression subtract(BytecodeExpression right)
    {
        return BytecodeExpressions.subtract(this, right);
    }

    default BytecodeExpression multiply(BytecodeExpression right)
    {
        return BytecodeExpressions.multiply(this, right);
    }

    default BytecodeExpression divide(BytecodeExpression right)
    {
        return BytecodeExpressions.divide(this, right);
    }

    default BytecodeExpression remainder(BytecodeExpression right)
    {
        return BytecodeExpressions.remainder(this, right);
    }

    default BytecodeExpression bitwiseAnd(BytecodeExpression right)
    {
        return BytecodeExpressions.bitwiseAnd(this, right);
    }

    default BytecodeExpression bitwiseOr(BytecodeExpression right)
    {
        return BytecodeExpressions.bitwiseOr(this, right);
    }

    default BytecodeExpression bitwiseXor(BytecodeExpression right)
    {
        return BytecodeExpressions.bitwiseXor(this, right);
    }

    default BytecodeExpression shiftLeft(BytecodeExpression right)
    {
        return BytecodeExpressions.shiftLeft(this, right);
    }

    default BytecodeExpression shiftRight(BytecodeExpression right)
    {
        return BytecodeExpressions.shiftRight(this, right);
    }

    default BytecodeExpression shiftRightUnsigned(BytecodeExpression right)
    {
        return BytecodeExpressions.shiftRightUnsigned(this, right);
    }

    default BytecodeExpression negate()
    {
        return BytecodeExpressions.negate(this);
    }

    default BytecodeExpression lessThan(BytecodeExpression right)
    {
        return BytecodeExpressions.lessThan(this, right);
    }

    default BytecodeExpression lessThanOrEqual(BytecodeExpression right)
    {
        return BytecodeExpressions.lessThanOrEqual(this, right);
    }

    default BytecodeExpression greaterThan(BytecodeExpression right)
    {
        return BytecodeExpressions.greaterThan(this, right);
    }

    default BytecodeExpression greaterThanOrEqual(BytecodeExpression right)
    {
        return BytecodeExpressions.greaterThanOrEqual(this, right);
    }

    default BytecodeExpression equal(BytecodeExpression right)
    {
        return BytecodeExpressions.equal(this, right);
    }

    default BytecodeExpression notEqual(BytecodeExpression right)
    {
        return BytecodeExpressions.notEqual(this, right);
    }

    default BytecodeExpression and(BytecodeExpression right)
    {
        return BytecodeExpressions.logicalAnd(this, right);
    }

    default BytecodeExpression or(BytecodeExpression right)
    {
        return BytecodeExpressions.logicalOr(this, right);
    }

    default BytecodeExpression not()
    {
        return BytecodeExpressions.logicalNot(this);
    }

    default BytecodeExpression isNull()
    {
        return BytecodeExpressions.isNull(this);
    }

    default BytecodeExpression isNotNull()
    {
        return BytecodeExpressions.isNotNull(this);
    }

    default BytecodeExpression cast(Class<?> targetType)
    {
        return BytecodeExpressions.cast(this, targetType);
    }

    default BytecodeExpression cast(ClassDesc targetType)
    {
        return BytecodeExpressions.cast(this, targetType);
    }

    default BytecodeExpression instanceOf(Class<?> testType)
    {
        return BytecodeExpressions.instanceOf(this, testType);
    }

    default BytecodeExpression instanceOf(ClassDesc testType)
    {
        return BytecodeExpressions.instanceOf(this, testType);
    }

    default BytecodeExpression invoke(Method method, BytecodeExpression... arguments)
    {
        requireNonNull(method, "method is null");
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("method is static: " + method);
        }
        return method.getDeclaringClass().isInterface()
                ? BytecodeExpressions.invokeInterface(this, classDesc(method.getDeclaringClass()), method.getName(), methodType(method), arguments)
                : BytecodeExpressions.invokeVirtual(this, classDesc(method.getDeclaringClass()), method.getName(), methodType(method), arguments);
    }

    default BytecodeExpression invoke(MethodDefinition method, BytecodeExpression... arguments)
    {
        requireNonNull(method, "method is null");
        if (method.isStatic()) {
            throw new IllegalArgumentException("method is static: " + method);
        }
        return switch (method.declaringKind()) {
            case CLASS, RECORD -> invokeVirtual(method.declaringType(), method.name(), method.methodType(), arguments);
            case INTERFACE -> invokeInterface(method.declaringType(), method.name(), method.methodType(), arguments);
        };
    }

    default BytecodeExpression invoke(String name, MethodTypeDesc methodType, BytecodeExpression... arguments)
    {
        return invokeVirtual(type(), name, methodType, arguments);
    }

    default BytecodeExpression invoke(String name, Class<?> returnType, BytecodeExpression... arguments)
    {
        return invoke(name, MethodTypeDesc.of(
                classDesc(returnType),
                Arrays.stream(arguments).map(BytecodeExpression::type).toList()), arguments);
    }

    default BytecodeExpression invokeVirtual(ClassDesc owner, String name, MethodTypeDesc methodType, BytecodeExpression... arguments)
    {
        return BytecodeExpressions.invokeVirtual(this, owner, name, methodType, arguments);
    }

    default BytecodeExpression invokeInterface(ClassDesc owner, String name, MethodTypeDesc methodType, BytecodeExpression... arguments)
    {
        return BytecodeExpressions.invokeInterface(this, owner, name, methodType, arguments);
    }

    default BytecodeExpression invokeSpecial(ClassDesc owner, String name, MethodTypeDesc methodType, BytecodeExpression... arguments)
    {
        if ("<init>".equals(name)) {
            throw new IllegalArgumentException("constructor invocation is only valid as a CodeBlock operation");
        }
        return BytecodeExpressions.invokeSpecial(this, owner, name, methodType, arguments);
    }

    default BytecodeExpression getField(Field field)
    {
        return BytecodeExpressions.getField(this, field);
    }

    default BytecodeExpression getField(FieldDefinition field)
    {
        return BytecodeExpressions.getField(this, field);
    }

    default BytecodeExpression getField(Class<?> owner, String name)
    {
        return BytecodeExpressions.getField(this, BytecodeExpressions.publicField(owner, name));
    }

    default BytecodeExpression getField(String name, Class<?> fieldType)
    {
        return BytecodeExpressions.getField(this, type(), name, classDesc(fieldType));
    }

    default BytecodeExpression getField(ClassDesc owner, String name, ClassDesc fieldType)
    {
        return BytecodeExpressions.getField(this, owner, name, fieldType);
    }

    default BytecodeExpression setField(Field field, BytecodeExpression value)
    {
        return BytecodeExpressions.setField(this, field, value);
    }

    default BytecodeExpression setField(FieldDefinition field, BytecodeExpression value)
    {
        return BytecodeExpressions.setField(this, field, value);
    }

    default BytecodeExpression setField(Class<?> owner, String name, BytecodeExpression value)
    {
        return BytecodeExpressions.setField(this, BytecodeExpressions.publicField(owner, name), value);
    }

    default BytecodeExpression setField(String name, BytecodeExpression value)
    {
        return BytecodeExpressions.setField(this, type(), name, value.type(), value);
    }

    default BytecodeExpression getElement(int index)
    {
        return BytecodeExpressions.getElement(this, BytecodeExpressions.constantInt(index));
    }

    default BytecodeExpression getElement(BytecodeExpression index)
    {
        return BytecodeExpressions.getElement(this, index);
    }

    default BytecodeExpression setElement(int index, BytecodeExpression value)
    {
        return BytecodeExpressions.setElement(this, BytecodeExpressions.constantInt(index), value);
    }

    default BytecodeExpression setElement(BytecodeExpression index, BytecodeExpression value)
    {
        return BytecodeExpressions.setElement(this, index, value);
    }

    default BytecodeExpression length()
    {
        return BytecodeExpressions.length(this);
    }

    default BytecodeExpression pop()
    {
        return BytecodeExpressions.pop(this);
    }

    default BytecodeExpression ret()
    {
        return BytecodeExpressions.ret(this);
    }

    default BytecodeExpression throwObject()
    {
        return BytecodeExpressions.throwObject(this);
    }
}
