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
import java.lang.classfile.Signature;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.Location.FIELD;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PROTECTED;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.lang.reflect.AccessFlag.VOLATILE;
import static java.util.Objects.requireNonNull;

/// Immutable field declaration and symbolic field reference.
public final class FieldDefinition
{
    private final ClassDesc declaringType;
    private final Set<AccessFlag> access;
    private final String name;
    private final ClassDesc type;
    private final Optional<Signature> signature;
    private final Optional<Object> constantValue;
    private final List<Annotation> visibleAnnotations;
    private final List<Annotation> invisibleAnnotations;

    /// Mutable field authoring context. The field is registered with its class only by
    /// [Builder#build()].
    public static final class Builder
    {
        private final Consumer<FieldDefinition> registrar;
        private final ClassDesc declaringType;
        private final String name;
        private final ClassDesc type;
        private Set<AccessFlag> access = Set.of();
        private boolean accessSet;
        private Optional<Signature> signature = Optional.empty();
        private Optional<Object> constantValue = Optional.empty();
        private final List<Annotation> visibleAnnotations = new ArrayList<>();
        private final List<Annotation> invisibleAnnotations = new ArrayList<>();
        private boolean built;

        Builder(Consumer<FieldDefinition> registrar, ClassDesc declaringType, String name, ClassDesc type)
        {
            this.registrar = requireNonNull(registrar, "registrar is null");
            this.declaringType = requireNonNull(declaringType, "declaringType is null");
            this.name = requireNonNull(name, "name is null");
            this.type = requireNonNull(type, "type is null");
        }

        public Builder access(AccessFlag... access)
        {
            checkNotBuilt();
            if (accessSet) {
                throw new IllegalStateException("access is already set");
            }
            Set<AccessFlag> flags = Set.of(requireNonNull(access, "access is null"));
            flags.stream()
                    .filter(flag -> !flag.locations().contains(FIELD))
                    .findFirst()
                    .ifPresent(flag -> {
                        throw new IllegalArgumentException("Access flag is not valid for a field: " + flag);
                    });
            this.access = flags;
            accessSet = true;
            return this;
        }

        public Builder signature(Signature signature)
        {
            checkNotBuilt();
            if (this.signature.isPresent()) {
                throw new IllegalStateException("signature is already set");
            }
            this.signature = Optional.of(requireNonNull(signature, "signature is null"));
            return this;
        }

        public Builder constantValue(Object constantValue)
        {
            checkNotBuilt();
            if (this.constantValue.isPresent()) {
                throw new IllegalStateException("constant value is already set");
            }
            this.constantValue = Optional.of(requireNonNull(constantValue, "constantValue is null"));
            return this;
        }

        public Builder addAnnotation(Annotation annotation)
        {
            checkNotBuilt();
            visibleAnnotations.add(requireNonNull(annotation, "annotation is null"));
            return this;
        }

        public Builder addInvisibleAnnotation(Annotation annotation)
        {
            checkNotBuilt();
            invisibleAnnotations.add(requireNonNull(annotation, "annotation is null"));
            return this;
        }

        /// Creates the immutable field declaration and registers it with the owning class.
        /// A builder may be built once.
        public FieldDefinition build()
        {
            if (built) {
                throw new IllegalStateException("field is already built");
            }
            FieldDefinition field = new FieldDefinition(
                    declaringType,
                    access,
                    name,
                    type,
                    signature,
                    constantValue,
                    visibleAnnotations,
                    invisibleAnnotations);
            registrar.accept(field);
            built = true;
            return field;
        }

        private void checkNotBuilt()
        {
            if (built) {
                throw new IllegalStateException("field is already built");
            }
        }
    }

    private FieldDefinition(
            ClassDesc declaringType,
            Set<AccessFlag> access,
            String name,
            ClassDesc type,
            Optional<Signature> signature,
            Optional<Object> constantValue,
            List<Annotation> visibleAnnotations,
            List<Annotation> invisibleAnnotations)
    {
        this.declaringType = requireNonNull(declaringType, "declaringType is null");
        this.access = Set.copyOf(requireNonNull(access, "access is null"));
        this.access.stream()
                .filter(flag -> !flag.locations().contains(FIELD))
                .findFirst()
                .ifPresent(flag -> {
                    throw new IllegalArgumentException("Access flag is not valid for a field: " + flag);
                });
        if (this.access.stream().filter(flag -> flag == PUBLIC || flag == PROTECTED || flag == PRIVATE).count() > 1) {
            throw new IllegalArgumentException("Field has conflicting visibility: " + name);
        }
        if (this.access.contains(FINAL) && this.access.contains(VOLATILE)) {
            throw new IllegalArgumentException("Field cannot be both final and volatile: " + name);
        }
        this.name = DescriptorUtils.requireFieldName(name);
        DescriptorUtils.requireFieldType(type, "type");
        this.type = type;
        this.signature = requireNonNull(signature, "signature is null");
        this.constantValue = requireNonNull(constantValue, "constantValue is null");
        constantValue.ifPresent(value -> {
            if (!access.contains(STATIC) || !access.contains(FINAL)) {
                throw new IllegalArgumentException("Constant value requires a static final field: " + name);
            }
            validateConstantValue(type, value);
        });
        this.visibleAnnotations = List.copyOf(requireNonNull(visibleAnnotations, "visibleAnnotations is null"));
        this.invisibleAnnotations = List.copyOf(requireNonNull(invisibleAnnotations, "invisibleAnnotations is null"));
    }

    static FieldDefinition recordComponentField(ClassDesc declaringType, String name, ClassDesc type)
    {
        return new FieldDefinition(
                declaringType,
                Set.of(PRIVATE, FINAL),
                name,
                type,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    public ClassDesc declaringType()
    {
        return declaringType;
    }

    public Set<AccessFlag> access()
    {
        return access;
    }

    public String name()
    {
        return name;
    }

    public ClassDesc type()
    {
        return type;
    }

    public Optional<Signature> signature()
    {
        return signature;
    }

    public Optional<Object> constantValue()
    {
        return constantValue;
    }

    public List<Annotation> visibleAnnotations()
    {
        return visibleAnnotations;
    }

    public List<Annotation> invisibleAnnotations()
    {
        return invisibleAnnotations;
    }

    public boolean isStatic()
    {
        return access.contains(STATIC);
    }

    @Override
    public String toString()
    {
        return ModelRendering.access(access) + type.displayName() + " " + name +
                constantValue.map(value -> " = " + value).orElse("");
    }

    private static void validateConstantValue(ClassDesc type, Object value)
    {
        boolean valid = switch (value) {
            case Boolean _ -> type.equals(ConstantDescs.CD_boolean);
            case Byte _ -> type.equals(ConstantDescs.CD_byte);
            case Character _ -> type.equals(ConstantDescs.CD_char);
            case Short _ -> type.equals(ConstantDescs.CD_short);
            case Integer _ -> type.equals(ConstantDescs.CD_int);
            case Long _ -> type.equals(ConstantDescs.CD_long);
            case Float _ -> type.equals(ConstantDescs.CD_float);
            case Double _ -> type.equals(ConstantDescs.CD_double);
            case String _ -> type.equals(ConstantDescs.CD_String);
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Constant value type does not match field " + type.displayName() + ": " + value.getClass().getName());
        }
    }
}
