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

import org.junit.jupiter.api.Test;

import java.lang.constant.ClassDesc;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.boundConstant;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.CodeBlock.blockBuilder;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;

/// A self-contained PageFunctionCompiler-shaped authoring fixture.
public class TestPageFunctionCompilerClassfile
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();
    private static final int NULL_SENTINEL = Integer.MIN_VALUE;

    @Test
    void testProjectionWorkClass()
            throws Exception
    {
        ProjectionOperator operator = new ProjectionOperator(5);
        ClassModel definition = definePageProjectionWorkClass(operator);
        Class<? extends ProjectionWork> generatedClass = StandardClassDefiner.builder(TestPageFunctionCompilerClassfile.class.getClassLoader())
                .build()
                .defineClass(definition, ProjectionWork.class);

        SourcePage page = SourcePage.page(
                new int[] {1, 2, NULL_SENTINEL, 4, 5},
                new int[] {10, 20, 30, 40, 50});
        ProjectionSession session = new ProjectionSession(3, -999);

        ProjectionWork rangeWork = generatedClass
                .getConstructor(IntArrayBuilder.class, ProjectionSession.class, SourcePage.class, SelectedPositions.class)
                .newInstance(new IntArrayBuilder(), session, page, SelectedPositions.range(1, 3));
        assertThat(rangeWork.process()).containsExactly(31, -999, 57);

        ProjectionWork listWork = generatedClass
                .getConstructor(IntArrayBuilder.class, ProjectionSession.class, SourcePage.class, SelectedPositions.class)
                .newInstance(new IntArrayBuilder(), session, page, SelectedPositions.list(1, 2, new int[] {4, 0, 3}));
        assertThat(listWork.process()).containsExactly(18, 57);
    }

    @Test
    void testPageFilterClass()
            throws Exception
    {
        PagePredicate predicate = new PagePredicate(45);
        ClassModel definition = definePageFilterClass(predicate);
        Class<? extends PageFilter> generatedClass = StandardClassDefiner.builder(TestPageFunctionCompilerClassfile.class.getClassLoader())
                .build()
                .defineClass(definition, PageFilter.class);
        PageFilter filter = generatedClass.getConstructor().newInstance();

        ProjectionSession session = new ProjectionSession(3, -999);
        assertThat(filter.filter(session, SourcePage.page(
                new int[] {1, 20, 30, 4},
                new int[] {10, 20, 20, 50})))
                .containsExactly(2, 3);

        // Reuse the generated instance with a larger page to exercise array growth.
        assertThat(filter.filter(session, SourcePage.page(
                new int[] {40, 1, 50, 2, 60, 3},
                new int[] {10, 2, 0, 3, 5, 4})))
                .containsExactly(0, 2, 4);
    }

    private static ClassModel definePageProjectionWorkClass(ProjectionOperator operator)
    {
        ClassDesc classType = ClassDesc.of(TestPageFunctionCompilerClassfile.class.getPackageName() + ".GeneratedPageProjectionWork" + NEXT_CLASS_ID.incrementAndGet());
        ClassDefinition classDefinition = ClassDefinition.define(classType)
                .access(PUBLIC, FINAL)
                .addInterface(ProjectionWork.class)
                .sourceFile("GeneratedPageProjectionWork.java");

        FieldDefinition outputBuilderField = classDefinition.field("outputBuilder", IntArrayBuilder.class).access(PRIVATE).build();
        FieldDefinition sessionField = classDefinition.field("session", ProjectionSession.class).access(PRIVATE).build();
        FieldDefinition selectedPositionsField = classDefinition.field("selectedPositions", SelectedPositions.class).access(PRIVATE).build();
        FieldDefinition leftField = classDefinition.field("block_0", int[].class).access(PRIVATE, FINAL).build();
        FieldDefinition rightField = classDefinition.field("block_1", int[].class).access(PRIVATE, FINAL).build();

        generateProcessMethod(classDefinition, outputBuilderField, sessionField, selectedPositionsField);
        generateEvaluateMethod(classDefinition, operator, outputBuilderField, leftField, rightField);

        Parameter outputBuilder = arg("outputBuilder", IntArrayBuilder.class);
        Parameter session = arg("session", ProjectionSession.class);
        Parameter page = arg("page", SourcePage.class);
        Parameter selectedPositions = arg("selectedPositions", SelectedPositions.class);
        MethodDefinition constructor = classDefinition.constructor(outputBuilder, session, page, selectedPositions).access(PUBLIC);
        CodeBlock.Builder body = constructor.body();
        Variable thisVariable = constructor.thisVariable();
        body.comment("super();")
                .invokeSuperConstructor()
                .append(thisVariable.setField(outputBuilderField, outputBuilder))
                .append(thisVariable.setField(sessionField, session))
                .append(thisVariable.setField(selectedPositionsField, selectedPositions))
                .append(thisVariable.setField(leftField, page.invoke("channel", int[].class, constantInt(0))))
                .append(thisVariable.setField(rightField, page.invoke("channel", int[].class, constantInt(1))))
                .ret();

        return classDefinition.build();
    }

    private static void generateProcessMethod(
            ClassDefinition classDefinition,
            FieldDefinition outputBuilderField,
            FieldDefinition sessionField,
            FieldDefinition selectedPositionsField)
    {
        MethodDefinition method = classDefinition.method("process", int[].class).access(PUBLIC);
        Variable thisVariable = method.thisVariable();
        CodeBlock.Builder body = method.body();

        Variable from = body.declare("from", thisVariable.getField(selectedPositionsField).invoke("getOffset", int.class));
        Variable to = body.declare("to", from.add(thisVariable.getField(selectedPositionsField).invoke("size", int.class)));
        Variable positions = body.declare(int[].class, "positions");
        Variable index = body.declare(int.class, "index");

        IfStatement ifStatement = IfStatement.builder()
                .condition(thisVariable.getField(selectedPositionsField).invoke("isList", boolean.class))
                .then(blockBuilder()
                        .append(positions.set(thisVariable.getField(selectedPositionsField).invoke("getPositions", int[].class)))
                        .append(ForLoop.builder()
                                .description("positions loop")
                                .initialize(index.set(from))
                                .condition(index.lessThan(to))
                                .update(index.increment())
                                .body(thisVariable.invoke("evaluate", void.class, thisVariable.getField(sessionField), positions.getElement(index)))
                                .build())
                        .build())
                .otherwise(ForLoop.builder()
                        .description("range based loop")
                        .initialize(index.set(from))
                        .condition(index.lessThan(to))
                        .update(index.increment())
                        .body(thisVariable.invoke("evaluate", void.class, thisVariable.getField(sessionField), index))
                        .build())
                .build();
        body.append(ifStatement)
                .ret(thisVariable.getField(outputBuilderField).invoke("build", int[].class));
    }

    private static void generateEvaluateMethod(
            ClassDefinition classDefinition,
            ProjectionOperator operator,
            FieldDefinition outputBuilderField,
            FieldDefinition leftField,
            FieldDefinition rightField)
    {
        Parameter session = arg("session", ProjectionSession.class);
        Parameter position = arg("position", int.class);
        MethodDefinition method = classDefinition.method("evaluate", void.class, session, position).access(PRIVATE);
        Variable thisVariable = method.thisVariable();
        CodeBlock.Builder body = method.body();

        Variable left = body.declare("left", thisVariable.getField(leftField).getElement(position));
        Variable wasNull = body.declare("wasNull", left.equal(constantInt(NULL_SENTINEL)));
        BytecodeExpression output = thisVariable.getField(outputBuilderField);
        BytecodeExpression projectedValue = boundConstant(operator, ProjectionOperator.class)
                .invoke("project", int.class, session, left, thisVariable.getField(rightField).getElement(position));
        body.append(IfStatement.builder()
                        .condition(wasNull)
                        .then(output.invoke("append", void.class, session.invoke("nullValue", int.class)))
                        .otherwise(output.invoke("append", void.class, projectedValue))
                        .build())
                .ret();
    }

    private static ClassModel definePageFilterClass(PagePredicate predicate)
    {
        ClassDesc classType = ClassDesc.of(TestPageFunctionCompilerClassfile.class.getPackageName() + ".GeneratedPageFilter" + NEXT_CLASS_ID.incrementAndGet());
        ClassDefinition classDefinition = ClassDefinition.define(classType)
                .access(PUBLIC, FINAL)
                .addInterface(PageFilter.class)
                .sourceFile("GeneratedPageFilter.java");
        FieldDefinition selectedPositionsField = classDefinition.field("selectedPositions", boolean[].class).access(PRIVATE).build();

        generateFilterMethod(classDefinition, predicate);
        generatePageFilterMethod(classDefinition, selectedPositionsField);

        MethodDefinition constructor = classDefinition.constructor().access(PUBLIC);
        CodeBlock.Builder body = constructor.body();
        Variable thisVariable = constructor.thisVariable();
        body.comment("super();")
                .invokeSuperConstructor()
                .append(thisVariable.setField(selectedPositionsField, newArray(boolean[].class, constantInt(0))))
                .ret();

        return classDefinition.build();
    }

    private static void generatePageFilterMethod(ClassDefinition classDefinition, FieldDefinition selectedPositionsField)
    {
        Parameter session = arg("session", ProjectionSession.class);
        Parameter page = arg("page", SourcePage.class);
        MethodDefinition method = classDefinition.method("filter", int[].class, session, page).access(PUBLIC);
        Variable thisVariable = method.thisVariable();
        CodeBlock.Builder body = method.body();

        Variable positionCount = body.declare("positionCount", page.invoke("positionCount", int.class));
        body.append(IfStatement.builder()
                .description("grow selectedPositions if necessary")
                .condition(thisVariable.getField(selectedPositionsField).length().lessThan(positionCount))
                .then(thisVariable.setField(selectedPositionsField, newArray(boolean[].class, positionCount)))
                .build());

        Variable selectedPositions = body.declare("selectedPositions", thisVariable.getField(selectedPositionsField));
        Variable position = body.declare(int.class, "position");
        body.append(ForLoop.builder()
                        .initialize(position.set(constantInt(0)))
                        .condition(position.lessThan(positionCount))
                        .update(position.increment())
                        .body(selectedPositions.setElement(position, thisVariable.invoke("filter", boolean.class, session, page, position)))
                        .build())
                .ret(invokeStatic(
                        TestPageFunctionCompilerClassfile.class,
                        "selectedPositions",
                        int[].class,
                        selectedPositions,
                        positionCount));
    }

    private static void generateFilterMethod(ClassDefinition classDefinition, PagePredicate predicate)
    {
        Parameter session = arg("session", ProjectionSession.class);
        Parameter page = arg("page", SourcePage.class);
        Parameter position = arg("position", int.class);
        MethodDefinition method = classDefinition.method("filter", boolean.class, session, page, position).access(PUBLIC);

        BytecodeExpression left = page.invoke("channel", int[].class, constantInt(0)).getElement(position);
        BytecodeExpression right = page.invoke("channel", int[].class, constantInt(1)).getElement(position);
        method.body().ret(boundConstant(predicate, PagePredicate.class)
                .invoke("test", boolean.class, left, right));
    }

    public static int[] selectedPositions(boolean[] selected, int positionCount)
    {
        int[] positions = new int[positionCount];
        int size = 0;
        for (int position = 0; position < positionCount; position++) {
            if (selected[position]) {
                positions[size++] = position;
            }
        }
        return Arrays.copyOf(positions, size);
    }

    public interface ProjectionWork
    {
        int[] process();
    }

    public interface PageFilter
    {
        int[] filter(ProjectionSession session, SourcePage page);
    }

    public record ProjectionSession(int multiplier, int nullValue) {}

    public record ProjectionOperator(int bias)
    {
        public int project(ProjectionSession session, int left, int right)
        {
            return left * session.multiplier() + right + bias;
        }
    }

    public record PagePredicate(int threshold)
    {
        public boolean test(int left, int right)
        {
            return left != NULL_SENTINEL && left + right >= threshold;
        }
    }

    public static final class SourcePage
    {
        private final int[][] channels;

        public SourcePage(int[][] channels)
        {
            this.channels = channels.clone();
            if (this.channels.length == 0) {
                throw new IllegalArgumentException("channels is empty");
            }
            int positionCount = this.channels[0].length;
            if (Arrays.stream(this.channels).anyMatch(channel -> channel.length != positionCount)) {
                throw new IllegalArgumentException("channel sizes differ");
            }
        }

        public static SourcePage page(int[]... channels)
        {
            return new SourcePage(channels);
        }

        public int[][] channels()
        {
            return channels.clone();
        }

        public int[] channel(int channel)
        {
            return channels[channel];
        }

        public int positionCount()
        {
            return channels[0].length;
        }
    }

    public static final class SelectedPositions
    {
        private final boolean list;
        private final int offset;
        private final int size;
        private final int[] positions;

        public SelectedPositions(boolean list, int offset, int size, int[] positions)
        {
            this.list = list;
            this.offset = offset;
            this.size = size;
            this.positions = positions.clone();
        }

        public static SelectedPositions range(int offset, int size)
        {
            return new SelectedPositions(false, offset, size, new int[0]);
        }

        public static SelectedPositions list(int offset, int size, int[] positions)
        {
            return new SelectedPositions(true, offset, size, positions);
        }

        public boolean isList()
        {
            return list;
        }

        public int getOffset()
        {
            return offset;
        }

        public int offset()
        {
            return offset;
        }

        public int size()
        {
            return size;
        }

        public int[] getPositions()
        {
            return positions.clone();
        }

        public int[] positions()
        {
            return positions.clone();
        }
    }

    public static final class IntArrayBuilder
    {
        private int[] values = new int[8];
        private int size;

        public void append(int value)
        {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        public int[] build()
        {
            return Arrays.copyOf(values, size);
        }
    }
}
