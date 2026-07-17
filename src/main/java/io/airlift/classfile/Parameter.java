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

import java.lang.classfile.Annotation;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static io.airlift.classfile.DescriptorUtils.classDesc;
import static io.airlift.classfile.Identity.different;
import static java.lang.reflect.AccessFlag.Location.METHOD_PARAMETER;
import static java.util.Objects.requireNonNull;

/// A symbolic method parameter that is also a directly usable [BytecodeExpression].
///
/// Parameters have identity: a parameter belongs to the method declaration in which it is used,
/// even when another parameter has the same name and type. JVM local-variable slots are assigned
/// only during emission.
public final class Parameter
        implements LocalValue
{
    private final String name;
    private final ClassDesc type;
    private final List<Annotation> visibleAnnotations = new ArrayList<>();
    private final List<Annotation> invisibleAnnotations = new ArrayList<>();
    private Set<AccessFlag> access = Set.of();
    private boolean accessSet;
    private Object owner;

    private Parameter(String name, ClassDesc type)
    {
        if (requireNonNull(name, "name is null").isBlank()) {
            throw new IllegalArgumentException("name is blank");
        }
        this.name = name;
        this.type = requireNonNull(type, "type is null");
    }

    public static Parameter arg(String name, Class<?> type)
    {
        return arg(name, classDesc(type));
    }

    public static Parameter arg(String name, ClassDesc type)
    {
        return new Parameter(name, type);
    }

    public Parameter access(AccessFlag... access)
    {
        if (accessSet) {
            throw new IllegalStateException("access is already set");
        }
        Set<AccessFlag> flags = Set.of(requireNonNull(access, "access is null"));
        flags.stream()
                .filter(flag -> !flag.locations().contains(METHOD_PARAMETER))
                .findFirst()
                .ifPresent(flag -> {
                    throw new IllegalArgumentException("Access flag is not valid for a method parameter: " + flag);
                });
        this.access = flags;
        accessSet = true;
        return this;
    }

    public Set<AccessFlag> access()
    {
        return Set.copyOf(access);
    }

    public Parameter addAnnotation(Annotation annotation)
    {
        visibleAnnotations.add(requireNonNull(annotation, "annotation is null"));
        return this;
    }

    public Parameter addInvisibleAnnotation(Annotation annotation)
    {
        invisibleAnnotations.add(requireNonNull(annotation, "annotation is null"));
        return this;
    }

    public List<Annotation> visibleAnnotations()
    {
        return List.copyOf(visibleAnnotations);
    }

    public List<Annotation> invisibleAnnotations()
    {
        return List.copyOf(invisibleAnnotations);
    }

    @Override
    public String name()
    {
        return name;
    }

    @Override
    public ClassDesc type()
    {
        return type;
    }

    void bind(Object method)
    {
        requireBindable(method);
        owner = method;
    }

    void requireBindable(Object method)
    {
        requireNonNull(method, "method is null");
        if (owner != null && different(owner, method)) {
            throw new IllegalArgumentException("Parameter is already attached to another method: " + name);
        }
    }

    Metadata metadata()
    {
        return new Metadata(this, access, visibleAnnotations, invisibleAnnotations);
    }

    @Override
    public String toString()
    {
        return name;
    }

    public record Metadata(
            Parameter parameter,
            Set<AccessFlag> access,
            List<Annotation> visibleAnnotations,
            List<Annotation> invisibleAnnotations)
    {
        public Metadata
        {
            requireNonNull(parameter, "parameter is null");
            access = Set.copyOf(requireNonNull(access, "access is null"));
            visibleAnnotations = List.copyOf(requireNonNull(visibleAnnotations, "visibleAnnotations is null"));
            invisibleAnnotations = List.copyOf(requireNonNull(invisibleAnnotations, "invisibleAnnotations is null"));
        }

        Metadata addAccess(AccessFlag flag)
        {
            EnumSet<AccessFlag> flags = EnumSet.noneOf(AccessFlag.class);
            flags.addAll(access);
            flags.add(requireNonNull(flag, "flag is null"));
            return new Metadata(parameter, flags, visibleAnnotations, invisibleAnnotations);
        }
    }
}
