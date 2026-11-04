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

import java.io.Serializable;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static io.airlift.classfile.Identity.same;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.reflect.AccessFlag.INTERFACE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

final class LinkageContext
{
    private final CompilationTarget target;
    private final Map<ClassDesc, ClassModel> generatedDefinitions;
    private final ClassHierarchyResolver hierarchyResolver;
    // Class resolution results for assignability checks, including types that do not resolve.
    private final Map<ClassDesc, Optional<Class<?>>> resolvedClasses = new HashMap<>();

    LinkageContext(CompilationTarget target, List<ClassModel> definitions)
    {
        this.target = requireNonNull(target, "target is null");
        definitions = List.copyOf(requireNonNull(definitions, "definitions is null"));

        Set<ClassDesc> interfaces = new LinkedHashSet<>();
        Map<ClassDesc, ClassDesc> superClasses = new LinkedHashMap<>();
        Map<ClassDesc, ClassModel> generatedDefinitions = new LinkedHashMap<>();
        for (ClassModel definition : definitions) {
            if (generatedDefinitions.putIfAbsent(definition.type(), definition) != null) {
                throw new IllegalArgumentException("Class is defined more than once: " + definition.type().displayName());
            }
            if (definition.access().contains(INTERFACE)) {
                interfaces.add(definition.type());
            }
            else {
                superClasses.put(definition.type(), definition.superClass());
            }
        }
        validateInheritanceCycles(generatedDefinitions);
        this.generatedDefinitions = Map.copyOf(generatedDefinitions);
        hierarchyResolver = ClassHierarchyResolver.of(interfaces, superClasses)
                .orElse(target.hierarchyResolver())
                .cached();
    }

    private static void validateInheritanceCycles(Map<ClassDesc, ClassModel> definitions)
    {
        Set<ClassDesc> complete = new LinkedHashSet<>();
        LinkedHashSet<ClassDesc> active = new LinkedHashSet<>();
        for (ClassDesc type : definitions.keySet()) {
            validateInheritanceCycles(type, definitions, active, complete);
        }
    }

    private static void validateInheritanceCycles(
            ClassDesc type,
            Map<ClassDesc, ClassModel> definitions,
            Set<ClassDesc> active,
            Set<ClassDesc> complete)
    {
        if (complete.contains(type)) {
            return;
        }
        if (!active.add(type)) {
            List<ClassDesc> path = active.stream().dropWhile(value -> !value.equals(type)).toList();
            throw new CompilationException("Generated inheritance cycle: " +
                    Stream.concat(path.stream(), Stream.of(type))
                            .map(ClassDesc::displayName)
                            .collect(joining(" -> ")));
        }
        ClassModel definition = definitions.get(type);
        if (definition != null) {
            if (definitions.containsKey(definition.superClass())) {
                validateInheritanceCycles(definition.superClass(), definitions, active, complete);
            }
            for (ClassDesc interfaceType : definition.interfaces()) {
                if (definitions.containsKey(interfaceType)) {
                    validateInheritanceCycles(interfaceType, definitions, active, complete);
                }
            }
        }
        active.remove(type);
        complete.add(type);
    }

    ClassHierarchyResolver hierarchyResolver()
    {
        return hierarchyResolver;
    }

    boolean hiddenClass()
    {
        return target.hiddenClass();
    }

    void requireGeneratedType(ClassDesc type)
    {
        target.requireGeneratedType(type);
    }

    boolean isInterface(ClassDesc type)
    {
        return ClassHierarchyResolver.ClassHierarchyInfo.ofInterface()
                .equals(hierarchyResolver.getClassInfo(requireNonNull(type, "type is null")));
    }

    /// Returns whether the verifier accepts a value of the source type where the target type is
    /// declared (JVMS 4.10.1.2). An interface target accepts any non-array reference; an array is
    /// accepted only by `Cloneable` and `Serializable`. A type that does not resolve, or a hidden
    /// class's type when the hidden class shares its name with a loaded class, passes, deferring
    /// to the JVM.
    boolean isAssignable(ClassDesc target, ClassDesc source)
    {
        requireNonNull(target, "target is null");
        requireNonNull(source, "source is null");
        if (target.isPrimitive() || source.isPrimitive() || target.equals(source) || target.equals(CD_Object)) {
            return true;
        }
        if (target.isArray()) {
            if (!source.isArray()) {
                return false;
            }
            ClassDesc targetComponent = target.componentType();
            ClassDesc sourceComponent = source.componentType();
            if (targetComponent.isPrimitive() || sourceComponent.isPrimitive()) {
                return targetComponent.equals(sourceComponent);
            }
            return isAssignable(targetComponent, sourceComponent);
        }

        ClassModel generatedTarget = generatedDefinitions.get(target);
        Class<?> targetClass = null;
        if (generatedTarget != null) {
            if (sharesLoadedName(target)) {
                return true;
            }
            if (source.isArray()) {
                return false;
            }
            if (generatedTarget.access().contains(INTERFACE)) {
                return true;
            }
        }
        else {
            Optional<Class<?>> resolved = resolveClass(target);
            if (resolved.isEmpty()) {
                return true;
            }
            targetClass = resolved.orElseThrow();
            if (source.isArray()) {
                return targetClass == Cloneable.class || targetClass == Serializable.class;
            }
            if (targetClass.isInterface()) {
                return true;
            }
        }

        // Walk generated superclasses first. A generated interface has no superclass path beyond
        // Object, which the target is not, so the walk stops there.
        ClassDesc current = source;
        ClassModel generatedSource = generatedDefinitions.get(current);
        while (generatedSource != null) {
            if (sharesLoadedName(current)) {
                return true;
            }
            if (generatedSource.access().contains(INTERFACE)) {
                return false;
            }
            current = generatedSource.superClass();
            if (current.equals(target)) {
                return true;
            }
            generatedSource = generatedDefinitions.get(current);
        }
        Optional<Class<?>> sourceClass = resolveClass(current);
        if (sourceClass.isEmpty()) {
            return true;
        }
        // A loaded class cannot extend a type generated in this unit.
        return targetClass != null && targetClass.isAssignableFrom(sourceClass.orElseThrow());
    }

    // A hidden class can take the name of a loaded class. A descriptor naming the generated type
    // then also names the loaded class, so the verifier's view of the type is unknown.
    private boolean sharesLoadedName(ClassDesc generatedType)
    {
        return target.hiddenClass() && resolveClass(generatedType).isPresent();
    }

    private Optional<Class<?>> resolveClass(ClassDesc type)
    {
        Optional<Class<?>> resolved = resolvedClasses.get(type);
        if (resolved == null) {
            resolved = target.resolveClass(type);
            resolvedClasses.put(type, resolved);
        }
        return resolved;
    }

    boolean isPublicMethod(ClassDesc owner, String name, MethodTypeDesc methodType)
    {
        ClassModel generatedDefinition = generatedDefinitions.get(owner);
        if (generatedDefinition != null) {
            return generatedDefinition.methods().stream()
                    .anyMatch(method -> method.name().equals(name) && method.methodType().equals(methodType) && method.access().contains(PUBLIC));
        }
        return target.resolveClass(owner)
                .stream()
                .flatMap(type -> Arrays.stream(type.getMethods()))
                .anyMatch(method -> method.getName().equals(name) && DescriptorUtils.methodType(method).equals(methodType));
    }

    boolean isCallerSensitiveMethod(ClassDesc owner, String name, MethodTypeDesc methodType)
    {
        if (generatedDefinitions.containsKey(owner)) {
            return false;
        }
        return target.resolveClass(owner)
                .stream()
                .flatMap(type -> Arrays.stream(type.getMethods()))
                .filter(method -> method.getName().equals(name) && DescriptorUtils.methodType(method).equals(methodType))
                .flatMap(method -> Arrays.stream(method.getDeclaredAnnotations()))
                .anyMatch(annotation -> annotation.annotationType().getName().equals("jdk.internal.reflect.CallerSensitive"));
    }

    boolean isPublicField(ClassDesc owner, String name, ClassDesc fieldType)
    {
        ClassModel generatedDefinition = generatedDefinitions.get(owner);
        if (generatedDefinition != null) {
            return generatedDefinition.fields().stream()
                    .anyMatch(field -> field.name().equals(name) && field.type().equals(fieldType) && field.access().contains(PUBLIC));
        }
        return target.resolveClass(owner)
                .stream()
                .flatMap(type -> Arrays.stream(type.getFields()))
                .anyMatch(field -> field.getName().equals(name) && DescriptorUtils.classDesc(field.getType()).equals(fieldType));
    }

    boolean isPublicConstructor(ClassDesc owner, MethodTypeDesc constructorType)
    {
        ClassModel generatedDefinition = generatedDefinitions.get(owner);
        if (generatedDefinition != null) {
            return generatedDefinition.methods().stream()
                    .anyMatch(method -> method.name().equals("<init>") && method.methodType().equals(constructorType) && method.access().contains(PUBLIC));
        }
        return target.resolveClass(owner)
                .stream()
                .flatMap(type -> Arrays.stream(type.getConstructors()))
                .anyMatch(constructor -> DescriptorUtils.methodType(constructor).equals(constructorType) && Modifier.isPublic(constructor.getModifiers()));
    }

    /// Returns why the definition cannot access the type, or empty when it can.
    Optional<String> accessProblem(ClassDesc type, ClassModel definition)
    {
        if (type.isArray()) {
            return accessProblem(type.componentType(), definition);
        }
        ClassModel generatedDefinition = generatedDefinitions.get(type);
        if (generatedDefinition == null) {
            return target.accessProblem(type, definition.type());
        }
        if (same(generatedDefinition, definition) ||
                generatedDefinition.access().contains(PUBLIC) ||
                generatedDefinition.type().packageName().equals(definition.type().packageName())) {
            return Optional.empty();
        }
        return Optional.of("generated type %s is package-private and declared in a different package".formatted(type.displayName()));
    }

    private void requireAccessible(ClassDesc type, ClassModel definition, String location)
    {
        Optional<String> problem = accessProblem(type, definition);
        if (problem.isPresent()) {
            throw linkageFailure(definition, location, problem.orElseThrow());
        }
    }

    static CompilationException linkageFailure(ClassModel definition, String location, String problem)
    {
        return new CompilationException("Linkage validation failed for %s:%n  %s%n  %s".formatted(definition.type().displayName(), location, problem));
    }

    void requireInterface(ClassDesc type, ClassModel definition, String location)
    {
        requireAccessible(type, definition, location);
        if (!isInterface(type)) {
            throw new CompilationException("Linkage validation failed for %s:%n  %s%n  type %s is not an interface"
                    .formatted(definition.type().displayName(), location, type.displayName()));
        }
    }

    /// Returns whether the value is an instance of the type; a type generated in this unit never
    /// matches, and a non-null value of another unresolved type passes, deferring to the JVM.
    boolean isInstance(Object value, ClassDesc type)
    {
        return !generatedDefinitions.containsKey(RuntimeDataRequirements.componentType(type)) && target.isInstance(value, type);
    }

    void requireInstance(Object value, ClassDesc type, ClassModel definition, String location)
    {
        ClassDesc componentType = RuntimeDataRequirements.componentType(type);
        if (generatedDefinitions.containsKey(componentType)) {
            throw new CompilationException("Linkage validation failed for %s:%n  %s%n  value of type %s cannot be an instance of generated type %s before it is defined"
                    .formatted(definition.type().displayName(), location, value.getClass().getName(), type.displayName()));
        }
        target.requireInstance(value, type, definition.type(), location);
    }

    void requireRecordComponentAccessible(ClassDesc type, ClassModel definition, String location)
    {
        requireAccessible(type, definition, location);
        if (!target.hiddenClass()) {
            return;
        }
        ClassDesc componentType = type;
        while (componentType.isArray()) {
            componentType = componentType.componentType();
        }
        if (generatedDefinitions.containsKey(componentType)) {
            throw new CompilationException("Linkage validation failed for %s:%n  %s%n  generated type %s cannot appear in a hidden record component descriptor because hidden classes are not discoverable by symbolic name"
                    .formatted(definition.type().displayName(), location, componentType.displayName()));
        }
    }

    CompilationTarget.AdaptedMethodHandle adapt(MethodHandle handle, List<ClassDesc> argumentTypes, ClassModel definition, String location)
    {
        return target.adapt(handle, argumentTypes, definition.type(), location);
    }
}
