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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/// A bundle of explicitly authored classfiles that share one runtime-data namespace.
///
/// Define the bundle through the supplied runtime definer; the raw bytes may depend on private
/// runtime bindings owned by the compiler.
public final class CompiledClassBundle
{
    private final CompilationTarget target;
    private final Map<ClassDesc, byte[]> classfiles;
    private final RuntimeData runtimeData;
    private final Set<ClassDesc> classDataTypes;

    CompiledClassBundle(CompilationTarget target, Map<ClassDesc, byte[]> classfiles, RuntimeData runtimeData, Set<ClassDesc> classDataTypes)
    {
        this.target = requireNonNull(target, "target is null");
        requireNonNull(classfiles, "classfiles is null");
        LinkedHashMap<ClassDesc, byte[]> copies = new LinkedHashMap<>();
        classfiles.forEach((type, classfile) -> copies.put(
                requireNonNull(type, "type is null"),
                requireNonNull(classfile, "classfile is null").clone()));
        this.classfiles = Collections.unmodifiableMap(copies);
        this.runtimeData = requireNonNull(runtimeData, "runtimeData is null");
        this.classDataTypes = Set.copyOf(requireNonNull(classDataTypes, "classDataTypes is null"));
    }

    public CompilationTarget target()
    {
        return target;
    }

    public Set<ClassDesc> types()
    {
        return classfiles.keySet();
    }

    public byte[] classfile(ClassDesc type)
    {
        byte[] classfile = classfiles.get(requireNonNull(type, "type is null"));
        if (classfile == null) {
            throw new IllegalArgumentException("Class was not compiled: " + type.displayName());
        }
        return classfile.clone();
    }

    public Map<ClassDesc, byte[]> classfiles()
    {
        LinkedHashMap<ClassDesc, byte[]> copies = new LinkedHashMap<>();
        classfiles.forEach((type, classfile) -> copies.put(type, classfile.clone()));
        return Collections.unmodifiableMap(copies);
    }

    RuntimeData runtimeData()
    {
        return runtimeData;
    }

    /// Validates the effective runtime data that will be installed for this bundle.
    void validateRuntimeData(RuntimeData runtimeData)
    {
        RuntimeDataRequirements.validate(target, classfiles.keySet().iterator().next(), classfiles.keySet(), classDataTypes, runtimeData);
    }
}
