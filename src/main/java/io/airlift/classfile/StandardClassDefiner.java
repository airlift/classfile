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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/// Defines nominal generated classes in a dedicated generated class loader.
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

    /// Returns the target describing the generated class loader used by this definer.
    public CompilationTarget compilationTarget()
    {
        return CompilationTarget.forClassLoader(classLoader);
    }

    public Class<?> defineClass(ClassModel definition)
    {
        requireNonNull(definition, "definition is null");
        RuntimeData configuredRuntimeData = classLoader.configuredRuntimeData();
        ClassCompiler compiler = ClassCompiler.forTarget(CompilationTarget.forClassLoader(classLoader));
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

    public DefinedClasses defineClasses(List<ClassModel> definitions)
    {
        requireNonNull(definitions, "definitions is null");
        RuntimeData configuredRuntimeData = classLoader.configuredRuntimeData();
        ClassCompiler compiler = ClassCompiler.forTarget(CompilationTarget.forClassLoader(classLoader));
        if (configuredRuntimeData.classData().isPresent()) {
            compiler = compiler.classData(configuredRuntimeData.classData().orElseThrow());
        }
        CompiledClassBundle compiledClasses = compiler.compileClassBundle(definitions);
        return defineCompiledClasses(compiledClasses);
    }

    public Class<?> defineCompiledClass(CompiledClass compiledClass)
    {
        requireNonNull(compiledClass, "compiledClass is null");
        compiledClass.target().requireCompatibleWith(compilationTarget());
        RuntimeData effectiveRuntimeData = RuntimeData.merge(classLoader.configuredRuntimeData(), compiledClass.runtimeData());
        compiledClass.validateRuntimeData(effectiveRuntimeData);
        return defineClassfiles(Map.of(compiledClass.type(), compiledClass.classfile()), effectiveRuntimeData)
                .definedClass(compiledClass.type());
    }

    public DefinedClasses defineCompiledClasses(CompiledClassBundle compiledClasses)
    {
        requireNonNull(compiledClasses, "compiledClasses is null");
        compiledClasses.target().requireCompatibleWith(compilationTarget());
        RuntimeData effectiveRuntimeData = RuntimeData.merge(classLoader.configuredRuntimeData(), compiledClasses.runtimeData());
        compiledClasses.validateRuntimeData(effectiveRuntimeData);
        return defineClassfiles(compiledClasses.classfiles(), effectiveRuntimeData);
    }

    private DefinedClasses defineClassfiles(Map<ClassDesc, byte[]> classfiles, RuntimeData runtimeData)
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

    @SuppressWarnings("BanClassLoader") // This loader exists specifically to define compiler-produced bytecode.
    private static final class GeneratedClassLoader
            extends ClassLoader
            implements RuntimeDataProvider
    {
        private final ClassLoader overrideLoader;
        private RuntimeData runtimeData;
        private final Map<String, byte[]> pending = new LinkedHashMap<>();

        private GeneratedClassLoader(ClassLoader parent, ClassLoader overrideLoader, RuntimeData runtimeData)
        {
            super(parent);
            this.overrideLoader = overrideLoader;
            this.runtimeData = runtimeData;
        }

        private synchronized Reservation reserve(Map<ClassDesc, byte[]> classfiles, RuntimeData runtimeData)
        {
            requireNonNull(classfiles, "classfiles is null");
            requireNonNull(runtimeData, "runtimeData is null");
            LinkedHashMap<String, byte[]> reservations = new LinkedHashMap<>();
            classfiles.forEach((type, classfile) -> reservations.put(
                    binaryName(requireNonNull(type, "type is null")),
                    requireNonNull(classfile, "classfile is null").clone()));
            for (String name : reservations.keySet()) {
                if (pending.containsKey(name) || findLoadedClass(name) != null) {
                    throw new IllegalArgumentException("Class is already defined or pending: " + name);
                }
            }
            RuntimeData effectiveRuntimeData = RuntimeData.merge(this.runtimeData, runtimeData);
            this.runtimeData = effectiveRuntimeData;
            pending.putAll(reservations);
            return new Reservation(reservations);
        }

        private synchronized void releasePending(Reservation reservation)
        {
            reservation.classfiles().forEach((name, classfile) -> {
                pending.remove(name, classfile);
            });
        }

        @Override
        protected synchronized Class<?> findClass(String name)
                throws ClassNotFoundException
        {
            byte[] classfile = pending.remove(name);
            if (classfile == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, classfile, 0, classfile.length);
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
            requireNonNull(generatedClass, "generatedClass is null");
            return runtimeData;
        }

        private synchronized RuntimeData configuredRuntimeData()
        {
            return runtimeData;
        }

        private record Reservation(Map<String, byte[]> classfiles)
        {
            private Reservation
            {
                classfiles = Map.copyOf(requireNonNull(classfiles, "classfiles is null"));
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
