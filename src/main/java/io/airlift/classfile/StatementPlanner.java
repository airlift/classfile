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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.airlift.classfile.Identity.different;
import static io.airlift.classfile.Identity.same;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.lang.reflect.AccessFlag.SYNTHETIC;
import static java.util.Objects.requireNonNull;

final class StatementPlanner
{
    // Model estimates are deliberately architecture-independent and are normally larger than
    // emitted bytecode. Keep enough headroom for bound runtime data and structured control flow,
    // whose emitted instructions are comparatively expensive, so helpers remain below the JIT
    // target selected by CompilationPolicy.
    private static final int ESTIMATE_TO_CODE_BUDGET_RATIO = 2;
    private static final int MAX_CONTINUATION_DEPTH = 256;

    private StatementPlanner() {}

    static Result plan(ClassModel definition, CompilationPolicy policy, Set<String> expressionHelpers, boolean hiddenClass)
    {
        requireNonNull(expressionHelpers, "expressionHelpers is null");
        Planner planner = new Planner(
                definition.type(),
                Math.max(64, policy.targetMethodCodeLimit() * ESTIMATE_TO_CODE_BUDGET_RATIO),
                policy.targetMethodCodeLimit(),
                Math.max(1, policy.hardMethodCodeLimit() * 9 / 10),
                hiddenClass,
                expressionHelpers,
                definition.methods().stream().map(MethodDefinition.Model::name).collect(Collectors.toSet()));
        ArrayList<MethodDefinition.Model> methods = new ArrayList<>();
        for (MethodDefinition.Model method : definition.methods()) {
            methods.add(planner.method(method));
        }
        if (planner.helpers.isEmpty()) {
            return new Result(definition, List.of());
        }
        methods.addAll(planner.helpers);
        return new Result(new ClassModel(
                definition.access(),
                definition.type(),
                definition.superClass(),
                definition.interfaces(),
                definition.fields(),
                methods,
                definition.kind(),
                definition.recordComponents(),
                definition.signature(),
                definition.sourceFile(),
                definition.visibleAnnotations(),
                definition.invisibleAnnotations()), List.copyOf(planner.names));
    }

    record Result(ClassModel model, List<String> generatedMethods) {}

    private static final class Planner
    {
        private final ClassDesc owner;
        private final int regionBudget;
        private final int methodBudget;
        private final int continuationHardEstimateBudget;
        private final boolean hiddenClass;
        private final Set<String> expressionHelpers;
        private final ArrayList<MethodDefinition.Model> helpers = new ArrayList<>();
        private final ArrayList<String> names = new ArrayList<>();
        private final Set<String> usedMethodNames;
        private int nextHelper;

        private Planner(
                ClassDesc owner,
                int regionBudget,
                int methodBudget,
                int continuationHardEstimateBudget,
                boolean hiddenClass,
                Set<String> expressionHelpers,
                Set<String> usedMethodNames)
        {
            this.owner = owner;
            this.regionBudget = regionBudget;
            this.methodBudget = methodBudget;
            this.continuationHardEstimateBudget = continuationHardEstimateBudget;
            this.hiddenClass = hiddenClass;
            this.expressionHelpers = Set.copyOf(expressionHelpers);
            this.usedMethodNames = new LinkedHashSet<>(usedMethodNames);
        }

        private MethodDefinition.Model method(MethodDefinition.Model method)
        {
            if (!method.hasBody() || method.isConstructor() || method.isClassInitializer() || expressionHelpers.contains(method.name())) {
                return method;
            }
            if (ExpressionPlanner.estimate(method.body()) <= methodBudget) {
                return method;
            }
            CodeBlock body = extract(method);
            if (body == method.body()) {
                return method;
            }
            return copy(method, body);
        }

        private CodeBlock extract(MethodDefinition.Model method)
        {
            List<Statement> source = method.body().statements();
            Optional<ContinuationExtraction> trailingContinuation = extractTrailingContinuation(method, source);
            if (trailingContinuation.isPresent()) {
                ContinuationExtraction extraction = trailingContinuation.orElseThrow();
                ArrayList<Statement> rewritten = new ArrayList<>(source.subList(0, extraction.start()));
                rewritten.add(extraction.invocation());
                source = List.copyOf(rewritten);
            }
            ArrayList<Statement> result = new ArrayList<>();
            boolean changed = trailingContinuation.isPresent();
            for (int index = 0; index < source.size(); ) {
                if (isEarlyFalseReturn(source.get(index))) {
                    int end = index;
                    int estimated = 0;
                    while (end < source.size() && isEarlyFalseReturn(source.get(end))) {
                        int candidateEstimate = ExpressionPlanner.estimate(source.get(end));
                        if (end > index && estimated + candidateEstimate > regionBudget) {
                            break;
                        }
                        estimated += candidateEstimate;
                        end++;
                    }
                    if (end - index >= 2) {
                        Optional<Statement> continuation = extractConditions(method, source.subList(index, end));
                        if (continuation.isPresent()) {
                            result.add(continuation.orElseThrow());
                            changed = true;
                            index = end;
                            continue;
                        }
                    }
                }
                if (source.get(index) instanceof CodeBlock block && isExtractableBlock(block)) {
                    int end = index;
                    int estimated = 0;
                    while (end < source.size() && source.get(end) instanceof CodeBlock candidate && isExtractableBlock(candidate)) {
                        int candidateEstimate = ExpressionPlanner.estimate(candidate);
                        if (end > index && estimated + candidateEstimate > regionBudget) {
                            break;
                        }
                        estimated += candidateEstimate;
                        end++;
                    }
                    List<CodeBlock> region = source.subList(index, end).stream()
                            .map(CodeBlock.class::cast)
                            .toList();
                    Optional<Statement> invocation = extractBlocks(method, region);
                    if (invocation.isPresent()) {
                        result.add(invocation.orElseThrow());
                        changed = true;
                        index = end;
                        continue;
                    }
                }
                if (!isSimpleExpression(source.get(index))) {
                    result.add(source.get(index++));
                    continue;
                }

                int end = index;
                int estimated = 0;
                LinkedHashSet<Variable> writes = new LinkedHashSet<>();
                while (end < source.size() && isSimpleExpression(source.get(end))) {
                    Statement candidate = source.get(end);
                    Set<Variable> candidateWrites = writtenVariables(candidate);
                    LinkedHashSet<Variable> combinedWrites = new LinkedHashSet<>(writes);
                    combinedWrites.addAll(candidateWrites);
                    int candidateEstimate = ExpressionPlanner.estimate(candidate);
                    if (end > index && (estimated + candidateEstimate > regionBudget || combinedWrites.size() > 1)) {
                        break;
                    }
                    writes = combinedWrites;
                    estimated += candidateEstimate;
                    end++;
                }

                if (end - index < 2) {
                    result.add(source.get(index++));
                    continue;
                }
                List<Statement> region = source.subList(index, end);
                Optional<Statement> invocation = extractRegion(method, region, writes.stream().findFirst());
                if (invocation.isEmpty()) {
                    result.add(source.get(index++));
                    continue;
                }
                result.add(invocation.orElseThrow());
                changed = true;
                index = end;
            }
            return changed ? method.body().withStatements(result) : method.body();
        }

        private Optional<ContinuationExtraction> extractTrailingContinuation(MethodDefinition.Model method, List<Statement> source)
        {
            if (source.size() < 3) {
                return Optional.empty();
            }
            Optional<BytecodeExpression> returnValue = returnValue(source.getLast());
            if (returnValue.isEmpty() || !ExpressionPlanner.canRewriteValue(returnValue.orElseThrow())) {
                return Optional.empty();
            }

            int start = source.size() - 1;
            while (start > 0 && source.get(start - 1) instanceof CodeBlock block && isExtractableBlock(block)) {
                start--;
            }
            if (source.size() - start < 3) {
                return Optional.empty();
            }
            List<CodeBlock> blocks = source.subList(start, source.size() - 1).stream()
                    .map(CodeBlock.class::cast)
                    .toList();

            LinkedHashSet<Variable> declarations = new LinkedHashSet<>();
            blocks.forEach(block -> collectDeclarations(block, declarations));
            LinkedHashSet<Variable> writes = new LinkedHashSet<>();
            blocks.forEach(block -> collectWrittenVariables(block, writes));
            writes.removeAll(declarations);
            if (writes.size() < 2 || writes.stream().anyMatch(variable -> !hasInitializer(method.body(), variable))) {
                return Optional.empty();
            }

            LinkedHashSet<LocalValue> locals = new LinkedHashSet<>();
            blocks.forEach(block -> locals.addAll(ExpressionPlanner.locals(block)));
            locals.addAll(ExpressionPlanner.locals(returnValue.orElseThrow()));
            locals.removeAll(declarations);
            Optional<Variable> receiver = hiddenReceiver(method, locals);
            if (hasUnsupportedOwnerLocal(locals, receiver)) {
                return Optional.empty();
            }
            int parameterSlots = locals.stream().mapToInt(local -> ExpressionPlanner.slotSize(local.type())).sum();
            if (parameterSlots > 255) {
                return Optional.empty();
            }

            List<List<CodeBlock>> regions = partitionContinuationBlocks(blocks, regionBudget);
            if (regions.size() > MAX_CONTINUATION_DEPTH) {
                if (regionBudget >= continuationHardEstimateBudget) {
                    throw continuationDepthException(method, regions.size());
                }
                List<List<CodeBlock>> coarsestRegions = partitionContinuationBlocks(blocks, continuationHardEstimateBudget);
                if (coarsestRegions.size() > MAX_CONTINUATION_DEPTH ||
                        coarsestRegions.stream().anyMatch(region -> estimate(region) > continuationHardEstimateBudget)) {
                    throw continuationDepthException(method, regions.size());
                }

                int low = regionBudget + 1;
                int high = continuationHardEstimateBudget;
                while (low < high) {
                    int budget = low + (high - low) / 2;
                    if (partitionContinuationBlocks(blocks, budget).size() <= MAX_CONTINUATION_DEPTH) {
                        high = budget;
                    }
                    else {
                        low = budget + 1;
                    }
                }
                regions = partitionContinuationBlocks(blocks, low);
            }
            if (regions.size() < 2) {
                return Optional.empty();
            }

            Continuation next = null;
            for (int index = regions.size() - 1; index >= 0; index--) {
                List<CodeBlock> region = regions.get(index);
                String methodName = helperName(method, "continuation");
                ArrayList<Parameter> parameters = new ArrayList<>(locals.size());
                IdentityHashMap<LocalValue, BytecodeExpression> replacements = new IdentityHashMap<>();
                LinkedHashMap<String, Integer> parameterNames = new LinkedHashMap<>();
                for (LocalValue local : locals) {
                    if (isReceiver(local, receiver)) {
                        continue;
                    }
                    String base = sanitize(local.name().equals("this") ? "receiver" : local.name());
                    int occurrence = parameterNames.merge(base, 1, Integer::sum);
                    Parameter parameter = Parameter.arg(occurrence == 1 ? base : base + occurrence, local.type());
                    parameters.add(parameter);
                    replacements.put(local, parameter);
                }

                MethodDefinition helper = new MethodDefinition(owner, methodName, method.returnType(), parameters);
                if (receiver.isPresent()) {
                    helper.access(PRIVATE, SYNTHETIC);
                    replacements.put(receiver.orElseThrow(), helper.thisVariable());
                }
                else {
                    helper.access(PRIVATE, STATIC, SYNTHETIC);
                }
                helper.comment("trailing continuation extracted from " + method.name());

                LinkedHashSet<Variable> regionWrites = new LinkedHashSet<>();
                region.forEach(block -> collectWrittenVariables(block, regionWrites));
                regionWrites.retainAll(writes);
                LinkedHashMap<String, Integer> localNames = new LinkedHashMap<>();
                for (Variable variable : regionWrites) {
                    String base = sanitize(variable.name()) + "State";
                    int occurrence = localNames.merge(base, 1, Integer::sum);
                    Variable helperVariable = helper.body().declare(
                            occurrence == 1 ? base : base + occurrence,
                            replacements.get(variable));
                    replacements.put(variable, helperVariable);
                }
                for (CodeBlock block : region) {
                    helper.body().append(ExpressionPlanner.rewrite(block, local -> replacements.getOrDefault(local, local)));
                }

                if (next == null) {
                    helper.body().ret(ExpressionPlanner.rewrite(returnValue.orElseThrow(), replacements::get));
                }
                else {
                    Continuation target = next;
                    BytecodeExpression[] arguments = locals.stream()
                            .filter(local -> !isReceiver(local, receiver))
                            .map(local -> replacements.getOrDefault(local, local))
                            .toArray(BytecodeExpression[]::new);
                    BytecodeExpression call;
                    if (receiver.isPresent()) {
                        call = helper.thisVariable().invokeSpecial(owner, target.name(), target.methodType(), arguments);
                    }
                    else {
                        call = BytecodeExpressions.invokeStatic(owner, target.name(), target.methodType(), arguments);
                    }
                    helper.body().ret(call);
                }
                helpers.add(helper.build());
                names.add(methodName);
                next = new Continuation(methodName, helper.methodType());
            }

            BytecodeExpression[] arguments = helperArguments(locals, receiver);
            Continuation first = requireNonNull(next, "next is null");
            BytecodeExpression call = receiver
                    .map(value -> value.invokeSpecial(owner, first.name(), first.methodType(), arguments))
                    .orElseGet(() -> BytecodeExpressions.invokeStatic(owner, first.name(), first.methodType(), arguments));
            return Optional.of(new ContinuationExtraction(start, new Statements.Expression(call.ret())));
        }

        private static List<List<CodeBlock>> partitionContinuationBlocks(List<CodeBlock> blocks, int budget)
        {
            ArrayList<List<CodeBlock>> regions = new ArrayList<>();
            for (int index = 0; index < blocks.size(); ) {
                int end = index;
                int estimated = 0;
                while (end < blocks.size()) {
                    int candidateEstimate = ExpressionPlanner.estimate(blocks.get(end));
                    if (end > index && (long) estimated + candidateEstimate > budget) {
                        break;
                    }
                    estimated += candidateEstimate;
                    end++;
                }
                regions.add(blocks.subList(index, end));
                index = end;
            }
            return regions;
        }

        private static int estimate(List<CodeBlock> blocks)
        {
            return blocks.stream().mapToInt(ExpressionPlanner::estimate).sum();
        }

        private static CompilationException continuationDepthException(MethodDefinition.Model method, int desiredDepth)
        {
            return new CompilationException("The requested target for method %s%s would create %s continuation helpers, but the compiler limits generated continuation depth to %s and cannot coarsen the helpers within the hard method limit"
                    .formatted(method.name(), method.methodType().descriptorString(), desiredDepth, MAX_CONTINUATION_DEPTH));
        }

        private Optional<Statement> extractConditions(MethodDefinition.Model method, List<Statement> region)
        {
            LinkedHashSet<LocalValue> locals = new LinkedHashSet<>();
            region.stream()
                    .map(IfStatement.class::cast)
                    .forEach(statement -> locals.addAll(ExpressionPlanner.locals(statement.condition())));
            Optional<Variable> receiver = hiddenReceiver(method, locals);
            if (hasUnsupportedOwnerLocal(locals, receiver)) {
                return Optional.empty();
            }
            int parameterSlots = locals.stream().mapToInt(local -> ExpressionPlanner.slotSize(local.type())).sum();
            if (parameterSlots > 255) {
                return Optional.empty();
            }

            String methodName = helperName(method, "conditions");
            ArrayList<Parameter> parameters = new ArrayList<>(locals.size());
            IdentityHashMap<LocalValue, BytecodeExpression> replacements = new IdentityHashMap<>();
            LinkedHashMap<String, Integer> parameterNames = new LinkedHashMap<>();
            for (LocalValue local : locals) {
                if (isReceiver(local, receiver)) {
                    continue;
                }
                String base = sanitize(local.name().equals("this") ? "receiver" : local.name());
                int occurrence = parameterNames.merge(base, 1, Integer::sum);
                Parameter parameter = Parameter.arg(occurrence == 1 ? base : base + occurrence, local.type());
                parameters.add(parameter);
                replacements.put(local, parameter);
            }

            MethodDefinition helper = new MethodDefinition(owner, methodName, ConstantDescs.CD_boolean, parameters);
            if (receiver.isPresent()) {
                helper.access(PRIVATE, SYNTHETIC);
                replacements.put(receiver.orElseThrow(), helper.thisVariable());
            }
            else {
                helper.access(PRIVATE, STATIC, SYNTHETIC);
            }
            helper.comment("ordered early-return conditions extracted from " + method.name());
            for (Statement statement : region) {
                IfStatement ifStatement = (IfStatement) statement;
                helper.body().append(IfStatement.builder()
                        .condition(ExpressionPlanner.rewrite(ifStatement.condition(), replacements::get))
                        .then(BytecodeExpressions.constantFalse().ret())
                        .build());
            }
            helper.body().ret(BytecodeExpressions.constantTrue());
            helpers.add(helper.build());
            names.add(methodName);

            BytecodeExpression[] arguments = helperArguments(locals, receiver);
            BytecodeExpression call = receiver
                    .map(value -> value.invokeSpecial(owner, methodName, helper.methodType(), arguments))
                    .orElseGet(() -> BytecodeExpressions.invokeStatic(owner, methodName, helper.methodType(), arguments));
            return Optional.of(IfStatement.builder()
                    .condition(call.not())
                    .then(BytecodeExpressions.constantFalse().ret())
                    .build());
        }

        private Optional<Statement> extractRegion(MethodDefinition.Model method, List<Statement> region, Optional<Variable> output)
        {
            LinkedHashSet<LocalValue> locals = new LinkedHashSet<>();
            region.forEach(statement -> locals.addAll(ExpressionPlanner.locals(statement)));
            boolean initializesOutput = output.filter(variable -> initializesOutput(region.getFirst(), variable)).isPresent();
            if (initializesOutput) {
                locals.remove(output.orElseThrow());
            }
            Optional<Variable> receiver = hiddenReceiver(method, locals);
            if (hasUnsupportedOwnerLocal(locals, receiver) || output.map(Variable::type).filter(this::referencesOwner).isPresent()) {
                return Optional.empty();
            }
            int parameterSlots = locals.stream().mapToInt(local -> ExpressionPlanner.slotSize(local.type())).sum();
            if (parameterSlots > 255) {
                return Optional.empty();
            }

            String methodName = helperName(method, "statements");
            ArrayList<Parameter> parameters = new ArrayList<>(locals.size());
            IdentityHashMap<LocalValue, BytecodeExpression> replacements = new IdentityHashMap<>();
            LinkedHashMap<String, Integer> parameterNames = new LinkedHashMap<>();
            for (LocalValue local : locals) {
                if (isReceiver(local, receiver)) {
                    continue;
                }
                String base = sanitize(local.name().equals("this") ? "receiver" : local.name());
                int occurrence = parameterNames.merge(base, 1, Integer::sum);
                Parameter parameter = Parameter.arg(occurrence == 1 ? base : base + occurrence, local.type());
                parameters.add(parameter);
                replacements.put(local, parameter);
            }

            MethodDefinition helper = new MethodDefinition(owner, methodName, output.map(Variable::type).orElse(CD_void), parameters);
            if (receiver.isPresent()) {
                helper.access(PRIVATE, SYNTHETIC);
                replacements.put(receiver.orElseThrow(), helper.thisVariable());
            }
            else {
                helper.access(PRIVATE, STATIC, SYNTHETIC);
            }
            helper.comment("statements extracted from " + method.name());
            output.ifPresent(variable -> {
                Variable helperOutput = initializesOutput
                        ? helper.body().declare(variable.type(), "result")
                        : helper.body().declare("result", replacements.get(variable));
                replacements.put(variable, helperOutput);
            });
            for (Statement statement : region) {
                Statements.Expression expression = (Statements.Expression) statement;
                helper.body().append(ExpressionPlanner.rewrite(expression.expression(), replacements::get));
            }
            if (output.isEmpty()) {
                helper.body().ret();
            }
            else {
                helper.body().ret(replacements.get(output.orElseThrow()));
            }
            helpers.add(helper.build());
            names.add(methodName);

            BytecodeExpression[] arguments = helperArguments(locals, receiver);
            BytecodeExpression call = receiver
                    .map(value -> value.invokeSpecial(owner, methodName, helper.methodType(), arguments))
                    .orElseGet(() -> BytecodeExpressions.invokeStatic(owner, methodName, helper.methodType(), arguments));
            return Optional.of(new Statements.Expression(output.map(variable -> variable.set(call)).orElse(call)));
        }

        private Optional<Statement> extractBlocks(MethodDefinition.Model method, List<CodeBlock> region)
        {
            LinkedHashSet<Variable> declarations = new LinkedHashSet<>();
            region.forEach(block -> collectDeclarations(block, declarations));

            LinkedHashSet<LocalValue> locals = new LinkedHashSet<>();
            region.forEach(block -> locals.addAll(ExpressionPlanner.locals(block)));
            locals.removeAll(declarations);
            Optional<Variable> receiver = hiddenReceiver(method, locals);
            if (hasUnsupportedOwnerLocal(locals, receiver)) {
                return Optional.empty();
            }

            LinkedHashSet<Variable> writes = new LinkedHashSet<>();
            region.forEach(block -> collectWrittenVariables(block, writes));
            writes.removeAll(declarations);
            if (writes.size() > 1) {
                return Optional.empty();
            }
            Optional<Variable> output = writes.stream().findFirst();
            if (output.filter(variable -> !hasInitializer(method.body(), variable)).isPresent() ||
                    output.map(Variable::type).filter(this::referencesOwner).isPresent()) {
                return Optional.empty();
            }

            int parameterSlots = locals.stream().mapToInt(local -> ExpressionPlanner.slotSize(local.type())).sum();
            if (parameterSlots > 255) {
                return Optional.empty();
            }

            String methodName = helperName(method, "blocks");
            ArrayList<Parameter> parameters = new ArrayList<>(locals.size());
            IdentityHashMap<LocalValue, BytecodeExpression> replacements = new IdentityHashMap<>();
            LinkedHashMap<String, Integer> parameterNames = new LinkedHashMap<>();
            for (LocalValue local : locals) {
                if (isReceiver(local, receiver)) {
                    continue;
                }
                String base = sanitize(local.name().equals("this") ? "receiver" : local.name());
                int occurrence = parameterNames.merge(base, 1, Integer::sum);
                Parameter parameter = Parameter.arg(occurrence == 1 ? base : base + occurrence, local.type());
                parameters.add(parameter);
                replacements.put(local, parameter);
            }

            MethodDefinition helper = new MethodDefinition(owner, methodName, output.map(Variable::type).orElse(CD_void), parameters);
            if (receiver.isPresent()) {
                helper.access(PRIVATE, SYNTHETIC);
                replacements.put(receiver.orElseThrow(), helper.thisVariable());
            }
            else {
                helper.access(PRIVATE, STATIC, SYNTHETIC);
            }
            helper.comment("blocks extracted from " + method.name());
            output.ifPresent(variable -> {
                Variable helperOutput = helper.body().declare("result", replacements.get(variable));
                replacements.put(variable, helperOutput);
            });
            for (CodeBlock block : region) {
                helper.body().append(ExpressionPlanner.rewrite(block, local -> replacements.getOrDefault(local, local)));
            }
            if (output.isEmpty()) {
                helper.body().ret();
            }
            else {
                helper.body().ret(replacements.get(output.orElseThrow()));
            }
            helpers.add(helper.build());
            names.add(methodName);

            BytecodeExpression[] arguments = helperArguments(locals, receiver);
            BytecodeExpression call = receiver
                    .map(value -> value.invokeSpecial(owner, methodName, helper.methodType(), arguments))
                    .orElseGet(() -> BytecodeExpressions.invokeStatic(owner, methodName, helper.methodType(), arguments));
            return Optional.of(new Statements.Expression(output.map(variable -> variable.set(call)).orElse(call)));
        }

        private Optional<Variable> hiddenReceiver(MethodDefinition.Model method, Set<? extends LocalValue> locals)
        {
            if (!hiddenClass || method.isStatic()) {
                return Optional.empty();
            }
            Variable receiver = method.thisVariable();
            return locals.stream().anyMatch(local -> same(local, receiver)) ? Optional.of(receiver) : Optional.empty();
        }

        private boolean hasUnsupportedOwnerLocal(Set<? extends LocalValue> locals, Optional<Variable> receiver)
        {
            return hiddenClass && locals.stream().anyMatch(local -> referencesOwner(local.type()) && !isReceiver(local, receiver));
        }

        private boolean referencesOwner(ClassDesc type)
        {
            while (type.isArray()) {
                type = type.componentType();
            }
            return type.equals(owner);
        }

        private static boolean isReceiver(LocalValue local, Optional<Variable> receiver)
        {
            return receiver.filter(value -> same(local, value)).isPresent();
        }

        private static BytecodeExpression[] helperArguments(Set<? extends LocalValue> locals, Optional<Variable> receiver)
        {
            return locals.stream()
                    .filter(local -> !isReceiver(local, receiver))
                    .toArray(BytecodeExpression[]::new);
        }

        private String helperName(MethodDefinition.Model method, String category)
        {
            String name;
            do {
                name = sanitize(method.name()) + "$" + category + "$" + ++nextHelper;
            }
            while (!usedMethodNames.add(name));
            return name;
        }

        private record Continuation(String name, MethodTypeDesc methodType) {}

        private record ContinuationExtraction(int start, Statement invocation) {}
    }

    private static MethodDefinition.Model copy(MethodDefinition.Model method, CodeBlock body)
    {
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

    private static boolean isSimpleExpression(Statement statement)
    {
        if (!(statement instanceof Statements.Expression(BytecodeExpression expression))) {
            return false;
        }
        if (!ExpressionPlanner.canRewriteValue(expression)) {
            return false;
        }
        if (!(expression instanceof CoreExpression core)) {
            return true;
        }
        return isSimpleCore(core);
    }

    private static boolean isSimpleCore(CoreExpression core)
    {
        return switch (core.node()) {
            case ExpressionNode.Adapter adapter -> !adapter.keyword().equals("return") && !adapter.keyword().equals("throw");
            case ExpressionNode.Constant constant -> !(constant.type().equals(CD_void) && constant.value() == null);
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
                 ExpressionNode.DynamicConstant _,
                 ExpressionNode.BoundConstant _,
                 ExpressionNode.BoundMethodHandleInvocation _,
                 ExpressionNode.LinkedMethodInvocation _,
                 ExpressionNode.InvokeDynamic _,
                 ExpressionNode.SetVariable _,
                 ExpressionNode.Increment _ -> true;
        };
    }

    private static boolean isExtractableBlock(CodeBlock block)
    {
        return block.statements().stream().allMatch(StatementPlanner::isExtractableStatement);
    }

    private static boolean isExtractableStatement(Statement statement)
    {
        return switch (statement) {
            case BytecodeExpression expression -> isExtractableExpression(expression);
            case Statements.Expression expression -> isExtractableExpression(expression.expression());
            case Statements.InitializedDeclaration declaration -> isExtractableExpression(declaration.initializer());
            case Statements.Declaration _,
                 Statements.Comment _ -> true;
            case CodeBlock block -> isExtractableBlock(block);
            case IfStatement value -> isExtractableExpression(value.condition()) &&
                    isExtractableBlock(value.ifTrue()) &&
                    isExtractableBlock(value.ifFalse());
            case DoWhileLoop _,
                 ForLoop _,
                 LoopJump _,
                 SwitchStatement _,
                 TryCatch _,
                 WhileLoop _,
                 Statements.ConstructorInvocation _,
                 Statements.Jump _,
                 Statements.LabelBinding _ -> false;
        };
    }

    private static boolean isExtractableExpression(BytecodeExpression expression)
    {
        return switch (expression) {
            case LocalValue _ -> true;
            case SyntheticExpression synthetic -> {
                ExpressionPlan plan = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                yield isExtractableBlock(plan.setup()) && isExtractableExpression(plan.value());
            }
            case CoreExpression core -> isSimpleCore(core) &&
                    core.node().children().stream().allMatch(StatementPlanner::isExtractableExpression);
        };
    }

    private static void collectDeclarations(Statement statement, Set<Variable> declarations)
    {
        switch (statement) {
            case BytecodeExpression expression -> collectDeclarations(expression, declarations);
            case Statements.Expression expression -> collectDeclarations(expression.expression(), declarations);
            case Statements.Declaration declaration -> declarations.add(declaration.variable());
            case Statements.InitializedDeclaration declaration -> {
                declarations.add(declaration.variable());
                collectDeclarations(declaration.initializer(), declarations);
            }
            case CodeBlock block -> block.statements().forEach(item -> collectDeclarations(item, declarations));
            case IfStatement value -> {
                collectDeclarations(value.condition(), declarations);
                collectDeclarations(value.ifTrue(), declarations);
                collectDeclarations(value.ifFalse(), declarations);
            }
            case DoWhileLoop _,
                 ForLoop _,
                 LoopJump _,
                 SwitchStatement _,
                 TryCatch _,
                 WhileLoop _,
                 Statements.Comment _,
                 Statements.ConstructorInvocation _,
                 Statements.Jump _,
                 Statements.LabelBinding _ -> {}
        }
    }

    private static void collectDeclarations(BytecodeExpression expression, Set<Variable> declarations)
    {
        switch (expression) {
            case LocalValue _ -> {}
            case SyntheticExpression synthetic -> {
                ExpressionPlan plan = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                collectDeclarations(plan.setup(), declarations);
                collectDeclarations(plan.value(), declarations);
            }
            case CoreExpression core -> core.node().children().forEach(child -> collectDeclarations(child, declarations));
        }
    }

    private static boolean isEarlyFalseReturn(Statement statement)
    {
        if (!(statement instanceof IfStatement ifStatement) || !ifStatement.ifFalse().isEmpty() || ifStatement.ifTrue().statements().size() != 1) {
            return false;
        }
        if (!ExpressionPlanner.canRewriteValue(ifStatement.condition())) {
            return false;
        }
        Statement body = ifStatement.ifTrue().statements().getFirst();
        if (!(body instanceof Statements.Expression(CoreExpression core))) {
            return false;
        }
        if (!(core.node() instanceof ExpressionNode.Adapter adapter) || !adapter.keyword().equals("return")) {
            return false;
        }
        return adapter.value() instanceof CoreExpression value &&
                value.node() instanceof ExpressionNode.Constant constant &&
                constant.type().equals(ConstantDescs.CD_boolean) &&
                Boolean.FALSE.equals(constant.value());
    }

    private static Optional<BytecodeExpression> returnValue(Statement statement)
    {
        if (!(statement instanceof Statements.Expression(CoreExpression core)) ||
                !(core.node() instanceof ExpressionNode.Adapter adapter) ||
                !adapter.keyword().equals("return")) {
            return Optional.empty();
        }
        return Optional.of(adapter.value());
    }

    private static Set<Variable> writtenVariables(Statement statement)
    {
        LinkedHashSet<Variable> writes = new LinkedHashSet<>();
        collectWrittenVariables(statement, writes);
        return Set.copyOf(writes);
    }

    private static void writtenVariables(BytecodeExpression expression, Set<Variable> writes)
    {
        switch (expression) {
            case LocalValue _ -> {}
            case SyntheticExpression synthetic -> {
                ExpressionPlan plan = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                plan.setup().statements().forEach(statement -> writes.addAll(writtenVariables(statement)));
                writtenVariables(plan.value(), writes);
            }
            case CoreExpression core -> {
                switch (core.node()) {
                    case ExpressionNode.SetVariable set -> writes.add(set.variable());
                    case ExpressionNode.Increment increment -> writes.add(increment.variable());
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
                         ExpressionNode.Invoke _,
                         ExpressionNode.NewInstance _,
                         ExpressionNode.DynamicConstant _,
                         ExpressionNode.BoundConstant _,
                         ExpressionNode.BoundMethodHandleInvocation _,
                         ExpressionNode.LinkedMethodInvocation _,
                         ExpressionNode.InvokeDynamic _,
                         ExpressionNode.Adapter _ -> {}
                }
                core.node().children().forEach(child -> writtenVariables(child, writes));
            }
        }
    }

    private static boolean initializesOutput(Statement statement, Variable output)
    {
        if (!(statement instanceof Statements.Expression(CoreExpression core)) ||
                !(core.node() instanceof ExpressionNode.SetVariable set) ||
                different(set.variable(), output)) {
            return false;
        }
        return ExpressionPlanner.locals(set.value()).stream().noneMatch(local -> same(local, output));
    }

    private static boolean hasInitializer(CodeBlock body, Variable variable)
    {
        return body.statements().stream()
                .filter(Statements.InitializedDeclaration.class::isInstance)
                .map(Statements.InitializedDeclaration.class::cast)
                .map(Statements.InitializedDeclaration::variable)
                .anyMatch(declaration -> same(declaration, variable));
    }

    private static void collectWrittenVariables(Statement statement, Set<Variable> writes)
    {
        switch (statement) {
            case BytecodeExpression expression -> writtenVariables(expression, writes);
            case Statements.Expression expression -> writtenVariables(expression.expression(), writes);
            case Statements.InitializedDeclaration declaration -> writtenVariables(declaration.initializer(), writes);
            case CodeBlock block -> block.statements().forEach(item -> collectWrittenVariables(item, writes));
            case IfStatement value -> {
                writtenVariables(value.condition(), writes);
                collectWrittenVariables(value.ifTrue(), writes);
                collectWrittenVariables(value.ifFalse(), writes);
            }
            case Statements.ConstructorInvocation invocation -> invocation.arguments().forEach(argument -> writtenVariables(argument, writes));
            case DoWhileLoop _,
                 ForLoop _,
                 LoopJump _,
                 SwitchStatement _,
                 TryCatch _,
                 WhileLoop _,
                 Statements.Comment _,
                 Statements.Declaration _,
                 Statements.Jump _,
                 Statements.LabelBinding _ -> {}
        }
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
