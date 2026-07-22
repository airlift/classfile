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
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/// Classes defined together in one nominal generated class loader.
///
/// The result provides lookup by the symbolic type used while authoring, avoiding dependence on
/// generated binary names at call sites.
public final class DefinedClasses
{
    private final Map<ClassDesc, Class<?>> classes;

    DefinedClasses(Map<ClassDesc, Class<?>> classes)
    {
        this.classes = Map.copyOf(requireNonNull(classes, "classes is null"));
    }

    public Set<ClassDesc> types()
    {
        return classes.keySet();
    }

    /// Returns the class defined for an authored class model.
    public Class<?> definedClass(ClassModel definition)
    {
        return definedClass(requireNonNull(definition, "definition is null").type());
    }

    /// Returns the class defined for a symbolic type.
    public Class<?> definedClass(ClassDesc type)
    {
        Class<?> definedClass = classes.get(requireNonNull(type, "type is null"));
        if (definedClass == null) {
            throw new IllegalArgumentException("Class was not defined: " + type.displayName());
        }
        return definedClass;
    }

    public <T> Class<? extends T> definedClass(ClassModel definition, Class<T> superType)
    {
        return definedClass(requireNonNull(definition, "definition is null").type(), superType);
    }

    /// Returns the class after verifying that it extends or implements the requested supertype.
    public <T> Class<? extends T> definedClass(ClassDesc type, Class<T> superType)
    {
        return definedClass(type).asSubclass(requireNonNull(superType, "superType is null"));
    }
}
