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
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.airlift.classfile.Identity.different;
import static java.util.Objects.requireNonNull;

final class PhysicalRewriter
{
    private PhysicalRewriter() {}

    static MethodDefinition.Model method(
            MethodDefinition.Model method,
            ClassDesc declaringType,
            Set<AccessFlag> access,
            BiFunction<ExpressionNode.Invoke, ClassDesc, ExpressionNode> invocationRewrite)
    {
        return new MethodDefinition.Model(
                declaringType,
                access,
                method.name(),
                method.methodType(),
                method.parameters(),
                method.parameterMetadata(),
                method.receiver(),
                method.methodBody().map(body -> block(body, declaringType, invocationRewrite)),
                method.exceptions(),
                method.signature(),
                method.comment(),
                method.visibleAnnotations(),
                method.invisibleAnnotations());
    }

    private static CodeBlock block(CodeBlock block, ClassDesc currentOwner, BiFunction<ExpressionNode.Invoke, ClassDesc, ExpressionNode> invocationRewrite)
    {
        ArrayList<Statement> statements = new ArrayList<>(block.statements().size());
        boolean changed = false;
        for (Statement statement : block.statements()) {
            Statement rewritten = statement(statement, currentOwner, invocationRewrite);
            statements.add(rewritten);
            changed |= different(rewritten, statement);
        }
        return changed ? block.withStatements(statements) : block;
    }

    private static Statement statement(Statement statement, ClassDesc currentOwner, BiFunction<ExpressionNode.Invoke, ClassDesc, ExpressionNode> invocationRewrite)
    {
        return switch (statement) {
            case BytecodeExpression expression -> expression(expression, currentOwner, invocationRewrite);
            case Statements.Expression value -> new Statements.Expression(expression(value.expression(), currentOwner, invocationRewrite));
            case Statements.InitializedDeclaration value -> new Statements.InitializedDeclaration(value.variable(), expression(value.initializer(), currentOwner, invocationRewrite));
            case CodeBlock value -> block(value, currentOwner, invocationRewrite);
            case IfStatement value -> value.rewrite(
                    expression(value.condition(), currentOwner, invocationRewrite),
                    block(value.ifTrue(), currentOwner, invocationRewrite),
                    block(value.ifFalse(), currentOwner, invocationRewrite));
            case ForLoop value -> value.rewrite(
                    block(value.initializer(), currentOwner, invocationRewrite),
                    expression(value.condition(), currentOwner, invocationRewrite),
                    block(value.update(), currentOwner, invocationRewrite),
                    block(value.body(), currentOwner, invocationRewrite));
            case WhileLoop value -> value.rewrite(
                    expression(value.condition(), currentOwner, invocationRewrite),
                    block(value.body(), currentOwner, invocationRewrite));
            case DoWhileLoop value -> value.rewrite(
                    expression(value.condition(), currentOwner, invocationRewrite),
                    block(value.body(), currentOwner, invocationRewrite));
            case SwitchStatement value -> value.rewrite(
                    expression(value.expression(), currentOwner, invocationRewrite),
                    value.cases().stream()
                            .map(caseValue -> new SwitchStatement.Case(caseValue.key(), block(caseValue.body(), currentOwner, invocationRewrite)))
                            .toList(),
                    block(value.defaultCase(), currentOwner, invocationRewrite));
            case TryCatch value -> value.rewrite(
                    block(value.tryBlock(), currentOwner, invocationRewrite),
                    value.catches().stream()
                            .map(catchClause -> new TryCatch.CatchClause(
                                    catchClause.exceptionType(),
                                    catchClause.variable(),
                                    block(catchClause.body(), currentOwner, invocationRewrite)))
                            .toList(),
                    value.finallyBlock().map(finallyBlock -> block(finallyBlock, currentOwner, invocationRewrite)));
            case Statements.ConstructorInvocation value -> new Statements.ConstructorInvocation(
                    value.target(),
                    value.declaredOwner(),
                    value.constructorType(),
                    value.arguments().stream().map(argument -> expression(argument, currentOwner, invocationRewrite)).toList());
            case LoopJump value -> value;
            case Statements.Comment value -> value;
            case Statements.Declaration value -> value;
            case Statements.Jump value -> value;
            case Statements.LabelBinding value -> value;
        };
    }

    private static BytecodeExpression expression(
            BytecodeExpression expression,
            ClassDesc currentOwner,
            BiFunction<ExpressionNode.Invoke, ClassDesc, ExpressionNode> invocationRewrite)
    {
        return switch (expression) {
            case LocalValue local -> local;
            case CoreExpression core -> new CoreExpression(node(core.node(), currentOwner, invocationRewrite));
            case SyntheticExpression synthetic -> {
                ExpressionPlan expansion = requireNonNull(synthetic.expansion(ExpansionContext.INSTANCE), "synthetic expansion is null");
                yield new RewrittenSynthetic(
                        synthetic.type(),
                        block(expansion.setup(), currentOwner, invocationRewrite),
                        expression(expansion.value(), currentOwner, invocationRewrite),
                        synthetic.toString());
            }
        };
    }

    private static ExpressionNode node(
            ExpressionNode node,
            ClassDesc currentOwner,
            BiFunction<ExpressionNode.Invoke, ClassDesc, ExpressionNode> invocationRewrite)
    {
        Function<BytecodeExpression, BytecodeExpression> rewrite = expression -> expression(expression, currentOwner, invocationRewrite);
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
            case ExpressionNode.Invoke value -> {
                ExpressionNode.Invoke rewritten = new ExpressionNode.Invoke(
                        value.type(),
                        value.kind(),
                        value.kind() == ExpressionNode.InvocationKind.STATIC ? null : rewrite.apply(value.target()),
                        value.owner(),
                        value.name(),
                        value.methodType(),
                        value.arguments().stream().map(rewrite).toList());
                yield invocationRewrite.apply(rewritten, currentOwner);
            }
            case ExpressionNode.NewInstance value -> new ExpressionNode.NewInstance(value.type(), value.constructorType(), value.arguments().stream().map(rewrite).toList());
            case ExpressionNode.DynamicConstant value -> value;
            case ExpressionNode.BoundConstant value -> value;
            case ExpressionNode.BoundMethodHandleInvocation value -> new ExpressionNode.BoundMethodHandleInvocation(value.type(), value.handle(), value.methodType(), value.arguments().stream().map(rewrite).toList());
            case ExpressionNode.LinkedMethodInvocation value -> new ExpressionNode.LinkedMethodInvocation(value.type(), value.method(), value.arguments().stream().map(rewrite).toList());
            case ExpressionNode.InvokeDynamic value -> new ExpressionNode.InvokeDynamic(value.type(), value.callSite(), value.arguments().stream().map(rewrite).toList());
            case ExpressionNode.SetVariable value -> new ExpressionNode.SetVariable(value.type(), value.variable(), rewrite.apply(value.value()));
            case ExpressionNode.Increment value -> value;
            case ExpressionNode.Adapter value -> new ExpressionNode.Adapter(value.type(), value.keyword(), rewrite.apply(value.value()));
        };
    }

    private record RewrittenSynthetic(ClassDesc type, CodeBlock setup, BytecodeExpression value, String rendering)
            implements SyntheticExpression
    {
        private RewrittenSynthetic
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
