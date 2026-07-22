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
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.lang.reflect.AccessFlag.SUPER;
import static java.lang.reflect.AccessFlag.SYNTHETIC;
import static java.util.Objects.requireNonNull;

final class ClassSharder
{
    private static final int MAX_HELPERS_PER_CLASS = 96;
    private static final int MAX_CONSTANT_REFERENCES_PER_CLASS = 20_000;

    private ClassSharder() {}

    static Result shard(ClassModel definition, Set<String> generatedMethods, LinkageContext linkage)
    {
        requireNonNull(generatedMethods, "generatedMethods is null");
        requireNonNull(linkage, "linkage is null");
        List<MethodDefinition.Model> generated = definition.methods().stream()
                .filter(method -> generatedMethods.contains(method.name()))
                .toList();
        int constantReferences = generated.stream().mapToInt(ClassSharder::constantReferences).sum();
        if (generated.size() <= MAX_HELPERS_PER_CLASS && constantReferences <= MAX_CONSTANT_REFERENCES_PER_CLASS) {
            return new Result(definition, List.of());
        }

        Set<MethodKey> generatedKeys = generated.stream().map(MethodKey::of).collect(Collectors.toSet());
        LinkedHashMap<MethodKey, GeneratedReferences> references = new LinkedHashMap<>();
        for (MethodDefinition.Model method : generated) {
            references.put(MethodKey.of(method), generatedReferences(method, definition.type(), generatedKeys));
        }
        if (hasForwardGeneratedDependencies(generated, references)) {
            return new Result(definition, List.of());
        }
        Set<MethodKey> movable = new LinkedHashSet<>();
        for (MethodDefinition.Model method : generated) {
            if (isMovable(method, definition.type(), generatedKeys, linkage, references.get(MethodKey.of(method)))) {
                movable.add(MethodKey.of(method));
            }
        }
        // A caller-sensitive helper keeps the generated family together. Ordered calls to
        // earlier generated helpers are movable because every cross-class dependency points
        // toward an already defined companion; forward dependencies remain pinned above.
        if (movable.size() != generated.size()) {
            return new Result(definition, List.of());
        }
        LinkedHashMap<MethodKey, ClassDesc> placements = new LinkedHashMap<>();
        ArrayList<List<MethodDefinition.Model>> groups = new ArrayList<>();
        ArrayList<MethodDefinition.Model> group = new ArrayList<>(MAX_HELPERS_PER_CLASS);
        int groupConstants = 0;
        for (MethodDefinition.Model method : generated) {
            int methodConstants = constantReferences(method);
            if (!group.isEmpty() && (group.size() == MAX_HELPERS_PER_CLASS || groupConstants + methodConstants > MAX_CONSTANT_REFERENCES_PER_CLASS)) {
                groups.add(List.copyOf(group));
                group.clear();
                groupConstants = 0;
            }
            group.add(method);
            groupConstants += methodConstants;
        }
        if (!group.isEmpty()) {
            groups.add(List.copyOf(group));
        }

        for (int index = 0; index < groups.size(); index++) {
            ClassDesc owner = auxiliaryType(definition.type(), index + 1);
            groups.get(index).forEach(method -> placements.put(MethodKey.of(method), owner));
        }

        List<MethodDefinition.Model> primaryMethods = definition.methods().stream()
                .filter(method -> !placements.containsKey(MethodKey.of(method)))
                .map(method -> rewrite(method, definition.type(), definition.type(), placements, method.access()))
                .toList();
        ClassModel primary = copy(definition, definition.type(), definition.access(), primaryMethods);

        ArrayList<ClassModel> auxiliaries = new ArrayList<>(groups.size());
        for (int index = 0; index < groups.size(); index++) {
            ClassDesc owner = auxiliaryType(definition.type(), index + 1);
            List<MethodDefinition.Model> methods = groups.get(index).stream()
                    .map(method -> rewrite(method, definition.type(), owner, placements, Set.of(PUBLIC, STATIC, SYNTHETIC)))
                    .toList();
            auxiliaries.add(new ClassModel(
                    Set.of(PUBLIC, FINAL, SUPER, SYNTHETIC),
                    owner,
                    CD_Object,
                    List.of(),
                    List.of(),
                    methods,
                    ClassKind.CLASS,
                    List.of(),
                    Optional.empty(),
                    definition.sourceFile(),
                    List.of(),
                    List.of()));
        }
        return new Result(primary, auxiliaries);
    }

    record Result(ClassModel primary, List<ClassModel> auxiliaries) {}

    private static MethodDefinition.Model rewrite(
            MethodDefinition.Model method,
            ClassDesc logicalOwner,
            ClassDesc owner,
            Map<MethodKey, ClassDesc> placements,
            Set<AccessFlag> access)
    {
        return PhysicalRewriter.method(method, owner, access, (invoke, currentOwner) -> {
            MethodKey key = new MethodKey(invoke.name(), invoke.methodType());
            ClassDesc targetOwner = placements.get(key);
            if (invoke.kind() != ExpressionNode.InvocationKind.STATIC || !invoke.owner().equals(logicalOwner) || targetOwner == null) {
                return invoke;
            }
            if (targetOwner.equals(currentOwner)) {
                return new ExpressionNode.Invoke(
                        invoke.type(),
                        invoke.kind(),
                        null,
                        targetOwner,
                        invoke.name(),
                        invoke.methodType(),
                        invoke.arguments());
            }
            return new ExpressionNode.LinkedMethodInvocation(
                    invoke.type(),
                    new CompiledUnit.LinkedMethod(targetOwner, invoke.name(), invoke.methodType()),
                    invoke.arguments());
        });
    }

    private static boolean isMovable(
            MethodDefinition.Model method,
            ClassDesc logicalOwner,
            Set<MethodKey> generated,
            LinkageContext linkage,
            GeneratedReferences references)
    {
        return !containsCallerSensitive(method.body(), logicalOwner, generated, linkage) &&
                (!linkage.hiddenClass() || !references.logicalTypeOutsideGeneratedCalls());
    }

    private static boolean hasForwardGeneratedDependencies(List<MethodDefinition.Model> generated, Map<MethodKey, GeneratedReferences> references)
    {
        LinkedHashMap<MethodKey, Integer> positions = new LinkedHashMap<>();
        for (int index = 0; index < generated.size(); index++) {
            positions.put(MethodKey.of(generated.get(index)), index);
        }
        for (int index = 0; index < generated.size(); index++) {
            for (MethodKey dependency : references.get(MethodKey.of(generated.get(index))).dependencies()) {
                if (positions.get(dependency) > index) {
                    return true;
                }
            }
        }
        return false;
    }

    private static GeneratedReferences generatedReferences(MethodDefinition.Model method, ClassDesc logicalOwner, Set<MethodKey> generated)
    {
        GeneratedReferenceScanner scanner = new GeneratedReferenceScanner(logicalOwner, generated);
        scanner.visit(method);
        return scanner.result();
    }

    private record GeneratedReferences(Set<MethodKey> dependencies, boolean logicalTypeOutsideGeneratedCalls)
    {
        private GeneratedReferences
        {
            dependencies = Set.copyOf(requireNonNull(dependencies, "dependencies is null"));
        }
    }

    private static boolean containsCallerSensitive(CodeBlock block, ClassDesc logicalOwner, Set<MethodKey> generated, LinkageContext linkage)
    {
        return block.statements().stream().anyMatch(statement -> containsCallerSensitive(statement, logicalOwner, generated, linkage));
    }

    private static boolean containsCallerSensitive(Statement statement, ClassDesc logicalOwner, Set<MethodKey> generated, LinkageContext linkage)
    {
        return switch (statement) {
            case BytecodeExpression expression -> containsCallerSensitive(expression, logicalOwner, generated, linkage);
            case Statements.Expression value -> containsCallerSensitive(value.expression(), logicalOwner, generated, linkage);
            case Statements.InitializedDeclaration value -> containsCallerSensitive(value.initializer(), logicalOwner, generated, linkage);
            case Statements.ConstructorInvocation _,
                 TryCatch _,
                 LoopJump _,
                 Statements.Jump _,
                 Statements.LabelBinding _ -> true;
            case CodeBlock value -> containsCallerSensitive(value, logicalOwner, generated, linkage);
            case IfStatement value -> containsCallerSensitive(value.condition(), logicalOwner, generated, linkage) || containsCallerSensitive(value.ifTrue(), logicalOwner, generated, linkage) || containsCallerSensitive(value.ifFalse(), logicalOwner, generated, linkage);
            case ForLoop value -> containsCallerSensitive(value.initializer(), logicalOwner, generated, linkage) || containsCallerSensitive(value.condition(), logicalOwner, generated, linkage) || containsCallerSensitive(value.update(), logicalOwner, generated, linkage) || containsCallerSensitive(value.body(), logicalOwner, generated, linkage);
            case WhileLoop value -> containsCallerSensitive(value.condition(), logicalOwner, generated, linkage) || containsCallerSensitive(value.body(), logicalOwner, generated, linkage);
            case DoWhileLoop value -> containsCallerSensitive(value.body(), logicalOwner, generated, linkage) || containsCallerSensitive(value.condition(), logicalOwner, generated, linkage);
            case SwitchStatement value -> containsCallerSensitive(value.expression(), logicalOwner, generated, linkage) || value.cases().stream().anyMatch(caseValue -> containsCallerSensitive(caseValue.body(), logicalOwner, generated, linkage)) || containsCallerSensitive(value.defaultCase(), logicalOwner, generated, linkage);
            case Statements.Comment _,
                 Statements.Declaration _ -> false;
        };
    }

    private static boolean containsCallerSensitive(BytecodeExpression expression, ClassDesc logicalOwner, Set<MethodKey> generated, LinkageContext linkage)
    {
        return switch (expression) {
            case LocalValue _ -> false;
            case SyntheticExpression synthetic -> {
                ExpressionPlan expansion = synthetic.expansion(ExpansionContext.INSTANCE);
                yield containsCallerSensitive(expansion.setup(), logicalOwner, generated, linkage) || containsCallerSensitive(expansion.value(), logicalOwner, generated, linkage);
            }
            case CoreExpression core -> {
                boolean sensitive = switch (core.node()) {
                    case ExpressionNode.FieldGet field -> field.owner().equals(logicalOwner) || !linkage.isPublicField(field.owner(), field.name(), field.type());
                    case ExpressionNode.FieldSet field -> field.owner().equals(logicalOwner) || !linkage.isPublicField(field.owner(), field.name(), field.fieldType());
                    case ExpressionNode.NewInstance instance -> instance.type().equals(logicalOwner) || !linkage.isPublicConstructor(instance.type(), instance.constructorType());
                    case ExpressionNode.DynamicConstant _,
                         ExpressionNode.InvokeDynamic _ -> true;
                    case ExpressionNode.Invoke invoke -> {
                        boolean generatedInvocation = invoke.owner().equals(logicalOwner) && generated.contains(new MethodKey(invoke.name(), invoke.methodType()));
                        yield !generatedInvocation &&
                                (invoke.owner().equals(logicalOwner) ||
                                        invoke.kind() == ExpressionNode.InvocationKind.SPECIAL ||
                                        isCallerSensitiveOwner(invoke.owner()) ||
                                        linkage.isCallerSensitiveMethod(invoke.owner(), invoke.name(), invoke.methodType()) ||
                                        !linkage.isPublicMethod(invoke.owner(), invoke.name(), invoke.methodType()));
                    }
                    case ExpressionNode.Constant _,
                         ExpressionNode.Binary _,
                         ExpressionNode.Unary _,
                         ExpressionNode.Cast _,
                         ExpressionNode.InstanceOf _,
                         ExpressionNode.InlineIf _,
                         ExpressionNode.ArrayLength _,
                         ExpressionNode.ArrayGet _,
                         ExpressionNode.ArraySet _,
                         ExpressionNode.NewArray _,
                         ExpressionNode.BoundConstant _,
                         ExpressionNode.BoundMethodHandleInvocation _,
                         ExpressionNode.LinkedMethodInvocation _,
                         ExpressionNode.SetVariable _,
                         ExpressionNode.Increment _,
                         ExpressionNode.Adapter _ -> false;
                };
                yield sensitive || core.node().children().stream().anyMatch(child -> containsCallerSensitive(child, logicalOwner, generated, linkage));
            }
        };
    }

    private static final class GeneratedReferenceScanner
    {
        private final ClassDesc logicalOwner;
        private final Set<MethodKey> generated;
        private final Set<MethodKey> dependencies = new LinkedHashSet<>();
        private boolean logicalTypeOutsideGeneratedCalls;

        private GeneratedReferenceScanner(ClassDesc logicalOwner, Set<MethodKey> generated)
        {
            this.logicalOwner = requireNonNull(logicalOwner, "logicalOwner is null");
            this.generated = requireNonNull(generated, "generated is null");
        }

        private GeneratedReferences result()
        {
            return new GeneratedReferences(dependencies, logicalTypeOutsideGeneratedCalls);
        }

        private void visit(MethodDefinition.Model method)
        {
            reference(method.methodType());
            method.exceptions().forEach(this::reference);
            if (method.signature().isPresent() ||
                    !method.visibleAnnotations().isEmpty() ||
                    !method.invisibleAnnotations().isEmpty() ||
                    method.parameterMetadata().stream().anyMatch(parameter -> !parameter.visibleAnnotations().isEmpty() || !parameter.invisibleAnnotations().isEmpty())) {
                logicalTypeOutsideGeneratedCalls = true;
            }
            visit(method.body());
        }

        private void visit(CodeBlock block)
        {
            block.statements().forEach(this::visit);
        }

        private void visit(Statement statement)
        {
            switch (statement) {
                case BytecodeExpression expression -> visit(expression);
                case Statements.Expression value -> visit(value.expression());
                case Statements.Declaration value -> reference(value.variable().type());
                case Statements.InitializedDeclaration value -> {
                    reference(value.variable().type());
                    visit(value.initializer());
                }
                case Statements.ConstructorInvocation value -> {
                    reference(value.declaredOwner());
                    reference(value.constructorType());
                    value.arguments().forEach(this::visit);
                }
                case CodeBlock value -> visit(value);
                case IfStatement value -> {
                    visit(value.condition());
                    visit(value.ifTrue());
                    visit(value.ifFalse());
                }
                case ForLoop value -> {
                    visit(value.initializer());
                    visit(value.condition());
                    visit(value.update());
                    visit(value.body());
                }
                case WhileLoop value -> {
                    visit(value.condition());
                    visit(value.body());
                }
                case DoWhileLoop value -> {
                    visit(value.body());
                    visit(value.condition());
                }
                case SwitchStatement value -> {
                    visit(value.expression());
                    value.cases().forEach(caseValue -> visit(caseValue.body()));
                    visit(value.defaultCase());
                }
                case TryCatch value -> {
                    visit(value.tryBlock());
                    value.catches().forEach(catchClause -> {
                        reference(catchClause.exceptionType());
                        visit(catchClause.body());
                    });
                    value.finallyBlock().ifPresent(this::visit);
                }
                case LoopJump _,
                     Statements.Comment _,
                     Statements.Jump _,
                     Statements.LabelBinding _ -> {}
            }
        }

        private void visit(BytecodeExpression expression)
        {
            switch (expression) {
                case LocalValue local -> reference(local.type());
                case SyntheticExpression synthetic -> {
                    ExpressionPlan expansion = synthetic.expansion(ExpansionContext.INSTANCE);
                    visit(expansion.setup());
                    visit(expansion.value());
                }
                case CoreExpression core -> {
                    ExpressionNode node = core.node();
                    reference(node.type());
                    switch (node) {
                        case ExpressionNode.Constant value -> {
                            if (value.value() instanceof ConstantDesc constant) {
                                reference(constant);
                            }
                        }
                        case ExpressionNode.InstanceOf value -> reference(value.testType());
                        case ExpressionNode.FieldGet value -> reference(value.owner());
                        case ExpressionNode.FieldSet value -> {
                            reference(value.owner());
                            reference(value.fieldType());
                        }
                        case ExpressionNode.Invoke value -> {
                            MethodKey key = new MethodKey(value.name(), value.methodType());
                            boolean generatedInvocation = value.kind() == ExpressionNode.InvocationKind.STATIC &&
                                    value.owner().equals(logicalOwner) &&
                                    generated.contains(key);
                            if (generatedInvocation) {
                                dependencies.add(key);
                            }
                            else {
                                reference(value.owner());
                            }
                            reference(value.methodType());
                        }
                        case ExpressionNode.NewInstance value -> reference(value.constructorType());
                        case ExpressionNode.DynamicConstant value -> reference(value.constant());
                        case ExpressionNode.BoundMethodHandleInvocation value -> reference(value.methodType());
                        case ExpressionNode.LinkedMethodInvocation value -> {
                            reference(value.method().owner());
                            reference(value.method().type());
                        }
                        case ExpressionNode.InvokeDynamic value -> {
                            reference(value.callSite().invocationType());
                            reference(value.callSite().bootstrapMethod());
                            Arrays.stream(value.callSite().bootstrapArgs()).forEach(this::reference);
                        }
                        case ExpressionNode.Binary _,
                             ExpressionNode.Unary _,
                             ExpressionNode.Cast _,
                             ExpressionNode.InlineIf _,
                             ExpressionNode.ArrayLength _,
                             ExpressionNode.ArrayGet _,
                             ExpressionNode.ArraySet _,
                             ExpressionNode.NewArray _,
                             ExpressionNode.BoundConstant _,
                             ExpressionNode.SetVariable _,
                             ExpressionNode.Increment _,
                             ExpressionNode.Adapter _ -> {}
                    }
                    node.children().forEach(this::visit);
                }
            }
        }

        private void reference(ConstantDesc constant)
        {
            logicalTypeOutsideGeneratedCalls |= referencesLogicalType(constant, logicalOwner);
        }

        private void reference(MethodTypeDesc type)
        {
            logicalTypeOutsideGeneratedCalls |= referencesLogicalType(type, logicalOwner);
        }

        private void reference(ClassDesc type)
        {
            logicalTypeOutsideGeneratedCalls |= referencesLogicalType(type, logicalOwner);
        }
    }

    private static boolean referencesLogicalType(ConstantDesc constant, ClassDesc logicalOwner)
    {
        if (constant instanceof ClassDesc type) {
            return referencesLogicalType(type, logicalOwner);
        }
        if (constant instanceof MethodTypeDesc type) {
            return referencesLogicalType(type, logicalOwner);
        }
        if (constant instanceof DirectMethodHandleDesc handle) {
            return referencesLogicalType(handle.owner(), logicalOwner) || referencesLogicalType(handle.invocationType(), logicalOwner);
        }
        if (constant instanceof MethodHandleDesc handle) {
            return referencesLogicalType(handle.invocationType(), logicalOwner);
        }
        if (constant instanceof DynamicConstantDesc<?> dynamic) {
            return referencesLogicalType(dynamic.constantType(), logicalOwner) ||
                    referencesLogicalType(dynamic.bootstrapMethod(), logicalOwner) ||
                    dynamic.bootstrapArgsList().stream().anyMatch(argument -> referencesLogicalType(argument, logicalOwner));
        }
        return false;
    }

    private static boolean referencesLogicalType(MethodTypeDesc type, ClassDesc logicalOwner)
    {
        return referencesLogicalType(type.returnType(), logicalOwner) || type.parameterList().stream().anyMatch(parameter -> referencesLogicalType(parameter, logicalOwner));
    }

    private static boolean referencesLogicalType(ClassDesc type, ClassDesc logicalOwner)
    {
        while (type.isArray()) {
            type = type.componentType();
        }
        return type.equals(logicalOwner);
    }

    private static ClassModel copy(ClassModel source, ClassDesc type, Set<AccessFlag> access, List<MethodDefinition.Model> methods)
    {
        return new ClassModel(
                access,
                type,
                source.superClass(),
                source.interfaces(),
                source.fields(),
                methods,
                source.kind(),
                source.recordComponents(),
                source.signature(),
                source.sourceFile(),
                source.visibleAnnotations(),
                source.invisibleAnnotations());
    }

    private static int constantReferences(MethodDefinition.Model method)
    {
        return constantReferences(method.body());
    }

    private static int constantReferences(CodeBlock block)
    {
        return block.statements().stream().mapToInt(ClassSharder::constantReferences).sum();
    }

    private static int constantReferences(Statement statement)
    {
        return switch (statement) {
            case BytecodeExpression expression -> constantReferences(expression);
            case Statements.Expression value -> constantReferences(value.expression());
            case Statements.InitializedDeclaration value -> constantReferences(value.initializer());
            case Statements.ConstructorInvocation value -> value.arguments().stream().mapToInt(ClassSharder::constantReferences).sum();
            case CodeBlock value -> constantReferences(value);
            case IfStatement value -> constantReferences(value.condition()) + constantReferences(value.ifTrue()) + constantReferences(value.ifFalse());
            case ForLoop value -> constantReferences(value.initializer()) + constantReferences(value.condition()) + constantReferences(value.update()) + constantReferences(value.body());
            case WhileLoop value -> constantReferences(value.condition()) + constantReferences(value.body());
            case DoWhileLoop value -> constantReferences(value.body()) + constantReferences(value.condition());
            case SwitchStatement value -> constantReferences(value.expression()) + value.cases().stream().mapToInt(caseValue -> constantReferences(caseValue.body())).sum() + constantReferences(value.defaultCase());
            case TryCatch value -> constantReferences(value.tryBlock()) + value.catches().stream().mapToInt(catchClause -> constantReferences(catchClause.body())).sum() + value.finallyBlock().map(ClassSharder::constantReferences).orElse(0);
            case LoopJump _,
                 Statements.Comment _,
                 Statements.Declaration _,
                 Statements.Jump _,
                 Statements.LabelBinding _ -> 0;
        };
    }

    private static int constantReferences(BytecodeExpression expression)
    {
        return switch (expression) {
            case LocalValue _ -> 0;
            case SyntheticExpression synthetic -> {
                ExpressionPlan expansion = synthetic.expansion(ExpansionContext.INSTANCE);
                yield constantReferences(expansion.setup()) + constantReferences(expansion.value());
            }
            case CoreExpression core -> (switch (core.node()) {
                case ExpressionNode.Constant _,
                     ExpressionNode.DynamicConstant _,
                     ExpressionNode.BoundConstant _,
                     ExpressionNode.BoundMethodHandleInvocation _ -> 1;
                case ExpressionNode.Binary _,
                     ExpressionNode.Unary _,
                     ExpressionNode.Cast _,
                     ExpressionNode.InstanceOf _,
                     ExpressionNode.InlineIf _,
                     ExpressionNode.ArrayLength _,
                     ExpressionNode.ArrayGet _,
                     ExpressionNode.ArraySet _,
                     ExpressionNode.NewArray _,
                     ExpressionNode.FieldGet _,
                     ExpressionNode.FieldSet _,
                     ExpressionNode.Invoke _,
                     ExpressionNode.NewInstance _,
                     ExpressionNode.LinkedMethodInvocation _,
                     ExpressionNode.InvokeDynamic _,
                     ExpressionNode.SetVariable _,
                     ExpressionNode.Increment _,
                     ExpressionNode.Adapter _ -> 0;
            }) + core.node().children().stream().mapToInt(ClassSharder::constantReferences).sum();
        };
    }

    private static boolean isCallerSensitiveOwner(ClassDesc owner)
    {
        return owner.equals(DescriptorUtils.classDesc(MethodHandles.class)) ||
                owner.equals(DescriptorUtils.classDesc(StackWalker.class));
    }

    private static ClassDesc auxiliaryType(ClassDesc primary, int index)
    {
        String packageName = primary.packageName();
        String simpleName = primary.displayName() + "$Generated" + index;
        return ClassDesc.of(packageName.isEmpty() ? simpleName : packageName + "." + simpleName);
    }

    private record MethodKey(String name, MethodTypeDesc type)
    {
        private static MethodKey of(MethodDefinition.Model method)
        {
            return new MethodKey(method.name(), method.methodType());
        }
    }
}
