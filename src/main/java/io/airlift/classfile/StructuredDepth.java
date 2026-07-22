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

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;

import static java.util.Objects.requireNonNull;

final class StructuredDepth
{
    static final int MAX_NESTING = 512;

    private static final int MAX_RECURSION = 128;
    private static final ArrayDeque<Node> NO_PENDING = new ArrayDeque<>(0);

    private StructuredDepth() {}

    static void validate(Statement statement)
    {
        new Validator().validate(requireNonNull(statement, "statement is null"), 0, 0, false, NO_PENDING);
    }

    private static final class Validator
    {
        private IdentityHashMap<SyntheticExpression, Integer> expandedDepth;

        private void validate(Object value, int depth, int recursion, boolean queued, ArrayDeque<Node> pending)
        {
            if (depth > MAX_NESTING) {
                throw new IllegalArgumentException("Structured statement nesting exceeds the supported limit of " + MAX_NESTING);
            }
            if (recursion == MAX_RECURSION) {
                validateIterative(value, depth);
                return;
            }

            switch (value) {
                case LocalValue _ -> {}
                case CoreExpression core -> {
                    List<BytecodeExpression> children = core.node().children();
                    if (queued) {
                        children.forEach(child -> visit(child, depth, recursion + 1, true, pending));
                    }
                    else {
                        for (int index = children.size() - 1; index >= 0; index--) {
                            visit(children.get(index), depth, recursion + 1, false, pending);
                        }
                    }
                }
                case SyntheticExpression synthetic -> {
                    if (shouldExpand(synthetic, depth)) {
                        ExpressionPlan expansion = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                        if (queued) {
                            visit(expansion.value(), depth, recursion + 1, true, pending);
                            visit(expansion.setup(), depth + 1, recursion + 1, true, pending);
                        }
                        else {
                            visit(expansion.setup(), depth + 1, recursion + 1, false, pending);
                            visit(expansion.value(), depth, recursion + 1, false, pending);
                        }
                    }
                }
                case Statements.Expression expression -> visit(expression.expression(), depth, recursion + 1, queued, pending);
                case Statements.InitializedDeclaration declaration -> visit(declaration.initializer(), depth, recursion + 1, queued, pending);
                case CodeBlock block -> {
                    if (queued) {
                        block.statements().forEach(child -> visit(child, depth + 1, recursion + 1, true, pending));
                    }
                    else {
                        for (int index = block.statements().size() - 1; index >= 0; index--) {
                            visit(block.statements().get(index), depth + 1, recursion + 1, false, pending);
                        }
                    }
                }
                case IfStatement statement -> {
                    if (queued) {
                        visit(statement.condition(), depth, recursion + 1, true, pending);
                        visit(statement.ifTrue(), depth + 1, recursion + 1, true, pending);
                        visit(statement.ifFalse(), depth + 1, recursion + 1, true, pending);
                    }
                    else {
                        visit(statement.ifFalse(), depth + 1, recursion + 1, false, pending);
                        visit(statement.ifTrue(), depth + 1, recursion + 1, false, pending);
                        visit(statement.condition(), depth, recursion + 1, false, pending);
                    }
                }
                case ForLoop statement -> {
                    if (queued) {
                        visit(statement.initializer(), depth + 1, recursion + 1, true, pending);
                        visit(statement.condition(), depth, recursion + 1, true, pending);
                        visit(statement.update(), depth + 1, recursion + 1, true, pending);
                        visit(statement.body(), depth + 1, recursion + 1, true, pending);
                    }
                    else {
                        visit(statement.body(), depth + 1, recursion + 1, false, pending);
                        visit(statement.update(), depth + 1, recursion + 1, false, pending);
                        visit(statement.condition(), depth, recursion + 1, false, pending);
                        visit(statement.initializer(), depth + 1, recursion + 1, false, pending);
                    }
                }
                case WhileLoop statement -> {
                    if (queued) {
                        visit(statement.condition(), depth, recursion + 1, true, pending);
                        visit(statement.body(), depth + 1, recursion + 1, true, pending);
                    }
                    else {
                        visit(statement.body(), depth + 1, recursion + 1, false, pending);
                        visit(statement.condition(), depth, recursion + 1, false, pending);
                    }
                }
                case DoWhileLoop statement -> {
                    if (queued) {
                        visit(statement.body(), depth + 1, recursion + 1, true, pending);
                        visit(statement.condition(), depth, recursion + 1, true, pending);
                    }
                    else {
                        visit(statement.condition(), depth, recursion + 1, false, pending);
                        visit(statement.body(), depth + 1, recursion + 1, false, pending);
                    }
                }
                case SwitchStatement statement -> {
                    if (queued) {
                        visit(statement.expression(), depth, recursion + 1, true, pending);
                        statement.cases().forEach(switchCase -> visit(switchCase.body(), depth + 1, recursion + 1, true, pending));
                        visit(statement.defaultCase(), depth + 1, recursion + 1, true, pending);
                    }
                    else {
                        visit(statement.defaultCase(), depth + 1, recursion + 1, false, pending);
                        for (int index = statement.cases().size() - 1; index >= 0; index--) {
                            visit(statement.cases().get(index).body(), depth + 1, recursion + 1, false, pending);
                        }
                        visit(statement.expression(), depth, recursion + 1, false, pending);
                    }
                }
                case TryCatch statement -> {
                    if (queued) {
                        visit(statement.tryBlock(), depth + 1, recursion + 1, true, pending);
                        statement.catches().forEach(catchClause -> visit(catchClause.body(), depth + 1, recursion + 1, true, pending));
                        statement.finallyBlock().ifPresent(finallyBlock -> visit(finallyBlock, depth + 1, recursion + 1, true, pending));
                    }
                    else {
                        statement.finallyBlock().ifPresent(finallyBlock -> visit(finallyBlock, depth + 1, recursion + 1, false, pending));
                        for (int index = statement.catches().size() - 1; index >= 0; index--) {
                            visit(statement.catches().get(index).body(), depth + 1, recursion + 1, false, pending);
                        }
                        visit(statement.tryBlock(), depth + 1, recursion + 1, false, pending);
                    }
                }
                case Statements.ConstructorInvocation invocation -> {
                    if (queued) {
                        invocation.arguments().forEach(argument -> visit(argument, depth, recursion + 1, true, pending));
                    }
                    else {
                        for (int index = invocation.arguments().size() - 1; index >= 0; index--) {
                            visit(invocation.arguments().get(index), depth, recursion + 1, false, pending);
                        }
                    }
                }
                case Statements.Declaration _,
                     Statements.Comment _,
                     Statements.Jump _,
                     Statements.LabelBinding _,
                     LoopJump _ -> {}
                default -> throw new AssertionError("Unknown structured value: " + value);
            }
        }

        private void validateIterative(Object value, int depth)
        {
            ArrayDeque<Node> pending = new ArrayDeque<>();
            pending.push(new Node(value, depth));
            while (!pending.isEmpty()) {
                Node node = pending.pop();
                validate(node.value(), node.depth(), 0, true, pending);
            }
        }

        private void visit(Object value, int depth, int recursion, boolean queued, ArrayDeque<Node> pending)
        {
            if (queued) {
                pending.push(new Node(value, depth));
            }
            else {
                validate(value, depth, recursion, false, pending);
            }
        }

        private boolean shouldExpand(SyntheticExpression synthetic, int depth)
        {
            if (expandedDepth == null) {
                expandedDepth = new IdentityHashMap<>();
            }
            Integer previousDepth = expandedDepth.put(synthetic, depth);
            return previousDepth == null || depth > previousDepth;
        }
    }

    private record Node(Object value, int depth)
    {
        private Node
        {
            requireNonNull(value, "value is null");
        }
    }
}
