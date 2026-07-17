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
import java.lang.classfile.ClassSignature;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.INTERFACE;
import static java.lang.reflect.AccessFlag.SUPER;
import static java.util.Objects.requireNonNull;

/// Immutable, validated class model produced by [ClassDefinition#build()].
public record ClassModel(
        Set<AccessFlag> access,
        ClassDesc type,
        ClassDesc superClass,
        List<ClassDesc> interfaces,
        List<FieldDefinition> fields,
        List<MethodDefinition.Model> methods,
        ClassKind kind,
        List<RecordComponentDefinition> recordComponents,
        Optional<ClassSignature> signature,
        Optional<String> sourceFile,
        List<Annotation> visibleAnnotations,
        List<Annotation> invisibleAnnotations)
{
    ClassModel(
            Set<AccessFlag> access,
            ClassDesc type,
            ClassDesc superClass,
            List<ClassDesc> interfaces,
            List<FieldDefinition> fields,
            List<MethodDefinition.Model> methods,
            ClassKind kind,
            List<RecordComponentDefinition> recordComponents,
            ClassSignature signature,
            String sourceFile,
            List<Annotation> visibleAnnotations,
            List<Annotation> invisibleAnnotations)
    {
        this(access,
                type,
                superClass,
                interfaces,
                fields,
                methods,
                kind,
                recordComponents,
                Optional.ofNullable(signature),
                Optional.ofNullable(sourceFile),
                visibleAnnotations,
                invisibleAnnotations);
    }

    public ClassModel(
            Set<AccessFlag> access,
            ClassDesc type,
            ClassDesc superClass,
            List<ClassDesc> interfaces,
            List<FieldDefinition> fields,
            List<MethodDefinition.Model> methods,
            ClassKind kind,
            List<RecordComponentDefinition> recordComponents,
            Optional<ClassSignature> signature,
            Optional<String> sourceFile,
            List<Annotation> visibleAnnotations,
            List<Annotation> invisibleAnnotations)
    {
        this.access = Set.copyOf(requireNonNull(access, "access is null"));
        this.type = requireNonNull(type, "type is null");
        this.superClass = requireNonNull(superClass, "superClass is null");
        this.interfaces = List.copyOf(requireNonNull(interfaces, "interfaces is null"));
        this.fields = List.copyOf(requireNonNull(fields, "fields is null"));
        this.methods = List.copyOf(requireNonNull(methods, "methods is null"));
        this.kind = requireNonNull(kind, "kind is null");
        this.recordComponents = List.copyOf(requireNonNull(recordComponents, "recordComponents is null"));
        this.signature = requireNonNull(signature, "signature is null");
        this.sourceFile = requireNonNull(sourceFile, "sourceFile is null");
        this.visibleAnnotations = List.copyOf(requireNonNull(visibleAnnotations, "visibleAnnotations is null"));
        this.invisibleAnnotations = List.copyOf(requireNonNull(invisibleAnnotations, "invisibleAnnotations is null"));
        ModelValidator.validate(this);
    }

    @Override
    public String toString()
    {
        boolean isInterface = kind == ClassKind.INTERFACE;
        boolean isRecord = kind == ClassKind.RECORD;
        StringBuilder builder = new StringBuilder()
                .append(switch (kind) {
                    case CLASS -> ModelRendering.access(access, INTERFACE, SUPER);
                    case INTERFACE -> ModelRendering.access(access, INTERFACE, SUPER, AccessFlag.ABSTRACT);
                    case RECORD -> ModelRendering.access(access, INTERFACE, SUPER, FINAL);
                })
                .append(switch (kind) {
                    case CLASS -> "class ";
                    case INTERFACE -> "interface ";
                    case RECORD -> "record ";
                })
                .append(type.displayName());
        if (isRecord) {
            builder.append(recordComponents.stream().map(Object::toString).collect(Collectors.joining(", ", "(", ")")));
        }
        else if (!isInterface && !superClass.equals(ConstantDescs.CD_Object)) {
            builder.append(" extends ").append(superClass.displayName());
        }
        if (!interfaces.isEmpty()) {
            builder.append(isInterface ? " extends " : " implements ")
                    .append(interfaces.stream().map(ClassDesc::displayName).collect(Collectors.joining(", ")));
        }
        builder.append(" {\n");
        List<FieldDefinition> displayedFields = isRecord ? fields.stream()
                                                           .filter(field -> recordComponents.stream().noneMatch(component -> component.field() == field))
                                                           .toList() : fields;
        displayedFields.forEach(field -> builder.append(ModelRendering.indent(field + ";", "    ")).append('\n'));
        if (!displayedFields.isEmpty() && !methods.isEmpty()) {
            builder.append('\n');
        }
        for (int index = 0; index < methods.size(); index++) {
            builder.append(ModelRendering.indent(methods.get(index).toString(), "    ")).append('\n');
            if (index + 1 < methods.size()) {
                builder.append('\n');
            }
        }
        return builder.append('}').toString();
    }
}
