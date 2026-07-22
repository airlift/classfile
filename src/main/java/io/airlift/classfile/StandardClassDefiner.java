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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/// Defines nominal generated classes in a dedicated generated class loader.
///
/// Compile for this definer's [StandardClassDefiner#compilationTarget()] so visibility and runtime
/// package checks reflect the loader that will define the classes.
///
/// A definer is safe to share across threads within a bounded generation scope. Every class
/// defined through one instance shares its generated loader and therefore its unloading lifetime;
/// do not retain one definer for an unbounded stream of generated classes.
public final class StandardClassDefiner
{
    private final GeneratedClassLoader classLoader;
    private final boolean initialize;

    private StandardClassDefiner(ClassLoader parent, ClassLoader overrideLoader, RuntimeData runtimeData, boolean initialize)
    {
        classLoader = new GeneratedClassLoader(parent, overrideLoader, runtimeData);
        this.initialize = initialize;
    }

    public static Builder builder(ClassLoader parent)
    {
        return new Builder(parent);
    }

    /// Returns the target describing the generated class loader used by this definer. Compile a
    /// [CompiledUnit] for this target before passing it to [StandardClassDefiner#defineUnit(CompiledUnit)].
    public CompilationTarget compilationTarget()
    {
        return CompilationTarget.forClassLoader(classLoader);
    }

    /// Compiles and defines one model as a nominal class using this definer's target.
    public Class<?> defineClass(ClassModel definition)
    {
        requireNonNull(definition, "definition is null");
        RuntimeData configuredRuntimeData = classLoader.configuredRuntimeData();
        ClassCompiler compiler = ClassCompiler.forTarget(compilationTarget());
        if (configuredRuntimeData.classData().isPresent()) {
            compiler = compiler.classData(configuredRuntimeData.classData().orElseThrow());
        }
        CompiledClass compiledClass = compiler.compileClass(definition);
        return defineCompiledClass(compiledClass);
    }

    public <T> Class<? extends T> defineClass(ClassModel definition, Class<T> superType)
    {
        return defineClass(definition).asSubclass(requireNonNull(superType, "superType is null"));
    }

    /// Compiles and defines explicitly authored nominal classes in the same generated class loader.
    public DefinedClasses defineClasses(List<ClassModel> definitions)
    {
        requireNonNull(definitions, "definitions is null");
        RuntimeData configuredRuntimeData = classLoader.configuredRuntimeData();
        ClassCompiler compiler = ClassCompiler.forTarget(compilationTarget());
        if (configuredRuntimeData.classData().isPresent()) {
            compiler = compiler.classData(configuredRuntimeData.classData().orElseThrow());
        }
        CompiledClassBundle compiledClasses = compiler.compileClassBundle(definitions);
        return defineCompiledClasses(compiledClasses);
    }

    /// Defines an already compiled nominal class. The artifact must have been compiled for this
    /// definer's target.
    public Class<?> defineCompiledClass(CompiledClass compiledClass)
    {
        requireNonNull(compiledClass, "compiledClass is null");
        compiledClass.target().requireCompatibleWith(compilationTarget());
        RuntimeData effectiveRuntimeData = RuntimeData.merge(classLoader.configuredRuntimeData(), compiledClass.runtimeData());
        compiledClass.validateRuntimeData(effectiveRuntimeData);
        return defineClassfiles(
                Map.of(compiledClass.type(), compiledClass.classfile()),
                Map.of(compiledClass.type(), effectiveRuntimeData))
                .definedClass(compiledClass.type());
    }

    /// Defines an already compiled nominal bundle in the same generated class loader.
    public DefinedClasses defineCompiledClasses(CompiledClassBundle compiledClasses)
    {
        requireNonNull(compiledClasses, "compiledClasses is null");
        compiledClasses.target().requireCompatibleWith(compilationTarget());
        RuntimeData effectiveRuntimeData = RuntimeData.merge(classLoader.configuredRuntimeData(), compiledClasses.runtimeData());
        compiledClasses.validateRuntimeData(effectiveRuntimeData);
        LinkedHashMap<ClassDesc, RuntimeData> runtimeData = new LinkedHashMap<>();
        compiledClasses.types().forEach(type -> runtimeData.put(type, effectiveRuntimeData));
        return defineClassfiles(compiledClasses.classfiles(), runtimeData);
    }

    /// Defines every physical class in dependency order and returns the primary class. The unit
    /// must have been compiled for this definer's target.
    public DefinedUnit defineUnit(CompiledUnit unit)
    {
        requireNonNull(unit, "unit is null");
        unit.target().requireCompatibleWith(compilationTarget());
        RuntimeData configuredRuntimeData = classLoader.configuredRuntimeData();
        unit.validateConfiguredRuntimeData(configuredRuntimeData);
        LinkedHashMap<ClassDesc, byte[]> classfiles = new LinkedHashMap<>();
        unit.types().forEach(type -> classfiles.put(type, unit.classfile(type)));
        GeneratedClassLoader.Reservation reservation = classLoader.reserve(classfiles, Map.of());

        boolean completed = false;
        try {
            Map<CompiledUnit.LinkedMethod, MethodHandle> linkedMethods = new LinkedHashMap<>();
            Map<ClassDesc, Class<?>> classes = new LinkedHashMap<>();
            for (ClassDesc type : unit.definitionOrder()) {
                RuntimeData compiledRuntimeData = unit.runtimeData(type, method -> {
                    MethodHandle handle = linkedMethods.get(method);
                    if (handle == null) {
                        throw new IllegalStateException("Generated method was not linked before its caller: " + method);
                    }
                    return handle;
                });
                RuntimeData effectiveRuntimeData = RuntimeData.merge(configuredRuntimeData, compiledRuntimeData);
                unit.validateRuntimeData(type, effectiveRuntimeData);
                classLoader.setRuntimeData(reservation, type, effectiveRuntimeData);
                Class<?> definedClass;
                try {
                    definedClass = Class.forName(binaryName(type), initialize, classLoader);
                }
                catch (ClassNotFoundException e) {
                    throw new IllegalStateException("Unable to define generated class " + type.displayName(), e);
                }
                classes.put(type, definedClass);
                for (CompiledUnit.LinkedMethod method : unit.linkedMethodsOwnedBy(type)) {
                    try {
                        MethodType methodType = MethodType.fromMethodDescriptorString(method.type().descriptorString(), definedClass.getClassLoader());
                        linkedMethods.put(method, MethodHandles.publicLookup().findStatic(definedClass, method.name(), methodType));
                    }
                    catch (NoSuchMethodException | IllegalAccessException e) {
                        throw new IllegalStateException("Unable to link generated method " + method, e);
                    }
                }
            }
            completed = true;
            return new DefinedUnit(unit.primaryType(), classes, Map.of());
        }
        finally {
            if (!completed) {
                classLoader.releasePending(reservation);
            }
        }
    }

    private DefinedClasses defineClassfiles(Map<ClassDesc, byte[]> classfiles, Map<ClassDesc, RuntimeData> runtimeData)
    {
        requireNonNull(classfiles, "classfiles is null");
        GeneratedClassLoader.Reservation reservation = classLoader.reserve(classfiles, requireNonNull(runtimeData, "runtimeData is null"));

        boolean completed = false;
        try {
            Map<ClassDesc, Class<?>> classes = new LinkedHashMap<>();
            for (ClassDesc type : classfiles.keySet()) {
                try {
                    classes.put(type, Class.forName(binaryName(type), initialize, classLoader));
                }
                catch (ClassNotFoundException e) {
                    throw new IllegalStateException("Unable to define generated class " + type.displayName(), e);
                }
            }
            completed = true;
            return new DefinedClasses(classes);
        }
        finally {
            if (!completed) {
                classLoader.releasePending(reservation);
            }
        }
    }

    /// Set-once configuration for nominal-class definition.
    public static final class Builder
    {
        private final ClassLoader parent;
        private ClassLoader overrideLoader;
        private RuntimeData runtimeData = RuntimeData.EMPTY;
        private boolean runtimeDataSet;
        private boolean initialize = true;
        private boolean initializeSet;
        private boolean overrideLoaderSet;

        private Builder(ClassLoader parent)
        {
            this.parent = requireNonNull(parent, "parent is null");
        }

        /// Configures a loader for non-generated dependencies that must retain identity from a
        /// plugin or overlay environment. Pending classes owned by this definer are resolved
        /// first, followed by this override loader and then normal parent delegation. The
        /// dedicated generated loader continues to define and own generated classes and their
        /// runtime data.
        public Builder overrideLoader(ClassLoader overrideLoader)
        {
            if (overrideLoaderSet) {
                throw new IllegalStateException("override loader is already set");
            }
            this.overrideLoader = requireNonNull(overrideLoader, "overrideLoader is null");
            overrideLoaderSet = true;
            return this;
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

        public StandardClassDefiner build()
        {
            return new StandardClassDefiner(parent, overrideLoader, runtimeData, initialize);
        }
    }

    /// Defines only bytecode produced by this compiler and never accepts serialized or
    /// otherwise untrusted classfile input.
    @SuppressWarnings("BanClassLoader")
    private static final class GeneratedClassLoader
            extends ClassLoader
            implements RuntimeDataProvider
    {
        private final ClassLoader overrideLoader;
        private final RuntimeData configuredRuntimeData;
        private final Map<String, RuntimeData> runtimeData = new HashMap<>();
        private final Map<String, PendingClass> pending = new HashMap<>();

        private GeneratedClassLoader(ClassLoader parent, ClassLoader overrideLoader, RuntimeData runtimeData)
        {
            super(parent);
            this.overrideLoader = overrideLoader;
            this.configuredRuntimeData = runtimeData;
        }

        private synchronized Reservation reserve(Map<ClassDesc, byte[]> classfiles, Map<ClassDesc, RuntimeData> runtimeData)
        {
            requireNonNull(classfiles, "classfiles is null");
            requireNonNull(runtimeData, "runtimeData is null");
            LinkedHashMap<String, byte[]> reservedClassfiles = new LinkedHashMap<>();
            LinkedHashMap<String, RuntimeData> reservedRuntimeData = new LinkedHashMap<>();
            classfiles.forEach((type, classfile) -> reservedClassfiles.put(
                    binaryName(requireNonNull(type, "type is null")),
                    requireNonNull(classfile, "classfile is null").clone()));
            runtimeData.forEach((type, value) -> reservedRuntimeData.put(
                    binaryName(requireNonNull(type, "type is null")),
                    RuntimeData.merge(configuredRuntimeData, requireNonNull(value, "runtimeData value is null"))));

            for (String name : reservedClassfiles.keySet()) {
                if (pending.containsKey(name) || findLoadedClass(name) != null) {
                    throw new IllegalArgumentException("Class is already defined or pending: " + name);
                }
            }
            for (Map.Entry<String, RuntimeData> entry : reservedRuntimeData.entrySet()) {
                RuntimeData existing = this.runtimeData.get(entry.getKey());
                if (existing != null && !RuntimeData.compatible(existing, entry.getValue())) {
                    throw new IllegalArgumentException("Runtime data is already set for " + entry.getKey());
                }
            }

            Reservation reservation = new Reservation();
            reservedRuntimeData.forEach((name, value) -> {
                if (this.runtimeData.putIfAbsent(name, value) == null) {
                    reservation.runtimeDataNames.add(name);
                }
            });
            reservedClassfiles.forEach((name, classfile) -> {
                PendingClass pendingClass = new PendingClass(classfile, reservation);
                reservation.classes.put(name, pendingClass);
                pending.put(name, pendingClass);
            });
            return reservation;
        }

        private synchronized void releasePending(Reservation reservation)
        {
            reservation.classes.forEach((name, pendingClass) -> {
                if (pending.remove(name, pendingClass) && reservation.runtimeDataNames.contains(name)) {
                    runtimeData.remove(name);
                }
            });
        }

        @Override
        protected synchronized Class<?> findClass(String name)
                throws ClassNotFoundException
        {
            PendingClass pendingClass = pending.get(name);
            if (pendingClass == null) {
                throw new ClassNotFoundException(name);
            }
            if (pendingClass.defining) {
                throw new ClassNotFoundException("Class is already being defined: " + name);
            }
            pendingClass.defining = true;
            try {
                byte[] classfile = pendingClass.classfile;
                Class<?> definedClass = defineClass(name, classfile, 0, classfile.length);
                pendingClass.defined = true;
                pending.remove(name, pendingClass);
                return definedClass;
            }
            catch (Throwable failure) {
                pendingClass.defining = false;
                throw failure;
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException
        {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded;
                synchronized (this) {
                    loaded = findLoadedClass(name);
                    if (loaded == null && pending.containsKey(name)) {
                        loaded = findClass(name);
                    }
                }
                if (loaded == null && overrideLoader != null) {
                    try {
                        loaded = overrideLoader.loadClass(name);
                    }
                    catch (ClassNotFoundException _) {
                        // Continue with normal parent delegation.
                    }
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        public synchronized RuntimeData runtimeData(Class<?> generatedClass)
        {
            return runtimeData.getOrDefault(requireNonNull(generatedClass, "generatedClass is null").getName(), configuredRuntimeData);
        }

        private RuntimeData configuredRuntimeData()
        {
            return configuredRuntimeData;
        }

        private synchronized void setRuntimeData(Reservation reservation, ClassDesc type, RuntimeData runtimeData)
        {
            requireNonNull(reservation, "reservation is null");
            String name = binaryName(requireNonNull(type, "type is null"));
            PendingClass pendingClass = reservation.classes.get(name);
            if (pendingClass == null || pendingClass.reservation != reservation || (pending.get(name) != pendingClass && !pendingClass.defined)) {
                throw new IllegalStateException("Class is not pending for this reservation: " + name);
            }
            RuntimeData effective = RuntimeData.merge(configuredRuntimeData, requireNonNull(runtimeData, "runtimeData is null"));
            RuntimeData existing = this.runtimeData.get(name);
            if (existing != null && !RuntimeData.compatible(existing, effective)) {
                throw new IllegalArgumentException("Runtime data is already set for " + name);
            }
            if (this.runtimeData.putIfAbsent(name, effective) == null) {
                reservation.runtimeDataNames.add(name);
            }
        }

        private static final class Reservation
        {
            private final Map<String, PendingClass> classes = new LinkedHashMap<>();
            private final Set<String> runtimeDataNames = new LinkedHashSet<>();
        }

        private static final class PendingClass
        {
            private final byte[] classfile;
            private final Reservation reservation;
            private boolean defining;
            private boolean defined;

            private PendingClass(byte[] classfile, Reservation reservation)
            {
                this.classfile = requireNonNull(classfile, "classfile is null");
                this.reservation = requireNonNull(reservation, "reservation is null");
            }
        }
    }

    private static String binaryName(ClassDesc type)
    {
        if (!type.isClassOrInterface()) {
            throw new IllegalArgumentException("type is not a class: " + type.displayName());
        }
        return type.packageName().isEmpty() ? type.displayName() : type.packageName() + "." + type.displayName();
    }
}
