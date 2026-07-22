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

import java.lang.invoke.MethodHandles;

import static java.util.Objects.requireNonNull;

public final class HiddenClassDefiner
{
    private final MethodHandles.Lookup hostLookup;
    private final LambdaFactory lambdaFactory;
    private final RuntimeData runtimeData;
    private final boolean initialize;
    private final MethodHandles.Lookup.ClassOption[] options;

    private HiddenClassDefiner(Builder builder)
    {
        hostLookup = builder.hostLookup;
        lambdaFactory = new LambdaFactory(hostLookup);
        runtimeData = builder.runtimeData;
        initialize = builder.initialize;
        options = builder.options.clone();
    }

    public static Builder builder(MethodHandles.Lookup hostLookup)
    {
        return new Builder(hostLookup);
    }

    /// Returns the target describing the host lookup used by this definer.
    public CompilationTarget compilationTarget()
    {
        return CompilationTarget.forLookup(hostLookup, options);
    }

    public MethodHandles.Lookup defineClass(ClassModel definition)
    {
        requireNonNull(definition, "definition is null");
        if (!runtimeData.bindings().isEmpty()) {
            throw new IllegalStateException("Preconfigured bindings cannot be used when compiling a class definition");
        }
        ClassCompiler compiler = ClassCompiler.forTarget(CompilationTarget.forLookup(hostLookup));
        if (runtimeData.classData().isPresent()) {
            compiler = compiler.classData(runtimeData.classData().orElseThrow());
        }
        CompiledClass compiledClass = compiler.compileClass(definition);
        return defineCompiledClass(compiledClass);
    }

    public MethodHandles.Lookup defineCompiledClass(CompiledClass compiledClass)
    {
        requireNonNull(compiledClass, "compiledClass is null");
        compiledClass.target().requireCompatibleWith(compilationTarget());
        RuntimeData effectiveRuntimeData = RuntimeData.merge(runtimeData, compiledClass.runtimeData());
        compiledClass.validateRuntimeData(effectiveRuntimeData);
        try {
            if (compiledClass.lambdaFactoryRequired()) {
                return hostLookup.defineHiddenClassWithClassData(
                        compiledClass.classfile(),
                        new HiddenClassRuntimeData(effectiveRuntimeData, lambdaFactory),
                        initialize,
                        options);
            }
            if (effectiveRuntimeData.isEmpty()) {
                return hostLookup.defineHiddenClass(compiledClass.classfile(), initialize, options);
            }
            return hostLookup.defineHiddenClassWithClassData(compiledClass.classfile(), effectiveRuntimeData, initialize, options);
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to define hidden class", e);
        }
    }

    public static final class Builder
    {
        private final MethodHandles.Lookup hostLookup;
        private RuntimeData runtimeData = RuntimeData.EMPTY;
        private boolean runtimeDataSet;
        private boolean initialize = true;
        private boolean initializeSet;
        private MethodHandles.Lookup.ClassOption[] options = {};
        private boolean optionsSet;

        private Builder(MethodHandles.Lookup hostLookup)
        {
            this.hostLookup = requireNonNull(hostLookup, "hostLookup is null");
        }

        public Builder runtimeData(RuntimeData runtimeData)
        {
            if (runtimeDataSet) {
                throw new IllegalStateException("runtime data is already set");
            }
            this.runtimeData = requireNonNull(runtimeData, "runtimeData is null");
            runtimeDataSet = true;
            return this;
        }

        public Builder initialize(boolean initialize)
        {
            if (initializeSet) {
                throw new IllegalStateException("initialize is already set");
            }
            this.initialize = initialize;
            initializeSet = true;
            return this;
        }

        public Builder options(MethodHandles.Lookup.ClassOption... options)
        {
            if (optionsSet) {
                throw new IllegalStateException("options are already set");
            }
            this.options = requireNonNull(options, "options is null").clone();
            optionsSet = true;
            return this;
        }

        public HiddenClassDefiner build()
        {
            return new HiddenClassDefiner(this);
        }
    }
}
