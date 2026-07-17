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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;

import static io.airlift.classfile.Identity.same;
import static java.util.Objects.requireNonNull;

/// Describes the runtime environment in which generated classes will be linked.
///
/// The target controls type visibility checks, stack-map hierarchy resolution, bound-handle
/// adaptation, and whether generated symbolic types may be referenced by name. A compiled artifact
/// must be defined in the environment represented by its target.
public final class CompilationTarget
{
    private final TypeResolver typeResolver;
    private final ClassHierarchyResolver hierarchyResolver;
    private final String description;
    private final boolean hiddenClass;

    private CompilationTarget(TypeResolver typeResolver, ClassHierarchyResolver hierarchyResolver, String description, boolean hiddenClass)
    {
        this.typeResolver = requireNonNull(typeResolver, "typeResolver is null");
        this.hierarchyResolver = requireNonNull(hierarchyResolver, "hierarchyResolver is null");
        this.description = requireNonNull(description, "description is null");
        this.hiddenClass = hiddenClass;
    }

    /// Creates a target for nominal classes defined by the supplied loader or a child generated
    /// loader with equivalent visibility.
    public static CompilationTarget forClassLoader(ClassLoader classLoader)
    {
        requireNonNull(classLoader, "classLoader is null");
        return new CompilationTarget(
                new ClassLoaderResolver(classLoader),
                ClassHierarchyResolver.ofClassLoading(classLoader),
                "class loader " + identity(classLoader),
                false);
    }

    /// Creates a target for hidden classes defined through the supplied host lookup.
    public static CompilationTarget forLookup(MethodHandles.Lookup lookup)
    {
        requireNonNull(lookup, "lookup is null");
        return new CompilationTarget(
                new LookupResolver(lookup),
                ClassHierarchyResolver.ofClassLoading(lookup),
                "lookup " + lookup.lookupClass().getName(),
                true);
    }

    ClassHierarchyResolver hierarchyResolver()
    {
        return hierarchyResolver;
    }

    boolean hiddenClass()
    {
        return hiddenClass;
    }

    Optional<Class<?>> resolveClass(ClassDesc type)
    {
        return typeResolver.resolveClass(requireNonNull(type, "type is null"));
    }

    void requireInstance(Object value, ClassDesc type, ClassDesc generatedType, String location)
    {
        requireNonNull(value, "value is null");
        requireNonNull(type, "type is null");
        requireNonNull(generatedType, "generatedType is null");
        requireNonNull(location, "location is null");
        resolveClass(type).ifPresent(resolved -> {
            if (!resolved.isInstance(value)) {
                throw new CompilationException("Linkage validation failed for %s:%n  %s%n  value of type %s does not resolve to an instance of %s in %s"
                        .formatted(generatedType.displayName(), location, value.getClass().getName(), resolved.getName(), description));
            }
        });
    }

    void requireAccessible(ClassDesc type, ClassDesc generatedType, String location)
    {
        requireNonNull(type, "type is null");
        if (type.isPrimitive()) {
            return;
        }
        if (type.isArray()) {
            requireAccessible(type.componentType(), generatedType, location);
            return;
        }

        Resolution resolution = typeResolver.resolve(type, generatedType);
        if (!resolution.accessible()) {
            throw linkageFailure(generatedType, location, type, resolution.problem());
        }
    }

    AdaptedMethodHandle adapt(MethodHandle handle, List<ClassDesc> argumentTypes, ClassDesc generatedType, String location)
    {
        requireNonNull(handle, "handle is null");
        argumentTypes = List.copyOf(requireNonNull(argumentTypes, "argumentTypes is null"));
        MethodType originalType = handle.type();
        if (argumentTypes.size() != originalType.parameterCount()) {
            throw new IllegalArgumentException("argumentTypes does not match method handle: " + originalType);
        }
        Class<?> returnType = accessibleHandleType(originalType.returnType(), generatedType);
        Class<?>[] parameterTypes = new Class<?>[originalType.parameterCount()];
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> originalParameter = originalType.parameterType(index);
            ClassDesc argumentType = argumentTypes.get(index);
            parameterTypes[index] = originalParameter.isPrimitive() ||
                    (DescriptorUtils.classDesc(originalParameter).equals(argumentType) && typeResolver.isAccessible(originalParameter, generatedType))
                    ? originalParameter
                    : Object.class;
        }
        MethodType adaptedType = MethodType.methodType(returnType, parameterTypes);
        try {
            return new AdaptedMethodHandle(handle.asType(adaptedType), adaptedType);
        }
        catch (RuntimeException e) {
            throw new CompilationException("Unable to adapt bound method handle at %s in %s from %s to %s for %s"
                    .formatted(location, generatedType.displayName(), originalType, adaptedType, description), e);
        }
    }

    private Class<?> accessibleHandleType(Class<?> type, ClassDesc generatedType)
    {
        if (type.isPrimitive()) {
            return type;
        }
        return typeResolver.isAccessible(type, generatedType) ? type : Object.class;
    }

    private CompilationException linkageFailure(ClassDesc generatedType, String location, ClassDesc type, String problem)
    {
        return new CompilationException("Linkage validation failed for %s:%n  %s%n  type %s is not accessible from %s: %s"
                .formatted(generatedType.displayName(), location, type.displayName(), description, problem));
    }

    private static String identity(Object value)
    {
        if (value == null) {
            return "bootstrap class loader";
        }
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    record AdaptedMethodHandle(MethodHandle handle, MethodType type)
    {
        AdaptedMethodHandle
        {
            requireNonNull(handle, "handle is null");
            requireNonNull(type, "type is null");
        }
    }

    private interface TypeResolver
    {
        Resolution resolve(ClassDesc type, ClassDesc generatedType);

        Optional<Class<?>> resolveClass(ClassDesc type);

        boolean isAccessible(Class<?> type, ClassDesc generatedType);
    }

    private record Resolution(boolean accessible, String problem)
    {
        private static Resolution accessibleResolution()
        {
            return new Resolution(true, "");
        }

        private static Resolution inaccessible(String problem)
        {
            return new Resolution(false, requireNonNull(problem, "problem is null"));
        }
    }

    private record ClassLoaderResolver(ClassLoader classLoader)
            implements TypeResolver
    {
        @Override
        public Resolution resolve(ClassDesc type, ClassDesc generatedType)
        {
            Class<?> resolved;
            try {
                resolved = Class.forName(binaryName(type), false, classLoader);
            }
            catch (ClassNotFoundException | LinkageError e) {
                return Resolution.inaccessible("the target cannot resolve the class (" + e + ")");
            }
            return isAccessible(resolved, generatedType)
                    ? Resolution.accessibleResolution()
                    : Resolution.inaccessible("resolved to " + identity(resolved.getClassLoader()) + " but the class is not accessible from the generated runtime package");
        }

        @Override
        public Optional<Class<?>> resolveClass(ClassDesc type)
        {
            try {
                return Optional.of(Class.forName(binaryName(type), false, classLoader));
            }
            catch (ClassNotFoundException | LinkageError e) {
                return Optional.empty();
            }
        }

        @Override
        public boolean isAccessible(Class<?> type, ClassDesc generatedType)
        {
            if (type.isArray()) {
                return isAccessible(type.componentType(), generatedType);
            }
            Class<?> resolved;
            try {
                resolved = Class.forName(type.getName(), false, classLoader);
            }
            catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
            if (resolved != type) {
                return false;
            }
            if (sameRuntimePackage(type, generatedType, classLoader)) {
                return true;
            }
            if (!Modifier.isPublic(type.getModifiers())) {
                return false;
            }
            Module targetModule = classLoader.getUnnamedModule();
            Module sourceModule = type.getModule();
            return !sourceModule.isNamed() || sourceModule.isExported(type.getPackageName(), targetModule);
        }
    }

    private record LookupResolver(MethodHandles.Lookup lookup)
            implements TypeResolver
    {
        @Override
        public Resolution resolve(ClassDesc type, ClassDesc generatedType)
        {
            try {
                Class<?> resolved = type.resolveConstantDesc(lookup);
                lookup.accessClass(resolved);
                return Resolution.accessibleResolution();
            }
            catch (ReflectiveOperationException | IllegalArgumentException | LinkageError e) {
                return Resolution.inaccessible("the target lookup cannot access the class (" + e + ")");
            }
        }

        @Override
        public Optional<Class<?>> resolveClass(ClassDesc type)
        {
            try {
                return Optional.of(type.resolveConstantDesc(lookup));
            }
            catch (ReflectiveOperationException | IllegalArgumentException | LinkageError e) {
                return Optional.empty();
            }
        }

        @Override
        public boolean isAccessible(Class<?> type, ClassDesc generatedType)
        {
            if (type.isArray()) {
                return isAccessible(type.componentType(), generatedType);
            }
            try {
                Class<?> resolved = DescriptorUtils.classDesc(type).resolveConstantDesc(lookup);
                if (resolved != type) {
                    return false;
                }
                lookup.accessClass(type);
                return true;
            }
            catch (ReflectiveOperationException | IllegalArgumentException | LinkageError e) {
                return false;
            }
        }
    }

    private static boolean sameRuntimePackage(Class<?> type, ClassDesc generatedType, ClassLoader targetLoader)
    {
        return same(type.getClassLoader(), targetLoader) && type.getPackageName().equals(generatedType.packageName());
    }

    private static String binaryName(ClassDesc type)
    {
        if (type.isArray()) {
            return type.descriptorString().replace('/', '.');
        }
        return type.packageName().isEmpty() ? type.displayName() : type.packageName() + "." + type.displayName();
    }
}
