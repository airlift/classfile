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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.airlift.classfile.Identity.different;
import static io.airlift.classfile.Identity.same;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.lang.reflect.AccessFlag.SYNTHETIC;
import static java.util.Objects.requireNonNull;

final class ExpressionPlanner
{
    private ExpressionPlanner() {}

    static Result plan(ClassModel definition, CompilationPolicy policy, boolean hiddenClass)
    {
        requireNonNull(definition, "definition is null");
        requireNonNull(policy, "policy is null");
        Planner planner = new Planner(
                definition.type(),
                Math.max(32, policy.targetMethodCodeLimit() / 2),
                hiddenClass,
                definition.methods().stream().map(MethodDefinition.Model::name).collect(Collectors.toSet()));
        ArrayList<MethodDefinition.Model> methods = new ArrayList<>(definition.methods().size());
        boolean changed = false;
        for (MethodDefinition.Model method : definition.methods()) {
            MethodDefinition.Model rewritten = planner.method(method);
            methods.add(rewritten);
            changed |= different(rewritten, method);
        }
        if (!changed && planner.helpers.isEmpty()) {
            return new Result(definition, List.of(), Set.copyOf(planner.lambdaImplementations));
        }
        ArrayList<MethodDefinition.Model> physicalMethods = new ArrayList<>(methods.size() + planner.helpers.size());
        physicalMethods.addAll(methods);
        physicalMethods.addAll(planner.helpers);
        ClassModel physical = new ClassModel(
                definition.access(),
                definition.type(),
                definition.superClass(),
                definition.interfaces(),
                definition.fields(),
                physicalMethods,
                definition.kind(),
                definition.recordComponents(),
                definition.signature(),
                definition.sourceFile(),
                definition.visibleAnnotations(),
                definition.invisibleAnnotations());
        return new Result(physical, List.copyOf(planner.names), Set.copyOf(planner.lambdaImplementations));
    }

    record Result(ClassModel model, List<String> generatedMethods, Set<HiddenClassLinkage.MethodReference> lambdaImplementations) {}

    private static final class Planner
    {
        private final ClassDesc owner;
        private final int expressionBudget;
        private final boolean hiddenClass;
        private final ArrayList<MethodDefinition.Model> helpers = new ArrayList<>();
        private final ArrayList<String> names = new ArrayList<>();
        private final Set<HiddenClassLinkage.MethodReference> lambdaImplementations = new LinkedHashSet<>();
        private final Set<String> usedMethodNames;
        private String logicalMethod;
        private Optional<Variable> logicalReceiver = Optional.empty();
        private int nextHelper;

        private Planner(ClassDesc owner, int expressionBudget, boolean hiddenClass, Set<String> usedMethodNames)
        {
            this.owner = owner;
            this.expressionBudget = expressionBudget;
            this.hiddenClass = hiddenClass;
            this.usedMethodNames = new LinkedHashSet<>(usedMethodNames);
        }

        private MethodDefinition.Model method(MethodDefinition.Model method)
        {
            if (!method.hasBody() || method.isClassInitializer()) {
                return method;
            }
            logicalMethod = sanitize(method.name());
            logicalReceiver = method.receiver();
            CodeBlock body = block(method.body());
            if (body == method.body()) {
                return method;
            }
            return new MethodDefinition.Model(
                    method.declaringType(),
                    method.access(),
                    method.name(),
                    method.methodType(),
                    method.parameters(),
                    method.parameterMetadata(),
                    method.receiver(),
                    Optional.of(body),
                    method.exceptions(),
                    method.signature(),
                    method.comment(),
                    method.visibleAnnotations(),
                    method.invisibleAnnotations());
        }

        private CodeBlock block(CodeBlock block)
        {
            boolean changed = false;
            ArrayList<Statement> statements = new ArrayList<>(block.statements().size());
            for (Statement statement : block.statements()) {
                Statement rewritten = statement(statement);
                statements.add(rewritten);
                changed |= different(rewritten, statement);
            }
            return changed ? block.withStatements(statements) : block;
        }

        private Statement statement(Statement statement)
        {
            return switch (statement) {
                case BytecodeExpression expression -> expression(expression, false);
                case Statements.Expression expression -> {
                    BytecodeExpression rewritten = expression(expression.expression(), false);
                    yield same(rewritten, expression.expression()) ? expression : new Statements.Expression(rewritten);
                }
                case Statements.InitializedDeclaration declaration -> {
                    BytecodeExpression initializer = expression(declaration.initializer(), false);
                    yield same(initializer, declaration.initializer()) ? declaration : new Statements.InitializedDeclaration(declaration.variable(), initializer);
                }
                case CodeBlock nested -> block(nested);
                case DoWhileLoop loop -> loop.rewrite(expression(loop.condition(), false), block(loop.body()));
                case ForLoop loop -> loop.rewrite(block(loop.initializer()), expression(loop.condition(), false), block(loop.update()), block(loop.body()));
                case IfStatement ifStatement -> ifStatement.rewrite(
                        expression(ifStatement.condition(), false),
                        block(ifStatement.ifTrue()),
                        block(ifStatement.ifFalse()));
                case LoopJump jump -> jump;
                case SwitchStatement switchStatement -> switchStatement.rewrite(
                        expression(switchStatement.expression(), false),
                        switchStatement.cases().stream()
                                .map(caseValue -> new SwitchStatement.Case(caseValue.key(), block(caseValue.body())))
                                .toList(),
                        block(switchStatement.defaultCase()));
                case TryCatch tryCatch -> tryCatch.rewrite(
                        block(tryCatch.tryBlock()),
                        tryCatch.catches().stream()
                                .map(catchClause -> new TryCatch.CatchClause(catchClause.exceptionType(), catchClause.variable(), block(catchClause.body())))
                                .toList(),
                        tryCatch.finallyBlock().map(this::block));
                case WhileLoop loop -> loop.rewrite(expression(loop.condition(), false), block(loop.body()));
                case Statements.Comment comment -> comment;
                case Statements.ConstructorInvocation invocation -> {
                    ArrayList<BytecodeExpression> arguments = new ArrayList<>(invocation.arguments().size());
                    boolean changed = false;
                    for (BytecodeExpression argument : invocation.arguments()) {
                        BytecodeExpression rewritten = expression(argument, false);
                        arguments.add(rewritten);
                        changed |= different(rewritten, argument);
                    }
                    yield changed ? new Statements.ConstructorInvocation(
                            invocation.target(),
                            invocation.declaredOwner(),
                            invocation.constructorType(),
                            arguments) : invocation;
                }
                case Statements.Declaration declaration -> declaration;
                case Statements.Jump jump -> jump;
                case Statements.LabelBinding binding -> binding;
            };
        }

        private BytecodeExpression expression(BytecodeExpression expression, boolean conditional)
        {
            return planExpression(expression, conditional).expression();
        }

        private PlannedExpression planExpression(BytecodeExpression expression, boolean conditional)
        {
            ArrayDeque<ExpressionFrame> pending = new ArrayDeque<>();
            pending.push(new ExpressionFrame(expression, conditional));
            PlannedExpression completed = null;
            while (!pending.isEmpty()) {
                ExpressionFrame frame = pending.peek();
                if (completed != null) {
                    frame.rewrittenChildren.add(completed);
                    completed = null;
                }

                if (frame.expression instanceof LocalValue) {
                    pending.pop();
                    completed = new PlannedExpression(frame.expression, 4, false);
                    continue;
                }
                if (frame.expression instanceof SyntheticExpression synthetic) {
                    pending.pop();
                    ExpressionPlan plan = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                    if (!plan.value().type().equals(synthetic.type())) {
                        throw new CompilationException("Synthetic expression expansion has type %s but declared %s: %s"
                                .formatted(plan.value().type().displayName(), synthetic.type().displayName(), synthetic));
                    }
                    CodeBlock setup = block(plan.setup());
                    PlannedExpression value = planExpression(plan.value(), frame.conditional);
                    completed = new PlannedExpression(
                            new SnapshotExpression(synthetic.type(), setup, value.expression(), synthetic.toString()),
                            estimate(setup) + value.estimate(),
                            !setup.isEmpty() || value.setupConstrained());
                    continue;
                }

                CoreExpression core = (CoreExpression) frame.expression;
                ExpressionNode node = core.node();
                if (hiddenClass && node instanceof ExpressionNode.InvokeDynamic invokeDynamic) {
                    HiddenClassLinkage.implementationMethod(invokeDynamic.callSite(), owner).ifPresent(lambdaImplementations::add);
                }
                List<BytecodeExpression> children = node.children();
                if (frame.rewrittenChildren.size() < children.size()) {
                    BytecodeExpression child = children.get(frame.rewrittenChildren.size());
                    pending.push(new ExpressionFrame(child, conditionalChild(node, child)));
                    continue;
                }

                boolean childrenChanged = false;
                for (int index = 0; index < children.size(); index++) {
                    childrenChanged |= different(children.get(index), frame.rewrittenChildren.get(index).expression());
                }
                ExpressionNode rewrittenNode = node;
                if (childrenChanged) {
                    Iterator<PlannedExpression> rewrittenChildren = frame.rewrittenChildren.iterator();
                    rewrittenNode = rewriteChildren(node, _ -> rewrittenChildren.next().expression());
                }
                BytecodeExpression rewritten = childrenChanged ? new CoreExpression(rewrittenNode) : frame.expression;
                int rewrittenEstimate = 8 + frame.rewrittenChildren.stream().mapToInt(PlannedExpression::estimate).sum();
                boolean setupConstrained = frame.rewrittenChildren.stream().anyMatch(PlannedExpression::setupConstrained);
                if (!setupConstrained && isExtractionCandidate(rewrittenNode) && rewrittenEstimate > expressionBudget) {
                    rewritten = extract(rewritten, frame.conditional);
                    rewrittenEstimate = estimate(rewritten);
                }
                pending.pop();
                completed = new PlannedExpression(rewritten, rewrittenEstimate, setupConstrained);
            }
            return requireNonNull(completed, "expression plan is null");
        }

        private static final class ExpressionFrame
        {
            private final BytecodeExpression expression;
            private final boolean conditional;
            private final ArrayList<PlannedExpression> rewrittenChildren = new ArrayList<>();

            private ExpressionFrame(BytecodeExpression expression, boolean conditional)
            {
                this.expression = expression;
                this.conditional = conditional;
            }
        }

        private record PlannedExpression(BytecodeExpression expression, int estimate, boolean setupConstrained) {}

        private BytecodeExpression extract(BytecodeExpression expression, boolean conditional)
        {
            LinkedHashSet<LocalValue> locals = new LinkedHashSet<>();
            collectLocals(expression, locals, Collections.newSetFromMap(new IdentityHashMap<>()));
            Optional<Variable> helperReceiver = hiddenClass
                    ? logicalReceiver.filter(receiver -> locals.stream().anyMatch(local -> same(local, receiver)))
                    : Optional.empty();
            if (hiddenClass && (referencesOwner(expression.type()) || locals.stream().anyMatch(local -> referencesOwner(local.type()) && !isReceiver(local, helperReceiver)))) {
                return expression;
            }
            int slots = locals.stream().mapToInt(local -> slotSize(local.type())).sum();
            if (slots > 255) {
                return expression;
            }

            String name;
            do {
                name = logicalMethod + "$expression$" + ++nextHelper;
            }
            while (!usedMethodNames.add(name));
            ArrayList<Parameter> parameters = new ArrayList<>(locals.size());
            IdentityHashMap<LocalValue, BytecodeExpression> replacements = new IdentityHashMap<>();
            LinkedHashMap<String, Integer> names = new LinkedHashMap<>();
            for (LocalValue local : locals) {
                if (isReceiver(local, helperReceiver)) {
                    continue;
                }
                String baseName = local.name().equals("this") ? "receiver" : sanitize(local.name());
                int occurrence = names.merge(baseName, 1, Integer::sum);
                Parameter parameter = Parameter.arg(occurrence == 1 ? baseName : baseName + occurrence, local.type());
                parameters.add(parameter);
                replacements.put(local, parameter);
            }

            MethodDefinition helper = new MethodDefinition(owner, name, expression.type(), parameters);
            if (helperReceiver.isPresent()) {
                helper.access(PRIVATE, SYNTHETIC);
                replacements.put(helperReceiver.orElseThrow(), helper.thisVariable());
            }
            else {
                helper.access(PRIVATE, STATIC, SYNTHETIC);
            }
            helper.comment((conditional ? "conditional " : "") + "expression extracted from " + logicalMethod);
            helper.body().ret(rewrite(expression, replacements::get));
            helpers.add(helper.build());
            this.names.add(name);
            BytecodeExpression[] arguments = locals.stream()
                    .filter(local -> !isReceiver(local, helperReceiver))
                    .toArray(BytecodeExpression[]::new);
            if (helperReceiver.isPresent()) {
                return helperReceiver.orElseThrow().invokeSpecial(owner, name, helper.methodType(), arguments);
            }
            return BytecodeExpressions.invokeStatic(owner, name, helper.methodType(), arguments);
        }

        private static boolean isReceiver(LocalValue local, Optional<Variable> receiver)
        {
            return receiver.filter(value -> same(local, value)).isPresent();
        }

        private boolean referencesOwner(ClassDesc type)
        {
            while (type.isArray()) {
                type = type.componentType();
            }
            return type.equals(owner);
        }
    }

    private record SnapshotExpression(ClassDesc type, CodeBlock setup, BytecodeExpression value, String rendering)
            implements SyntheticExpression
    {
        private SnapshotExpression
        {
            requireNonNull(type, "type is null");
            requireNonNull(setup, "setup is null");
            requireNonNull(value, "value is null");
            requireNonNull(rendering, "rendering is null");
        }

        @Override
        public ExpressionPlan expansion(ExpansionContext context)
        {
            requireNonNull(context, "context is null");
            return new ExpressionPlan(setup, value);
        }

        @Override
        public String toString()
        {
            return rendering;
        }
    }

    private static boolean conditionalChild(ExpressionNode parent, BytecodeExpression child)
    {
        return switch (parent) {
            case ExpressionNode.Binary binary -> (binary.operator().equals("&&") || binary.operator().equals("||")) && same(child, binary.right());
            case ExpressionNode.InlineIf inlineIf -> same(child, inlineIf.ifTrue()) || same(child, inlineIf.ifFalse());
            case ExpressionNode.Constant _,
                 ExpressionNode.Unary _,
                 ExpressionNode.Cast _,
                 ExpressionNode.InstanceOf _,
                 ExpressionNode.ArrayLength _,
                 ExpressionNode.ArrayGet _,
                 ExpressionNode.ArraySet _,
                 ExpressionNode.NewArray _,
                 ExpressionNode.FieldGet _,
                 ExpressionNode.FieldSet _,
                 ExpressionNode.Invoke _,
                 ExpressionNode.NewInstance _,
                 ExpressionNode.DynamicConstant _,
                 ExpressionNode.BoundConstant _,
                 ExpressionNode.BoundMethodHandleInvocation _,
                 ExpressionNode.LinkedMethodInvocation _,
                 ExpressionNode.InvokeDynamic _,
                 ExpressionNode.SetVariable _,
                 ExpressionNode.Increment _,
                 ExpressionNode.Adapter _ -> false;
        };
    }

    private static boolean isExtractionCandidate(ExpressionNode node)
    {
        return !node.type().equals(CD_void) && switch (node) {
            case ExpressionNode.Constant _,
                 ExpressionNode.DynamicConstant _,
                 ExpressionNode.BoundConstant _,
                 ExpressionNode.SetVariable _,
                 ExpressionNode.Increment _,
                 ExpressionNode.Adapter _ -> false;
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
                 ExpressionNode.InvokeDynamic _ -> true;
            case ExpressionNode.BoundMethodHandleInvocation _ -> false;
        };
    }

    static int estimate(BytecodeExpression expression)
    {
        int estimate = 0;
        ArrayDeque<BytecodeExpression> pending = new ArrayDeque<>();
        pending.push(expression);
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case LocalValue _ -> estimate += 4;
                case SyntheticExpression synthetic -> {
                    ExpressionPlan expansion = synthetic.expansion(ExpansionContext.INSTANCE);
                    estimate += estimate(expansion.setup());
                    pending.push(expansion.value());
                }
                case CoreExpression core -> {
                    estimate += 8;
                    core.node().children().forEach(pending::push);
                }
            }
        }
        return estimate;
    }

    static int estimate(CodeBlock block)
    {
        return block.statements().stream().mapToInt(ExpressionPlanner::estimate).sum();
    }

    static int estimate(Statement statement)
    {
        return switch (statement) {
            case BytecodeExpression expression -> estimate(expression);
            case Statements.Expression expression -> estimate(expression.expression());
            case Statements.InitializedDeclaration declaration -> 4 + estimate(declaration.initializer());
            case Statements.Declaration _,
                 Statements.Comment _,
                 Statements.LabelBinding _ -> 0;
            case CodeBlock block -> estimate(block);
            case IfStatement value -> 12 + estimate(value.condition()) + estimate(value.ifTrue()) + estimate(value.ifFalse());
            case ForLoop value -> 16 + estimate(value.initializer()) + estimate(value.condition()) + estimate(value.update()) + estimate(value.body());
            case WhileLoop value -> 12 + estimate(value.condition()) + estimate(value.body());
            case DoWhileLoop value -> 12 + estimate(value.condition()) + estimate(value.body());
            case SwitchStatement value -> 16 + estimate(value.expression()) + value.cases().stream().mapToInt(item -> estimate(item.body())).sum() + estimate(value.defaultCase());
            case TryCatch value -> 24 + estimate(value.tryBlock()) + value.catches().stream().mapToInt(item -> estimate(item.body())).sum() + value.finallyBlock().map(ExpressionPlanner::estimate).orElse(0);
            case LoopJump _,
                 Statements.Jump _ -> 8;
            case Statements.ConstructorInvocation invocation -> 8 + invocation.arguments().stream().mapToInt(ExpressionPlanner::estimate).sum();
        };
    }

    static Metrics metrics(Statement statement)
    {
        MetricsCounter counter = new MetricsCounter();
        collectMetrics(statement, counter);
        return new Metrics(counter.estimate, counter.invocations);
    }

    private static void collectMetrics(Statement statement, MetricsCounter counter)
    {
        switch (statement) {
            case BytecodeExpression expression -> collectMetrics(expression, counter);
            case Statements.Expression expression -> collectMetrics(expression.expression(), counter);
            case Statements.InitializedDeclaration declaration -> {
                counter.estimate += 4;
                collectMetrics(declaration.initializer(), counter);
            }
            case Statements.Declaration _,
                 Statements.Comment _,
                 Statements.LabelBinding _ -> {}
            case CodeBlock block -> block.statements().forEach(child -> collectMetrics(child, counter));
            case IfStatement value -> {
                counter.estimate += 12;
                collectMetrics(value.condition(), counter);
                collectMetrics(value.ifTrue(), counter);
                collectMetrics(value.ifFalse(), counter);
            }
            case ForLoop value -> {
                counter.estimate += 16;
                collectMetrics(value.initializer(), counter);
                collectMetrics(value.condition(), counter);
                collectMetrics(value.update(), counter);
                collectMetrics(value.body(), counter);
            }
            case WhileLoop value -> {
                counter.estimate += 12;
                collectMetrics(value.condition(), counter);
                collectMetrics(value.body(), counter);
            }
            case DoWhileLoop value -> {
                counter.estimate += 12;
                collectMetrics(value.condition(), counter);
                collectMetrics(value.body(), counter);
            }
            case SwitchStatement value -> {
                counter.estimate += 16;
                collectMetrics(value.expression(), counter);
                value.cases().forEach(item -> collectMetrics(item.body(), counter));
                collectMetrics(value.defaultCase(), counter);
            }
            case TryCatch value -> {
                counter.estimate += 24;
                collectMetrics(value.tryBlock(), counter);
                value.catches().forEach(item -> collectMetrics(item.body(), counter));
                value.finallyBlock().ifPresent(block -> collectMetrics(block, counter));
            }
            case LoopJump _,
                 Statements.Jump _ -> counter.estimate += 8;
            case Statements.ConstructorInvocation invocation -> {
                counter.estimate += 8;
                counter.invocations++;
                invocation.arguments().forEach(argument -> collectMetrics(argument, counter));
            }
        }
    }

    private static void collectMetrics(BytecodeExpression expression, MetricsCounter counter)
    {
        switch (expression) {
            case LocalValue _ -> counter.estimate += 4;
            case SyntheticExpression synthetic -> {
                ExpressionPlan expansion = synthetic.expansion(ExpansionContext.INSTANCE);
                collectMetrics(expansion.setup(), counter);
                collectMetrics(expansion.value(), counter);
            }
            case CoreExpression core -> {
                counter.estimate += 8;
                counter.invocations += switch (core.node()) {
                    case ExpressionNode.Invoke _,
                         ExpressionNode.NewInstance _,
                         ExpressionNode.BoundMethodHandleInvocation _,
                         ExpressionNode.LinkedMethodInvocation _,
                         ExpressionNode.InvokeDynamic _ -> 1;
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
                         ExpressionNode.FieldGet _,
                         ExpressionNode.FieldSet _,
                         ExpressionNode.DynamicConstant _,
                         ExpressionNode.BoundConstant _,
                         ExpressionNode.SetVariable _,
                         ExpressionNode.Increment _,
                         ExpressionNode.Adapter _ -> 0;
                };
                core.node().children().forEach(child -> collectMetrics(child, counter));
            }
        }
    }

    record Metrics(int estimate, int invocations) {}

    private static final class MetricsCounter
    {
        private int estimate;
        private int invocations;
    }

    static List<LocalValue> locals(BytecodeExpression expression)
    {
        LinkedHashSet<LocalValue> locals = new LinkedHashSet<>();
        collectLocals(expression, locals, Collections.newSetFromMap(new IdentityHashMap<>()));
        return List.copyOf(locals);
    }

    static List<LocalValue> locals(Statement statement)
    {
        LinkedHashSet<LocalValue> locals = new LinkedHashSet<>();
        collectLocals(statement, locals, Collections.newSetFromMap(new IdentityHashMap<>()));
        return List.copyOf(locals);
    }

    static boolean canRewriteValue(BytecodeExpression expression)
    {
        ArrayDeque<BytecodeExpression> pending = new ArrayDeque<>();
        pending.push(requireNonNull(expression, "expression is null"));
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case LocalValue _ -> {}
                case CoreExpression core -> core.node().children().forEach(pending::push);
                case SyntheticExpression synthetic -> {
                    ExpressionPlan plan = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                    if (!plan.setup().isEmpty()) {
                        return false;
                    }
                    pending.push(plan.value());
                }
            }
        }
        return true;
    }

    private static void collectLocals(BytecodeExpression expression, Set<LocalValue> locals, Set<SyntheticExpression> active)
    {
        switch (expression) {
            case LocalValue local -> locals.add(local);
            case CoreExpression core -> core.node().children().forEach(child -> collectLocals(child, locals, active));
            case SyntheticExpression synthetic -> {
                if (!active.add(synthetic)) {
                    throw new CompilationException("Recursive synthetic expression: " + synthetic);
                }
                try {
                    ExpressionPlan plan = synthetic.expansion(ExpansionContext.INSTANCE);
                    plan.setup().statements().forEach(statement -> collectLocals(statement, locals, active));
                    collectLocals(plan.value(), locals, active);
                }
                finally {
                    active.remove(synthetic);
                }
            }
        }
    }

    private static void collectLocals(Statement statement, Set<LocalValue> locals, Set<SyntheticExpression> active)
    {
        switch (statement) {
            case BytecodeExpression expression -> collectLocals(expression, locals, active);
            case Statements.Expression expression -> collectLocals(expression.expression(), locals, active);
            case Statements.InitializedDeclaration declaration -> collectLocals(declaration.initializer(), locals, active);
            case Statements.Declaration _,
                 Statements.Comment _,
                 LoopJump _,
                 Statements.Jump _,
                 Statements.LabelBinding _ -> {}
            case CodeBlock block -> block.statements().forEach(item -> collectLocals(item, locals, active));
            case IfStatement value -> {
                collectLocals(value.condition(), locals, active);
                collectLocals(value.ifTrue(), locals, active);
                collectLocals(value.ifFalse(), locals, active);
            }
            case ForLoop value -> {
                collectLocals(value.initializer(), locals, active);
                collectLocals(value.condition(), locals, active);
                collectLocals(value.update(), locals, active);
                collectLocals(value.body(), locals, active);
            }
            case WhileLoop value -> {
                collectLocals(value.condition(), locals, active);
                collectLocals(value.body(), locals, active);
            }
            case DoWhileLoop value -> {
                collectLocals(value.body(), locals, active);
                collectLocals(value.condition(), locals, active);
            }
            case SwitchStatement value -> {
                collectLocals(value.expression(), locals, active);
                value.cases().forEach(item -> collectLocals(item.body(), locals, active));
                collectLocals(value.defaultCase(), locals, active);
            }
            case TryCatch value -> {
                collectLocals(value.tryBlock(), locals, active);
                value.catches().forEach(item -> collectLocals(item.body(), locals, active));
                value.finallyBlock().ifPresent(item -> collectLocals(item, locals, active));
            }
            case Statements.ConstructorInvocation invocation -> invocation.arguments().forEach(item -> collectLocals(item, locals, active));
        }
    }

    static BytecodeExpression rewrite(BytecodeExpression expression, Function<LocalValue, BytecodeExpression> replacements)
    {
        return switch (expression) {
            case LocalValue local -> requireNonNull(replacements.apply(local), "No helper parameter for " + local.name());
            case SyntheticExpression synthetic -> {
                ExpressionPlan plan = synthetic.expansion(ExpansionContext.INSTANCE);
                if (!plan.setup().isEmpty()) {
                    throw new IllegalArgumentException("Synthetic setup cannot be moved into a value helper: " + synthetic);
                }
                yield rewrite(plan.value(), replacements);
            }
            case CoreExpression core -> new CoreExpression(rewriteChildren(core.node(), child -> rewrite(child, replacements)));
        };
    }

    static CodeBlock rewrite(CodeBlock block, Function<LocalValue, BytecodeExpression> replacements)
    {
        return block.withStatements(block.statements().stream()
                .map(statement -> rewrite(statement, replacements))
                .toList());
    }

    private static Statement rewrite(Statement statement, Function<LocalValue, BytecodeExpression> replacements)
    {
        return switch (statement) {
            case BytecodeExpression expression -> rewriteBlockExpression(expression, replacements);
            case Statements.Expression expression -> new Statements.Expression(rewriteBlockExpression(expression.expression(), replacements));
            case Statements.InitializedDeclaration declaration -> new Statements.InitializedDeclaration(declaration.variable(), rewriteBlockExpression(declaration.initializer(), replacements));
            case CodeBlock block -> rewrite(block, replacements);
            case IfStatement value -> value.rewrite(
                    rewriteBlockExpression(value.condition(), replacements),
                    rewrite(value.ifTrue(), replacements),
                    rewrite(value.ifFalse(), replacements));
            case ForLoop value -> value.rewrite(
                    rewrite(value.initializer(), replacements),
                    rewriteBlockExpression(value.condition(), replacements),
                    rewrite(value.update(), replacements),
                    rewrite(value.body(), replacements));
            case WhileLoop value -> value.rewrite(
                    rewriteBlockExpression(value.condition(), replacements),
                    rewrite(value.body(), replacements));
            case DoWhileLoop value -> value.rewrite(
                    rewriteBlockExpression(value.condition(), replacements),
                    rewrite(value.body(), replacements));
            case SwitchStatement value -> value.rewrite(
                    rewriteBlockExpression(value.expression(), replacements),
                    value.cases().stream()
                            .map(caseValue -> new SwitchStatement.Case(caseValue.key(), rewrite(caseValue.body(), replacements)))
                            .toList(),
                    rewrite(value.defaultCase(), replacements));
            case TryCatch value -> value.rewrite(
                    rewrite(value.tryBlock(), replacements),
                    value.catches().stream()
                            .map(catchClause -> new TryCatch.CatchClause(
                                    catchClause.exceptionType(),
                                    catchClause.variable(),
                                    rewrite(catchClause.body(), replacements)))
                            .toList(),
                    value.finallyBlock().map(finallyBlock -> rewrite(finallyBlock, replacements)));
            case Statements.ConstructorInvocation value -> new Statements.ConstructorInvocation(
                    value.target(),
                    value.declaredOwner(),
                    value.constructorType(),
                    value.arguments().stream().map(argument -> rewriteBlockExpression(argument, replacements)).toList());
            case Statements.Comment value -> value;
            case Statements.Declaration value -> value;
            case LoopJump value -> value;
            case Statements.Jump value -> value;
            case Statements.LabelBinding value -> value;
        };
    }

    private static BytecodeExpression rewriteBlockExpression(BytecodeExpression expression, Function<LocalValue, BytecodeExpression> replacements)
    {
        return switch (expression) {
            case LocalValue local -> requireNonNull(replacements.apply(local), "No helper parameter for " + local.name());
            case SyntheticExpression synthetic -> {
                ExpressionPlan plan = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                yield new SnapshotExpression(
                        synthetic.type(),
                        rewrite(plan.setup(), replacements),
                        rewriteBlockExpression(plan.value(), replacements),
                        synthetic.toString());
            }
            case CoreExpression core -> new CoreExpression(rewriteChildren(core.node(), child -> rewriteBlockExpression(child, replacements)));
        };
    }

    static ExpressionNode rewriteChildren(ExpressionNode node, Function<BytecodeExpression, BytecodeExpression> rewrite)
    {
        return switch (node) {
            case ExpressionNode.Constant value -> value;
            case ExpressionNode.Binary value -> new ExpressionNode.Binary(value.type(), value.operator(), rewrite.apply(value.left()), rewrite.apply(value.right()));
            case ExpressionNode.Unary value -> new ExpressionNode.Unary(value.type(), value.prefix(), rewrite.apply(value.value()));
            case ExpressionNode.Cast value -> new ExpressionNode.Cast(value.type(), rewrite.apply(value.value()));
            case ExpressionNode.InstanceOf value -> new ExpressionNode.InstanceOf(rewrite.apply(value.value()), value.testType());
            case ExpressionNode.InlineIf value -> new ExpressionNode.InlineIf(value.type(), rewrite.apply(value.condition()), rewrite.apply(value.ifTrue()), rewrite.apply(value.ifFalse()));
            case ExpressionNode.ArrayLength value -> new ExpressionNode.ArrayLength(rewrite.apply(value.array()));
            case ExpressionNode.ArrayGet value -> new ExpressionNode.ArrayGet(value.type(), rewrite.apply(value.array()), rewrite.apply(value.index()));
            case ExpressionNode.ArraySet value -> new ExpressionNode.ArraySet(value.type(), rewrite.apply(value.array()), rewrite.apply(value.index()), rewrite.apply(value.value()));
            case ExpressionNode.NewArray value -> new ExpressionNode.NewArray(value.type(), value.length() == null ? null : rewrite.apply(value.length()), value.elements().stream().map(rewrite).toList());
            case ExpressionNode.FieldGet value -> new ExpressionNode.FieldGet(value.type(), value.isStatic() ? null : rewrite.apply(value.target()), value.owner(), value.name(), value.isStatic());
            case ExpressionNode.FieldSet value -> new ExpressionNode.FieldSet(value.type(), value.isStatic() ? null : rewrite.apply(value.target()), value.owner(), value.name(), value.fieldType(), rewrite.apply(value.value()), value.isStatic());
            case ExpressionNode.Invoke value -> new ExpressionNode.Invoke(value.type(), value.kind(), value.kind() == ExpressionNode.InvocationKind.STATIC ? null : rewrite.apply(value.target()), value.owner(), value.name(), value.methodType(), value.arguments().stream().map(rewrite).toList());
            case ExpressionNode.NewInstance value -> new ExpressionNode.NewInstance(value.type(), value.constructorType(), value.arguments().stream().map(rewrite).toList());
            case ExpressionNode.DynamicConstant value -> value;
            case ExpressionNode.BoundConstant value -> value;
            case ExpressionNode.BoundMethodHandleInvocation value -> new ExpressionNode.BoundMethodHandleInvocation(value.type(), value.handle(), value.methodType(), value.arguments().stream().map(rewrite).toList());
            case ExpressionNode.LinkedMethodInvocation value -> new ExpressionNode.LinkedMethodInvocation(value.type(), value.method(), value.arguments().stream().map(rewrite).toList());
            case ExpressionNode.InvokeDynamic value -> new ExpressionNode.InvokeDynamic(value.type(), value.callSite(), value.arguments().stream().map(rewrite).toList());
            case ExpressionNode.SetVariable value -> {
                BytecodeExpression target = requireNonNull(rewrite.apply(value.variable()), "No helper local for " + value.variable().name());
                if (!(target instanceof Variable variable)) {
                    throw new IllegalArgumentException("Assignment target was not mapped to a helper variable: " + value.variable().name());
                }
                yield new ExpressionNode.SetVariable(value.type(), variable, rewrite.apply(value.value()));
            }
            case ExpressionNode.Increment value -> {
                BytecodeExpression target = requireNonNull(rewrite.apply(value.variable()), "No helper local for " + value.variable().name());
                if (!(target instanceof Variable variable)) {
                    throw new IllegalArgumentException("Increment target was not mapped to a helper variable: " + value.variable().name());
                }
                yield new ExpressionNode.Increment(value.type(), variable);
            }
            case ExpressionNode.Adapter value -> new ExpressionNode.Adapter(value.type(), value.keyword(), rewrite.apply(value.value()));
        };
    }

    static int slotSize(ClassDesc type)
    {
        return type.descriptorString().equals("J") || type.descriptorString().equals("D") ? 2 : 1;
    }

    private static String sanitize(String name)
    {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            result.append(Character.isJavaIdentifierPart(character) ? character : '_');
        }
        return result.isEmpty() ? "generated" : result.toString();
    }
}
