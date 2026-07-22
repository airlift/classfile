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
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static io.airlift.classfile.DescriptorUtils.simpleName;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

sealed interface ExpressionNode
{
    ClassDesc type();

    String formatOneLine();

    List<BytecodeExpression> children();

    record Constant(ClassDesc type, Object value, String rendering)
            implements ExpressionNode
    {
        public Constant
        {
            requireNonNull(type, "type is null");
            requireNonNull(rendering, "rendering is null");
        }

        @Override
        public String formatOneLine()
        {
            return rendering;
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of();
        }
    }

    record Binary(ClassDesc type, String operator, BytecodeExpression left, BytecodeExpression right)
            implements ExpressionNode
    {
        public Binary
        {
            requireNonNull(type, "type is null");
            requireNonNull(operator, "operator is null");
            requireNonNull(left, "left is null");
            requireNonNull(right, "right is null");
        }

        @Override
        public String formatOneLine()
        {
            return "(" + left + " " + operator + " " + right + ")";
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of(left, right);
        }
    }

    record Unary(ClassDesc type, String prefix, BytecodeExpression value)
            implements ExpressionNode
    {
        public Unary
        {
            requireNonNull(type, "type is null");
            requireNonNull(prefix, "prefix is null");
            requireNonNull(value, "value is null");
        }

        @Override
        public String formatOneLine()
        {
            if (prefix.equals("!")) {
                return "(!" + value + ")";
            }
            return prefix + "(" + value + ")";
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of(value);
        }
    }

    record Cast(ClassDesc type, BytecodeExpression value)
            implements ExpressionNode
    {
        public Cast
        {
            requireNonNull(type, "type is null");
            requireNonNull(value, "value is null");
        }

        @Override
        public String formatOneLine()
        {
            return "((" + simpleName(type) + ") " + value + ")";
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of(value);
        }
    }

    record InstanceOf(BytecodeExpression value, ClassDesc testType)
            implements ExpressionNode
    {
        public InstanceOf
        {
            requireNonNull(value, "value is null");
            requireNonNull(testType, "testType is null");
        }

        @Override
        public ClassDesc type()
        {
            return ConstantDescs.CD_boolean;
        }

        @Override
        public String formatOneLine()
        {
            return "(" + value + " instanceof " + simpleName(testType) + ")";
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of(value);
        }
    }

    record InlineIf(ClassDesc type, BytecodeExpression condition, BytecodeExpression ifTrue, BytecodeExpression ifFalse)
            implements ExpressionNode
    {
        public InlineIf
        {
            requireNonNull(type, "type is null");
            requireNonNull(condition, "condition is null");
            requireNonNull(ifTrue, "ifTrue is null");
            requireNonNull(ifFalse, "ifFalse is null");
        }

        @Override
        public String formatOneLine()
        {
            return "(" + condition + " ? " + ifTrue + " : " + ifFalse + ")";
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of(condition, ifTrue, ifFalse);
        }
    }

    record ArrayLength(BytecodeExpression array)
            implements ExpressionNode
    {
        public ArrayLength
        {
            requireNonNull(array, "array is null");
        }

        @Override
        public ClassDesc type()
        {
            return ConstantDescs.CD_int;
        }

        @Override
        public String formatOneLine()
        {
            return array + ".length";
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of(array);
        }
    }

    record ArrayGet(ClassDesc type, BytecodeExpression array, BytecodeExpression index)
            implements ExpressionNode
    {
        public ArrayGet
        {
            requireNonNull(type, "type is null");
            requireNonNull(array, "array is null");
            requireNonNull(index, "index is null");
        }

        @Override
        public String formatOneLine()
        {
            return array + "[" + index + "]";
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of(array, index);
        }
    }

    record ArraySet(ClassDesc type, BytecodeExpression array, BytecodeExpression index, BytecodeExpression value)
            implements ExpressionNode
    {
        public ArraySet
        {
            requireNonNull(type, "type is null");
            requireNonNull(array, "array is null");
            requireNonNull(index, "index is null");
            requireNonNull(value, "value is null");
        }

        @Override
        public String formatOneLine()
        {
            return array + "[" + index + "] = " + value;
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of(array, index, value);
        }
    }

    record NewArray(ClassDesc type, BytecodeExpression length, List<BytecodeExpression> elements)
            implements ExpressionNode
    {
        public NewArray
        {
            requireNonNull(type, "type is null");
            elements = List.copyOf(requireNonNull(elements, "elements is null"));
        }

        @Override
        public String formatOneLine()
        {
            String component = simpleName(type.componentType());
            if (length != null) {
                return "new " + component + "[" + length + "]";
            }
            return elements.stream().map(Object::toString).collect(joining(", ", "new " + component + "[] {", "}"));
        }

        @Override
        public List<BytecodeExpression> children()
        {
            if (length != null) {
                return List.of(length);
            }
            return elements;
        }
    }

    record FieldGet(ClassDesc type, BytecodeExpression target, ClassDesc owner, String name, boolean isStatic)
            implements ExpressionNode
    {
        public FieldGet
        {
            requireNonNull(type, "type is null");
            requireNonNull(owner, "owner is null");
            requireNonNull(name, "name is null");
        }

        @Override
        public String formatOneLine()
        {
            return (isStatic ? simpleName(owner) : target.toString()) + "." + name;
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return isStatic ? List.of() : List.of(target);
        }
    }

    record FieldSet(ClassDesc type, BytecodeExpression target, ClassDesc owner, String name, ClassDesc fieldType, BytecodeExpression value, boolean isStatic)
            implements ExpressionNode
    {
        public FieldSet
        {
            requireNonNull(type, "type is null");
            requireNonNull(owner, "owner is null");
            requireNonNull(name, "name is null");
            requireNonNull(fieldType, "fieldType is null");
            requireNonNull(value, "value is null");
        }

        @Override
        public String formatOneLine()
        {
            return (isStatic ? simpleName(owner) : target.toString()) + "." + name + " = " + value;
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return isStatic ? List.of(value) : List.of(target, value);
        }
    }

    enum InvocationKind
    {
        STATIC,
        VIRTUAL_OR_INTERFACE,
        VIRTUAL,
        INTERFACE,
        SPECIAL,
    }

    record Invoke(ClassDesc type, InvocationKind kind, BytecodeExpression target, ClassDesc owner, String name, MethodTypeDesc methodType, List<BytecodeExpression> arguments)
            implements ExpressionNode
    {
        public Invoke
        {
            requireNonNull(type, "type is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(owner, "owner is null");
            requireNonNull(name, "name is null");
            requireNonNull(methodType, "methodType is null");
            arguments = List.copyOf(requireNonNull(arguments, "arguments is null"));
        }

        @Override
        public String formatOneLine()
        {
            String receiver = kind == InvocationKind.STATIC ? simpleName(owner) : target.toString();
            return arguments.stream().map(Object::toString).collect(joining(", ", receiver + "." + name + "(", ")"));
        }

        @Override
        public List<BytecodeExpression> children()
        {
            if (kind == InvocationKind.STATIC) {
                return arguments;
            }
            ArrayList<BytecodeExpression> children = new ArrayList<>(arguments.size() + 1);
            children.add(target);
            children.addAll(arguments);
            return List.copyOf(children);
        }
    }

    record NewInstance(ClassDesc type, MethodTypeDesc constructorType, List<BytecodeExpression> arguments)
            implements ExpressionNode
    {
        public NewInstance
        {
            requireNonNull(type, "type is null");
            requireNonNull(constructorType, "constructorType is null");
            arguments = List.copyOf(requireNonNull(arguments, "arguments is null"));
        }

        @Override
        public String formatOneLine()
        {
            return arguments.stream().map(Object::toString).collect(joining(", ", "new " + simpleName(type) + "(", ")"));
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return arguments;
        }
    }

    record DynamicConstant(ClassDesc type, DynamicConstantDesc<?> constant)
            implements ExpressionNode
    {
        public DynamicConstant
        {
            requireNonNull(type, "type is null");
            requireNonNull(constant, "constant is null");
        }

        @Override
        public String formatOneLine()
        {
            return "condy(" + constant.constantName() + ")";
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of();
        }
    }

    record BoundConstant(ClassDesc type, Object value)
            implements ExpressionNode
    {
        public BoundConstant
        {
            requireNonNull(type, "type is null");
            requireNonNull(value, "value is null");
        }

        @Override
        public String formatOneLine()
        {
            return "bound(" + simpleName(type) + ")";
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of();
        }
    }

    record BoundMethodHandleInvocation(
            ClassDesc type,
            MethodHandle handle,
            MethodTypeDesc methodType,
            List<BytecodeExpression> arguments)
            implements ExpressionNode
    {
        public BoundMethodHandleInvocation
        {
            requireNonNull(type, "type is null");
            requireNonNull(handle, "handle is null");
            requireNonNull(methodType, "methodType is null");
            arguments = List.copyOf(requireNonNull(arguments, "arguments is null"));
        }

        @Override
        public String formatOneLine()
        {
            return arguments.stream().map(Object::toString).collect(joining(", ", "boundMethodHandle(", ")"));
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return arguments;
        }
    }

    record LinkedMethodInvocation(
            ClassDesc type,
            CompiledUnit.LinkedMethod method,
            List<BytecodeExpression> arguments)
            implements ExpressionNode
    {
        public LinkedMethodInvocation
        {
            requireNonNull(type, "type is null");
            requireNonNull(method, "method is null");
            arguments = List.copyOf(requireNonNull(arguments, "arguments is null"));
        }

        @Override
        public String formatOneLine()
        {
            return arguments.stream().map(Object::toString).collect(joining(", ", method.name() + "(", ")"));
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return arguments;
        }
    }

    record InvokeDynamic(ClassDesc type, DynamicCallSiteDesc callSite, List<BytecodeExpression> arguments)
            implements ExpressionNode
    {
        public InvokeDynamic
        {
            requireNonNull(type, "type is null");
            requireNonNull(callSite, "callSite is null");
            arguments = List.copyOf(requireNonNull(arguments, "arguments is null"));
        }

        @Override
        public String formatOneLine()
        {
            return arguments.stream().map(Object::toString).collect(joining(", ", "invokedynamic " + callSite.invocationName() + "(", ")"));
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return arguments;
        }
    }

    record SetVariable(ClassDesc type, Variable variable, BytecodeExpression value)
            implements ExpressionNode
    {
        public SetVariable
        {
            requireNonNull(type, "type is null");
            requireNonNull(variable, "variable is null");
            requireNonNull(value, "value is null");
        }

        @Override
        public String formatOneLine()
        {
            return variable.name() + " = " + value;
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of(variable, value);
        }
    }

    record Increment(ClassDesc type, Variable variable)
            implements ExpressionNode
    {
        public Increment
        {
            requireNonNull(type, "type is null");
            requireNonNull(variable, "variable is null");
        }

        @Override
        public String formatOneLine()
        {
            return variable.name() + "++";
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of(variable);
        }
    }

    record Adapter(ClassDesc type, String keyword, BytecodeExpression value)
            implements ExpressionNode
    {
        public Adapter
        {
            requireNonNull(type, "type is null");
            requireNonNull(keyword, "keyword is null");
            requireNonNull(value, "value is null");
        }

        @Override
        public String formatOneLine()
        {
            return keyword.isEmpty() ? value.toString() : keyword + " " + value;
        }

        @Override
        public List<BytecodeExpression> children()
        {
            return List.of(value);
        }
    }
}
