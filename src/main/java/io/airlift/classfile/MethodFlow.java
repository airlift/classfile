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

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.airlift.classfile.Identity.different;

/// Checks that every read of a tracked variable follows an assignment on every path, and that
/// the body of a method other than a class initializer cannot complete without returning or
/// throwing. The tracked variables are those declared without an initializer and those declared
/// with one that a forward jump can skip.
///
/// The analysis follows the control flow `ClassCompiler` emits: the same branch folding, the same
/// joins, the same inlined finally copies, and the same local aliasing. Like the emitter, each
/// placement of a block, such as each finally copy or each use of a synthetic expression, binds
/// fresh labels. A state is a row of words with a bit for each definitely assigned variable; the
/// sign bit of the last word is never a variable, so a state is reachable exactly when that word
/// is not negative. The unreachable state has every bit set, so dead code, which the JDK replaces
/// before verification, is never rejected.
final class MethodFlow
{
    private static final long UNREACHABLE = -1L;

    // Expression tasks; a branch leaves the true state current and pushes the false state.
    private static final int EVALUATE = 0;
    private static final int BRANCH = 1;
    private static final int ASSIGN = 2;
    private static final int SAVE = 3;
    private static final int MEET = 4;
    private static final int SWAP = 5;
    private static final int MEET_FALSE = 6;
    private static final int MEET_TRUE = 7;
    private static final int RETURN = 8;
    private static final int THROW = 9;

    private final MethodDefinition.Model method;
    // Words in each state, and the number of variables they can track.
    private final int stride;
    private final int capacity;
    // Declarations with an initializer that a forward jump can skip, and whether a forward jump
    // can skip one in a synthetic setup. Setups are expanded again here, so then every such
    // declaration in a setup is tracked. A finally copy that a jump in a setup runs counts as in
    // the setup, which only tracks more variables.
    private final Set<Variable> skippable;
    private final boolean skippableSetupDeclarations;
    private final boolean tracksSkippable;
    // Created at the first tracked declaration, as most methods have none.
    private IdentityHashMap<Variable, Integer> indexes;
    // Expansions are reused so labels and variables keep their identity across passes.
    private IdentityHashMap<SyntheticExpression, ExpressionPlan> plans;
    // Synthetic expressions the emitter places as value blocks, which do not alias locals, noted
    // only when a declaration in a setup can be skipped.
    private Set<SyntheticExpression> valueSynthetics;
    // The source of each skippable declaration the emitter turned into an alias in its placement.
    private IdentityHashMap<Variable, LocalValue> aliases;
    private IdentityHashMap<CodeBlock, boolean[]> blockAliases;
    private int setupDepth;
    private int pass;
    private boolean changed;

    private final long[] current;

    private int[] tasks = new int[16];
    private Object[] operands = new Object[16];
    private int taskCount;
    private long[] states;
    private int stateCount;

    // Each placement of a block with labels takes the next slots, in an order every pass repeats,
    // so a slot keeps its state across passes. A label maps to the slot of its innermost active
    // placement, and the slots it replaced are saved until the placement ends.
    private IdentityHashMap<CodeLabel, Integer> labelSlots;
    private int[] replacedSlots;
    private int replacedCount;
    private long[] slotStates;
    private int[] slotFinalizerDepths;
    private int[] slotBoundPasses;
    private int slotCount;
    private int placements;

    private Object[] loopTargets = new Object[4];
    private long[] loopBreaks;
    private long[] loopContinues;
    private int[] loopFinalizerDepths = new int[4];
    private int loopCount;

    private CodeBlock[] finalizers = new CodeBlock[4];
    private int finalizerDepth;

    private MethodFlow(MethodDefinition.Model method, int declarations, Set<Variable> skippable, boolean skippableSetupDeclarations)
    {
        this.method = method;
        stride = declarations / Long.SIZE + 1;
        capacity = stride * Long.SIZE - 1;
        this.skippable = skippable;
        this.skippableSetupDeclarations = skippableSetupDeclarations;
        tracksSkippable = !skippable.isEmpty() || skippableSetupDeclarations;
        current = new long[stride];
        states = new long[8 * stride];
        loopBreaks = new long[4 * stride];
        loopContinues = new long[4 * stride];
    }

    /// Validates the method, given an upper bound on the variables to track, the declarations
    /// with an initializer that a forward jump can skip, and whether a forward jump can skip a
    /// declaration with an initializer in a synthetic setup, which tracks every such declaration
    /// in a setup.
    static void validate(MethodDefinition.Model method, int declarations, Set<Variable> skippable, boolean skippableSetupDeclarations)
    {
        // The emitter appends the return that ends a class initializer.
        boolean checkCompletion = !method.isClassInitializer() && ModelValidator.canCompleteNormally(method.body());
        if (declarations == 0 && !checkCompletion) {
            return;
        }
        new MethodFlow(method, declarations, skippable, skippableSetupDeclarations).analyze(checkCompletion);
    }

    private void analyze(boolean checkCompletion)
    {
        // Backward jumps can lower a label state after its binding was analyzed, so repeat until
        // the label states are stable. States only shrink, so a read rejected in an early pass is
        // also rejected in the final one.
        do {
            changed = false;
            pass++;
            placements = 0;
            Arrays.fill(current, 0);
            analyzeBlock(method.body(), false);
        }
        while (changed);

        if (checkCompletion && reachable()) {
            throw new IllegalArgumentException("Method can complete without returning: " + method.declaration());
        }
    }

    // Analyzes a block the way the emitter places it: as an ordinary block or, for a synthetic
    // setup the emitter places as a value block, as a value block, which does not alias locals.
    private void analyzeBlock(CodeBlock block, boolean valueBlock)
    {
        List<CodeLabel> blockLabels = block.labels();
        if (!blockLabels.isEmpty()) {
            bindLabels(blockLabels);
        }
        List<Statement> statements = block.statements();
        int end = valueBlock ? statements.size() - 1 : statements.size();
        for (int index = 0; index < end; index++) {
            Statement statement = statements.get(index);
            if (tracksSkippable && statement instanceof Statements.InitializedDeclaration declaration && isSkippable(declaration.variable())) {
                initializeSkippable(declaration, !valueBlock && isAlias(block, index, declaration));
            }
            else {
                analyzeStatement(statement);
            }
        }
        if (valueBlock) {
            analyzeTerminal(statements.get(end));
        }
        if (!blockLabels.isEmpty()) {
            unbindLabels(blockLabels);
        }
    }

    // Analyzes the statement that ends a value block, which nests further value blocks.
    private void analyzeTerminal(Statement statement)
    {
        switch (statement) {
            case CodeBlock block -> analyzeBlock(block, true);
            case IfStatement ifStatement -> analyzeIf(ifStatement, true);
            default -> analyzeStatement(statement);
        }
    }

    private void analyzeStatement(Statement statement)
    {
        switch (statement) {
            case BytecodeExpression expression -> evaluate(expression);
            case Statements.Expression expression -> evaluate(expression.expression());
            case Statements.Comment _ -> {}
            case Statements.Declaration declaration -> declare(declaration.variable());
            case Statements.InitializedDeclaration declaration -> evaluate(declaration.initializer());
            case Statements.ConstructorInvocation invocation -> {
                List<BytecodeExpression> arguments = invocation.arguments();
                for (int index = 0; index < arguments.size(); index++) {
                    evaluate(arguments.get(index));
                }
            }
            case Statements.LabelBinding binding -> {
                int slot = slot(binding.label());
                meetInto(slotStates, slot);
                slotBoundPasses[slot] = pass;
                load(slotStates, slot);
            }
            case Statements.Jump jump -> {
                // Each finally copy the jump runs binds labels of its own.
                int slot = slot(jump.target());
                runFinalizers(slotFinalizerDepths[slot]);
                // A backward jump can lower the state of a label this pass already used, which then
                // needs another pass.
                if (meetInto(slotStates, slot)) {
                    changed |= slotBoundPasses[slot] == pass;
                }
                setUnreachable();
            }
            case LoopJump jump -> {
                int loop = loopCount - 1;
                while (different(loopTargets[loop], jump.target())) {
                    loop--;
                }
                runFinalizers(loopFinalizerDepths[loop]);
                meetInto(jump.kind() == LoopJump.Kind.BREAK ? loopBreaks : loopContinues, loop);
                setUnreachable();
            }
            case CodeBlock block -> analyzeBlock(block, false);
            case IfStatement ifStatement -> analyzeIf(ifStatement, false);
            case WhileLoop loop -> {
                branch(loop.condition());
                // The back edge only adds assignments, as nothing jumps into the body from outside.
                int index = pushLoop(loop.target());
                analyzeBlock(loop.body(), false);
                popState();
                meet(loopBreaks, index);
                popLoop(index);
            }
            case ForLoop loop -> {
                analyzeBlock(loop.initializer(), false);
                branch(loop.condition());
                int index = pushLoop(loop.target());
                analyzeBlock(loop.body(), false);
                meet(loopContinues, index);
                analyzeBlock(loop.update(), false);
                popState();
                meet(loopBreaks, index);
                popLoop(index);
            }
            case DoWhileLoop loop -> {
                int index = pushLoop(loop.target());
                analyzeBlock(loop.body(), false);
                meet(loopContinues, index);
                // The condition can contain loops of its own, so the breaks move to the state stack.
                pushRow(loopBreaks, index);
                popLoop(index);
                branch(loop.condition());
                popState();
                meetPopState();
            }
            case SwitchStatement switchStatement -> {
                // Every case ends with a jump past the switch, so cases never fall through.
                evaluate(switchStatement.expression());
                int selected = stateCount;
                pushState();
                pushUnreachable();
                for (SwitchStatement.Case caseValue : switchStatement.cases()) {
                    load(states, selected);
                    analyzeBlock(caseValue.body(), false);
                    meetInto(states, selected + 1);
                }
                load(states, selected);
                analyzeBlock(switchStatement.defaultCase(), false);
                meet(states, selected + 1);
                stateCount = selected;
            }
            case TryCatch tryCatch -> analyzeTryCatch(tryCatch);
        }
    }

    private void analyzeIf(IfStatement ifStatement, boolean valueBlocks)
    {
        branch(ifStatement.condition());
        analyzeBlock(ifStatement.ifTrue(), valueBlocks);
        swapState();
        analyzeBlock(ifStatement.ifFalse(), valueBlocks);
        meetPopState();
    }

    private void analyzeTryCatch(TryCatch statement)
    {
        // A handler starts from the intersection of the states across its protected range,
        // which is the entry state, as the protected code only adds assignments.
        Optional<CodeBlock> finallyBlock = statement.finallyBlock();
        int entry = stateCount;
        pushState();
        analyzeProtected(statement.tryBlock(), finallyBlock);
        pushState();
        for (TryCatch.CatchClause catchClause : statement.catches()) {
            load(states, entry);
            analyzeProtected(catchClause.body(), finallyBlock);
            meetInto(states, entry + 1);
        }
        if (finallyBlock.isPresent()) {
            // The catch-all copy rethrows, so its end is unreachable.
            load(states, entry);
            analyzeBlock(finallyBlock.orElseThrow(), false);
        }
        load(states, entry + 1);
        stateCount = entry;
    }

    // Analyzes a try or catch body, whose jumps and returns run the finally block, and then the
    // finally copy on its normal exit.
    private void analyzeProtected(CodeBlock body, Optional<CodeBlock> finallyBlock)
    {
        if (finallyBlock.isEmpty()) {
            analyzeBlock(body, false);
            return;
        }
        CodeBlock finalizer = finallyBlock.orElseThrow();
        if (finalizerDepth == finalizers.length) {
            finalizers = Arrays.copyOf(finalizers, finalizerDepth * 2);
        }
        // Inside an inlined finally copy, the finalizer entry at this depth can hold a suspended
        // outer finalizer, which is restored afterwards.
        CodeBlock suspended = finalizers[finalizerDepth];
        finalizers[finalizerDepth++] = finalizer;
        analyzeBlock(body, false);
        finalizers[--finalizerDepth] = suspended;
        analyzeBlock(finalizer, false);
    }

    // Analyzes the finally copies that the emitter inlines before a jump or return, innermost first.
    private void runFinalizers(int targetDepth)
    {
        int originalDepth = finalizerDepth;
        for (int index = originalDepth - 1; index >= targetDepth; index--) {
            finalizerDepth = index;
            analyzeBlock(finalizers[index], false);
        }
        finalizerDepth = originalDepth;
    }

    private void bindLabels(List<CodeLabel> blockLabels)
    {
        if (labelSlots == null) {
            labelSlots = new IdentityHashMap<>();
            replacedSlots = new int[8];
            slotStates = new long[8 * stride];
            slotFinalizerDepths = new int[8];
            slotBoundPasses = new int[8];
        }
        for (int index = 0; index < blockLabels.size(); index++) {
            int slot = placements++;
            if (slot == slotCount) {
                addSlot();
            }
            slotFinalizerDepths[slot] = finalizerDepth;
            if (replacedCount == replacedSlots.length) {
                replacedSlots = Arrays.copyOf(replacedSlots, replacedCount * 2);
            }
            Integer replaced = labelSlots.put(blockLabels.get(index), slot);
            replacedSlots[replacedCount++] = replaced == null ? -1 : replaced;
        }
    }

    private void unbindLabels(List<CodeLabel> blockLabels)
    {
        for (int index = blockLabels.size() - 1; index >= 0; index--) {
            labelSlots.put(blockLabels.get(index), replacedSlots[--replacedCount]);
        }
    }

    // Slots are created in the first pass and keep their state for the later ones.
    private void addSlot()
    {
        if (slotCount == slotBoundPasses.length) {
            int slots = slotCount * 2;
            slotStates = Arrays.copyOf(slotStates, slots * stride);
            slotFinalizerDepths = Arrays.copyOf(slotFinalizerDepths, slots);
            slotBoundPasses = Arrays.copyOf(slotBoundPasses, slots);
        }
        Arrays.fill(slotStates, slotCount * stride, (slotCount + 1) * stride, UNREACHABLE);
        slotCount++;
    }

    private int slot(CodeLabel label)
    {
        return labelSlots.get(label);
    }

    private int pushLoop(Object target)
    {
        if (loopCount == loopTargets.length) {
            int loops = loopCount * 2;
            loopTargets = Arrays.copyOf(loopTargets, loops);
            loopBreaks = Arrays.copyOf(loopBreaks, loops * stride);
            loopContinues = Arrays.copyOf(loopContinues, loops * stride);
            loopFinalizerDepths = Arrays.copyOf(loopFinalizerDepths, loops);
        }
        loopTargets[loopCount] = target;
        Arrays.fill(loopBreaks, loopCount * stride, (loopCount + 1) * stride, UNREACHABLE);
        Arrays.fill(loopContinues, loopCount * stride, (loopCount + 1) * stride, UNREACHABLE);
        loopFinalizerDepths[loopCount] = finalizerDepth;
        return loopCount++;
    }

    private void popLoop(int index)
    {
        loopCount = index;
        loopTargets[index] = null;
    }

    private void evaluate(BytecodeExpression expression)
    {
        run(EVALUATE, expression);
    }

    // Leaves the state when the condition is true and pushes the state when it is false.
    private void branch(BytecodeExpression condition)
    {
        run(BRANCH, condition);
    }

    // Walks expressions with an explicit task stack, as expression chains can be far deeper than
    // the structured nesting limit.
    private void run(int task, BytecodeExpression expression)
    {
        int base = taskCount;
        pushTask(task, expression);
        while (taskCount > base) {
            taskCount--;
            Object operand = operands[taskCount];
            operands[taskCount] = null;
            switch (tasks[taskCount]) {
                case EVALUATE -> evaluateStep((BytecodeExpression) operand);
                case BRANCH -> branchStep((BytecodeExpression) operand);
                case ASSIGN -> assign((Variable) operand);
                case SAVE -> pushState();
                case MEET -> meetPopState();
                case SWAP -> swapState();
                case MEET_FALSE -> {
                    // The right false state meets the left false state beneath it.
                    stateCount--;
                    int row = (stateCount - 1) * stride;
                    int rightFalse = stateCount * stride;
                    for (int word = 0; word < stride; word++) {
                        states[row + word] &= states[rightFalse + word];
                    }
                }
                case MEET_TRUE -> {
                    // The left true state, beneath the right false state, meets the current state,
                    // and the right false state takes its place.
                    stateCount--;
                    meet(states, stateCount - 1);
                    System.arraycopy(states, stateCount * stride, states, (stateCount - 1) * stride, stride);
                }
                case RETURN -> {
                    runFinalizers(0);
                    setUnreachable();
                }
                case THROW -> setUnreachable();
                default -> throw new AssertionError("Unknown task: " + tasks[taskCount]);
            }
        }
    }

    private void evaluateStep(BytecodeExpression expression)
    {
        switch (expression) {
            case LocalValue local -> read(local);
            case SyntheticExpression synthetic -> {
                ExpressionPlan plan = plan(synthetic);
                setupDepth++;
                analyzeBlock(plan.setup(), valueSynthetics != null && valueSynthetics.contains(synthetic));
                setupDepth--;
                // The setup's aliases stay bound while the value is evaluated.
                pushTask(EVALUATE, plan.value());
            }
            case CoreExpression core -> {
                switch (core.node()) {
                    // As a value, a logical operator joins its short circuit without folding constants.
                    case ExpressionNode.Binary binary when ModelValidator.isLogical(binary) -> {
                        pushTask(MEET);
                        pushTask(EVALUATE, binary.right());
                        pushTask(SAVE);
                        pushTask(EVALUATE, binary.left());
                    }
                    case ExpressionNode.InlineIf inlineIf -> {
                        pushTask(MEET);
                        pushTask(EVALUATE, inlineIf.ifFalse());
                        pushTask(SWAP);
                        pushTask(EVALUATE, inlineIf.ifTrue());
                        pushTask(BRANCH, inlineIf.condition());
                    }
                    // The variable is a write target, assigned after its value.
                    case ExpressionNode.SetVariable setVariable -> {
                        pushTask(ASSIGN, setVariable.variable());
                        pushTask(EVALUATE, setVariable.value());
                    }
                    case ExpressionNode.Increment increment -> read(increment.variable());
                    case ExpressionNode.Adapter adapter -> {
                        switch (adapter.keyword()) {
                            case "return" -> pushTask(RETURN);
                            case "throw" -> pushTask(THROW);
                            default -> {}
                        }
                        pushTask(EVALUATE, adapter.value());
                    }
                    case ExpressionNode.Constant constant when ModelValidator.isReturn(constant) -> pushTask(RETURN);
                    case ExpressionNode.Invoke invoke -> {
                        pushEvaluations(invoke.arguments());
                        if (invoke.kind() != ExpressionNode.InvocationKind.STATIC) {
                            pushTask(EVALUATE, invoke.target());
                        }
                    }
                    case ExpressionNode node -> pushEvaluations(node.children());
                }
            }
        }
    }

    // Mirrors the emitter's branch folding: literal booleans, negation, and short circuits.
    private void branchStep(BytecodeExpression condition)
    {
        if (condition instanceof CoreExpression core) {
            switch (core.node()) {
                case ExpressionNode.Constant constant when constant.value() instanceof Boolean value -> {
                    if (value) {
                        pushUnreachable();
                        return;
                    }
                    pushState();
                    setUnreachable();
                    return;
                }
                case ExpressionNode.Unary unary when unary.prefix().equals("!") -> {
                    pushTask(SWAP);
                    pushTask(BRANCH, unary.value());
                    return;
                }
                case ExpressionNode.Binary binary when binary.operator().equals("&&") -> {
                    pushTask(MEET_FALSE);
                    pushTask(BRANCH, binary.right());
                    pushTask(BRANCH, binary.left());
                    return;
                }
                case ExpressionNode.Binary binary when binary.operator().equals("||") -> {
                    pushTask(MEET_TRUE);
                    pushTask(BRANCH, binary.right());
                    pushTask(SWAP);
                    pushTask(BRANCH, binary.left());
                    return;
                }
                default -> {}
            }
        }
        pushTask(SAVE);
        pushTask(EVALUATE, condition);
    }

    private ExpressionPlan plan(SyntheticExpression synthetic)
    {
        if (plans == null) {
            plans = new IdentityHashMap<>();
        }
        ExpressionPlan plan = plans.get(synthetic);
        if (plan == null) {
            plan = synthetic.expansion(ExpansionContext.INSTANCE);
            plans.put(synthetic, plan);
            // Only the aliasing of skippable declarations in setups depends on how the emitter
            // places a setup.
            if (skippableSetupDeclarations && ClassCompiler.canEmitSyntheticValue(plan)) {
                if (valueSynthetics == null) {
                    valueSynthetics = Collections.newSetFromMap(new IdentityHashMap<>());
                }
                valueSynthetics.add(synthetic);
            }
        }
        return plan;
    }

    private boolean isSkippable(Variable variable)
    {
        return skippable.contains(variable) || (skippableSetupDeclarations && setupDepth > 0);
    }

    // Whether the emitter binds the declared variable to its initializer's local instead of
    // storing it, which it does only in an ordinary block.
    private boolean isAlias(CodeBlock block, int index, Statements.InitializedDeclaration declaration)
    {
        if (!(declaration.initializer() instanceof LocalValue)) {
            return false;
        }
        if (blockAliases == null) {
            blockAliases = new IdentityHashMap<>();
        }
        return blockAliases.computeIfAbsent(block, placed -> ClassCompiler.aliasCopyDeclarations(placed.statements()))[index];
    }

    // A skippable declaration that stores its variable assigns it only where it runs. An alias
    // stores nothing, so a read of it reads its source, even past a skipped declaration.
    private void initializeSkippable(Statements.InitializedDeclaration declaration, boolean alias)
    {
        Variable variable = declaration.variable();
        if (alias) {
            evaluate(declaration.initializer());
            if (aliases == null) {
                aliases = new IdentityHashMap<>();
            }
            aliases.put(variable, (LocalValue) declaration.initializer());
            return;
        }
        if (aliases != null) {
            aliases.remove(variable);
        }
        declare(variable);
        evaluate(declaration.initializer());
        assign(variable);
    }

    private void declare(Variable variable)
    {
        if (indexes == null) {
            indexes = new IdentityHashMap<>();
        }
        Integer index = indexes.get(variable);
        if (index == null) {
            // The declaration count bounds the variables, unless an expansion is not functional.
            if (indexes.size() == capacity) {
                return;
            }
            index = indexes.size();
            indexes.put(variable, index);
        }
        // A declaration emits nothing, so clearing the variable matters only when a backward jump
        // or a repeated placement of the block reaches the declaration again.
        if (reachable()) {
            current[index / Long.SIZE] &= ~(1L << index);
        }
    }

    private void assign(Variable variable)
    {
        int index = index(variable);
        if (index >= 0) {
            current[index / Long.SIZE] |= 1L << index;
        }
    }

    private void read(LocalValue local)
    {
        LocalValue source = local;
        if (aliases != null) {
            for (LocalValue alias = aliases.get(source); alias != null; alias = aliases.get(source)) {
                source = alias;
            }
        }
        int index = index(source);
        if (index >= 0 && (current[index / Long.SIZE] & (1L << index)) == 0) {
            throw new IllegalArgumentException("Variable %s might not have been initialized: %s".formatted(local.name(), method.declaration()));
        }
    }

    // Parameters, the receiver, and untracked variables are always assigned.
    private int index(LocalValue local)
    {
        if (indexes == null || !(local instanceof Variable variable)) {
            return -1;
        }
        Integer index = indexes.get(variable);
        return index == null ? -1 : index;
    }

    private boolean reachable()
    {
        return current[stride - 1] >= 0;
    }

    private void setUnreachable()
    {
        Arrays.fill(current, UNREACHABLE);
    }

    private void load(long[] rows, int row)
    {
        System.arraycopy(rows, row * stride, current, 0, stride);
    }

    private void meet(long[] rows, int row)
    {
        int offset = row * stride;
        for (int word = 0; word < stride; word++) {
            current[word] &= rows[offset + word];
        }
    }

    // Meets the current state into the row, returning whether the row changed.
    private boolean meetInto(long[] rows, int row)
    {
        int offset = row * stride;
        boolean lowered = false;
        for (int word = 0; word < stride; word++) {
            long merged = rows[offset + word] & current[word];
            lowered |= merged != rows[offset + word];
            rows[offset + word] = merged;
        }
        return lowered;
    }

    private void pushEvaluations(List<BytecodeExpression> expressions)
    {
        for (int index = expressions.size() - 1; index >= 0; index--) {
            pushTask(EVALUATE, expressions.get(index));
        }
    }

    private void pushTask(int task, Object operand)
    {
        pushTask(task);
        operands[taskCount - 1] = operand;
    }

    private void pushTask(int task)
    {
        if (taskCount == tasks.length) {
            tasks = Arrays.copyOf(tasks, taskCount * 2);
            operands = Arrays.copyOf(operands, taskCount * 2);
        }
        tasks[taskCount++] = task;
    }

    private void pushState()
    {
        pushRow(current, 0);
    }

    private void pushRow(long[] rows, int row)
    {
        growStates();
        System.arraycopy(rows, row * stride, states, stateCount * stride, stride);
        stateCount++;
    }

    private void pushUnreachable()
    {
        growStates();
        Arrays.fill(states, stateCount * stride, (stateCount + 1) * stride, UNREACHABLE);
        stateCount++;
    }

    private void growStates()
    {
        if ((stateCount + 1) * stride > states.length) {
            states = Arrays.copyOf(states, states.length * 2);
        }
    }

    private void popState()
    {
        stateCount--;
        load(states, stateCount);
    }

    private void meetPopState()
    {
        stateCount--;
        meet(states, stateCount);
    }

    private void swapState()
    {
        int offset = (stateCount - 1) * stride;
        for (int word = 0; word < stride; word++) {
            long saved = states[offset + word];
            states[offset + word] = current[word];
            current[word] = saved;
        }
    }
}
