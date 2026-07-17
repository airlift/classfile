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
import java.util.Map;

import static java.util.Objects.requireNonNull;

final class StructuredDepth
{
    static final int MAX_NESTING = 512;

    private StructuredDepth() {}

    static void validate(Statement statement)
    {
        ArrayDeque<Node> pending = new ArrayDeque<>();
        Map<SyntheticExpression, Integer> expandedDepth = new IdentityHashMap<>();
        pending.push(new Node(requireNonNull(statement, "statement is null"), 0));
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (node.depth() > MAX_NESTING) {
                throw new IllegalArgumentException("Structured statement nesting exceeds the supported limit of " + MAX_NESTING);
            }
            switch (node.value()) {
                case LocalValue _ -> {}
                case CoreExpression core -> core.node().children().forEach(child -> pending.push(new Node(child, node.depth())));
                case SyntheticExpression synthetic -> {
                    Integer previousDepth = expandedDepth.put(synthetic, node.depth());
                    if (previousDepth == null || node.depth() > previousDepth) {
                        ExpressionPlan expansion = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                        pending.push(new Node(expansion.value(), node.depth()));
                        pending.push(new Node(expansion.setup(), node.depth() + 1));
                    }
                }
                case Statements.Expression expression -> pending.push(new Node(expression.expression(), node.depth()));
                case Statements.InitializedDeclaration declaration -> pending.push(new Node(declaration.initializer(), node.depth()));
                case CodeBlock block -> block.statements().forEach(item -> pending.push(new Node(item, node.depth() + 1)));
                case IfStatement value -> {
                    pending.push(new Node(value.condition(), node.depth()));
                    pending.push(new Node(value.ifTrue(), node.depth() + 1));
                    pending.push(new Node(value.ifFalse(), node.depth() + 1));
                }
                case ForLoop value -> {
                    pending.push(new Node(value.initializer(), node.depth() + 1));
                    pending.push(new Node(value.condition(), node.depth()));
                    pending.push(new Node(value.update(), node.depth() + 1));
                    pending.push(new Node(value.body(), node.depth() + 1));
                }
                case WhileLoop value -> {
                    pending.push(new Node(value.condition(), node.depth()));
                    pending.push(new Node(value.body(), node.depth() + 1));
                }
                case DoWhileLoop value -> {
                    pending.push(new Node(value.body(), node.depth() + 1));
                    pending.push(new Node(value.condition(), node.depth()));
                }
                case SwitchStatement value -> {
                    pending.push(new Node(value.expression(), node.depth()));
                    value.cases().forEach(item -> pending.push(new Node(item.body(), node.depth() + 1)));
                    pending.push(new Node(value.defaultCase(), node.depth() + 1));
                }
                case TryCatch value -> {
                    pending.push(new Node(value.tryBlock(), node.depth() + 1));
                    value.catches().forEach(item -> pending.push(new Node(item.body(), node.depth() + 1)));
                    value.finallyBlock().ifPresent(item -> pending.push(new Node(item, node.depth() + 1)));
                }
                case Statements.ConstructorInvocation value -> value.arguments().forEach(argument -> pending.push(new Node(argument, node.depth())));
                case Statements.Declaration _,
                     Statements.Comment _,
                     Statements.Jump _,
                     Statements.LabelBinding _,
                     LoopJump _ -> {}
                default -> throw new AssertionError("Unknown structured value: " + node.value());
            }
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
