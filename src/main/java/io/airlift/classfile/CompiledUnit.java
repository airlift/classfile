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
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/// A complete compilation result, including any physical helper classes and their runtime linkage.
public final class CompiledUnit
{
    private final CompilationTarget target;
    private final ClassDesc primaryType;
    private final Map<ClassDesc, Artifact> artifacts;
    private final List<ClassDesc> definitionOrder;
    private final CompilationReport report;

    CompiledUnit(CompilationTarget target, ClassDesc primaryType, List<Artifact> artifacts, CompilationReport report)
    {
        this.target = requireNonNull(target, "target is null");
        this.primaryType = requireNonNull(primaryType, "primaryType is null");
        requireNonNull(artifacts, "artifacts is null");
        LinkedHashMap<ClassDesc, Artifact> artifactMap = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            requireNonNull(artifact, "artifact is null");
            if (artifactMap.putIfAbsent(artifact.type(), artifact) != null) {
                throw new IllegalArgumentException("Class is compiled more than once: " + artifact.type().displayName());
            }
        }
        if (!artifactMap.containsKey(primaryType)) {
            throw new IllegalArgumentException("Primary class was not compiled: " + primaryType.displayName());
        }
        this.artifacts = Collections.unmodifiableMap(artifactMap);
        this.definitionOrder = Collections.unmodifiableList(computeDefinitionOrder(artifactMap));
        this.report = requireNonNull(report, "report is null");
    }

    public CompilationTarget target()
    {
        return target;
    }

    public ClassDesc primaryType()
    {
        return primaryType;
    }

    public Set<ClassDesc> types()
    {
        return artifacts.keySet();
    }

    /// Returns a defensive copy of the physical classfile for the symbolic type.
    public byte[] classfile(ClassDesc type)
    {
        return artifact(type).classfile();
    }

    /// Reports emitted physical classes, methods, sizes, and non-fatal optimization warnings.
    public CompilationReport report()
    {
        return report;
    }

    /// Physical definition order. Dependencies always precede classes whose runtime data references them.
    public List<ClassDesc> definitionOrder()
    {
        return definitionOrder;
    }

    /// Returns generated methods owned by a physical type that must be linked before callers of
    /// that type are defined.
    Set<LinkedMethod> linkedMethodsOwnedBy(ClassDesc type)
    {
        requireNonNull(type, "type is null");
        LinkedHashSet<LinkedMethod> methods = new LinkedHashSet<>();
        artifacts.values().stream()
                .flatMap(artifact -> artifact.bindings().stream())
                .filter(MethodBinding.class::isInstance)
                .map(MethodBinding.class::cast)
                .map(MethodBinding::method)
                .filter(method -> method.owner().equals(type))
                .forEach(methods::add);
        return Set.copyOf(methods);
    }

    /// Returns whether hidden definition of the physical class must install the loader-owned
    /// lambda factory.
    boolean lambdaFactoryRequired(ClassDesc type)
    {
        return artifact(type).lambdaFactoryRequired();
    }

    /// Resolves the runtime data for one physical class after linked helper methods have been
    /// defined. Definers call this in [CompiledUnit#definitionOrder()].
    RuntimeData runtimeData(ClassDesc type, Function<LinkedMethod, MethodHandle> methodResolver)
    {
        requireNonNull(methodResolver, "methodResolver is null");
        Artifact artifact = artifact(type);
        List<Object> values = artifact.bindings().stream()
                .map(binding -> switch (binding) {
                    case ValueBinding value -> value.value();
                    case MethodBinding method -> requireNonNull(
                            methodResolver.apply(method.method()),
                            "Method resolver returned null for " + method.method());
                })
                .toList();
        return new RuntimeData(artifact.classData(), values);
    }

    /// Validates definer-configured runtime data before any physical class is defined.
    void validateConfiguredRuntimeData(RuntimeData configuredRuntimeData)
    {
        requireNonNull(configuredRuntimeData, "configuredRuntimeData is null");
        if (!configuredRuntimeData.bindings().isEmpty() && artifacts.values().stream().anyMatch(artifact -> !artifact.bindings().isEmpty())) {
            throw new IllegalArgumentException("Runtime data is incompatible");
        }
        for (Artifact artifact : artifacts.values()) {
            Optional<Object> effectiveClassData = RuntimeDataRequirements.mergeClassData(configuredRuntimeData.classData(), artifact.classData());
            RuntimeDataRequirements.validate(
                    target,
                    artifact.type(),
                    artifacts.keySet(),
                    artifact.classDataTypes(),
                    new RuntimeData(effectiveClassData, List.of()));
        }
    }

    /// Validates the effective runtime data for one physical class.
    void validateRuntimeData(ClassDesc type, RuntimeData runtimeData)
    {
        Artifact artifact = artifact(type);
        RuntimeDataRequirements.validate(target, artifact.type(), artifacts.keySet(), artifact.classDataTypes(), runtimeData);
    }

    private Artifact artifact(ClassDesc type)
    {
        Artifact artifact = artifacts.get(requireNonNull(type, "type is null"));
        if (artifact == null) {
            throw new IllegalArgumentException("Class was not compiled: " + type.displayName());
        }
        return artifact;
    }

    private static List<ClassDesc> computeDefinitionOrder(Map<ClassDesc, Artifact> artifacts)
    {
        ArrayList<ClassDesc> order = new ArrayList<>();
        LinkedHashSet<ClassDesc> visiting = new LinkedHashSet<>();
        LinkedHashSet<ClassDesc> visited = new LinkedHashSet<>();
        artifacts.keySet().forEach(type -> visit(type, artifacts, visiting, visited, order));
        return order;
    }

    private static void visit(
            ClassDesc type,
            Map<ClassDesc, Artifact> artifacts,
            Set<ClassDesc> visiting,
            Set<ClassDesc> visited,
            List<ClassDesc> order)
    {
        if (visited.contains(type)) {
            return;
        }
        if (!visiting.add(type)) {
            throw new IllegalArgumentException("Generated linkage contains a cycle: " + visiting + " -> " + type.displayName());
        }
        Artifact artifact = artifacts.get(type);
        for (Binding binding : artifact.bindings()) {
            if (binding instanceof MethodBinding(LinkedMethod method)) {
                ClassDesc dependency = method.owner();
                if (!artifacts.containsKey(dependency)) {
                    throw new IllegalArgumentException("Generated method link targets a class outside the compilation unit: " + method);
                }
                visit(dependency, artifacts, visiting, visited, order);
            }
        }
        visiting.remove(type);
        visited.add(type);
        order.add(type);
    }

    record LinkedMethod(ClassDesc owner, String name, MethodTypeDesc type)
    {
        LinkedMethod
        {
            requireNonNull(owner, "owner is null");
            if (requireNonNull(name, "name is null").isBlank()) {
                throw new IllegalArgumentException("name is blank");
            }
            requireNonNull(type, "type is null");
        }

        @Override
        public String toString()
        {
            return owner.displayName() + "." + name + type.descriptorString();
        }
    }

    static final class Artifact
    {
        private final ClassDesc type;
        private final byte[] classfile;
        private final Optional<Object> classData;
        private final List<Binding> bindings;
        private final Set<ClassDesc> classDataTypes;
        private final boolean lambdaFactoryRequired;
        private final CompilationReport.ClassInfo classInfo;

        Artifact(
                ClassDesc type,
                byte[] classfile,
                Optional<Object> classData,
                List<Binding> bindings,
                Set<ClassDesc> classDataTypes,
                boolean lambdaFactoryRequired,
                CompilationReport.ClassInfo classInfo)
        {
            this.type = requireNonNull(type, "type is null");
            this.classfile = requireNonNull(classfile, "classfile is null").clone();
            this.classData = requireNonNull(classData, "classData is null");
            this.bindings = List.copyOf(requireNonNull(bindings, "bindings is null"));
            this.classDataTypes = Set.copyOf(requireNonNull(classDataTypes, "classDataTypes is null"));
            this.lambdaFactoryRequired = lambdaFactoryRequired;
            this.classInfo = requireNonNull(classInfo, "classInfo is null");
            if (!type.equals(classInfo.type())) {
                throw new IllegalArgumentException("Class info type does not match artifact type");
            }
        }

        ClassDesc type()
        {
            return type;
        }

        public byte[] classfile()
        {
            return classfile.clone();
        }

        Optional<Object> classData()
        {
            return classData;
        }

        List<Binding> bindings()
        {
            return bindings;
        }

        Set<ClassDesc> classDataTypes()
        {
            return classDataTypes;
        }

        boolean lambdaFactoryRequired()
        {
            return lambdaFactoryRequired;
        }

        CompilationReport.ClassInfo classInfo()
        {
            return classInfo;
        }
    }

    sealed interface Binding
            permits MethodBinding, ValueBinding {}

    record ValueBinding(Object value)
            implements Binding
    {
        ValueBinding
        {
            requireNonNull(value, "value is null");
        }
    }

    record MethodBinding(LinkedMethod method)
            implements Binding
    {
        MethodBinding
        {
            requireNonNull(method, "method is null");
        }
    }
}
