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

import java.lang.classfile.Annotation;
import java.lang.classfile.MethodSignature;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.MANDATED;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PROTECTED;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.util.Objects.requireNonNull;

/// Authoring context for a record compact constructor. The super-constructor invocation and final
/// component-field assignments are supplied automatically.
public final class CompactConstructorDefinition
{
    private final List<RecordComponentDefinition> components;
    private final Map<RecordComponentDefinition, Parameter> parameterByComponent;
    private final Map<RecordComponentDefinition, BytecodeExpression> componentValues = new IdentityHashMap<>();
    private final MethodDefinition method;
    private boolean accessSet;

    CompactConstructorDefinition(ClassDesc declaringType, List<RecordComponentDefinition> components)
    {
        this.components = List.copyOf(requireNonNull(components, "components is null"));
        IdentityHashMap<RecordComponentDefinition, Parameter> parametersByComponent = new IdentityHashMap<>();
        ArrayList<Parameter> parameters = new ArrayList<>(components.size());
        for (RecordComponentDefinition component : components) {
            Parameter parameter = Parameter.arg(component.name(), component.type());
            parameters.add(parameter);
            parametersByComponent.put(component, parameter);
        }
        parameterByComponent = Collections.unmodifiableMap(parametersByComponent);
        method = new MethodDefinition(ClassKind.RECORD, declaringType, "<init>", CD_void, parameters);
    }

    public CompactConstructorDefinition access(AccessFlag... access)
    {
        method.access(access);
        accessSet = true;
        return this;
    }

    public Parameter parameter(RecordComponentDefinition component)
    {
        Parameter parameter = parameterByComponent.get(requireNonNull(component, "component is null"));
        if (parameter == null) {
            throw new IllegalArgumentException("Record component belongs to another record: " + component);
        }
        return parameter;
    }

    /// Overrides the final value assigned to a component after the compact body executes.
    public CompactConstructorDefinition initialize(RecordComponentDefinition component, BytecodeExpression value)
    {
        Parameter parameter = parameter(component);
        requireNonNull(value, "value is null");
        BytecodeExpressions.requireAssignable(parameter.type(), value.type(), "record component value");
        if (componentValues.putIfAbsent(component, value) != null) {
            throw new IllegalStateException("Record component value is already set: " + component.name());
        }
        return this;
    }

    public Variable thisVariable()
    {
        return method.thisVariable();
    }

    public CodeBlock.Builder body()
    {
        return method.body();
    }

    public CompactConstructorDefinition signature(MethodSignature signature)
    {
        method.signature(signature);
        return this;
    }

    public CompactConstructorDefinition comment(String comment)
    {
        method.comment(comment);
        return this;
    }

    public CompactConstructorDefinition addAnnotation(Annotation annotation)
    {
        method.addAnnotation(annotation);
        return this;
    }

    public CompactConstructorDefinition addInvisibleAnnotation(Annotation annotation)
    {
        method.addInvisibleAnnotation(annotation);
        return this;
    }

    public MethodTypeDesc methodType()
    {
        return method.methodType();
    }

    MethodDefinition.Model build(Set<AccessFlag> classAccess)
    {
        MethodDefinition.Model authored = method.build();
        StructuredDepth.validate(authored.body());
        componentValues.values().forEach(StructuredDepth::validate);
        if (containsReturn(authored.body(), Collections.newSetFromMap(new IdentityHashMap<>()))) {
            throw new IllegalArgumentException("Compact constructor body cannot return");
        }
        if (componentValues.values().stream()
                .anyMatch(value -> containsReturn(value, Collections.newSetFromMap(new IdentityHashMap<>())))) {
            throw new IllegalArgumentException("Compact constructor component value cannot return");
        }

        Set<AccessFlag> constructorAccess = authored.access();
        if (!accessSet) {
            EnumSet<AccessFlag> visibility = EnumSet.noneOf(AccessFlag.class);
            classAccess.stream()
                    .filter(flag -> flag == PUBLIC || flag == PROTECTED || flag == PRIVATE)
                    .forEach(visibility::add);
            constructorAccess = Set.copyOf(visibility);
        }

        ArrayList<Statement> statements = new ArrayList<>();
        statements.addAll(CodeBlock.blockBuilder().invokeSuperConstructor().build().statements());
        statements.addAll(authored.body().statements());
        CodeBlock.Builder suffix = CodeBlock.blockBuilder();
        for (RecordComponentDefinition component : components) {
            BytecodeExpression value = componentValues.getOrDefault(component, parameter(component));
            suffix.append(component.initialize(authored.thisVariable(), value));
        }
        suffix.ret();
        statements.addAll(suffix.build().statements());
        return new MethodDefinition.Model(
                authored.declaringType(),
                constructorAccess,
                authored.name(),
                authored.methodType(),
                authored.parameters(),
                authored.parameterMetadata(),
                Optional.of(authored.thisVariable()),
                Optional.of(authored.body().withStatements(statements)),
                authored.exceptions(),
                authored.signature(),
                authored.comment(),
                authored.visibleAnnotations(),
                authored.invisibleAnnotations())
                .addParameterAccess(MANDATED);
    }

    private static boolean containsReturn(Statement statement, Set<SyntheticExpression> activeExpressions)
    {
        return switch (statement) {
            case BytecodeExpression expression -> containsReturn(expression, activeExpressions);
            case CodeBlock block -> block.statements().stream().anyMatch(value -> containsReturn(value, activeExpressions));
            case DoWhileLoop loop -> containsReturn(loop.body(), activeExpressions) || containsReturn(loop.condition(), activeExpressions);
            case ForLoop loop -> containsReturn(loop.initializer(), activeExpressions) ||
                    containsReturn(loop.condition(), activeExpressions) ||
                    containsReturn(loop.update(), activeExpressions) ||
                    containsReturn(loop.body(), activeExpressions);
            case IfStatement ifStatement -> containsReturn(ifStatement.condition(), activeExpressions) ||
                    containsReturn(ifStatement.ifTrue(), activeExpressions) ||
                    containsReturn(ifStatement.ifFalse(), activeExpressions);
            case LoopJump _,
                 Statements.Comment _,
                 Statements.ConstructorInvocation _,
                 Statements.Declaration _,
                 Statements.Jump _,
                 Statements.LabelBinding _ -> false;
            case SwitchStatement switchStatement -> containsReturn(switchStatement.expression(), activeExpressions) ||
                    switchStatement.cases().stream().anyMatch(value -> containsReturn(value.body(), activeExpressions)) ||
                    containsReturn(switchStatement.defaultCase(), activeExpressions);
            case TryCatch tryCatch -> containsReturn(tryCatch.tryBlock(), activeExpressions) ||
                    tryCatch.catches().stream().anyMatch(value -> containsReturn(value.body(), activeExpressions)) ||
                    tryCatch.finallyBlock().map(value -> containsReturn(value, activeExpressions)).orElse(false);
            case WhileLoop loop -> containsReturn(loop.condition(), activeExpressions) || containsReturn(loop.body(), activeExpressions);
            case Statements.Expression expression -> containsReturn(expression.expression(), activeExpressions);
            case Statements.InitializedDeclaration declaration -> containsReturn(declaration.initializer(), activeExpressions);
        };
    }

    private static boolean containsReturn(BytecodeExpression expression, Set<SyntheticExpression> activeExpressions)
    {
        return switch (expression) {
            case LocalValue _ -> false;
            case CoreExpression core -> (core.node() instanceof ExpressionNode.Adapter adapter && adapter.keyword().equals("return")) ||
                    (core.node() instanceof ExpressionNode.Constant constant && constant.type().equals(CD_void) && constant.rendering().equals("return")) ||
                    core.node().children().stream().anyMatch(child -> containsReturn(child, activeExpressions));
            case SyntheticExpression synthetic -> {
                if (!activeExpressions.add(synthetic)) {
                    throw new IllegalArgumentException("Recursive synthetic expression: " + synthetic);
                }
                try {
                    ExpressionPlan expansion = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                    yield containsReturn(expansion.setup(), activeExpressions) || containsReturn(expansion.value(), activeExpressions);
                }
                finally {
                    activeExpressions.remove(synthetic);
                }
            }
        };
    }
}
