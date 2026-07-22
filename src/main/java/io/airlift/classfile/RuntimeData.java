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

import static io.airlift.classfile.Identity.same;
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

    static RuntimeData merge(RuntimeData configured, RuntimeData compiled)
    {
        requireNonNull(configured, "configured is null");
        requireNonNull(compiled, "compiled is null");
        if (isEmpty(configured) || compatible(configured, compiled)) {
            return compiled;
        }
        if (isEmpty(compiled)) {
            return configured;
        }
        if (compiled.bindings().isEmpty() && sameClassData(configured.classData(), compiled.classData())) {
            return configured;
        }
        if (configured.bindings().isEmpty() && sameClassData(configured.classData(), compiled.classData())) {
            return compiled;
        }
        if (configured.bindings().isEmpty() && compiled.classData().isEmpty()) {
            return new RuntimeData(configured.classData(), compiled.bindings());
        }
        throw new IllegalArgumentException("Runtime data is incompatible");
    }

    static boolean compatible(RuntimeData first, RuntimeData second)
    {
        requireNonNull(first, "first is null");
        requireNonNull(second, "second is null");
        if (!sameClassData(first.classData(), second.classData()) || first.bindings().size() != second.bindings().size()) {
            return false;
        }
        for (int index = 0; index < first.bindings().size(); index++) {
            if (!same(first.bindings().get(index), second.bindings().get(index))) {
                return false;
            }
        }
        return true;
    }

    static boolean sameClassData(Optional<Object> first, Optional<Object> second)
    {
        requireNonNull(first, "first is null");
        requireNonNull(second, "second is null");
        return first.isPresent() == second.isPresent() && (first.isEmpty() || same(first.orElseThrow(), second.orElseThrow()));
    }

    boolean isEmpty()
    {
        return classData.isEmpty() && bindings.isEmpty();
    }

    private static boolean isEmpty(RuntimeData runtimeData)
    {
        return runtimeData.isEmpty();
    }
}
