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
import java.lang.invoke.MethodHandle;
import java.util.List;

import static io.airlift.classfile.DescriptorUtils.isNarrowInteger;
import static io.airlift.classfile.DescriptorUtils.methodType;
import static java.util.Objects.requireNonNull;

/// A runtime-bound method handle whose linkage signature is selected for the compilation target.
public final class BoundMethodHandle
{
    private final MethodHandle handle;
    private final MethodTypeDesc type;

    BoundMethodHandle(MethodHandle handle)
    {
        this.handle = requireNonNull(handle, "handle is null");
        type = methodType(handle.type());
    }

    public MethodTypeDesc type()
    {
        return type;
    }

    /// Creates an invocation expression after validating arguments against the original handle
    /// signature. Inaccessible reference types are adapted when the expression is compiled for its
    /// target.
    public BytecodeExpression invoke(BytecodeExpression... arguments)
    {
        arguments = requireNonNull(arguments, "arguments is null").clone();
        if (arguments.length != type.parameterCount()) {
            throw new IllegalArgumentException("Expected %s arguments but got %s".formatted(type.parameterCount(), arguments.length));
        }
        for (int index = 0; index < arguments.length; index++) {
            BytecodeExpression argument = requireNonNull(arguments[index], "argument is null");
            ClassDesc expected = type.parameterType(index);
            ClassDesc actual = argument.type();
            if ((expected.isPrimitive() || actual.isPrimitive()) &&
                    !expected.equals(actual) &&
                    !(isNarrowInteger(expected) && isNarrowInteger(actual))) {
                throw new IllegalArgumentException("Argument %s has type %s; expected %s"
                        .formatted(index, actual.displayName(), expected.displayName()));
            }
        }
        return new CoreExpression(new ExpressionNode.BoundMethodHandleInvocation(
                type.returnType(),
                handle,
                type,
                List.of(arguments)));
    }

    @Override
    public String toString()
    {
        return "boundMethodHandle" + type.descriptorString();
    }
}
