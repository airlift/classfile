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

import org.junit.jupiter.api.Test;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
import static io.airlift.classfile.BytecodeExpressions.getStatic;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.setStatic;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.reflect.AccessFlag.ABSTRACT;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestClassKindsAndInterfaces
{
    private static final AtomicLong NEXT_ID = new AtomicLong();

    @Test
    void testLoadedClassFactoryOverloads()
    {
        assertThat(ClassDefinition.define(String.class).type()).isEqualTo(DescriptorUtils.classDesc(String.class));
        assertThat(ClassDefinition.define(String.class).kind()).isEqualTo(ClassKind.CLASS);
        assertThat(ClassDefinition.defineInterface(Runnable.class).type()).isEqualTo(DescriptorUtils.classDesc(Runnable.class));
        assertThat(ClassDefinition.defineInterface(Runnable.class).kind()).isEqualTo(ClassKind.INTERFACE);
        assertThat(ClassDefinition.defineRecord(FactoryRecord.class).type()).isEqualTo(DescriptorUtils.classDesc(FactoryRecord.class));
        assertThat(ClassDefinition.defineRecord(FactoryRecord.class).kind()).isEqualTo(ClassKind.RECORD);
    }

    @Test
    void testGeneratedInterfaceAndImplementation()
            throws Exception
    {
        ClassDefinition interfaceDefinition = ClassDefinition.defineInterface(generatedType("Operation")).access(PUBLIC);
        FieldDefinition factor = interfaceDefinition.field("FACTOR", int.class)
                .access(PUBLIC, STATIC, FINAL)
                .constantValue(2)
                .build();
        FieldDefinition initialized = interfaceDefinition.field("INITIALIZED", int.class)
                .access(PUBLIC, STATIC, FINAL)
                .build();
        interfaceDefinition.classInitializer().body().append(setStatic(initialized, constantInt(3)));
        Parameter abstractValue = arg("value", int.class);
        MethodDefinition abstractMethod = interfaceDefinition.method("apply", int.class, abstractValue).access(PUBLIC, ABSTRACT);
        Parameter defaultValue = arg("value", int.class);
        interfaceDefinition.method("twice", int.class, defaultValue)
                .access(PUBLIC)
                .body()
                .ret(defaultValue.multiply(getStatic(factor)));
        interfaceDefinition.method("factor", int.class)
                .access(PUBLIC, STATIC)
                .body()
                .ret(invokeStatic(interfaceDefinition.type(), "baseFactor", MethodTypeDesc.of(CD_int)));
        interfaceDefinition.method("baseFactor", int.class)
                .access(PRIVATE, STATIC)
                .body()
                .ret(getStatic(factor));
        interfaceDefinition.method("initialized", int.class)
                .access(PUBLIC, STATIC)
                .body()
                .ret(getStatic(initialized));
        ClassModel interfaceModel = interfaceDefinition.build();

        ClassDefinition implementationDefinition = ClassDefinition.define(generatedType("OperationImpl"))
                .access(PUBLIC)
                .addInterface(interfaceModel.type());
        implementationDefinition.defaultConstructor().access(PUBLIC);
        Parameter value = arg("value", int.class);
        implementationDefinition.method("apply", int.class, value)
                .access(PUBLIC)
                .body()
                .ret(value.add(constantInt(1)));
        ClassModel implementationModel = implementationDefinition.build();

        ClassDefinition callerDefinition = ClassDefinition.define(generatedType("OperationCaller")).access(PUBLIC, FINAL);
        Parameter operation = arg("operation", interfaceModel.type());
        Parameter callValue = arg("value", int.class);
        callerDefinition.method("call", int.class, operation, callValue)
                .access(PUBLIC, STATIC)
                .body()
                .ret(operation.invoke(abstractMethod, callValue));
        ClassModel callerModel = callerDefinition.build();

        DefinedClasses classes = StandardClassDefiner.builder(getClass().getClassLoader())
                .build()
                .defineClasses(List.of(interfaceModel, implementationModel, callerModel));
        Class<?> interfaceClass = classes.definedClass(interfaceModel);
        Class<?> implementationClass = classes.definedClass(implementationModel);
        Object instance = implementationClass.getConstructor().newInstance();

        assertThat(interfaceModel.kind()).isEqualTo(ClassKind.INTERFACE);
        assertThat(interfaceClass.isInterface()).isTrue();
        assertThat(interfaceClass.isAssignableFrom(implementationClass)).isTrue();
        assertThat(interfaceClass.getMethod("factor").invoke(null)).isEqualTo(2);
        assertThat(interfaceClass.getMethod("initialized").invoke(null)).isEqualTo(3);
        assertThat(interfaceClass.getMethod("apply", int.class).invoke(instance, 10)).isEqualTo(11);
        assertThat(interfaceClass.getMethod("twice", int.class).invoke(instance, 10)).isEqualTo(20);
        assertThat(classes.definedClass(callerModel).getMethod("call", interfaceClass, int.class).invoke(null, instance, 10)).isEqualTo(11);
        assertThat(interfaceModel.toString()).startsWith("public interface ").contains("int apply(int value);");
    }

    @Test
    void testInterfaceValidation()
    {
        ClassDefinition definition = ClassDefinition.defineInterface(generatedType("Invalid"));
        assertThatThrownBy(definition::constructor)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Interface cannot declare a constructor");
        assertThatThrownBy(() -> definition.superClass(Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Interface cannot declare a superclass");
        assertThatThrownBy(() -> definition.recordComponent("value", int.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Record components can only be declared on a record");

        ClassDefinition invalidField = ClassDefinition.defineInterface(generatedType("InvalidField"));
        invalidField.field("value", int.class).access(PUBLIC, STATIC).build();
        assertThatThrownBy(invalidField::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Interface field must be public static final: value");

        ClassDefinition invalidMethod = ClassDefinition.defineInterface(generatedType("InvalidMethod"));
        invalidMethod.method("value", int.class).body().ret(constantInt(1));
        assertThatThrownBy(invalidMethod::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Interface method must be exactly one of public or private: value");

        ClassDefinition invalidAbstract = ClassDefinition.defineInterface(generatedType("InvalidAbstract"));
        assertThatThrownBy(() -> invalidAbstract.method("value", int.class).access(PRIVATE, ABSTRACT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Abstract method has incompatible access flags: value");

        ClassDesc superInterfaceType = generatedType("SuperInterface");
        ClassDefinition superInterface = ClassDefinition.defineInterface(superInterfaceType)
                .access(PUBLIC, AccessFlag.SUPER);
        assertThatThrownBy(superInterface::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Interface has incompatible access flags: " + superInterfaceType.displayName());

        ClassDefinition strictMethod = ClassDefinition.defineInterface(generatedType("StrictMethod"));
        assertThatThrownBy(() -> strictMethod.method("value", int.class).access(PUBLIC, AccessFlag.STRICT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Access flag is not valid for a method: STRICT");
    }

    @Test
    void testImplementedTypesMustBeInterfaces()
    {
        ClassDefinition duplicate = ClassDefinition.define(generatedType("DuplicateInterface"))
                .addInterface(Runnable.class)
                .addInterface(Runnable.class);
        assertThatThrownBy(duplicate::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Interface already declared: Runnable");

        ClassDefinition knownClass = ClassDefinition.define(generatedType("KnownClassInterface"));
        assertThatThrownBy(() -> knownClass.addInterface(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Type is not an interface: java.lang.String");

        ClassDefinition symbolicClass = ClassDefinition.define(generatedType("SymbolicClassInterface"))
                .addInterface(DescriptorUtils.classDesc(String.class));
        ClassModel model = symbolicClass.build();
        assertThatThrownBy(() -> ClassCompiler.forTarget(CompilationTarget.forClassLoader(getClass().getClassLoader()))
                .compileClass(model))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("interface 0")
                .hasMessageContaining("String is not an interface");

        ClassDefinition generatedClass = ClassDefinition.define(generatedType("GeneratedClass"));
        ClassDefinition generatedImplementation = ClassDefinition.define(generatedType("GeneratedClassImplementation"))
                .addInterface(generatedClass.type());
        assertThatThrownBy(() -> ClassCompiler.forTarget(CompilationTarget.forClassLoader(getClass().getClassLoader()))
                .compileClassBundle(List.of(generatedClass.build(), generatedImplementation.build())))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("interface 0")
                .hasMessageContaining("is not an interface");
    }

    @Test
    void testInheritanceCyclesAreRejected()
    {
        ClassDesc selfClassType = generatedType("SelfClass");
        assertThatThrownBy(() -> ClassDefinition.define(selfClassType).superClass(selfClassType).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Class cannot extend itself: " + selfClassType.displayName());

        ClassDesc selfImplementationType = generatedType("SelfImplementation");
        assertThatThrownBy(() -> ClassDefinition.define(selfImplementationType).addInterface(selfImplementationType).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Class cannot implement itself: " + selfImplementationType.displayName());

        ClassDesc selfInterfaceType = generatedType("SelfInterface");
        assertThatThrownBy(() -> ClassDefinition.defineInterface(selfInterfaceType).addInterface(selfInterfaceType).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Interface cannot extend itself: " + selfInterfaceType.displayName());

        ClassDesc firstClassType = generatedType("FirstClassCycle");
        ClassDesc secondClassType = generatedType("SecondClassCycle");
        ClassModel firstClass = ClassDefinition.define(firstClassType).superClass(secondClassType).build();
        ClassModel secondClass = ClassDefinition.define(secondClassType).superClass(firstClassType).build();
        assertThatThrownBy(() -> ClassCompiler.forTarget(CompilationTarget.forClassLoader(getClass().getClassLoader()))
                .compileClassBundle(List.of(firstClass, secondClass)))
                .isInstanceOf(CompilationException.class)
                .hasMessage("Generated inheritance cycle: %s -> %s -> %s"
                        .formatted(firstClassType.displayName(), secondClassType.displayName(), firstClassType.displayName()));

        ClassDesc firstInterfaceType = generatedType("FirstInterfaceCycle");
        ClassDesc secondInterfaceType = generatedType("SecondInterfaceCycle");
        ClassModel firstInterface = ClassDefinition.defineInterface(firstInterfaceType).addInterface(secondInterfaceType).build();
        ClassModel secondInterface = ClassDefinition.defineInterface(secondInterfaceType).addInterface(firstInterfaceType).build();
        assertThatThrownBy(() -> ClassCompiler.forTarget(CompilationTarget.forClassLoader(getClass().getClassLoader()))
                .compileClassBundle(List.of(firstInterface, secondInterface)))
                .isInstanceOf(CompilationException.class)
                .hasMessage("Generated inheritance cycle: %s -> %s -> %s"
                        .formatted(firstInterfaceType.displayName(), secondInterfaceType.displayName(), firstInterfaceType.displayName()));
    }

    @Test
    void testRecursiveMemberDescriptorsAreAllowed()
            throws Exception
    {
        ClassDesc type = generatedType("RecursiveMember");
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        definition.method("next", type).access(PUBLIC, STATIC).body().ret(constantNull(type));

        Class<?> generated = StandardClassDefiner.builder(getClass().getClassLoader()).build().defineClass(definition.build());
        assertThat(generated.getDeclaredMethod("next").getReturnType()).isEqualTo(generated);
    }

    @Test
    void testHiddenInterface()
            throws Throwable
    {
        ClassDefinition definition = ClassDefinition.defineInterface(generatedType("Hidden")).access(PUBLIC);
        definition.method("value", int.class)
                .access(PUBLIC, STATIC)
                .body()
                .ret(constantInt(9));

        MethodHandles.Lookup lookup = HiddenClassDefiner.builder(MethodHandles.lookup()).build().defineClass(definition.build());
        assertThat(lookup.lookupClass().isInterface()).isTrue();
        assertThat(lookup.lookupClass().isHidden()).isTrue();
        assertThat((int) lookup.findStatic(lookup.lookupClass(), "value", MethodType.methodType(int.class)).invokeExact()).isEqualTo(9);
    }

    @Test
    void testClassKindCannotBeSpoofedWithFlags()
    {
        ClassDefinition definition = ClassDefinition.define(generatedType("Spoofed"))
                .access(AccessFlag.INTERFACE, ABSTRACT);
        assertThatThrownBy(definition::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Class kind does not match access flags: " + definition.type().displayName());

        ClassDefinition conflicting = ClassDefinition.define(generatedType("Conflicting"))
                .access(FINAL, ABSTRACT);
        assertThatThrownBy(conflicting::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Class cannot be both final and abstract: " + conflicting.type().displayName());

        assertThatThrownBy(() -> ClassDefinition.define(generatedType("Private")).access(PRIVATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Access flag is not valid for a class: PRIVATE");
    }

    private static ClassDesc generatedType(String name)
    {
        return ClassDesc.of(TestClassKindsAndInterfaces.class.getPackageName() + ".Generated" + name + NEXT_ID.incrementAndGet());
    }

    private record FactoryRecord(int value) {}
}
