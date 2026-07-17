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

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/// Caller-supplied class data used when defining compiled classes.
///
/// The compiler also associates private runtime bindings with compiled artifacts. Those bindings
/// are deliberately not part of this public API: callers configure class data, while the compiler
/// owns binding indexes and values.
public final class RuntimeData
{
    public static final RuntimeData EMPTY = new RuntimeData(Optional.empty(), List.of());

    private final Optional<Object> classData;
    private final List<Object> bindings;

    RuntimeData(Optional<Object> classData, List<Object> bindings)
    {
        this.classData = requireNonNull(classData, "classData is null");
        this.bindings = List.copyOf(requireNonNull(bindings, "bindings is null"));
        this.bindings.forEach(binding -> requireNonNull(binding, "binding is null"));
    }

    /// Creates runtime data containing only a class-data value.
    public static RuntimeData ofClassData(Object classData)
    {
        return new RuntimeData(Optional.of(requireNonNull(classData, "classData is null")), List.of());
    }

    /// Returns the caller-supplied class data, if present.
    public Optional<Object> classData()
    {
        return classData;
    }

    List<Object> bindings()
    {
        return bindings;
    }

    Object binding(int index)
    {
        return bindings.get(index);
    }

    boolean isEmpty()
    {
        return classData.isEmpty() && bindings.isEmpty();
    }
}
