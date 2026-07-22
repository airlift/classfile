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
    // emitted bytecode. Give the whole method more trigger headroom so an ordinary method is not
    // split prematurely, then use a tighter grouping budget so extracted helpers remain below the
    // JIT target selected by CompilationPolicy.
    private static final int METHOD_ESTIMATE_TO_CODE_BUDGET_RATIO = 3;
    private static final int REGION_ESTIMATE_TO_CODE_BUDGET_RATIO = 2;
    private static final int JIT_COMPLEXITY_ESTIMATE_BUDGET = 7_200;
    private static final int MIN_COMPLEX_INVOCATIONS_PER_BLOCK = 2;
    private static final int MAX_CONTINUATION_DEPTH = 256;

    private StatementPlanner() {}

    static Result plan(ClassModel definition, CompilationPolicy policy, Set<String> expressionHelpers, boolean hiddenClass)
    {
        requireNonNull(expressionHelpers, "expressionHelpers is null");
        Planner planner = new Planner(
                definition.type(),
                Math.max(64, policy.targetMethodCodeLimit() * METHOD_ESTIMATE_TO_CODE_BUDGET_RATIO),
                Math.max(64, policy.targetMethodCodeLimit() * REGION_ESTIMATE_TO_CODE_BUDGET_RATIO),
                Math.max(1, policy.hardMethodCodeLimit() * 9 / 10),
                policy.targetMethodCodeLimit(),
                policy.frequentInlineSize(),
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
        private final int methodBudget;
        private final int regionBudget;
        private final int continuationHardEstimateBudget;
        private final int complexSequenceBudget;
        private final int complexRegionBudget;
        private final int complexBlockEstimate;
        private final boolean hiddenClass;
        private final Set<String> expressionHelpers;
        private final ArrayList<MethodDefinition.Model> helpers = new ArrayList<>();
        private final ArrayList<String> names = new ArrayList<>();
        private final Set<String> usedMethodNames;
        private int nextHelper;

        private Planner(
                ClassDesc owner,
                int methodBudget,
                int regionBudget,
                int continuationHardEstimateBudget,
                int targetMethodCodeLimit,
                int frequentInlineSize,
                boolean hiddenClass,
                Set<String> expressionHelpers,
                Set<String> usedMethodNames)
        {
            this.owner = owner;
            this.methodBudget = methodBudget;
            this.regionBudget = regionBudget;
            this.continuationHardEstimateBudget = continuationHardEstimateBudget;
            this.complexSequenceBudget = Math.min(targetMethodCodeLimit, JIT_COMPLEXITY_ESTIMATE_BUDGET);
            this.complexRegionBudget = Math.max(64, complexSequenceBudget * 2 / 3);
            // Repeated, invocation-dense scoped blocks can exhaust C2's graph and inlining budgets
            // before reaching the ordinary huge-method target. Disable this secondary trigger for
            // intentionally tiny policies where it would dominate the authored target.
            this.complexBlockEstimate = complexSequenceBudget > frequentInlineSize * 2 ? frequentInlineSize : Integer.MAX_VALUE;
            this.hiddenClass = hiddenClass;
            this.expressionHelpers = Set.copyOf(expressionHelpers);
            this.usedMethodNames = new LinkedHashSet<>(usedMethodNames);
        }

        private MethodDefinition.Model method(MethodDefinition.Model method)
        {
            return method(method, methodBudget);
        }

        private MethodDefinition.Model method(MethodDefinition.Model method, int triggerBudget)
        {
            if (!method.hasBody() || method.isConstructor() || method.isClassInitializer() || expressionHelpers.contains(method.name())) {
                return method;
            }
            BodyAnalysis analysis = analyze(method.body());
            boolean ordinaryPlanning = analysis.estimate() > triggerBudget;
            boolean complexBlocks = analysis.complexScopedBlocks();
            if (!ordinaryPlanning && !complexBlocks) {
                return method;
            }
            CodeBlock plannedBody = planSyntheticSetups(method, method.body());
            MethodDefinition.Model plannedMethod = same(plannedBody, method.body()) ? method : copy(method, plannedBody);
            if (different(plannedBody, method.body())) {
                analysis = analyze(plannedBody);
            }
            ordinaryPlanning = analysis.estimate() > triggerBudget;
            complexBlocks = analysis.complexScopedBlocks();
            if (!ordinaryPlanning && !complexBlocks) {
                return plannedMethod;
            }
            CodeBlock branchBody = ordinaryPlanning ? extractBranches(plannedMethod, plannedBody, triggerBudget) : plannedBody;
            if (different(branchBody, plannedBody)) {
                plannedMethod = copy(plannedMethod, branchBody);
                analysis = analyze(branchBody);
                ordinaryPlanning = analysis.estimate() > triggerBudget;
                complexBlocks = analysis.complexScopedBlocks();
                if (!ordinaryPlanning && !complexBlocks) {
                    return plannedMethod;
                }
            }
            CodeBlock body = extract(plannedMethod, List.of(), !ordinaryPlanning);
            if (same(body, plannedMethod.body())) {
                return plannedMethod;
            }
            return copy(plannedMethod, body);
        }

        private BodyAnalysis analyze(CodeBlock body)
        {
            int bodyEstimate = 0;
            int count = 0;
            int estimate = 0;
            int invocations = 0;
            boolean complexScopedBlocks = false;
            for (Statement statement : body.statements()) {
                ExpressionPlanner.Metrics metrics = ExpressionPlanner.metrics(statement);
                bodyEstimate += metrics.estimate();
                if (statement instanceof Statements.Comment) {
                    continue;
                }
                if (!(statement instanceof CodeBlock block) || !isExtractableBlock(block)) {
                    complexScopedBlocks |= isComplexScopedBlockSequence(count, estimate, invocations);
                    count = 0;
                    estimate = 0;
                    invocations = 0;
                    continue;
                }
                count++;
                estimate += metrics.estimate();
                invocations += metrics.invocations();
            }
            complexScopedBlocks |= isComplexScopedBlockSequence(count, estimate, invocations);
            return new BodyAnalysis(bodyEstimate, complexScopedBlocks);
        }

        private boolean isComplexScopedBlockSequence(int count, int estimate, int invocations)
        {
            return count >= 2 &&
                    estimate > complexSequenceBudget &&
                    (long) estimate > (long) complexBlockEstimate * count &&
                    invocations >= count * MIN_COMPLEX_INVOCATIONS_PER_BLOCK;
        }

        private CodeBlock extractBranches(MethodDefinition.Model method, CodeBlock block, int triggerBudget)
        {
            if (ExpressionPlanner.estimate(block) <= triggerBudget) {
                return block;
            }

            boolean changed = false;
            ArrayList<Statement> statements = new ArrayList<>(block.statements().size());
            for (Statement statement : block.statements()) {
                Statement rewritten = extractBranches(method, statement, triggerBudget);
                statements.add(rewritten);
                changed |= different(rewritten, statement);
            }
            return changed ? block.withStatements(statements) : block;
        }

        private Statement extractBranches(MethodDefinition.Model method, Statement statement, int triggerBudget)
        {
            if (statement instanceof CodeBlock block) {
                return extractBranches(method, block, triggerBudget);
            }
            if (!(statement instanceof IfStatement ifStatement) || ExpressionPlanner.estimate(ifStatement) <= triggerBudget) {
                return statement;
            }

            CodeBlock ifTrue = extractBranch(method, ifStatement.ifTrue())
                    .orElseGet(() -> extractBranches(method, ifStatement.ifTrue(), triggerBudget));
            CodeBlock ifFalse = extractBranch(method, ifStatement.ifFalse())
                    .orElseGet(() -> extractBranches(method, ifStatement.ifFalse(), triggerBudget));
            if (same(ifTrue, ifStatement.ifTrue()) && same(ifFalse, ifStatement.ifFalse())) {
                return statement;
            }
            return ifStatement.rewrite(ifStatement.condition(), ifTrue, ifFalse);
        }

        private Optional<CodeBlock> extractBranch(MethodDefinition.Model method, CodeBlock branch)
        {
            if (branch.isEmpty() || !isExtractableBranchBlock(branch)) {
                return Optional.empty();
            }

            LinkedHashSet<Variable> declarations = new LinkedHashSet<>();
            collectDeclarations(branch, declarations);
            LinkedHashSet<Variable> writes = new LinkedHashSet<>();
            collectWrittenVariables(branch, writes);
            writes.removeAll(declarations);
            if (!writes.isEmpty()) {
                return Optional.empty();
            }

            LinkedHashSet<LocalValue> locals = new LinkedHashSet<>(ExpressionPlanner.locals(branch));
            locals.removeAll(declarations);
            Optional<Variable> receiver = hiddenReceiver(method, locals);
            if (hasUnsupportedOwnerLocal(locals, receiver)) {
                return Optional.empty();
            }
            int parameterSlots = locals.stream().mapToInt(local -> ExpressionPlanner.slotSize(local.type())).sum();
            if (parameterSlots > 255) {
                return Optional.empty();
            }

            String sourceName = method.name();
            int generatedSuffix = sourceName.indexOf("$branches$");
            if (generatedSuffix >= 0) {
                sourceName = sourceName.substring(0, generatedSuffix);
            }
            String methodName = helperName(sourceName, "branches");
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

            MethodDefinition helper = new MethodDefinition(owner, methodName, CD_void, parameters);
            if (receiver.isPresent()) {
                helper.access(PRIVATE, SYNTHETIC);
                replacements.put(receiver.orElseThrow(), helper.thisVariable());
            }
            else {
                helper.access(PRIVATE, STATIC, SYNTHETIC);
            }
            helper.comment("structured branch extracted from " + method.name());
            helper.body()
                    .append(ExpressionPlanner.rewrite(branch, local -> replacements.getOrDefault(local, local)))
                    .ret();
            MethodDefinition.Model plannedHelper = method(helper.build(), regionBudget);
            helpers.add(plannedHelper);
            names.add(methodName);

            BytecodeExpression[] arguments = helperArguments(locals, receiver);
            BytecodeExpression call = receiver
                    .map(value -> value.invokeSpecial(owner, methodName, helper.methodType(), arguments))
                    .orElseGet(() -> BytecodeExpressions.invokeStatic(owner, methodName, helper.methodType(), arguments));
            return Optional.of(CodeBlock.block(call));
        }

        private CodeBlock planSyntheticSetups(MethodDefinition.Model method, CodeBlock block)
        {
            boolean changed = false;
            ArrayList<Statement> statements = new ArrayList<>(block.statements().size());
            for (Statement statement : block.statements()) {
                Statement rewritten = planSyntheticSetups(method, statement);
                statements.add(rewritten);
                changed |= different(rewritten, statement);
            }
            return changed ? block.withStatements(statements) : block;
        }

        private Statement planSyntheticSetups(MethodDefinition.Model method, Statement statement)
        {
            return switch (statement) {
                case BytecodeExpression expression -> planSyntheticSetups(method, expression);
                case Statements.Expression expression -> {
                    BytecodeExpression rewritten = planSyntheticSetups(method, expression.expression());
                    yield same(rewritten, expression.expression()) ? expression : new Statements.Expression(rewritten);
                }
                case Statements.InitializedDeclaration declaration -> {
                    BytecodeExpression initializer = planSyntheticSetups(method, declaration.initializer());
                    yield same(initializer, declaration.initializer()) ? declaration : new Statements.InitializedDeclaration(declaration.variable(), initializer);
                }
                case CodeBlock nested -> planSyntheticSetups(method, nested);
                case DoWhileLoop loop -> loop.rewrite(
                        planSyntheticSetups(method, loop.condition()),
                        planSyntheticSetups(method, loop.body()));
                case ForLoop loop -> loop.rewrite(
                        planSyntheticSetups(method, loop.initializer()),
                        planSyntheticSetups(method, loop.condition()),
                        planSyntheticSetups(method, loop.update()),
                        planSyntheticSetups(method, loop.body()));
                case IfStatement ifStatement -> ifStatement.rewrite(
                        planSyntheticSetups(method, ifStatement.condition()),
                        planSyntheticSetups(method, ifStatement.ifTrue()),
                        planSyntheticSetups(method, ifStatement.ifFalse()));
                case SwitchStatement switchStatement -> switchStatement.rewrite(
                        planSyntheticSetups(method, switchStatement.expression()),
                        switchStatement.cases().stream()
                                .map(caseValue -> new SwitchStatement.Case(caseValue.key(), planSyntheticSetups(method, caseValue.body())))
                                .toList(),
                        planSyntheticSetups(method, switchStatement.defaultCase()));
                case TryCatch tryCatch -> tryCatch.rewrite(
                        planSyntheticSetups(method, tryCatch.tryBlock()),
                        tryCatch.catches().stream()
                                .map(catchClause -> new TryCatch.CatchClause(
                                        catchClause.exceptionType(),
                                        catchClause.variable(),
                                        planSyntheticSetups(method, catchClause.body())))
                                .toList(),
                        tryCatch.finallyBlock().map(finallyBlock -> planSyntheticSetups(method, finallyBlock)));
                case WhileLoop loop -> loop.rewrite(
                        planSyntheticSetups(method, loop.condition()),
                        planSyntheticSetups(method, loop.body()));
                case Statements.ConstructorInvocation invocation -> {
                    List<BytecodeExpression> arguments = invocation.arguments().stream()
                            .map(argument -> planSyntheticSetups(method, argument))
                            .toList();
                    yield arguments.equals(invocation.arguments()) ? invocation : new Statements.ConstructorInvocation(
                            invocation.target(),
                            invocation.declaredOwner(),
                            invocation.constructorType(),
                            arguments);
                }
                case LoopJump jump -> jump;
                case Statements.Comment comment -> comment;
                case Statements.Declaration declaration -> declaration;
                case Statements.Jump jump -> jump;
                case Statements.LabelBinding binding -> binding;
            };
        }

        private BytecodeExpression planSyntheticSetups(MethodDefinition.Model method, BytecodeExpression expression)
        {
            return switch (expression) {
                case LocalValue _ -> expression;
                case SyntheticExpression synthetic -> {
                    ExpressionPlan plan = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                    CodeBlock setup = planSyntheticSetups(method, plan.setup());
                    BytecodeExpression value = planSyntheticSetups(method, plan.value());
                    if (ExpressionPlanner.estimate(setup) > methodBudget) {
                        setup = extract(copy(method, setup), ExpressionPlanner.locals(value), false);
                    }
                    if (same(setup, plan.setup()) && same(value, plan.value())) {
                        yield synthetic;
                    }
                    yield new PlannedSyntheticExpression(synthetic.type(), setup, value, synthetic.toString());
                }
                case CoreExpression core -> {
                    ExpressionNode node = ExpressionPlanner.rewriteChildren(
                            core.node(),
                            child -> planSyntheticSetups(method, child));
                    yield node.equals(core.node()) ? core : new CoreExpression(node);
                }
            };
        }

        private CodeBlock extract(MethodDefinition.Model method, List<? extends LocalValue> liveAfterBody, boolean scopedBlocksOnly)
        {
            List<Statement> source = method.body().statements();
            Optional<ContinuationExtraction> trailingContinuation = scopedBlocksOnly ? Optional.empty() : extractTrailingContinuation(method, source);
            if (trailingContinuation.isPresent()) {
                ContinuationExtraction extraction = trailingContinuation.orElseThrow();
                ArrayList<Statement> rewritten = new ArrayList<>(source.subList(0, extraction.start()));
                rewritten.add(extraction.invocation());
                source = List.copyOf(rewritten);
            }
            ArrayList<Statement> result = new ArrayList<>();
            boolean changed = trailingContinuation.isPresent();
            for (int index = 0; index < source.size(); ) {
                Optional<Boolean> earlyReturnValue = scopedBlocksOnly ? Optional.empty() : orderedEarlyBooleanReturn(source.get(index));
                if (earlyReturnValue.isPresent()) {
                    boolean returnValue = earlyReturnValue.orElseThrow();
                    int end = index;
                    int estimated = 0;
                    while (end < source.size() && orderedEarlyBooleanReturn(source.get(end)).filter(value -> value == returnValue).isPresent()) {
                        int candidateEstimate = ExpressionPlanner.estimate(source.get(end));
                        if (end > index && estimated + candidateEstimate > regionBudget) {
                            break;
                        }
                        estimated += candidateEstimate;
                        end++;
                    }
                    if (end - index >= 2) {
                        Optional<Statement> continuation = extractConditions(method, source.subList(index, end), returnValue);
                        if (continuation.isPresent()) {
                            result.add(continuation.orElseThrow());
                            changed = true;
                            index = end;
                            continue;
                        }
                    }
                }
                Optional<ScopedBlockRun> scopedBlockRun = scopedBlockRun(source, index);
                if (scopedBlockRun.isPresent()) {
                    ScopedBlockRun run = scopedBlockRun.orElseThrow();
                    int totalEstimate = run.blocks().stream().mapToInt(block -> block.metrics().estimate()).sum();
                    int totalInvocations = run.blocks().stream().mapToInt(block -> block.metrics().invocations()).sum();
                    boolean complex = isComplexScopedBlockSequence(run.blocks().size(), totalEstimate, totalInvocations);
                    if (!scopedBlocksOnly || complex) {
                        int extractionBudget = complex ? complexRegionBudget : regionBudget;
                        int blockIndex = 0;
                        int sourceIndex = index;
                        while (blockIndex < run.blocks().size()) {
                            int blockEnd = blockIndex;
                            int estimated = 0;
                            while (blockEnd < run.blocks().size()) {
                                int candidateEstimate = run.blocks().get(blockEnd).metrics().estimate();
                                if (blockEnd > blockIndex && estimated + candidateEstimate > extractionBudget) {
                                    break;
                                }
                                estimated += candidateEstimate;
                                blockEnd++;
                            }
                            ScopedBlock last = run.blocks().get(blockEnd - 1);
                            List<CodeBlock> region = run.blocks().subList(blockIndex, blockEnd).stream()
                                    .map(ScopedBlock::block)
                                    .toList();
                            Optional<Statement> invocation = extractBlocks(method, region, source.subList(last.sourceEnd(), source.size()), liveAfterBody);
                            if (invocation.isPresent()) {
                                result.add(invocation.orElseThrow());
                                changed = true;
                            }
                            else {
                                result.addAll(source.subList(sourceIndex, last.sourceEnd()));
                            }
                            sourceIndex = last.sourceEnd();
                            blockIndex = blockEnd;
                        }
                        index = run.sourceEnd();
                        continue;
                    }
                }
                if (scopedBlocksOnly) {
                    result.add(source.get(index++));
                    continue;
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

        private static Optional<ScopedBlockRun> scopedBlockRun(List<Statement> source, int index)
        {
            if (!(source.get(index) instanceof CodeBlock first) || !isExtractableBlock(first)) {
                return Optional.empty();
            }

            ArrayList<ScopedBlock> blocks = new ArrayList<>();
            for (int end = index; end < source.size(); end++) {
                Statement statement = source.get(end);
                if (statement instanceof CodeBlock block && isExtractableBlock(block)) {
                    blocks.add(new ScopedBlock(block, ExpressionPlanner.metrics(block), end + 1));
                    continue;
                }
                if (statement instanceof Statements.Comment comment) {
                    ScopedBlock previous = blocks.removeLast();
                    ArrayList<Statement> statements = new ArrayList<>(previous.block().statements());
                    statements.add(comment);
                    blocks.add(new ScopedBlock(previous.block().withStatements(statements), previous.metrics(), end + 1));
                    continue;
                }
                break;
            }
            return Optional.of(new ScopedBlockRun(List.copyOf(blocks), blocks.getLast().sourceEnd()));
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

        private Optional<Statement> extractConditions(MethodDefinition.Model method, List<Statement> region, boolean earlyReturnValue)
        {
            LinkedHashSet<Variable> declarations = new LinkedHashSet<>();
            region.forEach(statement -> collectDeclarations(statement, declarations));

            LinkedHashSet<LocalValue> locals = new LinkedHashSet<>();
            region.forEach(statement -> {
                if (statement instanceof IfStatement ifStatement) {
                    locals.addAll(ExpressionPlanner.locals(ifStatement.condition()));
                }
                else {
                    locals.addAll(ExpressionPlanner.locals(statement));
                }
            });
            locals.removeAll(declarations);
            Optional<Variable> receiver = hiddenReceiver(method, locals);
            if (hasUnsupportedOwnerLocal(locals, receiver)) {
                return Optional.empty();
            }

            LinkedHashSet<Variable> writes = new LinkedHashSet<>();
            region.forEach(statement -> collectWrittenVariables(statement, writes));
            writes.removeAll(declarations);
            if (!writes.isEmpty()) {
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
                if (statement instanceof IfStatement ifStatement) {
                    helper.body().append(IfStatement.builder()
                            .condition(ExpressionPlanner.rewrite(ifStatement.condition(), replacements::get))
                            .then(BytecodeExpressions.constantBoolean(earlyReturnValue).ret())
                            .build());
                }
                else {
                    helper.body().append(ExpressionPlanner.rewrite((CodeBlock) statement, local -> replacements.getOrDefault(local, local)));
                }
            }
            helper.body().ret(BytecodeExpressions.constantBoolean(!earlyReturnValue));
            helpers.add(helper.build());
            names.add(methodName);

            BytecodeExpression[] arguments = helperArguments(locals, receiver);
            BytecodeExpression call = receiver
                    .map(value -> value.invokeSpecial(owner, methodName, helper.methodType(), arguments))
                    .orElseGet(() -> BytecodeExpressions.invokeStatic(owner, methodName, helper.methodType(), arguments));
            BytecodeExpression condition = earlyReturnValue ? call : call.not();
            return Optional.of(IfStatement.builder()
                    .condition(condition)
                    .then(BytecodeExpressions.constantBoolean(earlyReturnValue).ret())
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

        private Optional<Statement> extractBlocks(
                MethodDefinition.Model method,
                List<CodeBlock> region,
                List<Statement> liveAfter,
                List<? extends LocalValue> liveAfterBody)
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
            if (writes.stream().anyMatch(variable -> !hasInitializer(method.body(), variable))) {
                return Optional.empty();
            }
            LinkedHashSet<Variable> liveWrites = writes.stream()
                    .filter(variable -> isLiveAtStart(liveAfter, liveAfterBody, variable))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (liveWrites.size() > 1) {
                return Optional.empty();
            }
            Optional<Variable> output = liveWrites.stream().findFirst();
            if (output.map(Variable::type).filter(this::referencesOwner).isPresent()) {
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
            for (Variable variable : writes) {
                if (output.filter(value -> same(value, variable)).isPresent()) {
                    continue;
                }
                Variable helperLocal = helper.body().declare(sanitize(variable.name()) + "State", replacements.get(variable));
                replacements.put(variable, helperLocal);
            }
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
            return helperName(method.name(), category);
        }

        private String helperName(String sourceName, String category)
        {
            String name;
            do {
                name = sanitize(sourceName) + "$" + category + "$" + ++nextHelper;
            }
            while (!usedMethodNames.add(name));
            return name;
        }

        private record Continuation(String name, MethodTypeDesc methodType) {}

        private record ContinuationExtraction(int start, Statement invocation) {}

        private record BodyAnalysis(int estimate, boolean complexScopedBlocks) {}

        private record ScopedBlock(CodeBlock block, ExpressionPlanner.Metrics metrics, int sourceEnd) {}

        private record ScopedBlockRun(List<ScopedBlock> blocks, int sourceEnd) {}

        private record PlannedSyntheticExpression(ClassDesc type, CodeBlock setup, BytecodeExpression value, String rendering)
                implements SyntheticExpression
        {
            private PlannedSyntheticExpression
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

    private static boolean isExtractableBranchBlock(CodeBlock block)
    {
        return block.statements().stream().allMatch(StatementPlanner::isExtractableBranchStatement);
    }

    private static boolean isExtractableBranchStatement(Statement statement)
    {
        return switch (statement) {
            case BytecodeExpression expression -> isExtractableBranchExpression(expression);
            case Statements.Expression expression -> isExtractableBranchExpression(expression.expression());
            case Statements.InitializedDeclaration declaration -> isExtractableBranchExpression(declaration.initializer());
            case Statements.Declaration _,
                 Statements.Comment _ -> true;
            case CodeBlock block -> isExtractableBranchBlock(block);
            case IfStatement value -> isExtractableBranchExpression(value.condition()) &&
                    isExtractableBranchBlock(value.ifTrue()) &&
                    isExtractableBranchBlock(value.ifFalse());
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

    private static boolean isExtractableBranchExpression(BytecodeExpression expression)
    {
        if (expression instanceof CoreExpression core && core.node() instanceof ExpressionNode.Adapter adapter) {
            return adapter.keyword().equals("throw") && isExtractableExpression(adapter.value());
        }
        return isExtractableExpression(expression);
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

    private static Optional<Boolean> orderedEarlyBooleanReturn(Statement statement)
    {
        Optional<Boolean> directReturn = earlyBooleanReturnValue(statement);
        if (directReturn.isPresent()) {
            return directReturn;
        }
        if (!(statement instanceof CodeBlock block) || !isExtractableConditionBlock(block)) {
            return Optional.empty();
        }
        LinkedHashSet<Boolean> returnValues = new LinkedHashSet<>();
        collectBooleanReturnValues(block, returnValues);
        if (returnValues.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(returnValues.getFirst());
    }

    private static boolean isExtractableConditionBlock(CodeBlock block)
    {
        return block.statements().stream().allMatch(StatementPlanner::isExtractableConditionStatement);
    }

    private static boolean isExtractableConditionStatement(Statement statement)
    {
        return switch (statement) {
            case BytecodeExpression expression -> isExtractableConditionExpression(expression);
            case Statements.Expression expression -> isExtractableConditionExpression(expression.expression());
            case Statements.InitializedDeclaration declaration -> isExtractableExpression(declaration.initializer());
            case Statements.Declaration _,
                 Statements.Comment _ -> true;
            case CodeBlock block -> isExtractableConditionBlock(block);
            case IfStatement value -> isExtractableExpression(value.condition()) &&
                    isExtractableConditionBlock(value.ifTrue()) &&
                    isExtractableConditionBlock(value.ifFalse());
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

    private static boolean isExtractableConditionExpression(BytecodeExpression expression)
    {
        if (booleanReturnValue(expression).isPresent()) {
            return true;
        }
        if (expression instanceof CoreExpression core && core.node() instanceof ExpressionNode.Adapter adapter && adapter.keyword().equals("throw")) {
            return isExtractableExpression(adapter.value());
        }
        return isExtractableExpression(expression);
    }

    private static void collectBooleanReturnValues(Statement statement, Set<Boolean> returnValues)
    {
        switch (statement) {
            case BytecodeExpression expression -> booleanReturnValue(expression).ifPresent(returnValues::add);
            case Statements.Expression expression -> booleanReturnValue(expression.expression()).ifPresent(returnValues::add);
            case CodeBlock block -> block.statements().forEach(value -> collectBooleanReturnValues(value, returnValues));
            case IfStatement value -> {
                collectBooleanReturnValues(value.ifTrue(), returnValues);
                collectBooleanReturnValues(value.ifFalse(), returnValues);
            }
            case Statements.InitializedDeclaration _,
                 Statements.Declaration _,
                 Statements.Comment _,
                 DoWhileLoop _,
                 ForLoop _,
                 LoopJump _,
                 SwitchStatement _,
                 TryCatch _,
                 WhileLoop _,
                 Statements.ConstructorInvocation _,
                 Statements.Jump _,
                 Statements.LabelBinding _ -> {}
        }
    }

    private static Optional<Boolean> earlyBooleanReturnValue(Statement statement)
    {
        if (!(statement instanceof IfStatement ifStatement) || !ifStatement.ifFalse().isEmpty() || ifStatement.ifTrue().statements().size() != 1) {
            return Optional.empty();
        }
        if (!ExpressionPlanner.canRewriteValue(ifStatement.condition())) {
            return Optional.empty();
        }
        Statement body = ifStatement.ifTrue().statements().getFirst();
        if (!(body instanceof Statements.Expression expression)) {
            return Optional.empty();
        }
        return booleanReturnValue(expression.expression());
    }

    private static Optional<Boolean> booleanReturnValue(BytecodeExpression expression)
    {
        if (expression instanceof CoreExpression core &&
                core.node() instanceof ExpressionNode.Adapter adapter &&
                adapter.keyword().equals("return") &&
                adapter.value() instanceof CoreExpression value &&
                value.node() instanceof ExpressionNode.Constant constant &&
                constant.type().equals(ConstantDescs.CD_boolean) &&
                constant.value() instanceof Boolean booleanValue) {
            return Optional.of(booleanValue);
        }
        return Optional.empty();
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

    private static boolean isLiveAtStart(
            List<Statement> statements,
            List<? extends LocalValue> liveAfterStatements,
            Variable variable)
    {
        for (Statement statement : statements) {
            switch (firstAccess(statement, variable)) {
                case READ -> {
                    return true;
                }
                case WRITE -> {
                    return false;
                }
                case NONE -> {}
            }
        }
        return liveAfterStatements.stream().anyMatch(local -> same(local, variable));
    }

    private static FirstAccess firstAccess(Statement statement, Variable variable)
    {
        return switch (statement) {
            case BytecodeExpression expression -> firstAccess(expression, variable);
            case Statements.Expression expression -> firstAccess(expression.expression(), variable);
            case Statements.InitializedDeclaration declaration -> firstAccess(declaration.initializer(), variable);
            case CodeBlock block -> firstAccessStatements(block.statements(), variable);
            case IfStatement value -> {
                FirstAccess condition = firstAccess(value.condition(), variable);
                yield condition != FirstAccess.NONE ? condition : merge(
                        firstAccess(value.ifTrue(), variable),
                        firstAccess(value.ifFalse(), variable));
            }
            case DoWhileLoop value -> {
                FirstAccess body = firstAccess(value.body(), variable);
                yield body != FirstAccess.NONE ? body : firstAccess(value.condition(), variable);
            }
            case Statements.ConstructorInvocation value -> firstAccessExpressions(value.arguments(), variable);
            case ForLoop _,
                 SwitchStatement _,
                 TryCatch _,
                 WhileLoop _ -> ExpressionPlanner.locals(statement).stream().anyMatch(local -> same(local, variable)) ? FirstAccess.READ : FirstAccess.NONE;
            case LoopJump _,
                 Statements.Jump _ -> FirstAccess.READ;
            case Statements.Comment _,
                 Statements.Declaration _,
                 Statements.LabelBinding _ -> FirstAccess.NONE;
        };
    }

    private static FirstAccess firstAccess(BytecodeExpression expression, Variable variable)
    {
        return switch (expression) {
            case LocalValue local -> same(local, variable) ? FirstAccess.READ : FirstAccess.NONE;
            case SyntheticExpression synthetic -> {
                ExpressionPlan plan = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                FirstAccess setup = firstAccess(plan.setup(), variable);
                yield setup != FirstAccess.NONE ? setup : firstAccess(plan.value(), variable);
            }
            case CoreExpression core -> switch (core.node()) {
                case ExpressionNode.SetVariable value -> {
                    FirstAccess initializer = firstAccess(value.value(), variable);
                    yield initializer != FirstAccess.NONE ? initializer : same(value.variable(), variable) ? FirstAccess.WRITE : FirstAccess.NONE;
                }
                case ExpressionNode.Increment value -> same(value.variable(), variable) ? FirstAccess.READ : FirstAccess.NONE;
                case ExpressionNode.Binary value -> {
                    FirstAccess left = firstAccess(value.left(), variable);
                    if (left != FirstAccess.NONE) {
                        yield left;
                    }
                    FirstAccess right = firstAccess(value.right(), variable);
                    if ((value.operator().equals("&&") || value.operator().equals("||")) && right == FirstAccess.WRITE) {
                        yield FirstAccess.READ;
                    }
                    yield right;
                }
                case ExpressionNode.InlineIf value -> {
                    FirstAccess condition = firstAccess(value.condition(), variable);
                    yield condition != FirstAccess.NONE ? condition : merge(
                            firstAccess(value.ifTrue(), variable),
                            firstAccess(value.ifFalse(), variable));
                }
                default -> firstAccessExpressions(core.node().children(), variable);
            };
        };
    }

    private static FirstAccess firstAccessStatements(List<? extends Statement> statements, Variable variable)
    {
        for (Statement statement : statements) {
            FirstAccess access = firstAccess(statement, variable);
            if (access != FirstAccess.NONE) {
                return access;
            }
        }
        return FirstAccess.NONE;
    }

    private static FirstAccess firstAccessExpressions(List<? extends BytecodeExpression> expressions, Variable variable)
    {
        for (BytecodeExpression expression : expressions) {
            FirstAccess access = firstAccess(expression, variable);
            if (access != FirstAccess.NONE) {
                return access;
            }
        }
        return FirstAccess.NONE;
    }

    private static FirstAccess merge(FirstAccess left, FirstAccess right)
    {
        return left == right ? left : FirstAccess.READ;
    }

    private enum FirstAccess
    {
        NONE,
        READ,
        WRITE,
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
