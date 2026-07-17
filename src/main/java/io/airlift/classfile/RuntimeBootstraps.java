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
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.runtime.ObjectMethods;

import static java.util.Objects.requireNonNull;

/// Bootstrap methods used by classfiles emitted by this library.
///
/// These methods are public so the JVM can link generated constants and record object methods.
/// They are compiler infrastructure, not general authoring entry points.
public final class RuntimeBootstraps
{
    private RuntimeBootstraps() {}

    public static Object bindingConstant(MethodHandles.Lookup lookup, String name, Class<?> type, int index)
            throws IllegalAccessException
    {
        requireNonNull(lookup, "lookup is null");
        requireNonNull(name, "name is null");
        requireNonNull(type, "type is null");

        return type.cast(runtimeData(lookup).binding(index));
    }

    public static CallSite bindingCallSite(MethodHandles.Lookup lookup, String name, MethodType callSiteType, int index)
            throws IllegalAccessException
    {
        requireNonNull(lookup, "lookup is null");
        requireNonNull(name, "name is null");
        requireNonNull(callSiteType, "callSiteType is null");

        Object binding = runtimeData(lookup).binding(index);
        if (!(binding instanceof MethodHandle target)) {
            throw new IllegalArgumentException("Binding is not a method handle: " + index);
        }
        if (!target.type().equals(callSiteType)) {
            throw new IllegalArgumentException("Binding method type %s does not match call site type %s"
                    .formatted(target.type(), callSiteType));
        }
        return new ConstantCallSite(target);
    }

    public static Object classDataConstant(MethodHandles.Lookup lookup, String name, Class<?> type)
            throws IllegalAccessException
    {
        requireNonNull(lookup, "lookup is null");
        requireNonNull(name, "name is null");
        requireNonNull(type, "type is null");
        Object classData = runtimeData(lookup).classData()
                .orElseThrow(() -> new IllegalAccessException("Generated class does not have class data"));
        return type.cast(classData);
    }

    public static CallSite recordObjectMethod(
            MethodHandles.Lookup lookup,
            String methodName,
            MethodType callSiteType,
            String componentNames,
            String recordDescriptor,
            String... componentDescriptors)
            throws Throwable
    {
        requireNonNull(lookup, "lookup is null");
        requireNonNull(methodName, "methodName is null");
        requireNonNull(callSiteType, "callSiteType is null");
        requireNonNull(componentNames, "componentNames is null");
        requireNonNull(recordDescriptor, "recordDescriptor is null");
        requireNonNull(componentDescriptors, "componentDescriptors is null");

        Class<?> recordClass = lookup.lookupClass();
        String[] names = componentNames.isEmpty() ? new String[0] : componentNames.split(";", -1);
        if (names.length != componentDescriptors.length) {
            throw new IllegalArgumentException("Record component names and descriptors do not match");
        }
        MethodHandle[] getters = new MethodHandle[names.length];
        for (int index = 0; index < names.length; index++) {
            Class<?> componentType = resolveType(lookup, recordDescriptor, componentDescriptors[index]);
            getters[index] = lookup.findGetter(recordClass, names[index], componentType);
        }

        MethodType exactType = callSiteType.changeParameterType(0, recordClass);
        CallSite exact = (CallSite) ObjectMethods.bootstrap(
                lookup,
                methodName,
                exactType,
                recordClass,
                componentNames,
                getters);
        return new ConstantCallSite(exact.getTarget().asType(callSiteType));
    }

    private static Class<?> resolveType(MethodHandles.Lookup lookup, String recordDescriptor, String descriptor)
            throws ReflectiveOperationException
    {
        ClassDesc type = ClassDesc.ofDescriptor(descriptor);
        ClassDesc recordType = ClassDesc.ofDescriptor(recordDescriptor);
        if (type.equals(recordType)) {
            return lookup.lookupClass();
        }
        if (type.isArray()) {
            return Array.newInstance(resolveType(lookup, recordDescriptor, type.componentType().descriptorString()), 0).getClass();
        }
        return type.resolveConstantDesc(lookup);
    }

    private static RuntimeData runtimeData(MethodHandles.Lookup lookup)
            throws IllegalAccessException
    {
        if (lookup.lookupClass().isHidden()) {
            return MethodHandles.classData(lookup, "_", RuntimeData.class);
        }
        if (lookup.lookupClass().getClassLoader() instanceof RuntimeDataProvider provider) {
            return provider.runtimeData(lookup.lookupClass());
        }
        throw new IllegalAccessException("Generated class loader does not expose runtime data");
    }
}
