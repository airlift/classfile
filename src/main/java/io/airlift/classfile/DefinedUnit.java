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
import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/// Runtime classes defined from a [io.airlift.classfile.CompiledUnit].
///
/// A unit has one caller-selected primary class and may contain compiler-generated physical
/// classes. Hidden definitions additionally expose the private lookups needed to invoke members
/// without resolving generated names.
public final class DefinedUnit
{
    private final ClassDesc primaryType;
    private final Map<ClassDesc, Class<?>> classes;
    private final Map<ClassDesc, MethodHandles.Lookup> lookups;

    DefinedUnit(ClassDesc primaryType, Map<ClassDesc, Class<?>> classes, Map<ClassDesc, MethodHandles.Lookup> lookups)
    {
        this.primaryType = requireNonNull(primaryType, "primaryType is null");
        this.classes = Collections.unmodifiableMap(new LinkedHashMap<>(requireNonNull(classes, "classes is null")));
        this.lookups = Collections.unmodifiableMap(new LinkedHashMap<>(requireNonNull(lookups, "lookups is null")));
        if (!this.classes.containsKey(primaryType)) {
            throw new IllegalArgumentException("Primary class was not defined: " + primaryType.displayName());
        }
    }

    /// Returns the caller-selected primary class.
    public Class<?> primaryClass()
    {
        return classes.get(primaryType);
    }

    /// Returns the primary class after verifying that it extends or implements the requested
    /// supertype.
    public <T> Class<? extends T> primaryClass(Class<T> superType)
    {
        return primaryClass().asSubclass(requireNonNull(superType, "superType is null"));
    }

    /// Returns the primary class's defining lookup for hidden units, or empty for nominal units.
    public Optional<MethodHandles.Lookup> primaryLookup()
    {
        return Optional.ofNullable(lookups.get(primaryType));
    }

    public Set<ClassDesc> types()
    {
        return classes.keySet();
    }

    /// Returns the runtime class corresponding to a logical or compiler-generated symbolic type.
    public Class<?> definedClass(ClassDesc type)
    {
        Class<?> value = classes.get(requireNonNull(type, "type is null"));
        if (value == null) {
            throw new IllegalArgumentException("Class was not defined: " + type.displayName());
        }
        return value;
    }

    public <T> Class<? extends T> definedClass(ClassDesc type, Class<T> superType)
    {
        return definedClass(type).asSubclass(requireNonNull(superType, "superType is null"));
    }

    /// Returns the defining lookup for a hidden physical class, or empty for a nominal class.
    public Optional<MethodHandles.Lookup> lookup(ClassDesc type)
    {
        requireNonNull(type, "type is null");
        if (!classes.containsKey(type)) {
            throw new IllegalArgumentException("Class was not defined: " + type.displayName());
        }
        return Optional.ofNullable(lookups.get(type));
    }
}
