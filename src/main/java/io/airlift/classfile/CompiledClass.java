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
import java.util.Set;

import static java.util.Objects.requireNonNull;

/// One compiled classfile together with the runtime data required to define it.
///
/// Classfile bytes are defensively copied. The artifact must be defined in an environment
/// compatible with the [CompilationTarget] used to produce it. Define the artifact through
/// [StandardClassDefiner] or [HiddenClassDefiner]; the raw bytes may depend on private runtime
/// bindings owned by the compiler.
public final class CompiledClass
{
    private final CompilationTarget target;
    private final ClassDesc type;
    private final byte[] classfile;
    private final RuntimeData runtimeData;
    private final Set<ClassDesc> classDataTypes;
    private final boolean lambdaFactoryRequired;

    CompiledClass(
            CompilationTarget target,
            ClassDesc type,
            byte[] classfile,
            RuntimeData runtimeData,
            Set<ClassDesc> classDataTypes,
            boolean lambdaFactoryRequired)
    {
        this.target = requireNonNull(target, "target is null");
        this.type = requireNonNull(type, "type is null");
        this.classfile = requireNonNull(classfile, "classfile is null").clone();
        this.runtimeData = requireNonNull(runtimeData, "runtimeData is null");
        this.classDataTypes = Set.copyOf(requireNonNull(classDataTypes, "classDataTypes is null"));
        this.lambdaFactoryRequired = lambdaFactoryRequired;
    }

    public CompilationTarget target()
    {
        return target;
    }

    public ClassDesc type()
    {
        return type;
    }

    /// Returns a defensive copy of the classfile bytes.
    public byte[] classfile()
    {
        return classfile.clone();
    }

    RuntimeData runtimeData()
    {
        return runtimeData;
    }

    /// Returns whether hidden definition must install the loader-owned lambda factory.
    boolean lambdaFactoryRequired()
    {
        return lambdaFactoryRequired;
    }

    /// Validates the effective runtime data that will be installed for this artifact.
    void validateRuntimeData(RuntimeData runtimeData)
    {
        RuntimeDataRequirements.validate(target, type, Set.of(type), classDataTypes, runtimeData);
    }
}
