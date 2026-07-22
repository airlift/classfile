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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/// Defines generated hidden classes associated with a host [MethodHandles.Lookup].
///
/// Hidden classes are not discoverable by binary name. Compile for this definer's
/// [HiddenClassDefiner#compilationTarget()] and retain the returned lookup or [DefinedUnit] to
/// access the generated classes.
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

    /// Returns the target describing the host lookup used by this definer. Compile a [CompiledUnit]
    /// for this target before passing it to [HiddenClassDefiner#defineUnit(CompiledUnit)].
    public CompilationTarget compilationTarget()
    {
        return CompilationTarget.forLookup(hostLookup, options);
    }

    /// Compiles and defines one model as a hidden class using this definer's target.
    public MethodHandles.Lookup defineClass(ClassModel definition)
    {
        requireNonNull(definition, "definition is null");
        if (!runtimeData.bindings().isEmpty()) {
            throw new IllegalStateException("Preconfigured bindings cannot be used when compiling a class definition");
        }
        ClassCompiler compiler = ClassCompiler.forTarget(compilationTarget());
        if (runtimeData.classData().isPresent()) {
            compiler = compiler.classData(runtimeData.classData().orElseThrow());
        }
        CompiledClass compiledClass = compiler.compileClass(definition);
        return defineCompiledClass(compiledClass);
    }

    /// Defines an already compiled hidden class. The artifact must have been compiled for this
    /// definer's target.
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

    /// Defines every physical class in dependency order and returns the primary class and hidden
    /// lookups. The unit must have been compiled for this definer's target.
    public DefinedUnit defineUnit(CompiledUnit unit)
    {
        requireNonNull(unit, "unit is null");
        unit.target().requireCompatibleWith(compilationTarget());
        unit.validateConfiguredRuntimeData(runtimeData);
        Map<CompiledUnit.LinkedMethod, MethodHandle> linkedMethods = new LinkedHashMap<>();
        Map<ClassDesc, Class<?>> classes = new LinkedHashMap<>();
        Map<ClassDesc, MethodHandles.Lookup> lookups = new LinkedHashMap<>();

        for (ClassDesc type : unit.definitionOrder()) {
            RuntimeData compiledRuntimeData = unit.runtimeData(type, method -> {
                MethodHandle handle = linkedMethods.get(method);
                if (handle == null) {
                    throw new IllegalStateException("Generated method was not linked before its caller: " + method);
                }
                return handle;
            });
            RuntimeData effectiveRuntimeData = RuntimeData.merge(runtimeData, compiledRuntimeData);
            unit.validateRuntimeData(type, effectiveRuntimeData);
            MethodHandles.Lookup defined;
            try {
                if (unit.lambdaFactoryRequired(type)) {
                    defined = hostLookup.defineHiddenClassWithClassData(
                            unit.classfile(type),
                            new HiddenClassRuntimeData(effectiveRuntimeData, lambdaFactory),
                            initialize,
                            options);
                }
                else if (effectiveRuntimeData.isEmpty()) {
                    defined = hostLookup.defineHiddenClass(unit.classfile(type), initialize, options);
                }
                else {
                    defined = hostLookup.defineHiddenClassWithClassData(unit.classfile(type), effectiveRuntimeData, initialize, options);
                }
            }
            catch (IllegalAccessException e) {
                throw new IllegalStateException("Unable to define hidden class " + type.displayName(), e);
            }

            classes.put(type, defined.lookupClass());
            lookups.put(type, defined);
            for (CompiledUnit.LinkedMethod method : unit.linkedMethodsOwnedBy(type)) {
                try {
                    MethodType methodType = MethodType.fromMethodDescriptorString(method.type().descriptorString(), defined.lookupClass().getClassLoader());
                    linkedMethods.put(method, defined.findStatic(defined.lookupClass(), method.name(), methodType));
                }
                catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new IllegalStateException("Unable to link generated method " + method, e);
                }
            }
        }
        return new DefinedUnit(unit.primaryType(), classes, lookups);
    }

    /// Set-once configuration for hidden-class definition.
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
