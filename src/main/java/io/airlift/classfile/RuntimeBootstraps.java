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
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
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

    public static CallSite hiddenClassLambdaMetafactory(
            MethodHandles.Lookup lookup,
            String methodName,
            MethodType callSiteType,
            String logicalOwnerDescriptor,
            int referenceKind,
            String implementationName,
            String implementationDescriptor,
            String samMethodDescriptor,
            String instantiatedMethodDescriptor)
            throws Throwable
    {
        requireNonNull(lookup, "lookup is null");
        requireNonNull(methodName, "methodName is null");
        requireNonNull(callSiteType, "callSiteType is null");
        requireNonNull(logicalOwnerDescriptor, "logicalOwnerDescriptor is null");
        requireNonNull(implementationName, "implementationName is null");
        requireNonNull(implementationDescriptor, "implementationDescriptor is null");
        requireNonNull(samMethodDescriptor, "samMethodDescriptor is null");
        requireNonNull(instantiatedMethodDescriptor, "instantiatedMethodDescriptor is null");

        Class<?> owner = lookup.lookupClass();
        MethodType implementationType = resolveMethodType(lookup, logicalOwnerDescriptor, implementationDescriptor);
        MethodHandle implementation = switch (referenceKind) {
            case MethodHandleInfo.REF_invokeStatic -> lookup.findStatic(owner, implementationName, implementationType);
            case MethodHandleInfo.REF_invokeVirtual, MethodHandleInfo.REF_invokeInterface -> lookup.findVirtual(owner, implementationName, implementationType);
            case MethodHandleInfo.REF_invokeSpecial -> lookup.findSpecial(owner, implementationName, implementationType, owner);
            case MethodHandleInfo.REF_newInvokeSpecial -> lookup.findConstructor(owner, implementationType);
            default -> throw new IllegalArgumentException("Reference kind is not a method invocation: " + referenceKind);
        };
        MethodType safeImplementationType = eraseType(owner, implementation.type());
        MethodType samMethodType = eraseType(owner, resolveMethodType(lookup, logicalOwnerDescriptor, samMethodDescriptor));
        MethodType instantiatedMethodType = eraseType(owner, resolveMethodType(lookup, logicalOwnerDescriptor, instantiatedMethodDescriptor));

        implementation = implementation.asType(safeImplementationType);

        MethodHandle factory = lambdaFactory(lookup).factory(
                methodName,
                callSiteType,
                samMethodType,
                implementation,
                instantiatedMethodType);
        if (callSiteType.parameterCount() == 0) {
            Object lambda = factory.invoke();
            return new ConstantCallSite(MethodHandles.constant(callSiteType.returnType(), lambda));
        }
        return new ConstantCallSite(factory.asType(callSiteType));
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

    private static MethodType resolveMethodType(MethodHandles.Lookup lookup, String logicalOwnerDescriptor, String descriptor)
            throws ReflectiveOperationException
    {
        MethodTypeDesc methodType = MethodTypeDesc.ofDescriptor(descriptor);
        Class<?> returnType = resolveType(lookup, logicalOwnerDescriptor, methodType.returnType().descriptorString());
        Class<?>[] parameterTypes = new Class<?>[methodType.parameterCount()];
        for (int index = 0; index < parameterTypes.length; index++) {
            parameterTypes[index] = resolveType(lookup, logicalOwnerDescriptor, methodType.parameterType(index).descriptorString());
        }
        return MethodType.methodType(returnType, parameterTypes);
    }

    private static MethodType eraseType(Class<?> logicalOwner, MethodType type)
    {
        Class<?> returnType = eraseType(logicalOwner, type.returnType());
        Class<?>[] parameterTypes = type.parameterArray();
        for (int index = 0; index < parameterTypes.length; index++) {
            parameterTypes[index] = eraseType(logicalOwner, parameterTypes[index]);
        }
        return MethodType.methodType(returnType, parameterTypes);
    }

    private static Class<?> eraseType(Class<?> logicalOwner, Class<?> type)
    {
        Class<?> componentType = type;
        while (componentType.isArray()) {
            componentType = componentType.componentType();
        }
        return componentType == logicalOwner ? Object.class : type;
    }

    private static RuntimeData runtimeData(MethodHandles.Lookup lookup)
            throws IllegalAccessException
    {
        if (lookup.lookupClass().isHidden()) {
            Object data = MethodHandles.classData(lookup, "_", Object.class);
            if (data instanceof HiddenClassRuntimeData hiddenData) {
                return hiddenData.runtimeData();
            }
            return (RuntimeData) data;
        }
        if (lookup.lookupClass().getClassLoader() instanceof RuntimeDataProvider provider) {
            return provider.runtimeData(lookup.lookupClass());
        }
        throw new IllegalAccessException("Generated class loader does not expose runtime data");
    }

    private static LambdaFactory lambdaFactory(MethodHandles.Lookup lookup)
            throws IllegalAccessException
    {
        if (!lookup.lookupClass().isHidden()) {
            throw new IllegalAccessException("Hidden lambda linkage requires a hidden caller");
        }
        Object data = MethodHandles.classData(lookup, "_", Object.class);
        if (data instanceof HiddenClassRuntimeData hiddenData) {
            return hiddenData.lambdaFactory();
        }
        throw new IllegalAccessException("Hidden lambda linkage does not have a host factory");
    }
}
