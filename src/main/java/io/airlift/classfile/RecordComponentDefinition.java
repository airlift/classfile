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
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/// A record component and the generated members through which it is accessed.
public final class RecordComponentDefinition
{
    private final ClassDesc declaringType;
    private final String name;
    private final ClassDesc type;
    private final FieldDefinition field;
    private final Accessor accessor;
    private final Signature signature;
    private final List<Annotation> visibleAnnotations;
    private final List<Annotation> invisibleAnnotations;

    /// Mutable record-component authoring context. The component and its generated field and
    /// accessor are registered with the record only by [Builder#build()].
    public static final class Builder
    {
        private final ClassDefinition owner;
        private final String name;
        private final ClassDesc type;
        private Signature signature;
        private final List<Annotation> visibleAnnotations = new ArrayList<>();
        private final List<Annotation> invisibleAnnotations = new ArrayList<>();
        private boolean built;

        Builder(ClassDefinition owner, String name, ClassDesc type)
        {
            this.owner = requireNonNull(owner, "owner is null");
            this.name = requireNonNull(name, "name is null");
            this.type = requireNonNull(type, "type is null");
        }

        public Builder signature(Signature signature)
        {
            checkNotBuilt();
            if (this.signature != null) {
                throw new IllegalStateException("signature is already set");
            }
            this.signature = requireNonNull(signature, "signature is null");
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

        /// Creates the component definition and registers it with the owning record. A builder may
        /// be built once.
        public RecordComponentDefinition build()
        {
            checkNotBuilt();
            RecordComponentDefinition component = new RecordComponentDefinition(
                    owner.type(),
                    name,
                    type,
                    signature,
                    visibleAnnotations,
                    invisibleAnnotations);
            owner.addRecordComponent(component);
            built = true;
            return component;
        }

        private void checkNotBuilt()
        {
            if (built) {
                throw new IllegalStateException("record component is already built");
            }
        }
    }

    private RecordComponentDefinition(
            ClassDesc declaringType,
            String name,
            ClassDesc type,
            Signature signature,
            List<Annotation> visibleAnnotations,
            List<Annotation> invisibleAnnotations)
    {
        this.declaringType = requireNonNull(declaringType, "declaringType is null");
        this.name = requireNonNull(name, "name is null");
        DescriptorUtils.requireFieldType(type, "type");
        this.type = type;
        this.signature = signature;
        this.visibleAnnotations = List.copyOf(requireNonNull(visibleAnnotations, "visibleAnnotations is null"));
        this.invisibleAnnotations = List.copyOf(requireNonNull(invisibleAnnotations, "invisibleAnnotations is null"));
        field = FieldDefinition.recordComponentField(declaringType, name, type);
        accessor = new Accessor(declaringType, name, type);
    }

    public ClassDesc declaringType()
    {
        return declaringType;
    }

    public String name()
    {
        return name;
    }

    public ClassDesc type()
    {
        return type;
    }

    public FieldDefinition field()
    {
        return field;
    }

    public Accessor accessor()
    {
        return accessor;
    }

    public Optional<Signature> signature()
    {
        return Optional.ofNullable(signature);
    }

    public List<Annotation> visibleAnnotations()
    {
        return visibleAnnotations;
    }

    public List<Annotation> invisibleAnnotations()
    {
        return invisibleAnnotations;
    }

    /// Invokes the public component accessor.
    public BytecodeExpression get(BytecodeExpression receiver)
    {
        return accessor.invoke(receiver);
    }

    /// Reads the private backing field directly from code in the declaring record.
    public BytecodeExpression read(BytecodeExpression receiver)
    {
        return requireNonNull(receiver, "receiver is null").getField(field);
    }

    /// Initializes the private final backing field. Model validation restricts this to the canonical constructor.
    public BytecodeExpression initialize(BytecodeExpression receiver, BytecodeExpression value)
    {
        return requireNonNull(receiver, "receiver is null").setField(field, requireNonNull(value, "value is null"));
    }

    @Override
    public String toString()
    {
        return type.displayName() + " " + name;
    }

    public record Accessor(ClassDesc owner, String name, ClassDesc returnType)
    {
        public Accessor
        {
            requireNonNull(owner, "owner is null");
            requireNonNull(name, "name is null");
            requireNonNull(returnType, "returnType is null");
        }

        public MethodTypeDesc methodType()
        {
            return MethodTypeDesc.of(returnType);
        }

        public BytecodeExpression invoke(BytecodeExpression receiver)
        {
            return requireNonNull(receiver, "receiver is null").invokeVirtual(owner, name, methodType());
        }

        @Override
        public String toString()
        {
            return owner.displayName() + "." + name + methodType().descriptorString();
        }
    }
}
