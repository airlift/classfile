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
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

final class RuntimeDataRequirements
{
    private RuntimeDataRequirements() {}

    static void validate(
            CompilationTarget target,
            ClassDesc owner,
            Set<ClassDesc> generatedTypes,
            Set<ClassDesc> requiredTypes,
            RuntimeData runtimeData)
    {
        requireNonNull(target, "target is null");
        requireNonNull(owner, "owner is null");
        Set<ClassDesc> generatedTypeSet = Set.copyOf(requireNonNull(generatedTypes, "generatedTypes is null"));
        Set<ClassDesc> requiredTypeSet = Set.copyOf(requireNonNull(requiredTypes, "requiredTypes is null"));
        requireNonNull(runtimeData, "runtimeData is null");
        if (requiredTypeSet.isEmpty()) {
            return;
        }

        Object classData = runtimeData.classData()
                .orElseThrow(() -> new CompilationException("Class data is required by " + owner.displayName()));
        for (ClassDesc requiredType : requiredTypeSet) {
            ClassDesc componentType = componentType(requiredType);
            if (generatedTypeSet.contains(componentType)) {
                throw new CompilationException("Class data for %s cannot be an instance of generated type %s before it is defined"
                        .formatted(owner.displayName(), requiredType.displayName()));
            }
            target.requireInstance(classData, requiredType, owner, "runtime class data");
        }
    }

    static Optional<Object> mergeClassData(Optional<Object> configured, Optional<Object> compiled)
    {
        requireNonNull(configured, "configured is null");
        requireNonNull(compiled, "compiled is null");
        if (configured.isPresent() && compiled.isPresent() && !configured.equals(compiled)) {
            throw new IllegalArgumentException("Runtime data is incompatible");
        }
        return compiled.isPresent() ? compiled : configured;
    }

    static ClassDesc componentType(ClassDesc type)
    {
        ClassDesc componentType = requireNonNull(type, "type is null");
        while (componentType.isArray()) {
            componentType = componentType.componentType();
        }
        return componentType;
    }
}
