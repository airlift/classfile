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
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

final class LinkageValidator
{
    private LinkageValidator() {}

    static Set<ClassDesc> validate(ClassModel definition, LinkageContext linkage, Optional<Object> classData)
    {
        requireNonNull(definition, "definition is null");
        requireNonNull(linkage, "linkage is null");
        Context context = new Context(definition, linkage, requireNonNull(classData, "classData is null"));

        context.type(definition.superClass(), "super class");
        for (int index = 0; index < definition.interfaces().size(); index++) {
            context.interfaceType(definition.interfaces().get(index), "interface " + index);
        }
        for (FieldDefinition field : definition.fields()) {
            context.type(field.type(), "field " + field.name());
        }
        for (RecordComponentDefinition component : definition.recordComponents()) {
            context.recordComponentType(component.type(), "record component " + component.name());
        }
        for (MethodDefinition.Model method : definition.methods()) {
            String methodLocation = "method " + method.name() + method.methodType().descriptorString();
            context.methodType(method.methodType(), methodLocation);
            for (int index = 0; index < method.exceptions().size(); index++) {
                context.type(method.exceptions().get(index), methodLocation + " exception " + index);
            }
            if (method.hasBody()) {
                context.statement(method.body(), methodLocation + " body");
            }
        }
        return Set.copyOf(context.classDataTypes);
    }

    private static final class Context
    {
        private final ClassModel definition;
        private final LinkageContext linkage;
        private final Optional<Object> classData;
        private final Set<ClassDesc> classDataTypes = new LinkedHashSet<>();
        private final Set<SyntheticExpression> activeSyntheticExpressions = Collections.newSetFromMap(new IdentityHashMap<>());

        private Context(ClassModel definition, LinkageContext linkage, Optional<Object> classData)
        {
            this.definition = definition;
            this.linkage = linkage;
            this.classData = classData;
        }

        private void type(ClassDesc type, String location)
        {
            linkage.requireAccessible(type, definition, location);
        }

        private void recordComponentType(ClassDesc type, String location)
        {
            linkage.requireRecordComponentAccessible(type, definition, location);
        }

        private void interfaceType(ClassDesc type, String location)
        {
            linkage.requireInterface(type, definition, location);
        }

        private void methodType(MethodTypeDesc type, String location)
        {
            type(type.returnType(), location + " return type");
            for (int index = 0; index < type.parameterCount(); index++) {
                type(type.parameterType(index), location + " parameter " + index);
            }
        }

        private void statement(Statement statement, String location)
        {
            switch (statement) {
                case BytecodeExpression expression -> expression(expression, location);
                case CodeBlock block -> {
                    for (int index = 0; index < block.statements().size(); index++) {
                        statement(block.statements().get(index), location + " statement " + index);
                    }
                }
                case DoWhileLoop loop -> {
                    statement(loop.body(), location + " do body");
                    expression(loop.condition(), location + " do condition");
                }
                case ForLoop loop -> {
                    statement(loop.initializer(), location + " for initializer");
                    expression(loop.condition(), location + " for condition");
                    statement(loop.update(), location + " for update");
                    statement(loop.body(), location + " for body");
                }
                case IfStatement ifStatement -> {
                    expression(ifStatement.condition(), location + " if condition");
                    statement(ifStatement.ifTrue(), location + " then");
                    statement(ifStatement.ifFalse(), location + " otherwise");
                }
                case LoopJump _,
                     Statements.Comment _,
                     Statements.Jump _,
                     Statements.LabelBinding _ -> {}
                case SwitchStatement switchStatement -> {
                    expression(switchStatement.expression(), location + " switch expression");
                    for (SwitchStatement.Case switchCase : switchStatement.cases()) {
                        statement(switchCase.body(), location + " case " + switchCase.key());
                    }
                    statement(switchStatement.defaultCase(), location + " default case");
                }
                case TryCatch tryCatch -> {
                    statement(tryCatch.tryBlock(), location + " try");
                    for (TryCatch.CatchClause catchClause : tryCatch.catches()) {
                        type(catchClause.exceptionType(), location + " catch type");
                        type(catchClause.variable().type(), location + " catch variable " + catchClause.variable().name());
                        statement(catchClause.body(), location + " catch " + catchClause.exceptionType().displayName());
                    }
                    tryCatch.finallyBlock().ifPresent(block -> statement(block, location + " finally"));
                }
                case WhileLoop loop -> {
                    expression(loop.condition(), location + " while condition");
                    statement(loop.body(), location + " while body");
                }
                case Statements.ConstructorInvocation invocation -> {
                    ClassDesc owner = switch (invocation.target()) {
                        case SUPER -> definition.superClass();
                        case THIS -> definition.type();
                    };
                    type(owner, location + " constructor owner");
                    methodType(invocation.constructorType(), location + " constructor");
                    expressions(invocation.arguments(), location + " constructor argument");
                }
                case Statements.Declaration declaration -> type(declaration.variable().type(), location + " local " + declaration.variable().name());
                case Statements.Expression expression -> expression(expression.expression(), location);
                case Statements.InitializedDeclaration declaration -> {
                    type(declaration.variable().type(), location + " local " + declaration.variable().name());
                    expression(declaration.initializer(), location + " initializer");
                }
            }
        }

        private void expression(BytecodeExpression expression, String location)
        {
            record Pending(BytecodeExpression expression, String location) {}

            record SyntheticExit(SyntheticExpression expression) {}

            ArrayDeque<Object> pending = new ArrayDeque<>();
            ArrayList<SyntheticExpression> enteredSynthetics = new ArrayList<>();
            pending.push(new Pending(expression, location));
            try {
                while (!pending.isEmpty()) {
                    Object item = pending.pop();
                    if (item instanceof SyntheticExit exit) {
                        activeSyntheticExpressions.remove(exit.expression());
                        enteredSynthetics.removeLast();
                        continue;
                    }

                    Pending current = (Pending) item;
                    switch (current.expression()) {
                        case LocalValue local -> type(local.type(), current.location() + " local " + local.name());
                        case CoreExpression core -> {
                            node(core.node(), current.location());
                            List<BytecodeExpression> children = core.node().children();
                            for (int index = children.size() - 1; index >= 0; index--) {
                                pending.push(new Pending(children.get(index), current.location() + " operand " + index));
                            }
                        }
                        case SyntheticExpression synthetic -> {
                            if (!activeSyntheticExpressions.add(synthetic)) {
                                throw new CompilationException("Recursive synthetic expression at " + current.location() + ": " + synthetic);
                            }
                            enteredSynthetics.add(synthetic);
                            ExpressionPlan plan = synthetic.expansion(ExpansionContext.INSTANCE);
                            statement(plan.setup(), current.location() + " synthetic setup");
                            pending.push(new SyntheticExit(synthetic));
                            pending.push(new Pending(plan.value(), current.location() + " synthetic value"));
                        }
                    }
                }
            }
            finally {
                enteredSynthetics.forEach(activeSyntheticExpressions::remove);
            }
        }

        private void node(ExpressionNode node, String location)
        {
            switch (node) {
                case ExpressionNode.Constant constant -> {
                    if (constant.value() instanceof ClassDesc classDesc) {
                        type(classDesc, location + " class constant");
                    }
                }
                case ExpressionNode.Binary _,
                     ExpressionNode.Unary _,
                     ExpressionNode.InlineIf _,
                     ExpressionNode.ArrayLength _,
                     ExpressionNode.Adapter _ -> {}
                case ExpressionNode.Cast cast -> type(cast.type(), location + " cast type");
                case ExpressionNode.InstanceOf instanceOf -> type(instanceOf.testType(), location + " instanceof type");
                case ExpressionNode.ArrayGet arrayGet -> type(arrayGet.array().type(), location + " array type");
                case ExpressionNode.ArraySet arraySet -> type(arraySet.array().type(), location + " array type");
                case ExpressionNode.NewArray newArray -> type(newArray.type(), location + " array type");
                case ExpressionNode.FieldGet field -> {
                    type(field.owner(), location + " field owner");
                    type(field.type(), location + " field type");
                }
                case ExpressionNode.FieldSet field -> {
                    type(field.owner(), location + " field owner");
                    type(field.fieldType(), location + " field type");
                }
                case ExpressionNode.Invoke invoke -> {
                    type(invoke.owner(), location + " invocation owner");
                    methodType(invoke.methodType(), location + " invocation descriptor");
                }
                case ExpressionNode.NewInstance newInstance -> {
                    type(newInstance.type(), location + " constructor owner");
                    methodType(newInstance.constructorType(), location + " constructor descriptor");
                }
                case ExpressionNode.DynamicConstant dynamicConstant -> {
                    dynamicConstant(dynamicConstant.constant(), location);
                    if (dynamicConstant.constant().bootstrapMethod().equals(BootstrapDescriptors.classDataConstant())) {
                        classDataTypes.add(dynamicConstant.type());
                        classData.ifPresent(value -> linkage.requireInstance(value, dynamicConstant.type(), definition, location + " class data"));
                    }
                }
                case ExpressionNode.BoundConstant boundConstant -> {
                    type(boundConstant.type(), location + " bound constant type");
                    linkage.requireInstance(boundConstant.value(), boundConstant.type(), definition, location + " bound constant");
                }
                case ExpressionNode.BoundMethodHandleInvocation _ -> {
                    // The original handle signature is deliberately replaced with a target-accessible signature.
                }
                case ExpressionNode.LinkedMethodInvocation linked -> methodType(linked.method().type(), location + " linked method descriptor");
                case ExpressionNode.InvokeDynamic invokeDynamic -> dynamicCallSite(invokeDynamic.callSite(), location);
                case ExpressionNode.SetVariable setVariable -> type(setVariable.variable().type(), location + " local " + setVariable.variable().name());
                case ExpressionNode.Increment increment -> type(increment.variable().type(), location + " local " + increment.variable().name());
            }
        }

        private void expressions(List<? extends BytecodeExpression> expressions, String location)
        {
            for (int index = 0; index < expressions.size(); index++) {
                expression(expressions.get(index), location + " " + index);
            }
        }

        private void dynamicConstant(DynamicConstantDesc<?> constant, String location)
        {
            type(constant.constantType(), location + " dynamic constant type");
            methodHandle(constant.bootstrapMethod(), location + " bootstrap method");
            constantDescs(constant.bootstrapArgs(), location + " bootstrap argument");
        }

        private void dynamicCallSite(DynamicCallSiteDesc callSite, String location)
        {
            methodType(callSite.invocationType(), location + " dynamic invocation type");
            methodHandle(callSite.bootstrapMethod(), location + " bootstrap method");
            constantDescs(callSite.bootstrapArgs(), location + " bootstrap argument");
        }

        private void methodHandle(MethodHandleDesc handle, String location)
        {
            methodType(handle.invocationType(), location + " invocation type");
            if (handle instanceof DirectMethodHandleDesc direct) {
                type(direct.owner(), location + " owner");
            }
        }

        private void constantDescs(ConstantDesc[] constants, String location)
        {
            for (int index = 0; index < constants.length; index++) {
                ConstantDesc constant = constants[index];
                String argumentLocation = location + " " + index;
                if (constant instanceof ClassDesc classDesc) {
                    type(classDesc, argumentLocation);
                }
                else if (constant instanceof MethodTypeDesc methodType) {
                    methodType(methodType, argumentLocation);
                }
                else if (constant instanceof MethodHandleDesc methodHandle) {
                    methodHandle(methodHandle, argumentLocation);
                }
                else if (constant instanceof DynamicConstantDesc<?> dynamicConstant) {
                    dynamicConstant(dynamicConstant, argumentLocation);
                }
            }
        }
    }
}
