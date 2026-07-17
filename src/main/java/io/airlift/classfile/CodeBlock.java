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
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.airlift.classfile.DescriptorUtils.classDesc;
import static io.airlift.classfile.DescriptorUtils.methodType;
import static io.airlift.classfile.Identity.different;
import static io.airlift.classfile.Statements.ConstructorTarget.SUPER;
import static io.airlift.classfile.Statements.ConstructorTarget.THIS;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/// Immutable, reusable sequence of high-level statements.
public final class CodeBlock
        implements Statement
{
    private final Object scope;
    private final List<Statement> statements;
    private final List<CodeLabel> labels;
    private final String description;

    private CodeBlock(Object scope, List<Statement> statements, List<CodeLabel> labels, String description)
    {
        this.scope = requireNonNull(scope, "scope is null");
        this.statements = List.copyOf(requireNonNull(statements, "statements is null"));
        this.labels = List.copyOf(requireNonNull(labels, "labels is null"));
        this.description = description;
    }

    public static Builder blockBuilder()
    {
        return new Builder();
    }

    public static CodeBlock block()
    {
        return blockBuilder().build();
    }

    public static CodeBlock block(Statement... statements)
    {
        Builder builder = blockBuilder();
        for (Statement statement : statements) {
            builder.append(statement);
        }
        return builder.build();
    }

    public String description()
    {
        return description;
    }

    public boolean isEmpty()
    {
        return statements.isEmpty();
    }

    List<Statement> statements()
    {
        return statements;
    }

    Object scope()
    {
        return scope;
    }

    List<CodeLabel> labels()
    {
        return labels;
    }

    CodeBlock withStatements(List<Statement> statements)
    {
        return new CodeBlock(scope, statements, labels, description);
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder("{\n");
        for (Statement statement : statements) {
            builder.append("    ").append(format(statement).replace("\n", "\n    ")).append('\n');
        }
        return builder.append('}').toString();
    }

    private static String format(Statement statement)
    {
        return switch (statement) {
            case BytecodeExpression expression -> expression.toString();
            case CodeBlock block -> block.toString();
            case DoWhileLoop doWhileLoop -> doWhileLoop.toString();
            case ForLoop forLoop -> forLoop.toString();
            case IfStatement ifStatement -> ifStatement.toString();
            case LoopJump loopJump -> loopJump.toString();
            case SwitchStatement switchStatement -> switchStatement.toString();
            case TryCatch tryCatch -> tryCatch.toString();
            case WhileLoop whileLoop -> whileLoop.toString();
            case Statements.Declaration declaration -> declaration.variable().type().displayName() + " " + declaration.variable().name() + ";";
            case Statements.InitializedDeclaration declaration -> declaration.variable().type().displayName() + " " + declaration.variable().name() + " = " + declaration.initializer() + ";";
            case Statements.Expression expression -> expression.expression().toString();
            case Statements.Comment comment -> "// " + comment.text();
            case Statements.ConstructorInvocation invocation -> invocation.toString();
            case Statements.Jump jump -> jump.toString();
            case Statements.LabelBinding labelBinding -> labelBinding.toString();
        };
    }

    String toInlineString()
    {
        return statements.stream()
                .map(CodeBlock::format)
                .map(CodeBlock::withoutTerminatingSemicolon)
                .collect(joining(", "));
    }

    private static String withoutTerminatingSemicolon(String value)
    {
        return value.endsWith(";") ? value.substring(0, value.length() - 1) : value;
    }

    /// Mutable lexical-scope authoring context for an immutable [CodeBlock].
    public static final class Builder
    {
        private final Object scope = new Object();
        private final Map<String, Variable> variables = new LinkedHashMap<>();
        private final Map<String, CodeLabel> labels = new LinkedHashMap<>();
        private final List<Statement> statements = new ArrayList<>();
        private String description;

        private Builder() {}

        /// Declares an uninitialized local owned by this block's lexical scope.
        public Variable declare(Class<?> type, String name)
        {
            return declare(classDesc(type), name);
        }

        /// Declares an uninitialized local with a symbolic type owned by this block's lexical scope.
        public Variable declare(ClassDesc type, String name)
        {
            Variable variable = new Variable(name, type, scope);
            if (variables.putIfAbsent(name, variable) != null) {
                throw new IllegalArgumentException("Variable already declared in this block: " + name);
            }
            statements.add(new Statements.Declaration(variable));
            return variable;
        }

        /// Declares and initializes a local, inferring its type from the initial value.
        public Variable declare(String name, BytecodeExpression initialValue)
        {
            requireNonNull(initialValue, "initialValue is null");
            Variable variable = new Variable(name, initialValue.type(), scope);
            if (variables.putIfAbsent(name, variable) != null) {
                throw new IllegalArgumentException("Variable already declared in this block: " + name);
            }
            statements.add(new Statements.InitializedDeclaration(variable, initialValue));
            return variable;
        }

        /// Appends a reusable logical statement. Attaching a statement does not transfer ownership
        /// or assign labels and local-variable slots.
        public Builder append(Statement statement)
        {
            requireNonNull(statement, "statement is null");
            switch (statement) {
                case BytecodeExpression expression -> statements.add(new Statements.Expression(expression));
                case CodeBlock block -> statements.add(block);
                case DoWhileLoop doWhileLoop -> statements.add(doWhileLoop);
                case ForLoop forLoop -> statements.add(forLoop);
                case IfStatement ifStatement -> statements.add(ifStatement);
                case LoopJump loopJump -> statements.add(loopJump);
                case SwitchStatement switchStatement -> statements.add(switchStatement);
                case TryCatch tryCatch -> statements.add(tryCatch);
                case WhileLoop whileLoop -> statements.add(whileLoop);
                case Statements.Comment comment -> statements.add(comment);
                case Statements.ConstructorInvocation invocation -> statements.add(invocation);
                case Statements.Declaration declaration -> statements.add(declaration);
                case Statements.Expression expression -> statements.add(expression);
                case Statements.InitializedDeclaration declaration -> statements.add(declaration);
                case Statements.Jump jump -> statements.add(jump);
                case Statements.LabelBinding labelBinding -> statements.add(labelBinding);
            }
            return this;
        }

        /// Invokes the direct superclass constructor whose parameter types are inferred from the
        /// arguments. This operation is only valid while authoring a constructor.
        public Builder invokeSuperConstructor(BytecodeExpression... arguments)
        {
            return invokeSuperConstructor(inferredConstructorType(arguments), arguments);
        }

        public Builder invokeSuperConstructor(Constructor<?> constructor, BytecodeExpression... arguments)
        {
            requireNonNull(constructor, "constructor is null");
            return constructorInvocation(SUPER, classDesc(constructor.getDeclaringClass()), methodType(constructor), arguments);
        }

        public Builder invokeSuperConstructor(MethodTypeDesc constructorType, BytecodeExpression... arguments)
        {
            return constructorInvocation(SUPER, null, constructorType, arguments);
        }

        /// Delegates to another constructor in the generated class, inferring its parameter types
        /// from the arguments. This operation is only valid while authoring a constructor.
        public Builder invokeThisConstructor(BytecodeExpression... arguments)
        {
            return invokeThisConstructor(inferredConstructorType(arguments), arguments);
        }

        public Builder invokeThisConstructor(MethodTypeDesc constructorType, BytecodeExpression... arguments)
        {
            return constructorInvocation(THIS, null, constructorType, arguments);
        }

        private Builder constructorInvocation(
                Statements.ConstructorTarget target,
                ClassDesc declaredOwner,
                MethodTypeDesc constructorType,
                BytecodeExpression... arguments)
        {
            requireNonNull(constructorType, "constructorType is null");
            if (!constructorType.returnType().equals(CD_void)) {
                throw new IllegalArgumentException("constructor descriptor must return void: " + constructorType);
            }
            List<BytecodeExpression> argumentList = List.copyOf(Arrays.asList(requireNonNull(arguments, "arguments is null")));
            BytecodeExpressions.validateArguments(constructorType, argumentList);
            return append(new Statements.ConstructorInvocation(target, declaredOwner, constructorType, argumentList));
        }

        private static MethodTypeDesc inferredConstructorType(BytecodeExpression[] arguments)
        {
            requireNonNull(arguments, "arguments is null");
            return MethodTypeDesc.of(CD_void, Arrays.stream(arguments)
                    .map(argument -> requireNonNull(argument, "argument is null").type())
                    .toList());
        }

        public Builder ret()
        {
            return append(BytecodeExpressions.ret());
        }

        public Builder ret(BytecodeExpression value)
        {
            return append(requireNonNull(value, "value is null").ret());
        }

        public Builder throwObject(BytecodeExpression value)
        {
            return append(requireNonNull(value, "value is null").throwObject());
        }

        /// Creates an advanced symbolic control-flow target owned by this block.
        ///
        /// Prefer structured control flow when possible. A label must be marked exactly once before
        /// the block is built.
        public CodeLabel label(String name)
        {
            CodeLabel label = new CodeLabel(name, scope);
            if (labels.putIfAbsent(name, label) != null) {
                throw new IllegalArgumentException("Label already declared in this block: " + name);
            }
            return label;
        }

        /// Binds a label owned by this block at the current position.
        public Builder mark(CodeLabel label)
        {
            requireNonNull(label, "label is null");
            if (different(label.owner(), scope)) {
                throw new IllegalArgumentException("Label is owned by another block: " + label.name());
            }
            statements.add(new Statements.LabelBinding(label));
            return this;
        }

        /// Jumps to a visible symbolic label. Completed-model validation verifies label scope.
        public Builder jump(CodeLabel target)
        {
            statements.add(new Statements.Jump(requireNonNull(target, "target is null")));
            return this;
        }

        public Builder comment(String text)
        {
            statements.add(new Statements.Comment(requireNonNull(text, "text is null")));
            return this;
        }

        public Builder description(String description)
        {
            if (this.description != null) {
                throw new IllegalStateException("description is already set");
            }
            this.description = requireNonNull(description, "description is null");
            return this;
        }

        boolean isEmpty()
        {
            return statements.isEmpty();
        }

        /// Creates an immutable reusable snapshot and validates labels owned by this block.
        /// Local-variable scope and cross-block references are validated when the containing class
        /// model is built.
        public CodeBlock build()
        {
            for (CodeLabel label : labels.values()) {
                long bindings = statements.stream()
                        .filter(Statements.LabelBinding.class::isInstance)
                        .map(Statements.LabelBinding.class::cast)
                        .filter(binding -> binding.label() == label)
                        .count();
                if (bindings == 0) {
                    throw new IllegalArgumentException("Label is not bound: " + label.name());
                }
                if (bindings > 1) {
                    throw new IllegalArgumentException("Label is bound more than once: " + label.name());
                }
            }
            return new CodeBlock(scope, statements, List.copyOf(labels.values()), description);
        }
    }
}
