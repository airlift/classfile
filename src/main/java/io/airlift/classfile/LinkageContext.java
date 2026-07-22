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

import java.lang.classfile.ClassHierarchyResolver;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.airlift.classfile.Identity.same;
import static java.lang.reflect.AccessFlag.INTERFACE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

final class LinkageContext
{
    private final CompilationTarget target;
    private final Map<ClassDesc, ClassModel> generatedDefinitions;
    private final ClassHierarchyResolver hierarchyResolver;

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

    void requireAccessible(ClassDesc type, ClassModel definition, String location)
    {
        if (type.isArray()) {
            requireAccessible(type.componentType(), definition, location);
            return;
        }
        ClassModel generatedDefinition = generatedDefinitions.get(type);
        if (generatedDefinition == null) {
            target.requireAccessible(type, definition.type(), location);
            return;
        }
        if (same(generatedDefinition, definition) ||
                generatedDefinition.access().contains(PUBLIC) ||
                generatedDefinition.type().packageName().equals(definition.type().packageName())) {
            return;
        }
        throw new CompilationException("Linkage validation failed for %s:%n  %s%n  generated type %s is package-private and declared in a different package"
                .formatted(definition.type().displayName(), location, type.displayName()));
    }

    void requireInterface(ClassDesc type, ClassModel definition, String location)
    {
        requireAccessible(type, definition, location);
        if (!isInterface(type)) {
            throw new CompilationException("Linkage validation failed for %s:%n  %s%n  type %s is not an interface"
                    .formatted(definition.type().displayName(), location, type.displayName()));
        }
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
