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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(2)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 6, time = 1)
public class BenchmarkClassfileGeneration
{
    @Benchmark
    public ClassCompiler createCompiler(CompilerData data)
    {
        data.compilerSink = ClassCompiler.forTarget(data.target);
        return data.compilerSink;
    }

    @Benchmark
    public CompiledClass compileClass(BenchmarkData data)
    {
        return ClassCompiler.forTarget(data.target).compileClass(data.model);
    }

    @Benchmark
    public CompiledClass compileClassReusingCompiler(BenchmarkData data)
    {
        return data.compiler.compileClass(data.model);
    }

    @Benchmark
    public MethodHandles.Lookup defineCompiledClass(BenchmarkData data)
    {
        return data.definer.defineCompiledClass(data.compiledClass);
    }

    @Benchmark
    public MethodHandles.Lookup defineRawClassfile(BenchmarkData data)
            throws IllegalAccessException
    {
        return data.lookup.defineHiddenClass(data.classfile, false);
    }

    @Benchmark
    public MethodHandles.Lookup compileAndDefineClass(BenchmarkData data)
    {
        CompiledClass compiledClass = ClassCompiler.forTarget(data.target).compileClass(data.model);
        return data.definer.defineCompiledClass(compiledClass);
    }

    @State(Scope.Thread)
    public static class CompilerData
    {
        private CompilationTarget target;
        @SuppressWarnings("FieldCanBeLocal") // Volatile publication prevents escape analysis from eliminating compiler creation.
        private volatile ClassCompiler compilerSink;

        @Setup(Level.Trial)
        public void setup()
        {
            target = HiddenClassDefiner.builder(MethodHandles.lookup()).build().compilationTarget();
        }
    }

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"STATE_1", "STATE_5", "STATE_64", "STATE_512"})
        private Shape shape;

        private MethodHandles.Lookup lookup;
        private CompilationTarget target;
        private HiddenClassDefiner definer;
        private ClassCompiler compiler;
        private ClassModel model;
        private CompiledClass compiledClass;
        private byte[] classfile;

        @Setup(Level.Trial)
        public void setup()
        {
            lookup = MethodHandles.lookup();
            definer = HiddenClassDefiner.builder(lookup).build();
            target = definer.compilationTarget();
            compiler = ClassCompiler.forTarget(target);
            model = stateModel(shape.fieldCount());
            compiledClass = compiler.compileClass(model);
            classfile = compiledClass.classfile();
        }
    }

    public enum Shape
    {
        STATE_1(1),
        STATE_5(5),
        STATE_64(64),
        STATE_512(512);

        private final int fieldCount;

        Shape(int fieldCount)
        {
            this.fieldCount = fieldCount;
        }

        public int fieldCount()
        {
            return fieldCount;
        }
    }

    private static ClassModel stateModel(int fieldCount)
    {
        ClassDefinition definition = ClassDefinition.define(ClassDesc.of(BenchmarkClassfileGeneration.class.getPackageName() + ".GeneratedBenchmarkState"))
                .access(PUBLIC, FINAL);
        definition.defaultConstructor().access(PUBLIC);

        List<FieldDefinition> fields = new ArrayList<>(fieldCount);
        for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
            FieldDefinition field = definition.field("field" + fieldIndex, long.class)
                    .access(PRIVATE)
                    .build();
            fields.add(field);

            MethodDefinition getter = definition.method("getField" + fieldIndex, long.class).access(PUBLIC);
            getter.body().append(getter.thisVariable().getField(field).ret());

            Parameter value = Parameter.arg("value", long.class);
            MethodDefinition setter = definition.method("setField" + fieldIndex, void.class, value).access(PUBLIC);
            setter.body()
                    .append(setter.thisVariable().setField(field, value))
                    .ret();
        }

        MethodDefinition clear = definition.method("clear", void.class).access(PUBLIC);
        for (FieldDefinition field : fields) {
            clear.body().append(clear.thisVariable().setField(field, constantLong(0)));
        }
        clear.body().ret();

        MethodDefinition estimatedSize = definition.method("estimatedSize", long.class).access(PUBLIC);
        Variable size = estimatedSize.body().declare("size", constantLong(0));
        for (FieldDefinition field : fields) {
            estimatedSize.body().append(size.set(size.add(estimatedSize.thisVariable().getField(field))));
        }
        estimatedSize.body().append(size.ret());

        return definition.build();
    }

    public static void main(String[] args)
            throws Exception
    {
        BenchmarkSupport.run(BenchmarkClassfileGeneration.class, args);
    }
}
