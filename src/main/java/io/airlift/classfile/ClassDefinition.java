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

import javax.lang.model.SourceVersion;

import java.lang.classfile.Annotation;
import java.lang.classfile.ClassSignature;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.airlift.classfile.DescriptorUtils.classDesc;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.INTERFACE;
import static java.lang.reflect.AccessFlag.Location.CLASS;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PROTECTED;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.util.Objects.requireNonNull;

/// Mutable authoring context for a class. [ClassDefinition#build()] creates an immutable, validated
/// snapshot that can be compiled or defined.
public final class ClassDefinition
{
    private final ClassDesc type;
    private final ClassKind kind;
    private final List<ClassDesc> interfaces = new ArrayList<>();
    private final List<FieldDefinition> fields = new ArrayList<>();
    private final List<MethodDefinition> methods = new ArrayList<>();
    private final List<RecordComponentDefinition> recordComponents = new ArrayList<>();
    private final List<Annotation> visibleAnnotations = new ArrayList<>();
    private final List<Annotation> invisibleAnnotations = new ArrayList<>();
    private Set<AccessFlag> access = Set.of();
    private boolean accessSet;
    private ClassDesc superClass = CD_Object;
    private boolean superClassSet;
    private ClassSignature signature;
    private String sourceFile;
    private MethodDefinition classInitializer;
    private CompactConstructorDefinition compactConstructor;
    private boolean defaultConstructorDeclared;

    private ClassDefinition(ClassDesc type, ClassKind kind)
    {
        this.type = requireClass(type, "type");
        this.kind = requireNonNull(kind, "kind is null");
    }

    public static ClassDefinition define(ClassDesc type)
    {
        return new ClassDefinition(type, ClassKind.CLASS);
    }

    public static ClassDefinition define(Class<?> type)
    {
        return define(classDesc(type));
    }

    public static ClassDefinition defineRecord(ClassDesc type)
    {
        ClassDefinition definition = new ClassDefinition(type, ClassKind.RECORD);
        definition.superClass = classDesc(Record.class);
        definition.superClassSet = true;
        return definition;
    }

    public static ClassDefinition defineRecord(Class<?> type)
    {
        return defineRecord(classDesc(type));
    }

    public static ClassDefinition defineInterface(ClassDesc type)
    {
        return new ClassDefinition(type, ClassKind.INTERFACE);
    }

    public static ClassDefinition defineInterface(Class<?> type)
    {
        return defineInterface(classDesc(type));
    }

    public ClassDefinition access(AccessFlag... access)
    {
        if (accessSet) {
            throw new IllegalStateException("access is already set");
        }
        Set<AccessFlag> flags = Set.of(requireNonNull(access, "access is null"));
        flags.stream()
                .filter(flag -> !flag.locations().contains(CLASS))
                .findFirst()
                .ifPresent(flag -> {
                    throw new IllegalArgumentException("Access flag is not valid for a class: " + flag);
                });
        this.access = flags;
        accessSet = true;
        return this;
    }

    public ClassDefinition superClass(Class<?> superClass)
    {
        return superClass(classDesc(superClass));
    }

    public ClassDefinition superClass(ClassDesc superClass)
    {
        if (kind == ClassKind.INTERFACE) {
            throw new IllegalStateException("Interface cannot declare a superclass");
        }
        if (superClassSet) {
            throw new IllegalStateException("super class is already set");
        }
        if (defaultConstructorDeclared) {
            throw new IllegalStateException("super class cannot be set after the default constructor is declared");
        }
        this.superClass = requireClass(superClass, "superClass");
        superClassSet = true;
        return this;
    }

    public ClassDefinition addInterface(Class<?> interfaceType)
    {
        requireNonNull(interfaceType, "interfaceType is null");
        if (!interfaceType.isInterface()) {
            throw new IllegalArgumentException("Type is not an interface: " + interfaceType.getName());
        }
        return addInterface(classDesc(interfaceType));
    }

    public ClassDefinition addInterface(ClassDesc interfaceType)
    {
        interfaces.add(requireClass(interfaceType, "interface"));
        return this;
    }

    public FieldDefinition.Builder field(String name, Class<?> type)
    {
        return field(name, classDesc(type));
    }

    public FieldDefinition.Builder field(String name, ClassDesc type)
    {
        return new FieldDefinition.Builder(this::addField, this.type, name, type);
    }

    public RecordComponentDefinition.Builder recordComponent(String name, Class<?> type)
    {
        return recordComponent(name, classDesc(type));
    }

    public RecordComponentDefinition.Builder recordComponent(String name, ClassDesc type)
    {
        if (kind != ClassKind.RECORD) {
            throw new IllegalStateException("Record components can only be declared on a record");
        }
        return new RecordComponentDefinition.Builder(this, name, type);
    }

    public MethodDefinition method(String name, Class<?> returnType, Parameter... parameters)
    {
        return method(name, classDesc(returnType), parameters);
    }

    public MethodDefinition method(String name, ClassDesc returnType, Parameter... parameters)
    {
        List<Parameter> parameterList = List.of(parameters);
        if (kind == ClassKind.INTERFACE && name.equals("<init>")) {
            throw new IllegalStateException("Interface cannot declare a constructor");
        }
        if (name.equals("<init>") && !returnType.equals(CD_void)) {
            throw new IllegalArgumentException("Constructor must return void");
        }
        if (name.equals("<clinit>") && (!returnType.equals(CD_void) || !parameterList.isEmpty())) {
            throw new IllegalArgumentException("Class initializer must have descriptor ()void");
        }
        MethodTypeDesc methodType = MethodTypeDesc.of(returnType, parameterList.stream().map(Parameter::type).toList());
        if (compactConstructor != null && name.equals("<init>") && compactConstructor.methodType().equals(methodType)) {
            throw new IllegalArgumentException("Method already declared: " + name + methodType.descriptorString());
        }
        if (methods.stream().anyMatch(existing -> existing.name().equals(name) && existing.methodType().equals(methodType))) {
            throw new IllegalArgumentException("Method already declared: " + name + methodType.descriptorString());
        }
        MethodDefinition method = new MethodDefinition(kind, type, name, returnType, parameterList);
        methods.add(method);
        return method;
    }

    public MethodDefinition constructor(Parameter... parameters)
    {
        if (kind == ClassKind.INTERFACE) {
            throw new IllegalStateException("Interface cannot declare a constructor");
        }
        return method("<init>", CD_void, parameters);
    }

    /// Declares a no-argument constructor that directly invokes the no-argument superclass
    /// constructor. The returned method may be further configured.
    public MethodDefinition defaultConstructor()
    {
        MethodDefinition constructor = constructor();
        constructor.body()
                .invokeSuperConstructor(MethodTypeDesc.of(CD_void))
                .ret();
        defaultConstructorDeclared = true;
        return constructor;
    }

    /// Begins the canonical compact constructor for this record.
    ///
    /// The superclass invocation and final component-field assignments are generated automatically.
    /// Record components cannot be added after this method is called.
    public CompactConstructorDefinition compactConstructor()
    {
        if (kind != ClassKind.RECORD) {
            throw new IllegalStateException("Compact constructor can only be declared on a record");
        }
        if (compactConstructor != null) {
            throw new IllegalStateException("Compact constructor is already declared");
        }
        MethodTypeDesc canonicalType = MethodTypeDesc.of(CD_void, recordComponents.stream().map(RecordComponentDefinition::type).toList());
        if (methods.stream().anyMatch(method -> method.name().equals("<init>") && method.methodType().equals(canonicalType))) {
            throw new IllegalArgumentException("Method already declared: <init>" + canonicalType.descriptorString());
        }
        compactConstructor = new CompactConstructorDefinition(type, recordComponents);
        return compactConstructor;
    }

    /// Returns the single static class-initializer authoring context, creating it when necessary.
    public MethodDefinition classInitializer()
    {
        if (classInitializer == null) {
            classInitializer = method("<clinit>", CD_void).access(STATIC);
        }
        return classInitializer;
    }

    public ClassDefinition signature(ClassSignature signature)
    {
        if (this.signature != null) {
            throw new IllegalStateException("signature is already set");
        }
        this.signature = requireNonNull(signature, "signature is null");
        return this;
    }

    public ClassDefinition sourceFile(String sourceFile)
    {
        if (this.sourceFile != null) {
            throw new IllegalStateException("source file is already set");
        }
        this.sourceFile = requireNonNull(sourceFile, "sourceFile is null");
        return this;
    }

    public ClassDefinition addAnnotation(Annotation annotation)
    {
        visibleAnnotations.add(requireNonNull(annotation, "annotation is null"));
        return this;
    }

    public ClassDefinition addInvisibleAnnotation(Annotation annotation)
    {
        invisibleAnnotations.add(requireNonNull(annotation, "annotation is null"));
        return this;
    }

    public ClassDesc type()
    {
        return type;
    }

    public ClassKind kind()
    {
        return kind;
    }

    /// Creates an immutable validated snapshot of the current declaration.
    ///
    /// The authoring context remains mutable and may later produce another independent snapshot.
    /// Statements and expressions attached to the snapshot remain reusable logical values.
    public ClassModel build()
    {
        Set<AccessFlag> classAccess = switch (kind) {
            case CLASS -> access;
            case INTERFACE -> addAccess(access, INTERFACE, AccessFlag.ABSTRACT);
            case RECORD -> addAccess(access, FINAL);
        };
        List<MethodDefinition.Model> methodModels = methods.stream().map(MethodDefinition::build).collect(Collectors.toCollection(ArrayList::new));
        if (compactConstructor != null) {
            methodModels.add(compactConstructor.build(classAccess));
        }
        if (kind == ClassKind.RECORD) {
            synthesizeRecordMethods(methodModels, classAccess);
        }
        return new ClassModel(
                classAccess,
                type,
                superClass,
                interfaces,
                fields,
                methodModels,
                kind,
                recordComponents,
                signature,
                sourceFile,
                visibleAnnotations,
                invisibleAnnotations);
    }

    void addRecordComponent(RecordComponentDefinition component)
    {
        requireNonNull(component, "component is null");
        if (compactConstructor != null) {
            throw new IllegalStateException("Record component cannot be declared after the compact constructor");
        }
        if (!component.declaringType().equals(type)) {
            throw new IllegalArgumentException("Record component is declared by another class: " + component);
        }
        if (!SourceVersion.isIdentifier(component.name()) || SourceVersion.isKeyword(component.name())) {
            throw new IllegalArgumentException("Invalid record component name: " + component.name());
        }
        if (Set.of("clone", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait").contains(component.name())) {
            throw new IllegalArgumentException("Record component name conflicts with Object method: " + component.name());
        }
        if (recordComponents.stream().anyMatch(existing -> existing.name().equals(component.name()))) {
            throw new IllegalArgumentException("Record component already declared: " + component.name());
        }
        if (fields.stream().anyMatch(existing -> existing.name().equals(component.name()))) {
            throw new IllegalArgumentException("Field already declared: " + component.name());
        }
        recordComponents.add(component);
        fields.add(component.field());
    }

    private void synthesizeRecordMethods(List<MethodDefinition.Model> methodModels, Set<AccessFlag> classAccess)
    {
        for (RecordComponentDefinition component : recordComponents) {
            MethodTypeDesc accessorType = MethodTypeDesc.of(component.type());
            if (!hasMethod(methodModels, component.name(), accessorType)) {
                MethodDefinition accessor = new MethodDefinition(type, component.name(), component.type(), List.of());
                accessor.access(PUBLIC);
                accessor.body().ret(component.read(accessor.thisVariable()));
                methodModels.add(accessor.build());
            }
        }

        MethodTypeDesc canonicalType = MethodTypeDesc.of(CD_void, recordComponents.stream().map(RecordComponentDefinition::type).toList());
        if (!hasMethod(methodModels, "<init>", canonicalType)) {
            List<Parameter> parameters = recordComponents.stream()
                    .map(component -> Parameter.arg(component.name(), component.type()))
                    .toList();
            MethodDefinition constructor = new MethodDefinition(type, "<init>", CD_void, parameters);
            AccessFlag[] visibility = classAccess.stream()
                    .filter(flag -> flag == PUBLIC || flag == PROTECTED || flag == PRIVATE)
                    .toArray(AccessFlag[]::new);
            constructor.access(visibility);
            constructor.body().invokeSuperConstructor();
            for (int index = 0; index < recordComponents.size(); index++) {
                constructor.body().append(recordComponents.get(index).initialize(constructor.thisVariable(), parameters.get(index)));
            }
            constructor.body().ret();
            methodModels.add(constructor.build());
        }
        synthesizeObjectMethod(methodModels, "toString", CD_String, List.of());
        synthesizeObjectMethod(methodModels, "hashCode", CD_int, List.of());
        synthesizeObjectMethod(methodModels, "equals", CD_boolean, List.of(Parameter.arg("other", Object.class)));
    }

    private void synthesizeObjectMethod(List<MethodDefinition.Model> methodModels, String name, ClassDesc returnType, List<Parameter> parameters)
    {
        MethodTypeDesc methodType = MethodTypeDesc.of(returnType, parameters.stream().map(Parameter::type).toList());
        if (hasMethod(methodModels, name, methodType)) {
            return;
        }
        MethodDefinition method = new MethodDefinition(type, name, returnType, parameters);
        method.access(PUBLIC, FINAL);
        ArrayList<BytecodeExpression> arguments = new ArrayList<>(parameters.size() + 1);
        arguments.add(method.thisVariable());
        arguments.addAll(parameters);
        method.body().ret(RecordSupport.objectMethod(name, returnType, type, recordComponents, arguments.toArray(BytecodeExpression[]::new)));
        methodModels.add(method.build());
    }

    private static boolean hasMethod(List<MethodDefinition.Model> methods, String name, MethodTypeDesc type)
    {
        return methods.stream().anyMatch(method -> method.name().equals(name) && method.methodType().equals(type));
    }

    private static Set<AccessFlag> addAccess(Set<AccessFlag> access, AccessFlag... flags)
    {
        EnumSet<AccessFlag> result = EnumSet.noneOf(AccessFlag.class);
        result.addAll(access);
        result.addAll(List.of(flags));
        return Set.copyOf(result);
    }

    private void addField(FieldDefinition field)
    {
        if (fields.stream().anyMatch(existing -> existing.name().equals(field.name()))) {
            throw new IllegalArgumentException("Field already declared: " + field.name());
        }
        fields.add(field);
    }

    private static ClassDesc requireClass(ClassDesc type, String name)
    {
        requireNonNull(type, name + " is null");
        if (!type.isClassOrInterface()) {
            throw new IllegalArgumentException(name + " is not a class or interface: " + type.displayName());
        }
        return type;
    }
}
