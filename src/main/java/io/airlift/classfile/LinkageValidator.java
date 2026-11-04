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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
            context.type(field.type(), "field ", field.name());
        }
        for (RecordComponentDefinition component : definition.recordComponents()) {
            context.recordComponentType(component.type(), "record component " + component.name());
        }
        for (MethodDefinition.Model method : definition.methods()) {
            context.method(method);
        }
        return Set.copyOf(context.classDataTypes);
    }

    private static final class Context
    {
        private static final int NO_OPERAND = -1;

        private final ClassModel definition;
        private final LinkageContext linkage;
        private final Optional<Object> classData;
        private final Set<ClassDesc> classDataTypes = new LinkedHashSet<>();
        private final Set<SyntheticExpression> activeSyntheticExpressions = Collections.newSetFromMap(new IdentityHashMap<>());
        // Types already found accessible from this definition; the answer depends only on the type.
        private final Set<ClassDesc> accessibleTypes = new HashSet<>();
        // Location of the node being checked, reused across the walk.
        private final StringBuilder location = new StringBuilder();
        // Expression stack without a per-node entry object: each expression or synthetic exit
        // marker, the location length of its parent, and its operand index.
        private Object[] pendingExpressions = new Object[32];
        private int[] pendingLocationLengths = new int[32];
        private int[] pendingOperands = new int[32];
        private int pendingCount;

        private Context(ClassModel definition, LinkageContext linkage, Optional<Object> classData)
        {
            this.definition = definition;
            this.linkage = linkage;
            this.classData = classData;
        }

        private void method(MethodDefinition.Model method)
        {
            location.setLength(0);
            location.append("method ").append(method.name()).append(method.methodType().descriptorString());
            methodType(method.methodType(), "");
            for (int index = 0; index < method.exceptions().size(); index++) {
                type(method.exceptions().get(index), " exception ", index);
            }
            if (method.hasBody()) {
                statement(method.body(), " body");
            }
        }

        private void type(ClassDesc type, String segment)
        {
            Optional<String> problem = accessProblem(type);
            if (problem.isPresent()) {
                enter(segment);
                throw accessFailure(problem.orElseThrow());
            }
        }

        private void type(ClassDesc type, String segment, int index)
        {
            Optional<String> problem = accessProblem(type);
            if (problem.isPresent()) {
                enter(segment, index);
                throw accessFailure(problem.orElseThrow());
            }
        }

        private void type(ClassDesc type, String segment, String name)
        {
            Optional<String> problem = accessProblem(type);
            if (problem.isPresent()) {
                enter(segment, name);
                throw accessFailure(problem.orElseThrow());
            }
        }

        private Optional<String> accessProblem(ClassDesc type)
        {
            if (type.isPrimitive() || accessibleTypes.contains(type)) {
                return Optional.empty();
            }
            Optional<String> problem = linkage.accessProblem(type, definition);
            if (problem.isEmpty()) {
                accessibleTypes.add(type);
            }
            return problem;
        }

        private CompilationException accessFailure(String problem)
        {
            return LinkageContext.linkageFailure(definition, location.toString(), problem);
        }

        private void recordComponentType(ClassDesc type, String locationText)
        {
            linkage.requireRecordComponentAccessible(type, definition, locationText);
        }

        private void interfaceType(ClassDesc type, String locationText)
        {
            linkage.requireInterface(type, definition, locationText);
        }

        private void methodType(MethodTypeDesc type, String segment)
        {
            int mark = enter(segment);
            type(type.returnType(), " return type");
            for (int index = 0; index < type.parameterCount(); index++) {
                type(type.parameterType(index), " parameter ", index);
            }
            exit(mark);
        }

        private void statement(Statement statement, String segment)
        {
            int mark = enter(segment);
            statement(statement);
            exit(mark);
        }

        private void statement(Statement statement)
        {
            switch (statement) {
                case BytecodeExpression expression -> expression(expression, "");
                case CodeBlock block -> {
                    for (int index = 0; index < block.statements().size(); index++) {
                        int mark = enter(" statement ", index);
                        statement(block.statements().get(index));
                        exit(mark);
                    }
                }
                case DoWhileLoop loop -> {
                    statement(loop.body(), " do body");
                    expression(loop.condition(), " do condition");
                }
                case ForLoop loop -> {
                    statement(loop.initializer(), " for initializer");
                    expression(loop.condition(), " for condition");
                    statement(loop.update(), " for update");
                    statement(loop.body(), " for body");
                }
                case IfStatement ifStatement -> {
                    expression(ifStatement.condition(), " if condition");
                    statement(ifStatement.ifTrue(), " then");
                    statement(ifStatement.ifFalse(), " otherwise");
                }
                case LoopJump _,
                     Statements.Comment _,
                     Statements.Jump _,
                     Statements.LabelBinding _ -> {}
                case SwitchStatement switchStatement -> {
                    expression(switchStatement.expression(), " switch expression");
                    for (SwitchStatement.Case switchCase : switchStatement.cases()) {
                        int mark = enter(" case ", switchCase.key());
                        statement(switchCase.body());
                        exit(mark);
                    }
                    statement(switchStatement.defaultCase(), " default case");
                }
                case TryCatch tryCatch -> {
                    statement(tryCatch.tryBlock(), " try");
                    for (TryCatch.CatchClause catchClause : tryCatch.catches()) {
                        type(catchClause.exceptionType(), " catch type");
                        type(catchClause.variable().type(), " catch variable ", catchClause.variable().name());
                        int mark = enter(" catch ", catchClause.exceptionType().displayName());
                        statement(catchClause.body());
                        exit(mark);
                    }
                    if (tryCatch.finallyBlock().isPresent()) {
                        statement(tryCatch.finallyBlock().orElseThrow(), " finally");
                    }
                }
                case WhileLoop loop -> {
                    expression(loop.condition(), " while condition");
                    statement(loop.body(), " while body");
                }
                case Statements.ConstructorInvocation invocation -> {
                    ClassDesc owner = switch (invocation.target()) {
                        case SUPER -> definition.superClass();
                        case THIS -> definition.type();
                    };
                    type(owner, " constructor owner");
                    methodType(invocation.constructorType(), " constructor");
                    expressions(invocation.arguments(), " constructor argument");
                }
                case Statements.Declaration declaration -> type(declaration.variable().type(), " local ", declaration.variable().name());
                case Statements.Expression expression -> expression(expression.expression(), "");
                case Statements.InitializedDeclaration declaration -> {
                    type(declaration.variable().type(), " local ", declaration.variable().name());
                    expression(declaration.initializer(), " initializer");
                }
            }
        }

        private void expression(BytecodeExpression expression, String segment)
        {
            record SyntheticExit(SyntheticExpression expression) {}

            int mark = enter(segment);
            int base = pendingCount;
            push(expression, location.length(), NO_OPERAND);
            while (pendingCount > base) {
                pendingCount--;
                Object item = pendingExpressions[pendingCount];
                pendingExpressions[pendingCount] = null;
                if (item instanceof SyntheticExit exit) {
                    activeSyntheticExpressions.remove(exit.expression());
                    continue;
                }

                location.setLength(pendingLocationLengths[pendingCount]);
                if (pendingOperands[pendingCount] != NO_OPERAND) {
                    location.append(" operand ").append(pendingOperands[pendingCount]);
                }
                switch ((BytecodeExpression) item) {
                    case LocalValue local -> type(local.type(), " local ", local.name());
                    case CoreExpression core -> {
                        node(core.node());
                        List<BytecodeExpression> children = core.node().children();
                        for (int index = children.size() - 1; index >= 0; index--) {
                            push(children.get(index), location.length(), index);
                        }
                    }
                    case SyntheticExpression synthetic -> {
                        if (!activeSyntheticExpressions.add(synthetic)) {
                            throw new CompilationException("Recursive synthetic expression at " + location + ": " + synthetic);
                        }
                        ExpressionPlan plan = synthetic.expansion(ExpansionContext.INSTANCE);
                        statement(plan.setup(), " synthetic setup");
                        location.append(" synthetic value");
                        push(new SyntheticExit(synthetic), location.length(), NO_OPERAND);
                        push(plan.value(), location.length(), NO_OPERAND);
                    }
                }
            }
            exit(mark);
        }

        private void push(Object item, int locationLength, int operand)
        {
            if (pendingCount == pendingExpressions.length) {
                pendingExpressions = Arrays.copyOf(pendingExpressions, pendingCount * 2);
                pendingLocationLengths = Arrays.copyOf(pendingLocationLengths, pendingCount * 2);
                pendingOperands = Arrays.copyOf(pendingOperands, pendingCount * 2);
            }
            pendingExpressions[pendingCount] = item;
            pendingLocationLengths[pendingCount] = locationLength;
            pendingOperands[pendingCount] = operand;
            pendingCount++;
        }

        private void node(ExpressionNode node)
        {
            switch (node) {
                case ExpressionNode.Constant constant -> {
                    if (constant.value() instanceof ClassDesc classDesc) {
                        type(classDesc, " class constant");
                    }
                }
                case ExpressionNode.Binary _,
                     ExpressionNode.Unary _,
                     ExpressionNode.InlineIf _,
                     ExpressionNode.ArrayLength _,
                     ExpressionNode.Adapter _ -> {}
                case ExpressionNode.Cast cast -> type(cast.type(), " cast type");
                case ExpressionNode.InstanceOf instanceOf -> type(instanceOf.testType(), " instanceof type");
                case ExpressionNode.ArrayGet arrayGet -> type(arrayGet.array().type(), " array type");
                case ExpressionNode.ArraySet arraySet -> type(arraySet.array().type(), " array type");
                case ExpressionNode.NewArray newArray -> type(newArray.type(), " array type");
                case ExpressionNode.FieldGet field -> {
                    type(field.owner(), " field owner");
                    type(field.type(), " field type");
                }
                case ExpressionNode.FieldSet field -> {
                    type(field.owner(), " field owner");
                    type(field.fieldType(), " field type");
                }
                case ExpressionNode.Invoke invoke -> {
                    type(invoke.owner(), " invocation owner");
                    methodType(invoke.methodType(), " invocation descriptor");
                }
                case ExpressionNode.NewInstance newInstance -> {
                    type(newInstance.type(), " constructor owner");
                    methodType(newInstance.constructorType(), " constructor descriptor");
                }
                case ExpressionNode.DynamicConstant dynamicConstant -> {
                    dynamicConstant(dynamicConstant.constant());
                    if (dynamicConstant.constant().bootstrapMethod().equals(BootstrapDescriptors.classDataConstant())) {
                        classDataTypes.add(dynamicConstant.type());
                        if (classData.isPresent() && !linkage.isInstance(classData.orElseThrow(), dynamicConstant.type())) {
                            int mark = enter(" class data");
                            linkage.requireInstance(classData.orElseThrow(), dynamicConstant.type(), definition, location.toString());
                            exit(mark);
                        }
                    }
                }
                case ExpressionNode.BoundConstant boundConstant -> {
                    type(boundConstant.type(), " bound constant type");
                    if (!linkage.isInstance(boundConstant.value(), boundConstant.type())) {
                        int mark = enter(" bound constant");
                        linkage.requireInstance(boundConstant.value(), boundConstant.type(), definition, location.toString());
                        exit(mark);
                    }
                }
                case ExpressionNode.BoundMethodHandleInvocation _ -> {
                    // The original handle signature is deliberately replaced with a target-accessible signature.
                }
                case ExpressionNode.LinkedMethodInvocation linked -> methodType(linked.method().type(), " linked method descriptor");
                case ExpressionNode.InvokeDynamic invokeDynamic -> dynamicCallSite(invokeDynamic.callSite());
                case ExpressionNode.SetVariable setVariable -> type(setVariable.variable().type(), " local ", setVariable.variable().name());
                case ExpressionNode.Increment increment -> type(increment.variable().type(), " local ", increment.variable().name());
            }
        }

        private void expressions(List<? extends BytecodeExpression> expressions, String segment)
        {
            int mark = enter(segment);
            for (int index = 0; index < expressions.size(); index++) {
                int expressionMark = enter(" ", index);
                expression(expressions.get(index), "");
                exit(expressionMark);
            }
            exit(mark);
        }

        private void dynamicConstant(DynamicConstantDesc<?> constant)
        {
            type(constant.constantType(), " dynamic constant type");
            methodHandle(constant.bootstrapMethod(), " bootstrap method");
            constantDescs(constant.bootstrapArgs(), " bootstrap argument");
        }

        private void dynamicCallSite(DynamicCallSiteDesc callSite)
        {
            methodType(callSite.invocationType(), " dynamic invocation type");
            methodHandle(callSite.bootstrapMethod(), " bootstrap method");
            constantDescs(callSite.bootstrapArgs(), " bootstrap argument");
        }

        private void methodHandle(MethodHandleDesc handle, String segment)
        {
            int mark = enter(segment);
            methodType(handle.invocationType(), " invocation type");
            if (handle instanceof DirectMethodHandleDesc direct) {
                type(direct.owner(), " owner");
            }
            exit(mark);
        }

        private void constantDescs(ConstantDesc[] constants, String segment)
        {
            int mark = enter(segment);
            for (int index = 0; index < constants.length; index++) {
                ConstantDesc constant = constants[index];
                int argumentMark = enter(" ", index);
                if (constant instanceof ClassDesc classDesc) {
                    type(classDesc, "");
                }
                else if (constant instanceof MethodTypeDesc methodType) {
                    methodType(methodType, "");
                }
                else if (constant instanceof MethodHandleDesc methodHandle) {
                    methodHandle(methodHandle, "");
                }
                else if (constant instanceof DynamicConstantDesc<?> dynamicConstant) {
                    dynamicConstant(dynamicConstant);
                }
                exit(argumentMark);
            }
            exit(mark);
        }

        private int enter(String segment)
        {
            int mark = location.length();
            location.append(segment);
            return mark;
        }

        private int enter(String segment, int index)
        {
            int mark = location.length();
            location.append(segment).append(index);
            return mark;
        }

        private int enter(String segment, String name)
        {
            int mark = location.length();
            location.append(segment).append(name);
            return mark;
        }

        private void exit(int mark)
        {
            location.setLength(mark);
        }
    }
}
