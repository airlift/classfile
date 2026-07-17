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
import java.lang.classfile.MethodSignature;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.lang.reflect.AccessFlag.ABSTRACT;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.Location.METHOD;
import static java.lang.reflect.AccessFlag.NATIVE;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PROTECTED;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.lang.reflect.AccessFlag.SYNCHRONIZED;
import static java.lang.reflect.AccessFlag.SYNTHETIC;
import static java.lang.reflect.AccessFlag.VARARGS;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/// Mutable authoring context for a method owned by a [ClassDefinition].
public final class MethodDefinition
{
    private final ClassDesc declaringType;
    private final ClassKind declaringKind;
    private final String name;
    private final MethodTypeDesc methodType;
    private final List<Parameter> parameters;
    private final CodeBlock.Builder body = CodeBlock.blockBuilder();
    private final List<ClassDesc> exceptions = new ArrayList<>();
    private final List<Annotation> visibleAnnotations = new ArrayList<>();
    private final List<Annotation> invisibleAnnotations = new ArrayList<>();
    private Set<AccessFlag> access = Set.of();
    private boolean accessSet;
    private Optional<Variable> receiver;
    private MethodSignature signature;
    private String comment;

    MethodDefinition(ClassDesc declaringType, String name, ClassDesc returnType, List<Parameter> parameters)
    {
        this(ClassKind.CLASS, declaringType, name, returnType, parameters);
    }

    MethodDefinition(ClassKind declaringKind, ClassDesc declaringType, String name, ClassDesc returnType, List<Parameter> parameters)
    {
        this.declaringKind = requireNonNull(declaringKind, "declaringKind is null");
        this.declaringType = requireNonNull(declaringType, "declaringType is null");
        this.name = DescriptorUtils.requireMethodName(name);
        this.parameters = List.copyOf(requireNonNull(parameters, "parameters is null"));
        HashSet<String> parameterNames = new HashSet<>();
        for (Parameter parameter : parameters) {
            if (!parameterNames.add(parameter.name())) {
                throw new IllegalArgumentException("Duplicate parameter name: " + parameter.name());
            }
        }
        parameters.forEach(parameter -> parameter.requireBindable(this));
        parameters.forEach(parameter -> parameter.bind(this));
        methodType = MethodTypeDesc.of(requireNonNull(returnType, "returnType is null"), parameters.stream().map(Parameter::type).toList());
        receiver = Optional.of(new Variable("this", declaringType, this));
    }

    public MethodDefinition access(AccessFlag... access)
    {
        if (accessSet) {
            throw new IllegalStateException("access is already set");
        }
        Set<AccessFlag> newAccess = Set.of(requireNonNull(access, "access is null"));
        validateAccess(name, newAccess);
        validateParameterSlots(methodType, newAccess);
        this.access = newAccess;
        if (newAccess.contains(STATIC)) {
            receiver = Optional.empty();
        }
        accessSet = true;
        return this;
    }

    public ClassDesc declaringType()
    {
        return declaringType;
    }

    public ClassKind declaringKind()
    {
        return declaringKind;
    }

    public String name()
    {
        return name;
    }

    public MethodTypeDesc methodType()
    {
        return methodType;
    }

    public ClassDesc returnType()
    {
        return methodType.returnType();
    }

    public boolean isStatic()
    {
        return access.contains(STATIC);
    }

    public Variable thisVariable()
    {
        return receiver.orElseThrow(() -> new IllegalStateException("Static method does not have a receiver"));
    }

    public CodeBlock.Builder body()
    {
        if (!hasBody()) {
            throw new IllegalStateException("Method does not have a body: " + name);
        }
        return body;
    }

    public MethodDefinition addException(ClassDesc exception)
    {
        exceptions.add(requireNonNull(exception, "exception is null"));
        return this;
    }

    public MethodDefinition addException(Class<? extends Throwable> exception)
    {
        return addException(DescriptorUtils.classDesc(exception));
    }

    public MethodDefinition signature(MethodSignature signature)
    {
        if (this.signature != null) {
            throw new IllegalStateException("signature is already set");
        }
        this.signature = requireNonNull(signature, "signature is null");
        return this;
    }

    public MethodDefinition comment(String comment)
    {
        if (this.comment != null) {
            throw new IllegalStateException("comment is already set");
        }
        this.comment = requireNonNull(comment, "comment is null");
        return this;
    }

    public MethodDefinition addAnnotation(Annotation annotation)
    {
        visibleAnnotations.add(requireNonNull(annotation, "annotation is null"));
        return this;
    }

    public MethodDefinition addInvisibleAnnotation(Annotation annotation)
    {
        invisibleAnnotations.add(requireNonNull(annotation, "annotation is null"));
        return this;
    }

    @Override
    public String toString()
    {
        String parameterList = parameters.stream()
                .map(parameter -> parameter.type().displayName() + " " + parameter.name())
                .collect(joining(", "));
        return (name.equals("<init>") ? declaringType.displayName() : returnType().displayName() + " " + name) + "(" + parameterList + ")";
    }

    Model build()
    {
        validateAccess(name, access);
        validateMethodType(name, methodType, access);
        if (name.equals("<init>") && !Set.of(PUBLIC, PROTECTED, PRIVATE, VARARGS, SYNTHETIC).containsAll(access)) {
            throw new IllegalArgumentException("Constructor has incompatible access flags");
        }
        if (name.equals("<clinit>") && (!access.equals(Set.of(STATIC)) || !parameters.isEmpty())) {
            throw new IllegalArgumentException("Class initializer must be static with no parameters");
        }
        if (!hasBody() && !body.isEmpty()) {
            throw new IllegalArgumentException("Abstract or native method cannot have a body: " + name);
        }
        return new Model(
                declaringType,
                access,
                name,
                methodType,
                parameters,
                parameters.stream().map(Parameter::metadata).toList(),
                receiver,
                hasBody() ? Optional.of(body.build()) : Optional.empty(),
                exceptions,
                Optional.ofNullable(signature),
                Optional.ofNullable(comment),
                visibleAnnotations,
                invisibleAnnotations);
    }

    private boolean hasBody()
    {
        return !access.contains(ABSTRACT) && !access.contains(NATIVE);
    }

    static void validateAccess(String name, Set<AccessFlag> access)
    {
        requireNonNull(name, "name is null");
        requireNonNull(access, "access is null");
        access.stream()
                .filter(flag -> !flag.locations().contains(METHOD))
                .findFirst()
                .ifPresent(flag -> {
                    throw new IllegalArgumentException("Access flag is not valid for a method: " + flag);
                });
        if (access.stream().filter(flag -> flag == PUBLIC || flag == PROTECTED || flag == PRIVATE).count() > 1) {
            throw new IllegalArgumentException("Method has conflicting visibility: " + name);
        }
        if (access.contains(ABSTRACT) && access.stream().anyMatch(Set.of(PRIVATE, STATIC, FINAL, SYNCHRONIZED, NATIVE)::contains)) {
            throw new IllegalArgumentException("Abstract method has incompatible access flags: " + name);
        }
        if (name.equals("<init>") && !Set.of(PUBLIC, PROTECTED, PRIVATE, VARARGS, SYNTHETIC).containsAll(access)) {
            throw new IllegalArgumentException("Constructor has incompatible access flags");
        }
    }

    private static void validateMethodType(String name, MethodTypeDesc methodType, Set<AccessFlag> access)
    {
        if (name.equals("<init>") && !methodType.returnType().descriptorString().equals("V")) {
            throw new IllegalArgumentException("Constructor must return void");
        }
        if (name.equals("<clinit>") && !methodType.descriptorString().equals("()V")) {
            throw new IllegalArgumentException("Class initializer must have descriptor ()V");
        }
        validateParameterSlots(methodType, access);
    }

    private static void validateParameterSlots(MethodTypeDesc methodType, Set<AccessFlag> access)
    {
        int slots = access.contains(STATIC) ? 0 : 1;
        for (ClassDesc parameterType : methodType.parameterList()) {
            String descriptor = parameterType.descriptorString();
            slots += descriptor.equals("J") || descriptor.equals("D") ? 2 : 1;
        }
        if (slots > 255) {
            throw new IllegalArgumentException("Method parameters use %s local-variable slots; the JVM limit is 255%s"
                    .formatted(slots, access.contains(STATIC) ? "" : " including the receiver"));
        }
    }

    public record Model(
            ClassDesc declaringType,
            Set<AccessFlag> access,
            String name,
            MethodTypeDesc methodType,
            List<Parameter> parameters,
            List<Parameter.Metadata> parameterMetadata,
            Optional<Variable> receiver,
            Optional<CodeBlock> methodBody,
            List<ClassDesc> exceptions,
            Optional<MethodSignature> signature,
            Optional<String> comment,
            List<Annotation> visibleAnnotations,
            List<Annotation> invisibleAnnotations)
    {
        public Model
        {
            requireNonNull(declaringType, "declaringType is null");
            access = Set.copyOf(requireNonNull(access, "access is null"));
            name = DescriptorUtils.requireMethodName(name);
            validateAccess(name, access);
            requireNonNull(methodType, "methodType is null");
            validateMethodType(name, methodType, access);
            parameters = List.copyOf(requireNonNull(parameters, "parameters is null"));
            if (name.equals("<clinit>") && (!access.equals(Set.of(STATIC)) || !parameters.isEmpty())) {
                throw new IllegalArgumentException("Class initializer must be static with no parameters");
            }
            parameterMetadata = List.copyOf(requireNonNull(parameterMetadata, "parameterMetadata is null"));
            if (!parameterMetadata.stream().map(Parameter.Metadata::parameter).toList().equals(parameters)) {
                throw new IllegalArgumentException("Parameter metadata does not match parameters");
            }
            requireNonNull(receiver, "receiver is null");
            requireNonNull(methodBody, "methodBody is null");
            if (access.contains(STATIC) == receiver.isPresent()) {
                throw new IllegalArgumentException("Method receiver does not match static access: " + name);
            }
            if ((!access.contains(ABSTRACT) && !access.contains(NATIVE)) != methodBody.isPresent()) {
                throw new IllegalArgumentException("Method body does not match access: " + name);
            }
            exceptions = List.copyOf(requireNonNull(exceptions, "exceptions is null"));
            requireNonNull(signature, "signature is null");
            requireNonNull(comment, "comment is null");
            visibleAnnotations = List.copyOf(requireNonNull(visibleAnnotations, "visibleAnnotations is null"));
            invisibleAnnotations = List.copyOf(requireNonNull(invisibleAnnotations, "invisibleAnnotations is null"));
        }

        public ClassDesc returnType()
        {
            return methodType.returnType();
        }

        public Variable thisVariable()
        {
            return receiver.orElseThrow(() -> new IllegalStateException("Static method does not have a receiver"));
        }

        public CodeBlock body()
        {
            return methodBody.orElseThrow(() -> new IllegalStateException("Method does not have a body: " + name));
        }

        public boolean isStatic()
        {
            return access.contains(STATIC);
        }

        public boolean isConstructor()
        {
            return name.equals("<init>");
        }

        public boolean isClassInitializer()
        {
            return name.equals("<clinit>");
        }

        public boolean hasBody()
        {
            return !access.contains(ABSTRACT) && !access.contains(NATIVE);
        }

        Model addParameterAccess(AccessFlag flag)
        {
            return new Model(
                    declaringType,
                    access,
                    name,
                    methodType,
                    parameters,
                    parameterMetadata.stream().map(metadata -> metadata.addAccess(flag)).toList(),
                    receiver,
                    methodBody,
                    exceptions,
                    signature,
                    comment,
                    visibleAnnotations,
                    invisibleAnnotations);
        }

        @Override
        public String toString()
        {
            if (isClassInitializer()) {
                return "static " + body();
            }
            String parameterList = parameters.stream()
                    .map(parameter -> parameter.type().displayName() + " " + parameter.name())
                    .collect(joining(", "));
            String exceptions = this.exceptions.isEmpty() ? "" : this.exceptions.stream()
                                                                 .map(ClassDesc::displayName)
                                                                 .collect(joining(", ", " throws ", ""));
            String declaration = ModelRendering.access(access) +
                    (isConstructor() ? declaringType.displayName() : returnType().displayName() + " " + name) +
                    "(" + parameterList + ")" + exceptions;
            String prefix = comment.map(value -> "// " + value + "\n").orElse("");
            return prefix + declaration + (hasBody() ? " " + body() : ";");
        }
    }
}
