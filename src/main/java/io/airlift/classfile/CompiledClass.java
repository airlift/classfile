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

import static java.util.Objects.requireNonNull;

/// One compiled classfile together with the runtime data required to define it.
///
/// Classfile bytes are defensively copied. The artifact must be defined in an environment
/// compatible with the [CompilationTarget] used to produce it. Define the artifact through
/// [StandardClassDefiner] or [HiddenClassDefiner]; the raw bytes may depend on private runtime
/// bindings owned by the compiler.
public final class CompiledClass
{
    private final ClassDesc type;
    private final byte[] classfile;
    private final RuntimeData runtimeData;

    public CompiledClass(ClassDesc type, byte[] classfile, RuntimeData runtimeData)
    {
        this.type = requireNonNull(type, "type is null");
        this.classfile = requireNonNull(classfile, "classfile is null").clone();
        this.runtimeData = requireNonNull(runtimeData, "runtimeData is null");
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
}
