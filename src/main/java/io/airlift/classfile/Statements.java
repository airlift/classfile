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
import java.util.List;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

final class Statements
{
    private Statements() {}

    enum ConstructorTarget
    {
        SUPER,
        THIS,
    }

    record ConstructorInvocation(
            ConstructorTarget target,
            ClassDesc declaredOwner,
            MethodTypeDesc constructorType,
            List<BytecodeExpression> arguments)
            implements Statement
    {
        ConstructorInvocation
        {
            requireNonNull(target, "target is null");
            requireNonNull(constructorType, "constructorType is null");
            arguments = List.copyOf(requireNonNull(arguments, "arguments is null"));
        }

        @Override
        public String toString()
        {
            return arguments.stream()
                    .map(Object::toString)
                    .collect(joining(", ", target == ConstructorTarget.SUPER ? "super(" : "this(", ");"));
        }
    }

    record Expression(BytecodeExpression expression)
            implements Statement
    {
        Expression
        {
            requireNonNull(expression, "expression is null");
        }
    }

    record Declaration(Variable variable)
            implements Statement
    {
        Declaration
        {
            requireNonNull(variable, "variable is null");
        }
    }

    record InitializedDeclaration(Variable variable, BytecodeExpression initializer)
            implements Statement
    {
        InitializedDeclaration
        {
            requireNonNull(variable, "variable is null");
            requireNonNull(initializer, "initializer is null");
        }
    }

    record Comment(String text)
            implements Statement
    {
        Comment
        {
            requireNonNull(text, "text is null");
        }
    }

    record LabelBinding(CodeLabel label)
            implements Statement
    {
        LabelBinding
        {
            requireNonNull(label, "label is null");
        }

        @Override
        public String toString()
        {
            return label + ":";
        }
    }

    record Jump(CodeLabel target)
            implements Statement
    {
        Jump
        {
            requireNonNull(target, "target is null");
        }

        @Override
        public String toString()
        {
            return "goto " + target + ";";
        }
    }
}
