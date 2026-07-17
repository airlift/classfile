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
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.AccessFlag.Location;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.airlift.classfile.DescriptorUtils.isNarrowInteger;
import static io.airlift.classfile.Identity.different;
import static io.airlift.classfile.Identity.same;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.ABSTRACT;
import static java.lang.reflect.AccessFlag.ANNOTATION;
import static java.lang.reflect.AccessFlag.BRIDGE;
import static java.lang.reflect.AccessFlag.ENUM;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.INTERFACE;
import static java.lang.reflect.AccessFlag.Location.FIELD;
import static java.lang.reflect.AccessFlag.Location.METHOD;
import static java.lang.reflect.AccessFlag.MODULE;
import static java.lang.reflect.AccessFlag.NATIVE;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PROTECTED;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.lang.reflect.AccessFlag.SUPER;
import static java.lang.reflect.AccessFlag.SYNTHETIC;
import static java.lang.reflect.AccessFlag.VARARGS;

final class ModelValidator
{
    private ModelValidator() {}

    static void validate(ClassModel definition)
    {
        if (!definition.type().isClassOrInterface()) {
            throw new IllegalArgumentException("type is not a class or interface: " + definition.type().displayName());
        }
        if (!definition.superClass().isClassOrInterface()) {
            throw new IllegalArgumentException("superClass is not a class or interface: " + definition.superClass().displayName());
        }
        if (definition.type().equals(definition.superClass())) {
            throw new IllegalArgumentException("Class cannot extend itself: " + definition.type().displayName());
        }
        Set<ClassDesc> interfaceTypes = new HashSet<>();
        definition.interfaces().forEach(interfaceType -> {
            if (!interfaceType.isClassOrInterface()) {
                throw new IllegalArgumentException("interface is not a class or interface: " + interfaceType.displayName());
            }
            if (!interfaceTypes.add(interfaceType)) {
                throw new IllegalArgumentException("Interface already declared: " + interfaceType.displayName());
            }
            if (definition.type().equals(interfaceType)) {
                String relationship = definition.kind() == ClassKind.INTERFACE ? "Interface cannot extend itself: " : "Class cannot implement itself: ";
                throw new IllegalArgumentException(relationship + definition.type().displayName());
            }
        });
        validateKind(definition);
        definition.methods().stream()
                .filter(MethodDefinition.Model::hasBody)
                .forEach(method -> StructuredDepth.validate(method.body()));

        Set<String> fieldNames = new HashSet<>();
        for (FieldDefinition field : definition.fields()) {
            validateAccessLocation(field.access(), FIELD, "field");
            if (!field.declaringType().equals(definition.type())) {
                throw new IllegalArgumentException("Field is declared by another class: " + field);
            }
            if (!fieldNames.add(field.name())) {
                throw new IllegalArgumentException("Field already declared: " + field.name());
            }
            if (definition.kind() == ClassKind.INTERFACE) {
                Set<AccessFlag> required = Set.of(PUBLIC, STATIC, FINAL);
                if (!field.access().containsAll(required) || !Set.of(PUBLIC, STATIC, FINAL, SYNTHETIC).containsAll(field.access())) {
                    throw new IllegalArgumentException("Interface field must be public static final: " + field.name());
                }
            }
        }

        Set<String> methodKeys = new HashSet<>();
        for (MethodDefinition.Model method : definition.methods()) {
            validateAccessLocation(method.access(), METHOD, "method");
            MethodDefinition.validateAccess(method.name(), method.access());
            if (!method.declaringType().equals(definition.type())) {
                throw new IllegalArgumentException("Method is declared by another class: " + method);
            }
            String methodKey = method.name() + method.methodType().descriptorString();
            if (!methodKeys.add(methodKey)) {
                throw new IllegalArgumentException("Method already declared: " + methodKey);
            }
            if (!method.methodType().parameterList().equals(method.parameters().stream().map(Parameter::type).toList())) {
                throw new IllegalArgumentException("Method parameters do not match descriptor: " + method);
            }
            if (method.isConstructor() && (method.access().contains(STATIC) || method.access().contains(ABSTRACT) || method.access().contains(NATIVE))) {
                throw new IllegalArgumentException("Constructor cannot be static, abstract, or native");
            }
            if (method.isClassInitializer() && (!method.access().equals(Set.of(STATIC)) || !method.parameters().isEmpty())) {
                throw new IllegalArgumentException("Class initializer must be static with no parameters");
            }
            if (method.hasBody() != method.methodBody().isPresent()) {
                throw new IllegalArgumentException("Method body does not match access flags: " + method);
            }
            if (method.hasBody()) {
                validateMethod(definition, method);
            }
            if (definition.kind() == ClassKind.INTERFACE) {
                validateInterfaceMethod(method);
            }
        }
        validateRecord(definition);
        validateConstructorDelegationCycles(definition);
    }

    private static void validateKind(ClassModel definition)
    {
        definition.access().stream()
                .filter(flag -> !flag.locations().contains(Location.CLASS))
                .findFirst()
                .ifPresent(flag -> {
                    throw new IllegalArgumentException("Access flag is not valid for a class: " + flag);
                });
        if (definition.access().contains(MODULE)) {
            throw new IllegalArgumentException("Module classfiles are not supported: " + definition.type().displayName());
        }
        switch (definition.kind()) {
            case CLASS -> {
                if (definition.access().contains(INTERFACE) || definition.access().contains(ANNOTATION) || definition.access().contains(ENUM)) {
                    throw new IllegalArgumentException("Class kind does not match access flags: " + definition.type().displayName());
                }
                if (definition.access().contains(FINAL) && definition.access().contains(ABSTRACT)) {
                    throw new IllegalArgumentException("Class cannot be both final and abstract: " + definition.type().displayName());
                }
                if (!definition.recordComponents().isEmpty()) {
                    throw new IllegalArgumentException("Class has record components: " + definition.type().displayName());
                }
            }
            case INTERFACE -> {
                if (!definition.superClass().equals(ConstantDescs.CD_Object)) {
                    throw new IllegalArgumentException("Interface must have java.lang.Object as its superclass: " + definition.type().displayName());
                }
                if (!definition.access().contains(INTERFACE) || !definition.access().contains(ABSTRACT)) {
                    throw new IllegalArgumentException("Interface must have interface and abstract access: " + definition.type().displayName());
                }
                if (definition.access().contains(FINAL) || definition.access().contains(SUPER) || definition.access().contains(ANNOTATION) || definition.access().contains(ENUM)) {
                    throw new IllegalArgumentException("Interface has incompatible access flags: " + definition.type().displayName());
                }
                if (!definition.recordComponents().isEmpty()) {
                    throw new IllegalArgumentException("Interface has record components: " + definition.type().displayName());
                }
            }
            case RECORD -> {}
        }
    }

    private static void validateAccessLocation(Set<AccessFlag> access, Location location, String target)
    {
        access.stream()
                .filter(flag -> !flag.locations().contains(location))
                .findFirst()
                .ifPresent(flag -> {
                    throw new IllegalArgumentException("Access flag is not valid for a " + target + ": " + flag);
                });
    }

    private static void validateInterfaceMethod(MethodDefinition.Model method)
    {
        if (method.isClassInitializer()) {
            return;
        }
        if (method.isConstructor()) {
            throw new IllegalArgumentException("Interface cannot declare a constructor");
        }
        boolean publicMethod = method.access().contains(PUBLIC);
        boolean privateMethod = method.access().contains(PRIVATE);
        if (publicMethod == privateMethod) {
            throw new IllegalArgumentException("Interface method must be exactly one of public or private: " + method.name());
        }
        Set<AccessFlag> allowed = Set.of(PUBLIC, PRIVATE, STATIC, ABSTRACT, BRIDGE, SYNTHETIC, VARARGS);
        if (!allowed.containsAll(method.access())) {
            throw new IllegalArgumentException("Interface method has incompatible access flags: " + method.name());
        }
        if (method.access().contains(ABSTRACT) && (privateMethod || method.access().contains(STATIC))) {
            throw new IllegalArgumentException("Abstract interface method must be public and non-static: " + method.name());
        }
    }

    private static void validateRecord(ClassModel definition)
    {
        if (definition.kind() != ClassKind.RECORD) {
            if (!definition.recordComponents().isEmpty()) {
                throw new IllegalArgumentException("Non-record class has record components: " + definition.type().displayName());
            }
            return;
        }

        ClassDesc recordType = DescriptorUtils.classDesc(Record.class);
        if (!definition.superClass().equals(recordType)) {
            throw new IllegalArgumentException("Record must directly extend java.lang.Record: " + definition.type().displayName());
        }
        if (!definition.access().contains(FINAL)) {
            throw new IllegalArgumentException("Record must be final: " + definition.type().displayName());
        }
        for (AccessFlag forbidden : List.of(ABSTRACT, INTERFACE, ANNOTATION, ENUM)) {
            if (definition.access().contains(forbidden)) {
                throw new IllegalArgumentException("Record cannot be " + forbidden.name().toLowerCase(Locale.ROOT) + ": " + definition.type().displayName());
            }
        }

        LinkedHashMap<String, RecordComponentDefinition> components = new LinkedHashMap<>();
        for (RecordComponentDefinition component : definition.recordComponents()) {
            if (!component.declaringType().equals(definition.type())) {
                throw new IllegalArgumentException("Record component is declared by another class: " + component);
            }
            if (components.putIfAbsent(component.name(), component) != null) {
                throw new IllegalArgumentException("Record component already declared: " + component.name());
            }
            if (!definition.fields().contains(component.field())) {
                throw new IllegalArgumentException("Record component field is missing: " + component.name());
            }
            if (!component.field().access().equals(Set.of(PRIVATE, FINAL))) {
                throw new IllegalArgumentException("Record component field must be private final: " + component.name());
            }
        }
        definition.fields().stream()
                .filter(field -> !field.isStatic())
                .filter(field -> components.values().stream().noneMatch(component -> component.field() == field))
                .findFirst()
                .ifPresent(field -> {
                    throw new IllegalArgumentException("Record cannot declare additional instance field: " + field.name());
                });

        for (RecordComponentDefinition component : components.values()) {
            List<MethodDefinition.Model> accessors = definition.methods().stream()
                    .filter(method -> method.name().equals(component.name()))
                    .filter(method -> method.methodType().parameterCount() == 0)
                    .toList();
            if (accessors.size() != 1 || !accessors.getFirst().returnType().equals(component.type())) {
                throw new IllegalArgumentException("Record component accessor must have descriptor %s: %s"
                        .formatted(component.accessor().methodType().descriptorString(), component.name()));
            }
            MethodDefinition.Model accessor = accessors.getFirst();
            if (!accessor.access().contains(PUBLIC) || accessor.isStatic() || !accessor.exceptions().isEmpty() || !accessor.hasBody()) {
                throw new IllegalArgumentException("Record component accessor must be public, non-static, and declare no exceptions: " + component.name());
            }
        }

        validateRecordObjectMethod(definition, "toString", MethodTypeDesc.of(ConstantDescs.CD_String));
        validateRecordObjectMethod(definition, "hashCode", MethodTypeDesc.of(ConstantDescs.CD_int));
        validateRecordObjectMethod(definition, "equals", MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object));

        MethodTypeDesc canonicalType = MethodTypeDesc.of(CD_void, components.values().stream().map(RecordComponentDefinition::type).toList());
        MethodDefinition.Model canonical = definition.methods().stream()
                .filter(MethodDefinition.Model::isConstructor)
                .filter(method -> method.methodType().equals(canonicalType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Record canonical constructor is missing: " + canonicalType.descriptorString()));
        if (!canonical.parameters().stream().map(Parameter::name).toList().equals(List.copyOf(components.keySet()))) {
            throw new IllegalArgumentException("Canonical constructor parameter names must match record components: " + components.keySet());
        }
        if (!canonical.exceptions().isEmpty()) {
            throw new IllegalArgumentException("Canonical constructor cannot declare exceptions");
        }
        validateCanonicalVisibility(definition, canonical);
        validateRecordConstructors(definition, canonical, components);
    }

    private static void validateRecordObjectMethod(ClassModel definition, String name, MethodTypeDesc expectedType)
    {
        List<MethodDefinition.Model> matches = definition.methods().stream()
                .filter(method -> method.name().equals(name))
                .filter(method -> method.methodType().parameterList().equals(expectedType.parameterList()))
                .toList();
        if (matches.size() != 1 || !matches.getFirst().returnType().equals(expectedType.returnType())) {
            throw new IllegalArgumentException("Record method must have descriptor %s: %s"
                    .formatted(expectedType.descriptorString(), name));
        }
        MethodDefinition.Model method = matches.getFirst();
        if (!method.access().contains(PUBLIC) || method.isStatic() || !method.exceptions().isEmpty() || !method.hasBody()) {
            throw new IllegalArgumentException("Record method must be public and non-static: " + name);
        }
    }

    private static void validateCanonicalVisibility(ClassModel definition, MethodDefinition.Model canonical)
    {
        if (definition.access().contains(PUBLIC) && !canonical.access().contains(PUBLIC)) {
            throw new IllegalArgumentException("Canonical constructor of a public record must be public");
        }
        if (definition.access().contains(PROTECTED) && !canonical.access().contains(PUBLIC) && !canonical.access().contains(PROTECTED)) {
            throw new IllegalArgumentException("Canonical constructor is more restrictive than the record");
        }
        if (!definition.access().contains(PRIVATE) &&
                !definition.access().contains(PROTECTED) &&
                !definition.access().contains(PUBLIC) &&
                canonical.access().contains(PRIVATE)) {
            throw new IllegalArgumentException("Canonical constructor is more restrictive than the record");
        }
    }

    private static void validateRecordConstructors(
            ClassModel definition,
            MethodDefinition.Model canonical,
            Map<String, RecordComponentDefinition> components)
    {
        for (MethodDefinition.Model method : definition.methods()) {
            RecordEvents events = new RecordEvents(definition, method, components);
            if (method.hasBody()) {
                events.scan(method.body(), false);
            }
            if (!method.isConstructor()) {
                if (!events.writes.isEmpty()) {
                    throw new IllegalArgumentException("Record component field can only be initialized in the canonical constructor: " + events.writes.getFirst().name());
                }
                continue;
            }
            if (different(method, canonical)) {
                if (!events.writes.isEmpty()) {
                    throw new IllegalArgumentException("Non-canonical record constructor initializes component field: " + events.writes.getFirst().name());
                }
                if (events.invocations.size() != 1 ||
                        events.invocations.getFirst().controlled() ||
                        events.invocations.getFirst().invocation().target() != Statements.ConstructorTarget.THIS ||
                        !events.invocations.getFirst().invocation().constructorType().equals(canonical.methodType())) {
                    throw new IllegalArgumentException("Non-canonical record constructor must delegate directly to the canonical constructor: " + method);
                }
                continue;
            }

            if (events.invocations.size() != 1 ||
                    events.invocations.getFirst().controlled() ||
                    events.invocations.getFirst().invocation().target() != Statements.ConstructorTarget.SUPER ||
                    !events.invocations.getFirst().invocation().constructorType().equals(MethodTypeDesc.of(CD_void))) {
                throw new IllegalArgumentException("Record canonical constructor must directly invoke java.lang.Record() exactly once");
            }
            boolean hasNormalCompletion = NormalCompletionAnalyzer.hasNormalCompletion(method.body());
            ConstructorEvent superCall = events.invocations.getFirst();
            for (String component : components.keySet()) {
                List<RecordWrite> writes = events.writes.stream().filter(write -> write.name().equals(component)).toList();
                if (writes.size() != 1) {
                    if (writes.isEmpty() && !hasNormalCompletion) {
                        continue;
                    }
                    throw new IllegalArgumentException("Record canonical constructor must initialize component exactly once: " + component);
                }
                RecordWrite write = writes.getFirst();
                if (write.controlled()) {
                    throw new IllegalArgumentException("Record component initialization cannot be conditional: " + component);
                }
                if (write.order() < superCall.order()) {
                    throw new IllegalArgumentException("Record component is initialized before java.lang.Record(): " + component);
                }
                if (events.returns.stream().anyMatch(returnEvent -> returnEvent.order() < write.order())) {
                    throw new IllegalArgumentException("Record canonical constructor can return before initializing component: " + component);
                }
            }
            if (events.rawJump) {
                throw new IllegalArgumentException("Record canonical constructor cannot use raw jumps because component initialization cannot be proven");
            }
        }
    }

    private record ConstructorEvent(Statements.ConstructorInvocation invocation, boolean controlled, int order) {}

    private record RecordWrite(String name, boolean controlled, int order) {}

    private record ReturnEvent(int order) {}

    private record Completion(boolean fallsThrough, boolean normalExit)
    {
        private static final Completion CONTINUES = new Completion(true, false);
        private static final Completion STOPS = new Completion(false, false);
        private static final Completion RETURNS = new Completion(false, true);
    }

    private static final class NormalCompletionAnalyzer
    {
        private final Set<SyntheticExpression> activeExpressions = identitySet();

        private static boolean hasNormalCompletion(CodeBlock body)
        {
            Completion completion = new NormalCompletionAnalyzer().analyzeBlock(body);
            return completion.fallsThrough() || completion.normalExit();
        }

        private Completion analyzeBlock(CodeBlock block)
        {
            Completion result = Completion.CONTINUES;
            for (Statement statement : block.statements()) {
                if (!result.fallsThrough()) {
                    break;
                }
                Completion next = analyzeStatement(statement);
                result = new Completion(next.fallsThrough(), result.normalExit() || next.normalExit());
            }
            return result;
        }

        private Completion analyzeStatement(Statement statement)
        {
            return switch (statement) {
                case BytecodeExpression expression -> analyzeExpression(expression);
                case Statements.Expression expression -> analyzeExpression(expression.expression());
                case Statements.InitializedDeclaration declaration -> analyzeExpression(declaration.initializer());
                case Statements.ConstructorInvocation invocation -> analyzeExpressions(invocation.arguments());
                case CodeBlock block -> analyzeBlock(block);
                case IfStatement ifStatement -> analyzeIf(ifStatement);
                case SwitchStatement switchStatement -> analyzeSwitch(switchStatement);
                case DoWhileLoop loop -> analyzeDoWhile(loop);
                case ForLoop loop -> analyzeLoop(loop.initializer(), loop.condition(), loop.body(), loop.update());
                case WhileLoop loop -> analyzeWhile(loop);
                case TryCatch tryCatch -> analyzeTryCatch(tryCatch);
                case LoopJump _ -> Completion.RETURNS;
                case Statements.Jump _ -> Completion.STOPS;
                case Statements.Comment _,
                     Statements.Declaration _,
                     Statements.LabelBinding _ -> Completion.CONTINUES;
            };
        }

        private Completion analyzeIf(IfStatement statement)
        {
            Completion condition = analyzeExpression(statement.condition());
            if (!condition.fallsThrough()) {
                return condition;
            }
            Completion ifTrue = analyzeBlock(statement.ifTrue());
            Completion ifFalse = analyzeBlock(statement.ifFalse());
            return new Completion(
                    ifTrue.fallsThrough() || ifFalse.fallsThrough(),
                    condition.normalExit() || ifTrue.normalExit() || ifFalse.normalExit());
        }

        private Completion analyzeSwitch(SwitchStatement statement)
        {
            Completion expression = analyzeExpression(statement.expression());
            if (!expression.fallsThrough()) {
                return expression;
            }
            boolean fallsThrough = false;
            boolean normalExit = expression.normalExit();
            for (SwitchStatement.Case caseValue : statement.cases()) {
                Completion body = analyzeBlock(caseValue.body());
                fallsThrough |= body.fallsThrough();
                normalExit |= body.normalExit();
            }
            Completion defaultCase = analyzeBlock(statement.defaultCase());
            return new Completion(fallsThrough || defaultCase.fallsThrough(), normalExit || defaultCase.normalExit());
        }

        private Completion analyzeLoop(CodeBlock initializer, BytecodeExpression condition, CodeBlock body, CodeBlock update)
        {
            Completion prefix = analyzeBlock(initializer);
            if (prefix.fallsThrough()) {
                prefix = then(prefix, analyzeExpression(condition));
            }
            if (!prefix.fallsThrough()) {
                return prefix;
            }
            Completion iteration = analyzeBlock(body);
            if (iteration.fallsThrough()) {
                iteration = then(iteration, analyzeBlock(update));
            }
            return new Completion(true, prefix.normalExit() || iteration.normalExit());
        }

        private Completion analyzeDoWhile(DoWhileLoop loop)
        {
            Completion body = analyzeBlock(loop.body());
            return body.fallsThrough() ? then(body, analyzeExpression(loop.condition())) : body;
        }

        private Completion analyzeWhile(WhileLoop loop)
        {
            Completion condition = analyzeExpression(loop.condition());
            if (!condition.fallsThrough()) {
                return condition;
            }
            Completion body = analyzeBlock(loop.body());
            return new Completion(true, condition.normalExit() || body.normalExit());
        }

        private Completion analyzeTryCatch(TryCatch statement)
        {
            Completion result = analyzeBlock(statement.tryBlock());
            for (TryCatch.CatchClause catchClause : statement.catches()) {
                Completion catchResult = analyzeBlock(catchClause.body());
                result = new Completion(
                        result.fallsThrough() || catchResult.fallsThrough(),
                        result.normalExit() || catchResult.normalExit());
            }
            if (statement.finallyBlock().isPresent()) {
                Completion finallyResult = analyzeBlock(statement.finallyBlock().orElseThrow());
                if (!finallyResult.fallsThrough()) {
                    return finallyResult;
                }
                return new Completion(result.fallsThrough(), result.normalExit() || finallyResult.normalExit());
            }
            return result;
        }

        private Completion analyzeExpression(BytecodeExpression expression)
        {
            return switch (expression) {
                case LocalValue _ -> Completion.CONTINUES;
                case CoreExpression core -> analyzeNode(core.node());
                case SyntheticExpression synthetic -> analyzeSynthetic(synthetic);
            };
        }

        private Completion analyzeNode(ExpressionNode node)
        {
            if (node instanceof ExpressionNode.InlineIf inlineIf) {
                Completion condition = analyzeExpression(inlineIf.condition());
                if (!condition.fallsThrough()) {
                    return condition;
                }
                Completion ifTrue = analyzeExpression(inlineIf.ifTrue());
                Completion ifFalse = analyzeExpression(inlineIf.ifFalse());
                return new Completion(
                        ifTrue.fallsThrough() || ifFalse.fallsThrough(),
                        condition.normalExit() || ifTrue.normalExit() || ifFalse.normalExit());
            }
            if (node instanceof ExpressionNode.Binary binary && (binary.operator().equals("&&") || binary.operator().equals("||"))) {
                Completion left = analyzeExpression(binary.left());
                if (!left.fallsThrough()) {
                    return left;
                }
                Completion right = analyzeExpression(binary.right());
                return new Completion(true, left.normalExit() || right.normalExit());
            }

            Completion children = analyzeExpressions(node.children());
            if (!children.fallsThrough()) {
                return children;
            }
            if (node instanceof ExpressionNode.Adapter adapter) {
                return switch (adapter.keyword()) {
                    case "return" -> new Completion(false, true);
                    case "throw" -> new Completion(false, children.normalExit());
                    default -> children;
                };
            }
            if (node instanceof ExpressionNode.Constant constant && constant.type().equals(CD_void) && constant.value() == null) {
                return Completion.RETURNS;
            }
            return children;
        }

        private Completion analyzeSynthetic(SyntheticExpression synthetic)
        {
            if (!activeExpressions.add(synthetic)) {
                throw new IllegalArgumentException("Recursive synthetic expression: " + synthetic);
            }
            try {
                ExpressionPlan plan = synthetic.expansion(ExpansionContext.INSTANCE);
                Completion setup = analyzeBlock(plan.setup());
                return setup.fallsThrough() ? then(setup, analyzeExpression(plan.value())) : setup;
            }
            finally {
                activeExpressions.remove(synthetic);
            }
        }

        private Completion analyzeExpressions(List<BytecodeExpression> expressions)
        {
            Completion result = Completion.CONTINUES;
            for (BytecodeExpression expression : expressions) {
                if (!result.fallsThrough()) {
                    break;
                }
                result = then(result, analyzeExpression(expression));
            }
            return result;
        }

        private static Completion then(Completion first, Completion second)
        {
            if (!first.fallsThrough()) {
                return first;
            }
            return new Completion(second.fallsThrough(), first.normalExit() || second.normalExit());
        }
    }

    private static final class RecordEvents
    {
        private final ClassModel definition;
        private final MethodDefinition.Model method;
        private final Map<String, RecordComponentDefinition> components;
        private final List<ConstructorEvent> invocations = new ArrayList<>();
        private final List<RecordWrite> writes = new ArrayList<>();
        private final List<ReturnEvent> returns = new ArrayList<>();
        private final Set<SyntheticExpression> activeExpressions = identitySet();
        private boolean rawJump;
        private int order;

        private RecordEvents(ClassModel definition, MethodDefinition.Model method, Map<String, RecordComponentDefinition> components)
        {
            this.definition = definition;
            this.method = method;
            this.components = components;
        }

        private void scan(Statement statement, boolean controlled)
        {
            switch (statement) {
                case BytecodeExpression expression -> scan(expression, controlled);
                case CodeBlock block -> block.statements().forEach(value -> scan(value, controlled));
                case DoWhileLoop loop -> {
                    scan(loop.body(), true);
                    scan(loop.condition(), true);
                }
                case ForLoop loop -> {
                    scan(loop.initializer(), true);
                    scan(loop.condition(), true);
                    scan(loop.update(), true);
                    scan(loop.body(), true);
                }
                case IfStatement ifStatement -> {
                    scan(ifStatement.condition(), true);
                    scan(ifStatement.ifTrue(), true);
                    scan(ifStatement.ifFalse(), true);
                }
                case LoopJump _,
                     Statements.Comment _,
                     Statements.Declaration _,
                     Statements.LabelBinding _ -> {}
                case Statements.Jump _ -> rawJump = true;
                case SwitchStatement switchStatement -> {
                    scan(switchStatement.expression(), true);
                    switchStatement.cases().forEach(value -> scan(value.body(), true));
                    scan(switchStatement.defaultCase(), true);
                }
                case TryCatch tryCatch -> {
                    scan(tryCatch.tryBlock(), true);
                    tryCatch.catches().forEach(value -> scan(value.body(), true));
                    tryCatch.finallyBlock().ifPresent(value -> scan(value, true));
                }
                case WhileLoop loop -> {
                    scan(loop.condition(), true);
                    scan(loop.body(), true);
                }
                case Statements.ConstructorInvocation invocation -> {
                    invocation.arguments().forEach(argument -> scan(argument, controlled));
                    invocations.add(new ConstructorEvent(invocation, controlled, order++));
                }
                case Statements.Expression expression -> scan(expression.expression(), controlled);
                case Statements.InitializedDeclaration declaration -> scan(declaration.initializer(), controlled);
            }
        }

        private void scan(BytecodeExpression expression, boolean controlled)
        {
            switch (expression) {
                case LocalValue _ -> {}
                case CoreExpression core -> {
                    switch (core.node()) {
                        case ExpressionNode.InlineIf inlineIf -> {
                            scan(inlineIf.condition(), controlled);
                            scan(inlineIf.ifTrue(), true);
                            scan(inlineIf.ifFalse(), true);
                        }
                        case ExpressionNode.Binary binary when binary.operator().equals("&&") || binary.operator().equals("||") -> {
                            scan(binary.left(), controlled);
                            scan(binary.right(), true);
                        }
                        default -> core.node().children().forEach(child -> scan(child, controlled));
                    }
                    if (core.node() instanceof ExpressionNode.FieldSet fieldSet &&
                            !fieldSet.isStatic() &&
                            fieldSet.owner().equals(definition.type())) {
                        RecordComponentDefinition component = components.get(fieldSet.name());
                        if (component != null && component.type().equals(fieldSet.fieldType())) {
                            if (fieldSet.target() != method.thisVariable()) {
                                throw new IllegalArgumentException("Record component initialization must target this: " + component.name());
                            }
                            writes.add(new RecordWrite(component.name(), controlled, order++));
                        }
                    }
                    if ((core.node() instanceof ExpressionNode.Adapter adapter && adapter.keyword().equals("return")) ||
                            (core.node() instanceof ExpressionNode.Constant constant && constant.type().equals(CD_void) && constant.value() == null)) {
                        returns.add(new ReturnEvent(order++));
                    }
                }
                case SyntheticExpression synthetic -> {
                    if (!activeExpressions.add(synthetic)) {
                        throw new IllegalArgumentException("Recursive synthetic expression: " + synthetic);
                    }
                    try {
                        ExpressionPlan expansion = synthetic.expansion(ExpansionContext.INSTANCE);
                        scan(expansion.setup(), controlled);
                        scan(expansion.value(), controlled);
                    }
                    finally {
                        activeExpressions.remove(synthetic);
                    }
                }
            }
        }
    }

    static void validateMethod(ClassModel definition, MethodDefinition.Model method)
    {
        Set<LocalValue> visible = identitySet();
        visible.addAll(method.parameters());
        if (!method.isStatic()) {
            visible.add(method.thisVariable());
        }
        validateBlock(method.body(), visible, identitySet(), identitySet(), identitySet(), identitySet(), false);
        validateReturns(method.body(), method.returnType(), identitySet());
        validateConstructor(definition, method);
    }

    private static void validateConstructor(ClassModel definition, MethodDefinition.Model method)
    {
        ConstructorValidator validator = new ConstructorValidator(definition, method);
        if (!method.isConstructor()) {
            validator.rejectConstructorInvocations(method.body());
            return;
        }
        validator.validate(method.body());
    }

    private static void validateConstructorDelegationCycles(ClassModel definition)
    {
        Map<MethodTypeDesc, Set<MethodTypeDesc>> delegations = new HashMap<>();
        definition.methods().stream()
                .filter(MethodDefinition.Model::isConstructor)
                .forEach(constructor -> {
                    Set<MethodTypeDesc> targets = new HashSet<>();
                    collectThisConstructorTargets(constructor.body(), targets);
                    delegations.put(constructor.methodType(), targets);
                });

        Set<MethodTypeDesc> complete = new HashSet<>();
        Set<MethodTypeDesc> active = new HashSet<>();
        for (MethodTypeDesc constructor : delegations.keySet()) {
            validateConstructorDelegationCycles(constructor, delegations, active, complete);
        }
    }

    private static void validateConstructorDelegationCycles(
            MethodTypeDesc constructor,
            Map<MethodTypeDesc, Set<MethodTypeDesc>> delegations,
            Set<MethodTypeDesc> active,
            Set<MethodTypeDesc> complete)
    {
        if (complete.contains(constructor)) {
            return;
        }
        if (!active.add(constructor)) {
            throw new IllegalArgumentException("Recursive constructor delegation: " + constructor.descriptorString());
        }
        for (MethodTypeDesc target : delegations.getOrDefault(constructor, Set.of())) {
            validateConstructorDelegationCycles(target, delegations, active, complete);
        }
        active.remove(constructor);
        complete.add(constructor);
    }

    private static void collectThisConstructorTargets(CodeBlock block, Set<MethodTypeDesc> targets)
    {
        for (Statement statement : block.statements()) {
            switch (statement) {
                case Statements.ConstructorInvocation invocation -> {
                    if (invocation.target() == Statements.ConstructorTarget.THIS) {
                        targets.add(invocation.constructorType());
                    }
                }
                case BytecodeExpression _,
                     Statements.Expression _,
                     Statements.Comment _,
                     Statements.Declaration _,
                     Statements.InitializedDeclaration _,
                     Statements.Jump _,
                     Statements.LabelBinding _,
                     LoopJump _ -> {}
                case CodeBlock nested -> collectThisConstructorTargets(nested, targets);
                case IfStatement ifStatement -> {
                    collectThisConstructorTargets(ifStatement.ifTrue(), targets);
                    collectThisConstructorTargets(ifStatement.ifFalse(), targets);
                }
                case ForLoop forLoop -> {
                    collectThisConstructorTargets(forLoop.initializer(), targets);
                    collectThisConstructorTargets(forLoop.update(), targets);
                    collectThisConstructorTargets(forLoop.body(), targets);
                }
                case WhileLoop whileLoop -> collectThisConstructorTargets(whileLoop.body(), targets);
                case DoWhileLoop doWhileLoop -> collectThisConstructorTargets(doWhileLoop.body(), targets);
                case SwitchStatement switchStatement -> {
                    switchStatement.cases().forEach(caseValue -> collectThisConstructorTargets(caseValue.body(), targets));
                    collectThisConstructorTargets(switchStatement.defaultCase(), targets);
                }
                case TryCatch tryCatch -> {
                    collectThisConstructorTargets(tryCatch.tryBlock(), targets);
                    tryCatch.catches().forEach(catchClause -> collectThisConstructorTargets(catchClause.body(), targets));
                    tryCatch.finallyBlock().ifPresent(finallyBlock -> collectThisConstructorTargets(finallyBlock, targets));
                }
            }
        }
    }

    private static void validateReturns(CodeBlock block, ClassDesc returnType, Set<SyntheticExpression> activeExpressions)
    {
        for (Statement statement : block.statements()) {
            switch (statement) {
                case BytecodeExpression expression -> validateReturns(expression, returnType, activeExpressions);
                case Statements.Expression expression -> validateReturns(expression.expression(), returnType, activeExpressions);
                case Statements.Comment _,
                     Statements.ConstructorInvocation _,
                     Statements.Declaration _,
                     Statements.Jump _,
                     Statements.LabelBinding _,
                     LoopJump _ -> {}
                case Statements.InitializedDeclaration declaration -> validateReturns(declaration.initializer(), returnType, activeExpressions);
                case CodeBlock nested -> validateReturns(nested, returnType, activeExpressions);
                case IfStatement ifStatement -> {
                    validateReturns(ifStatement.condition(), returnType, activeExpressions);
                    validateReturns(ifStatement.ifTrue(), returnType, activeExpressions);
                    validateReturns(ifStatement.ifFalse(), returnType, activeExpressions);
                }
                case ForLoop forLoop -> {
                    validateReturns(forLoop.initializer(), returnType, activeExpressions);
                    validateReturns(forLoop.condition(), returnType, activeExpressions);
                    validateReturns(forLoop.update(), returnType, activeExpressions);
                    validateReturns(forLoop.body(), returnType, activeExpressions);
                }
                case WhileLoop whileLoop -> {
                    validateReturns(whileLoop.condition(), returnType, activeExpressions);
                    validateReturns(whileLoop.body(), returnType, activeExpressions);
                }
                case DoWhileLoop doWhileLoop -> {
                    validateReturns(doWhileLoop.body(), returnType, activeExpressions);
                    validateReturns(doWhileLoop.condition(), returnType, activeExpressions);
                }
                case SwitchStatement switchStatement -> {
                    validateReturns(switchStatement.expression(), returnType, activeExpressions);
                    switchStatement.cases().forEach(caseValue -> validateReturns(caseValue.body(), returnType, activeExpressions));
                    validateReturns(switchStatement.defaultCase(), returnType, activeExpressions);
                }
                case TryCatch tryCatch -> {
                    validateReturns(tryCatch.tryBlock(), returnType, activeExpressions);
                    tryCatch.catches().forEach(catchClause -> validateReturns(catchClause.body(), returnType, activeExpressions));
                    tryCatch.finallyBlock().ifPresent(finallyBlock -> validateReturns(finallyBlock, returnType, activeExpressions));
                }
            }
        }
    }

    private static void validateReturns(BytecodeExpression expression, ClassDesc returnType, Set<SyntheticExpression> activeExpressions)
    {
        record SyntheticExit(SyntheticExpression expression) {}

        ArrayDeque<Object> pending = new ArrayDeque<>();
        ArrayList<SyntheticExpression> enteredSynthetics = new ArrayList<>();
        pending.push(expression);
        try {
            while (!pending.isEmpty()) {
                Object item = pending.pop();
                if (item instanceof SyntheticExit exit) {
                    activeExpressions.remove(exit.expression());
                    enteredSynthetics.removeLast();
                    continue;
                }

                switch ((BytecodeExpression) item) {
                    case LocalValue _ -> {}
                    case CoreExpression core -> {
                        validateReturnNode(core.node(), returnType);
                        List<BytecodeExpression> children = core.node().children();
                        for (int index = children.size() - 1; index >= 0; index--) {
                            pending.push(children.get(index));
                        }
                    }
                    case SyntheticExpression synthetic -> {
                        if (!activeExpressions.add(synthetic)) {
                            throw new IllegalArgumentException("Recursive synthetic expression: " + synthetic);
                        }
                        enteredSynthetics.add(synthetic);
                        ExpressionPlan plan = synthetic.expansion(ExpansionContext.INSTANCE);
                        validateReturns(plan.setup(), returnType, activeExpressions);
                        pending.push(new SyntheticExit(synthetic));
                        pending.push(plan.value());
                    }
                }
            }
        }
        finally {
            enteredSynthetics.forEach(activeExpressions::remove);
        }
    }

    private static void validateReturnNode(ExpressionNode node, ClassDesc returnType)
    {
        switch (node) {
            case ExpressionNode.Adapter adapter -> {
                if (adapter.keyword().equals("return")) {
                    requireReturnType(returnType, adapter.value().type());
                }
            }
            case ExpressionNode.Constant constant -> {
                if (constant.type().equals(CD_void) && !returnType.equals(CD_void)) {
                    throw new IllegalArgumentException("Void return in method returning " + returnType.displayName());
                }
            }
            case ExpressionNode.ArrayGet _,
                 ExpressionNode.ArrayLength _,
                 ExpressionNode.ArraySet _,
                 ExpressionNode.Binary _,
                 ExpressionNode.BoundConstant _,
                 ExpressionNode.BoundMethodHandleInvocation _,
                 ExpressionNode.Cast _,
                 ExpressionNode.DynamicConstant _,
                 ExpressionNode.FieldGet _,
                 ExpressionNode.FieldSet _,
                 ExpressionNode.Increment _,
                 ExpressionNode.InlineIf _,
                 ExpressionNode.InstanceOf _,
                 ExpressionNode.Invoke _,
                 ExpressionNode.InvokeDynamic _,
                 ExpressionNode.NewArray _,
                 ExpressionNode.NewInstance _,
                 ExpressionNode.SetVariable _,
                 ExpressionNode.Unary _ -> {}
        }
    }

    private static void requireReturnType(ClassDesc target, ClassDesc source)
    {
        if (target.equals(CD_void)) {
            throw new IllegalArgumentException("Value return in void method: " + source.displayName());
        }
        if ((target.isPrimitive() || source.isPrimitive()) &&
                !target.equals(source) &&
                !(isNarrowInteger(target) && isNarrowInteger(source))) {
            throw new IllegalArgumentException("Return type " + source.displayName() + " is not assignable to " + target.displayName());
        }
    }

    private static void validateBlock(
            CodeBlock block,
            Set<LocalValue> inherited,
            Set<Object> activeLoops,
            Set<CodeBlock> activeBlocks,
            Set<SyntheticExpression> activeExpressions,
            Set<CodeLabel> inheritedLabels,
            boolean accumulate)
    {
        if (!activeBlocks.add(block)) {
            throw new IllegalArgumentException("CodeBlock cycle detected" + blockDescription(block));
        }
        Set<LocalValue> visible = accumulate ? inherited : copy(inherited);
        Set<CodeLabel> visibleLabels = copy(inheritedLabels);
        visibleLabels.addAll(block.labels());
        Set<CodeLabel> boundLabels = identitySet();
        try {
            for (Statement statement : block.statements()) {
                validateStatement(statement, visible, activeLoops, activeBlocks, activeExpressions, visibleLabels, boundLabels, block);
            }
            for (CodeLabel label : block.labels()) {
                if (!boundLabels.contains(label)) {
                    throw new IllegalArgumentException("Label is not bound: " + label.name());
                }
            }
        }
        finally {
            activeBlocks.remove(block);
        }
    }

    private static void validateStatement(
            Statement statement,
            Set<LocalValue> visible,
            Set<Object> activeLoops,
            Set<CodeBlock> activeBlocks,
            Set<SyntheticExpression> activeExpressions,
            Set<CodeLabel> visibleLabels,
            Set<CodeLabel> boundLabels,
            CodeBlock currentBlock)
    {
        switch (statement) {
            case BytecodeExpression expression -> validateExpression(expression, visible, activeLoops, activeBlocks, activeExpressions, visibleLabels);
            case Statements.Expression expression -> validateExpression(expression.expression(), visible, activeLoops, activeBlocks, activeExpressions, visibleLabels);
            case Statements.Comment _ -> {}
            case Statements.ConstructorInvocation invocation -> invocation.arguments().forEach(argument ->
                    validateExpression(argument, visible, activeLoops, activeBlocks, activeExpressions, visibleLabels));
            case Statements.LabelBinding binding -> {
                if (different(binding.label().owner(), currentBlock.scope())) {
                    throw new IllegalArgumentException("Label binding belongs to another block: " + binding.label().name());
                }
                if (!boundLabels.add(binding.label())) {
                    throw new IllegalArgumentException("Label is bound more than once: " + binding.label().name());
                }
            }
            case Statements.Jump jump -> {
                if (!visibleLabels.contains(jump.target())) {
                    throw new IllegalArgumentException("Label is not in scope: " + jump.target().name());
                }
            }
            case Statements.Declaration declaration -> {
                if (different(declaration.variable().owner(), currentBlock.scope())) {
                    throw new IllegalArgumentException("Variable declaration belongs to another block: " + declaration.variable().name());
                }
                visible.add(declaration.variable());
            }
            case Statements.InitializedDeclaration declaration -> {
                if (different(declaration.variable().owner(), currentBlock.scope())) {
                    throw new IllegalArgumentException("Variable declaration belongs to another block: " + declaration.variable().name());
                }
                validateExpression(declaration.initializer(), visible, activeLoops, activeBlocks, activeExpressions, visibleLabels);
                visible.add(declaration.variable());
            }
            case CodeBlock block -> validateBlock(block, visible, activeLoops, activeBlocks, activeExpressions, visibleLabels, false);
            case IfStatement ifStatement -> {
                validateExpression(ifStatement.condition(), visible, activeLoops, activeBlocks, activeExpressions, visibleLabels);
                validateBlock(ifStatement.ifTrue(), visible, activeLoops, activeBlocks, activeExpressions, visibleLabels, false);
                validateBlock(ifStatement.ifFalse(), visible, activeLoops, activeBlocks, activeExpressions, visibleLabels, false);
            }
            case ForLoop forLoop -> {
                Set<LocalValue> loopVisible = copy(visible);
                validateBlock(forLoop.initializer(), loopVisible, activeLoops, activeBlocks, activeExpressions, visibleLabels, true);
                validateExpression(forLoop.condition(), loopVisible, activeLoops, activeBlocks, activeExpressions, visibleLabels);
                Set<Object> nestedLoops = copy(activeLoops);
                nestedLoops.add(forLoop.target());
                validateBlock(forLoop.body(), loopVisible, nestedLoops, activeBlocks, activeExpressions, visibleLabels, false);
                validateBlock(forLoop.update(), loopVisible, nestedLoops, activeBlocks, activeExpressions, visibleLabels, false);
            }
            case WhileLoop whileLoop -> {
                validateExpression(whileLoop.condition(), visible, activeLoops, activeBlocks, activeExpressions, visibleLabels);
                Set<Object> nestedLoops = copy(activeLoops);
                nestedLoops.add(whileLoop.target());
                validateBlock(whileLoop.body(), visible, nestedLoops, activeBlocks, activeExpressions, visibleLabels, false);
            }
            case DoWhileLoop doWhileLoop -> {
                Set<Object> nestedLoops = copy(activeLoops);
                nestedLoops.add(doWhileLoop.target());
                validateBlock(doWhileLoop.body(), visible, nestedLoops, activeBlocks, activeExpressions, visibleLabels, false);
                validateExpression(doWhileLoop.condition(), visible, activeLoops, activeBlocks, activeExpressions, visibleLabels);
            }
            case LoopJump jump -> {
                if (!activeLoops.contains(jump.target())) {
                    throw new IllegalArgumentException("Loop jump is outside its target loop");
                }
            }
            case SwitchStatement switchStatement -> {
                validateExpression(switchStatement.expression(), visible, activeLoops, activeBlocks, activeExpressions, visibleLabels);
                switchStatement.cases().forEach(caseValue -> validateBlock(caseValue.body(), visible, activeLoops, activeBlocks, activeExpressions, visibleLabels, false));
                validateBlock(switchStatement.defaultCase(), visible, activeLoops, activeBlocks, activeExpressions, visibleLabels, false);
            }
            case TryCatch tryCatch -> {
                validateBlock(tryCatch.tryBlock(), visible, activeLoops, activeBlocks, activeExpressions, visibleLabels, false);
                for (TryCatch.CatchClause catchClause : tryCatch.catches()) {
                    Set<LocalValue> catchVisible = copy(visible);
                    catchVisible.add(catchClause.variable());
                    validateBlock(catchClause.body(), catchVisible, activeLoops, activeBlocks, activeExpressions, visibleLabels, false);
                }
                tryCatch.finallyBlock().ifPresent(finallyBlock ->
                        validateBlock(finallyBlock, visible, activeLoops, activeBlocks, activeExpressions, visibleLabels, false));
            }
        }
    }

    private static void validateExpression(
            BytecodeExpression expression,
            Set<LocalValue> visible,
            Set<Object> activeLoops,
            Set<CodeBlock> activeBlocks,
            Set<SyntheticExpression> activeExpressions,
            Set<CodeLabel> visibleLabels)
    {
        record Pending(BytecodeExpression expression, Set<LocalValue> visible) {}

        record SyntheticExit(SyntheticExpression expression) {}

        ArrayDeque<Object> pending = new ArrayDeque<>();
        ArrayList<SyntheticExpression> enteredSynthetics = new ArrayList<>();
        pending.push(new Pending(expression, visible));
        try {
            while (!pending.isEmpty()) {
                Object item = pending.pop();
                if (item instanceof SyntheticExit exit) {
                    activeExpressions.remove(exit.expression());
                    enteredSynthetics.removeLast();
                    continue;
                }

                Pending current = (Pending) item;
                switch (current.expression()) {
                    case LocalValue local -> {
                        if (!current.visible().contains(local)) {
                            throw new IllegalArgumentException("Variable is not in scope: " + local.name());
                        }
                    }
                    case CoreExpression core -> {
                        List<BytecodeExpression> children = core.node().children();
                        for (int index = children.size() - 1; index >= 0; index--) {
                            pending.push(new Pending(children.get(index), current.visible()));
                        }
                    }
                    case SyntheticExpression synthetic -> {
                        if (!activeExpressions.add(synthetic)) {
                            throw new IllegalArgumentException("Recursive synthetic expression: " + synthetic);
                        }
                        enteredSynthetics.add(synthetic);
                        ExpressionPlan plan = synthetic.expansion(ExpansionContext.INSTANCE);
                        if (same(plan.value(), synthetic)) {
                            throw new IllegalArgumentException("Synthetic expression expands to itself: " + synthetic);
                        }
                        if (!plan.value().type().equals(synthetic.type())) {
                            throw new IllegalArgumentException("Expansion value type %s does not match expression type %s"
                                    .formatted(plan.value().type().displayName(), synthetic.type().displayName()));
                        }
                        Set<LocalValue> expansionVisible = copy(current.visible());
                        validateBlock(plan.setup(), expansionVisible, activeLoops, activeBlocks, activeExpressions, visibleLabels, true);
                        pending.push(new SyntheticExit(synthetic));
                        pending.push(new Pending(plan.value(), expansionVisible));
                    }
                }
            }
        }
        finally {
            enteredSynthetics.forEach(activeExpressions::remove);
        }
    }

    private static String blockDescription(CodeBlock block)
    {
        return block.description() == null ? "" : ": " + block.description();
    }

    private static <T> Set<T> identitySet()
    {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static <T> Set<T> copy(Set<T> source)
    {
        Set<T> copy = identitySet();
        copy.addAll(source);
        return copy;
    }

    private enum ConstructorState
    {
        UNINITIALIZED,
        INITIALIZED,
    }

    private enum AbruptCompletion
    {
        NONE,
        RETURN,
        THROW,
    }

    private static final class ConstructorValidator
    {
        private final ClassModel definition;
        private final MethodDefinition.Model method;
        private final Set<SyntheticExpression> activeExpressions = identitySet();
        private final Map<CodeLabel, Set<ConstructorState>> jumpStates = new IdentityHashMap<>();
        private boolean jumpStatesChanged;

        private ConstructorValidator(ClassModel definition, MethodDefinition.Model method)
        {
            this.definition = definition;
            this.method = method;
        }

        private void validate(CodeBlock body)
        {
            Set<ConstructorState> result;
            do {
                jumpStatesChanged = false;
                result = analyzeBlock(body, EnumSet.of(ConstructorState.UNINITIALIZED), true);
            }
            while (jumpStatesChanged);
            if (result.contains(ConstructorState.UNINITIALIZED)) {
                throw new IllegalArgumentException("Constructor can complete without invoking this(...) or super(...): " + method);
            }
        }

        private Set<ConstructorState> analyzeBlock(CodeBlock block, Set<ConstructorState> incoming, boolean constructorInvocationAllowed)
        {
            Set<ConstructorState> states = copyStates(incoming);
            for (Statement statement : block.statements()) {
                states = analyzeStatement(statement, states, constructorInvocationAllowed);
            }
            return states;
        }

        private Set<ConstructorState> analyzeStatement(
                Statement statement,
                Set<ConstructorState> states,
                boolean constructorInvocationAllowed)
        {
            if (statement instanceof Statements.LabelBinding binding) {
                Set<ConstructorState> merged = copyStates(states);
                merged.addAll(jumpStates.getOrDefault(binding.label(), Set.of()));
                return merged;
            }
            if (states.isEmpty()) {
                return states;
            }
            return switch (statement) {
                case BytecodeExpression expression -> analyzeExpressionStatement(expression, states, constructorInvocationAllowed);
                case Statements.Expression expression -> analyzeExpressionStatement(expression.expression(), states, constructorInvocationAllowed);
                case Statements.Comment _,
                     Statements.Declaration _ -> states;
                case Statements.InitializedDeclaration declaration -> {
                    validateExpression(declaration.initializer(), states, constructorInvocationAllowed);
                    yield states;
                }
                case Statements.ConstructorInvocation invocation -> analyzeConstructorInvocation(invocation, states, constructorInvocationAllowed);
                case Statements.Jump jump -> {
                    Set<ConstructorState> targetStates = jumpStates.computeIfAbsent(jump.target(), _ -> EnumSet.noneOf(ConstructorState.class));
                    jumpStatesChanged |= targetStates.addAll(states);
                    yield EnumSet.noneOf(ConstructorState.class);
                }
                case LoopJump _ -> EnumSet.noneOf(ConstructorState.class);
                case CodeBlock nested -> analyzeBlock(nested, states, constructorInvocationAllowed);
                case IfStatement ifStatement -> {
                    validateExpression(ifStatement.condition(), states, constructorInvocationAllowed);
                    Set<ConstructorState> result = analyzeBlock(ifStatement.ifTrue(), states, constructorInvocationAllowed);
                    result.addAll(analyzeBlock(ifStatement.ifFalse(), states, constructorInvocationAllowed));
                    yield result;
                }
                case ForLoop forLoop -> analyzeForLoop(forLoop, states, constructorInvocationAllowed);
                case WhileLoop whileLoop -> analyzeWhileLoop(whileLoop, states, constructorInvocationAllowed);
                case DoWhileLoop doWhileLoop -> analyzeDoWhileLoop(doWhileLoop, states, constructorInvocationAllowed);
                case SwitchStatement switchStatement -> analyzeSwitch(switchStatement, states, constructorInvocationAllowed);
                case TryCatch tryCatch -> analyzeTryCatch(tryCatch, states);
                case Statements.LabelBinding _ -> throw new AssertionError("handled above");
            };
        }

        private Set<ConstructorState> analyzeExpressionStatement(
                BytecodeExpression expression,
                Set<ConstructorState> states,
                boolean constructorInvocationAllowed)
        {
            validateExpression(expression, states, constructorInvocationAllowed);
            return switch (abruptCompletion(expression)) {
                case NONE -> states;
                case THROW -> EnumSet.noneOf(ConstructorState.class);
                case RETURN -> {
                    if (states.contains(ConstructorState.UNINITIALIZED)) {
                        throw new IllegalArgumentException("Constructor returns before invoking this(...) or super(...): " + method);
                    }
                    yield EnumSet.noneOf(ConstructorState.class);
                }
            };
        }

        private Set<ConstructorState> analyzeConstructorInvocation(
                Statements.ConstructorInvocation invocation,
                Set<ConstructorState> states,
                boolean constructorInvocationAllowed)
        {
            if (!constructorInvocationAllowed) {
                throw new IllegalArgumentException("Constructor invocation is not valid inside a loop or protected region: " + invocation);
            }
            if (states.contains(ConstructorState.INITIALIZED)) {
                throw new IllegalArgumentException("Constructor invokes this(...) or super(...) more than once on a control-flow path: " + method);
            }
            ClassDesc expectedOwner = switch (invocation.target()) {
                case SUPER -> definition.superClass();
                case THIS -> definition.type();
            };
            if (invocation.declaredOwner() != null && !invocation.declaredOwner().equals(expectedOwner)) {
                throw new IllegalArgumentException("Constructor target %s does not match %s"
                        .formatted(invocation.declaredOwner().displayName(), expectedOwner.displayName()));
            }
            if (invocation.target() == Statements.ConstructorTarget.THIS && definition.methods().stream()
                    .noneMatch(candidate -> candidate.isConstructor() && candidate.methodType().equals(invocation.constructorType()))) {
                throw new IllegalArgumentException("Constructor does not exist: " + invocation.constructorType().descriptorString());
            }
            invocation.arguments().forEach(argument -> validateExpression(argument, states, constructorInvocationAllowed));
            return EnumSet.of(ConstructorState.INITIALIZED);
        }

        private Set<ConstructorState> analyzeForLoop(
                ForLoop loop,
                Set<ConstructorState> states,
                boolean constructorInvocationAllowed)
        {
            Set<ConstructorState> initialized = analyzeBlock(loop.initializer(), states, constructorInvocationAllowed);
            validateExpression(loop.condition(), initialized, constructorInvocationAllowed);
            analyzeBlock(loop.body(), initialized, false);
            analyzeBlock(loop.update(), initialized, false);
            return initialized;
        }

        private Set<ConstructorState> analyzeWhileLoop(
                WhileLoop loop,
                Set<ConstructorState> states,
                boolean constructorInvocationAllowed)
        {
            validateExpression(loop.condition(), states, constructorInvocationAllowed);
            analyzeBlock(loop.body(), states, false);
            return states;
        }

        private Set<ConstructorState> analyzeDoWhileLoop(
                DoWhileLoop loop,
                Set<ConstructorState> states,
                boolean constructorInvocationAllowed)
        {
            analyzeBlock(loop.body(), states, false);
            validateExpression(loop.condition(), states, constructorInvocationAllowed);
            return states;
        }

        private Set<ConstructorState> analyzeSwitch(
                SwitchStatement statement,
                Set<ConstructorState> states,
                boolean constructorInvocationAllowed)
        {
            validateExpression(statement.expression(), states, constructorInvocationAllowed);
            Set<ConstructorState> result = EnumSet.noneOf(ConstructorState.class);
            statement.cases().forEach(caseValue -> result.addAll(analyzeBlock(caseValue.body(), states, constructorInvocationAllowed)));
            result.addAll(analyzeBlock(statement.defaultCase(), states, constructorInvocationAllowed));
            return result;
        }

        private Set<ConstructorState> analyzeTryCatch(TryCatch statement, Set<ConstructorState> states)
        {
            Set<ConstructorState> result = analyzeBlock(statement.tryBlock(), states, false);
            for (TryCatch.CatchClause catchClause : statement.catches()) {
                result.addAll(analyzeBlock(catchClause.body(), states, false));
            }
            if (statement.finallyBlock().isPresent()) {
                result = analyzeBlock(statement.finallyBlock().orElseThrow(), result, false);
            }
            return result;
        }

        private void validateExpression(
                BytecodeExpression expression,
                Set<ConstructorState> states,
                boolean constructorInvocationAllowed)
        {
            if (!states.contains(ConstructorState.UNINITIALIZED)) {
                return;
            }

            record Pending(BytecodeExpression expression, Set<ConstructorState> states, boolean constructorInvocationAllowed) {}

            record SyntheticExit(SyntheticExpression expression) {}

            ArrayDeque<Object> pending = new ArrayDeque<>();
            ArrayList<SyntheticExpression> enteredSynthetics = new ArrayList<>();
            pending.push(new Pending(expression, states, constructorInvocationAllowed));
            try {
                while (!pending.isEmpty()) {
                    Object item = pending.pop();
                    if (item instanceof SyntheticExit exit) {
                        activeExpressions.remove(exit.expression());
                        enteredSynthetics.removeLast();
                        continue;
                    }

                    Pending current = (Pending) item;
                    if (!current.states().contains(ConstructorState.UNINITIALIZED)) {
                        continue;
                    }
                    switch (current.expression()) {
                        case LocalValue local -> {
                            if (local == method.thisVariable()) {
                                throw new IllegalArgumentException("Constructor uses this before invoking this(...) or super(...): " + current.expression());
                            }
                        }
                        case CoreExpression core -> {
                            List<BytecodeExpression> children;
                            if (core.node() instanceof ExpressionNode.FieldSet fieldSet &&
                                    !fieldSet.isStatic() &&
                                    fieldSet.target() == method.thisVariable() &&
                                    fieldSet.owner().equals(definition.type())) {
                                children = List.of(fieldSet.value());
                            }
                            else {
                                children = core.node().children();
                            }
                            for (int index = children.size() - 1; index >= 0; index--) {
                                pending.push(new Pending(children.get(index), current.states(), current.constructorInvocationAllowed()));
                            }
                        }
                        case SyntheticExpression synthetic -> {
                            if (!activeExpressions.add(synthetic)) {
                                throw new IllegalArgumentException("Recursive synthetic expression: " + synthetic);
                            }
                            enteredSynthetics.add(synthetic);
                            ExpressionPlan plan = synthetic.expansion(ExpansionContext.INSTANCE);
                            Set<ConstructorState> result = analyzeBlock(plan.setup(), current.states(), false);
                            pending.push(new SyntheticExit(synthetic));
                            pending.push(new Pending(plan.value(), result, false));
                        }
                    }
                }
            }
            finally {
                enteredSynthetics.forEach(activeExpressions::remove);
            }
        }

        private void rejectConstructorInvocations(CodeBlock block)
        {
            for (Statement statement : block.statements()) {
                switch (statement) {
                    case Statements.ConstructorInvocation invocation -> throw new IllegalArgumentException("Constructor invocation is only valid in a constructor: " + invocation);
                    case BytecodeExpression _,
                         Statements.Expression _,
                         Statements.Comment _,
                         Statements.Declaration _,
                         Statements.InitializedDeclaration _,
                         Statements.Jump _,
                         Statements.LabelBinding _,
                         LoopJump _ -> {}
                    case CodeBlock nested -> rejectConstructorInvocations(nested);
                    case IfStatement ifStatement -> {
                        rejectConstructorInvocations(ifStatement.ifTrue());
                        rejectConstructorInvocations(ifStatement.ifFalse());
                    }
                    case ForLoop forLoop -> {
                        rejectConstructorInvocations(forLoop.initializer());
                        rejectConstructorInvocations(forLoop.update());
                        rejectConstructorInvocations(forLoop.body());
                    }
                    case WhileLoop whileLoop -> rejectConstructorInvocations(whileLoop.body());
                    case DoWhileLoop doWhileLoop -> rejectConstructorInvocations(doWhileLoop.body());
                    case SwitchStatement switchStatement -> {
                        switchStatement.cases().forEach(caseValue -> rejectConstructorInvocations(caseValue.body()));
                        rejectConstructorInvocations(switchStatement.defaultCase());
                    }
                    case TryCatch tryCatch -> {
                        rejectConstructorInvocations(tryCatch.tryBlock());
                        tryCatch.catches().forEach(catchClause -> rejectConstructorInvocations(catchClause.body()));
                        tryCatch.finallyBlock().ifPresent(this::rejectConstructorInvocations);
                    }
                }
            }
        }

        private static AbruptCompletion abruptCompletion(BytecodeExpression expression)
        {
            if (!(expression instanceof CoreExpression core)) {
                return AbruptCompletion.NONE;
            }
            if (core.node() instanceof ExpressionNode.Adapter adapter) {
                return switch (adapter.keyword()) {
                    case "return" -> AbruptCompletion.RETURN;
                    case "throw" -> AbruptCompletion.THROW;
                    default -> AbruptCompletion.NONE;
                };
            }
            if (core.node() instanceof ExpressionNode.Constant constant && constant.type().equals(CD_void) && constant.value() == null) {
                return AbruptCompletion.RETURN;
            }
            return AbruptCompletion.NONE;
        }

        private static Set<ConstructorState> copyStates(Set<ConstructorState> states)
        {
            return states.isEmpty() ? EnumSet.noneOf(ConstructorState.class) : EnumSet.copyOf(states);
        }
    }
}
