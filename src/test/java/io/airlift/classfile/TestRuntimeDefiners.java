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

import java.io.IOException;
import java.io.InputStream;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

import static io.airlift.classfile.BytecodeExpressions.boundConstant;
import static io.airlift.classfile.BytecodeExpressions.boundMethodHandle;
import static io.airlift.classfile.BytecodeExpressions.classData;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.invokeDynamic;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.BytecodeExpressions.setStatic;
import static io.airlift.classfile.DescriptorUtils.classDesc;
import static io.airlift.classfile.Parameter.arg;
import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.invoke.MethodHandles.Lookup.ClassOption.NESTMATE;
import static java.lang.reflect.AccessFlag.ABSTRACT;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRuntimeDefiners
{
    private static final AtomicLong NEXT_CLASS_ID = new AtomicLong();

    @Test
    void testStandardClassesShareDefiningLoader()
            throws Exception
    {
        ClassDefinition firstDefinition = ClassDefinition.define(generatedClass("First")).access(PUBLIC, FINAL);
        MethodDefinition firstValue = firstDefinition.method("value", CD_int).access(PUBLIC, STATIC);
        firstValue.body().append(constantInt(41).ret());
        ClassModel first = firstDefinition.build();

        ClassDefinition secondDefinition = ClassDefinition.define(generatedClass("Second")).access(PUBLIC, FINAL);
        MethodDefinition secondValue = secondDefinition.method("value", CD_int).access(PUBLIC, STATIC);
        secondValue.body().append(invokeStatic(firstValue).add(constantInt(1)).ret());
        ClassModel second = secondDefinition.build();

        DefinedClasses classes = StandardClassDefiner.builder(getClass().getClassLoader())
                .build()
                .defineClasses(List.of(second, first));

        assertThat(classes.definedClass(second).getMethod("value").invoke(null)).isEqualTo(42);
        assertThat(classes.definedClass(first).getClassLoader()).isSameAs(classes.definedClass(second).getClassLoader());
        assertThat(classes.definedClass(second.type(), Object.class)).isSameAs(classes.definedClass(second));
        assertThatThrownBy(() -> classes.definedClass(generatedClass("Missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Class was not defined:");
    }

    @Test
    void testHiddenClass()
            throws Throwable
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("Hidden")).access(PUBLIC, FINAL);
        MethodDefinition value = classDefinition.method("value", CD_int).access(PUBLIC, STATIC);
        value.body().append(constantInt(99).ret());
        ClassModel definition = classDefinition.build();

        MethodHandles.Lookup hidden = HiddenClassDefiner.builder(MethodHandles.lookup())
                .build()
                .defineClass(definition);

        assertThat(hidden.lookupClass().isHidden()).isTrue();
        assertThat(hidden.findStatic(hidden.lookupClass(), "value", MethodType.methodType(int.class)).invoke()).isEqualTo(99);
    }

    @Test
    void testLambdaMetafactoryWithHiddenImplementation()
            throws Exception
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("HiddenLambda")).access(PUBLIC, FINAL);
        Parameter capture = arg("capture", String.class);
        Parameter value = arg("value", Long.class);
        MethodDefinition target = classDefinition.method("lambda", Long.class, capture, value).access(PRIVATE);
        target.body().ret(value);

        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDesc(LambdaMetafactory.class),
                "metafactory",
                MethodTypeDesc.of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType, CD_MethodType, CD_MethodHandle, CD_MethodType));
        MethodHandleDesc implementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.VIRTUAL,
                classDefinition.type(),
                target.name(),
                target.methodType());
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(
                bootstrap,
                "apply",
                MethodTypeDesc.of(classDesc(Function.class), classDefinition.type(), CD_String),
                MethodTypeDesc.of(CD_Object, CD_Object),
                implementation,
                MethodTypeDesc.of(classDesc(Long.class), classDesc(Long.class)));

        MethodDefinition get = classDefinition.method("get", Function.class).access(PUBLIC);
        get.body().ret(invokeDynamic(callSite, get.thisVariable(), constantString("capture")));

        Parameter staticValue = arg("value", Long.class);
        MethodDefinition staticTarget = classDefinition.method("staticLambda", Long.class, staticValue).access(PRIVATE, STATIC);
        staticTarget.body().ret(staticValue);
        MethodHandleDesc staticImplementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDefinition.type(),
                staticTarget.name(),
                staticTarget.methodType());
        DynamicCallSiteDesc staticCallSite = DynamicCallSiteDesc.of(
                bootstrap,
                "apply",
                MethodTypeDesc.of(classDesc(Function.class)),
                MethodTypeDesc.of(CD_Object, CD_Object),
                staticImplementation,
                MethodTypeDesc.of(classDesc(Long.class), classDesc(Long.class)));
        MethodDefinition getStatic = classDefinition.method("getStatic", Function.class).access(PUBLIC);
        getStatic.body().ret(invokeDynamic(staticCallSite));

        Parameter primitiveValue = arg("value", int.class);
        MethodDefinition primitiveTarget = classDefinition.method("primitiveLambda", int.class, primitiveValue).access(PRIVATE, STATIC);
        primitiveTarget.body().ret(primitiveValue);
        MethodHandleDesc primitiveImplementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDefinition.type(),
                primitiveTarget.name(),
                primitiveTarget.methodType());
        DynamicCallSiteDesc primitiveCallSite = DynamicCallSiteDesc.of(
                bootstrap,
                "applyAsInt",
                MethodTypeDesc.of(classDesc(IntUnaryOperator.class)),
                MethodTypeDesc.of(CD_int, CD_int),
                primitiveImplementation,
                MethodTypeDesc.of(CD_int, CD_int));
        MethodDefinition getPrimitive = classDefinition.method("getPrimitive", IntUnaryOperator.class).access(PUBLIC);
        getPrimitive.body().ret(invokeDynamic(primitiveCallSite));

        classDefinition.constructor().access(PUBLIC).body().invokeSuperConstructor().ret();

        Class<?> generated = HiddenClassDefiner.builder(MethodHandles.lookup()).build().defineClass(classDefinition.build()).lookupClass();
        Object instance = generated.getConstructor().newInstance();
        @SuppressWarnings("unchecked")
        Function<Long, Long> function = (Function<Long, Long>) generated.getMethod("get").invoke(instance);
        assertThat(function.apply(42L)).isEqualTo(42L);
        assertThat(function.getClass().isHidden()).isTrue();
        assertThat(function.getClass().getDeclaredFields())
                .extracting(Field::getType)
                .containsExactly(Object.class, String.class);
        @SuppressWarnings("unchecked")
        Function<Long, Long> staticFunction = (Function<Long, Long>) generated.getMethod("getStatic").invoke(instance);
        assertThat(staticFunction.apply(99L)).isEqualTo(99L);
        assertThat(staticFunction.getClass().isHidden()).isTrue();
        assertThat(staticFunction.getClass().getDeclaredFields()).isEmpty();
        IntUnaryOperator primitiveFunction = (IntUnaryOperator) generated.getMethod("getPrimitive").invoke(instance);
        assertThat(primitiveFunction.applyAsInt(123)).isEqualTo(123);
        assertThat(primitiveFunction.getClass().isHidden()).isTrue();
        assertThat(primitiveFunction.getClass().getDeclaredFields()).isEmpty();
    }

    @Test
    void testHiddenGeneratedLambdaInterfaceIsRejected()
            throws Exception
    {
        for (boolean alternateMetafactory : List.of(false, true)) {
            ClassDefinition definition = ClassDefinition.defineInterface(generatedClass("HiddenLambdaInterface" + alternateMetafactory))
                    .access(PUBLIC);
            Parameter value = arg("value", int.class);
            definition.method("apply", int.class, value).access(PUBLIC, ABSTRACT);

            Parameter implementationValue = arg("value", int.class);
            MethodDefinition implementation = definition.method("implementation", int.class, implementationValue).access(PUBLIC, STATIC);
            implementation.body().ret(implementationValue.add(constantInt(1)));
            MethodHandleDesc implementationHandle = MethodHandleDesc.ofMethod(
                    DirectMethodHandleDesc.Kind.INTERFACE_STATIC,
                    definition.type(),
                    implementation.name(),
                    implementation.methodType());
            DynamicCallSiteDesc callSite = lambdaCallSite(
                    alternateMetafactory,
                    "apply",
                    MethodTypeDesc.of(definition.type()),
                    MethodTypeDesc.of(CD_int, CD_int),
                    implementationHandle,
                    MethodTypeDesc.of(CD_int, CD_int),
                    0);
            MethodDefinition factory = definition.method("factory", Object.class).access(PUBLIC, STATIC);
            factory.body().ret(invokeDynamic(callSite));
            ClassModel model = definition.build();

            Class<?> nominal = StandardClassDefiner.builder(getClass().getClassLoader()).build().defineClass(model);
            Object lambda = nominal.getMethod("factory").invoke(null);
            assertThat(nominal.getMethod("apply", int.class).invoke(lambda, 41)).isEqualTo(42);

            HiddenClassDefiner hiddenDefiner = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
            assertThatThrownBy(() -> ClassCompiler.forTarget(hiddenDefiner.compilationTarget()).compileClass(model))
                    .isInstanceOf(CompilationException.class)
                    .hasMessageContaining("generated hidden type")
                    .hasMessageContaining("functional interface")
                    .hasMessageContaining("nominal interface");
            assertThatThrownBy(() -> ClassCompiler.forTarget(hiddenDefiner.compilationTarget()).compileUnit(model))
                    .isInstanceOf(CompilationException.class)
                    .hasMessageContaining("generated hidden type")
                    .hasMessageContaining("functional interface")
                    .hasMessageContaining("nominal interface");
        }
    }

    @Test
    void testAltMetafactoryWithHiddenImplementation()
            throws Exception
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("HiddenAltLambda")).access(PUBLIC, FINAL);

        Parameter value = arg("value", Long.class);
        MethodDefinition target = classDefinition.method("lambda", Long.class, value).access(PRIVATE);
        target.body().ret(value);
        MethodHandleDesc implementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.VIRTUAL,
                classDefinition.type(),
                target.name(),
                target.methodType());
        DynamicCallSiteDesc callSite = lambdaCallSite(
                true,
                "apply",
                MethodTypeDesc.of(classDesc(Function.class), classDefinition.type()),
                MethodTypeDesc.of(CD_Object, CD_Object),
                implementation,
                MethodTypeDesc.of(classDesc(Long.class), classDesc(Long.class)),
                0);
        MethodDefinition get = classDefinition.method("get", Function.class).access(PUBLIC);
        get.body().ret(invokeDynamic(callSite, get.thisVariable()));

        Parameter staticValue = arg("value", Long.class);
        MethodDefinition staticTarget = classDefinition.method("staticLambda", Long.class, staticValue).access(PRIVATE, STATIC);
        staticTarget.body().ret(staticValue);
        MethodHandleDesc staticImplementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDefinition.type(),
                staticTarget.name(),
                staticTarget.methodType());
        DynamicCallSiteDesc staticCallSite = lambdaCallSite(
                true,
                "apply",
                MethodTypeDesc.of(classDesc(Function.class)),
                MethodTypeDesc.of(CD_Object, CD_Object),
                staticImplementation,
                MethodTypeDesc.of(classDesc(Long.class), classDesc(Long.class)),
                0);
        classDefinition.method("getStatic", Function.class).access(PUBLIC)
                .body().ret(invokeDynamic(staticCallSite));

        Parameter primitiveValue = arg("value", int.class);
        MethodDefinition primitiveTarget = classDefinition.method("primitiveLambda", int.class, primitiveValue).access(PRIVATE, STATIC);
        primitiveTarget.body().ret(primitiveValue);
        MethodHandleDesc primitiveImplementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDefinition.type(),
                primitiveTarget.name(),
                primitiveTarget.methodType());
        DynamicCallSiteDesc primitiveCallSite = lambdaCallSite(
                true,
                "applyAsInt",
                MethodTypeDesc.of(classDesc(IntUnaryOperator.class)),
                MethodTypeDesc.of(CD_int, CD_int),
                primitiveImplementation,
                MethodTypeDesc.of(CD_int, CD_int),
                0);
        classDefinition.method("getPrimitive", IntUnaryOperator.class).access(PUBLIC)
                .body().ret(invokeDynamic(primitiveCallSite));

        MethodDefinition voidTarget = classDefinition.method("voidLambda", void.class).access(PRIVATE, STATIC);
        voidTarget.body().ret();
        MethodHandleDesc voidImplementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDefinition.type(),
                voidTarget.name(),
                voidTarget.methodType());
        DynamicCallSiteDesc voidCallSite = lambdaCallSite(
                true,
                "run",
                MethodTypeDesc.of(classDesc(Runnable.class)),
                MethodTypeDesc.of(CD_void),
                voidImplementation,
                MethodTypeDesc.of(CD_void),
                0);
        classDefinition.method("getRunnable", Runnable.class).access(PUBLIC)
                .body().ret(invokeDynamic(voidCallSite));

        MethodDefinition constructor = classDefinition.constructor().access(PUBLIC);
        constructor.body().invokeSuperConstructor().ret();
        MethodHandleDesc constructorImplementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.CONSTRUCTOR,
                classDefinition.type(),
                "<init>",
                constructor.methodType());
        DynamicCallSiteDesc constructorCallSite = lambdaCallSite(
                true,
                "get",
                MethodTypeDesc.of(classDesc(Supplier.class)),
                MethodTypeDesc.of(CD_Object),
                constructorImplementation,
                MethodTypeDesc.of(CD_Object),
                0);
        classDefinition.method("getConstructor", Supplier.class).access(PUBLIC, STATIC)
                .body().ret(invokeDynamic(constructorCallSite));

        Class<?> generated = HiddenClassDefiner.builder(MethodHandles.lookup()).build()
                .defineClass(classDefinition.build())
                .lookupClass();
        Object instance = generated.getConstructor().newInstance();
        @SuppressWarnings("unchecked")
        Function<Long, Long> function = (Function<Long, Long>) generated.getMethod("get").invoke(instance);
        assertThat(function.apply(42L)).isEqualTo(42L);
        @SuppressWarnings("unchecked")
        Function<Long, Long> staticFunction = (Function<Long, Long>) generated.getMethod("getStatic").invoke(instance);
        assertThat(staticFunction.apply(99L)).isEqualTo(99L);
        IntUnaryOperator primitiveFunction = (IntUnaryOperator) generated.getMethod("getPrimitive").invoke(instance);
        assertThat(primitiveFunction.applyAsInt(123)).isEqualTo(123);
        ((Runnable) generated.getMethod("getRunnable").invoke(instance)).run();
        @SuppressWarnings("unchecked")
        Supplier<Object> supplier = (Supplier<Object>) generated.getMethod("getConstructor").invoke(null);
        assertThat(supplier.get().getClass()).isSameAs(generated);
    }

    @Test
    void testAltMetafactoryFlagsRequireExplicitSupport()
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("HiddenAltLambdaFlags")).access(PUBLIC, FINAL);
        Parameter value = arg("value", Long.class);
        MethodDefinition target = classDefinition.method("lambda", Long.class, value).access(PRIVATE, STATIC);
        target.body().ret(value);
        MethodHandleDesc implementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDefinition.type(),
                target.name(),
                target.methodType());
        DynamicCallSiteDesc callSite = lambdaCallSite(
                true,
                "apply",
                MethodTypeDesc.of(classDesc(Function.class)),
                MethodTypeDesc.of(CD_Object, CD_Object),
                implementation,
                MethodTypeDesc.of(classDesc(Long.class), classDesc(Long.class)),
                LambdaMetafactory.FLAG_SERIALIZABLE);
        classDefinition.method("get", Function.class).access(PUBLIC, STATIC)
                .body().ret(invokeDynamic(callSite));

        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        assertThatThrownBy(() -> ClassCompiler.forTarget(definer.compilationTarget()).compileClass(classDefinition.build()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("altMetafactory")
                .hasMessageContaining("flags 1");
    }

    @Test
    void testLinkedHiddenLambdaIsCollectable()
            throws Exception
    {
        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        try {
            List<WeakReference<Class<?>>> generatedClasses = new ArrayList<>();
            generatedClasses.addAll(linkedHiddenLambdaClasses(definer, false));
            generatedClasses.addAll(linkedHiddenLambdaClasses(definer, true));

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (generatedClasses.stream().anyMatch(reference -> reference.get() != null) && System.nanoTime() < deadline) {
                System.gc();
                Thread.sleep(Duration.ofMillis(10));
            }
            assertThat(generatedClasses).allSatisfy(reference -> assertThat(reference.get()).isNull());
        }
        finally {
            Reference.reachabilityFence(definer);
        }
    }

    private static List<WeakReference<Class<?>>> linkedHiddenLambdaClasses(HiddenClassDefiner definer, boolean alternateMetafactory)
            throws Exception
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("CollectableHiddenLambda")).access(PUBLIC, FINAL);
        Parameter capture = arg("capture", String.class);
        Parameter value = arg("value", Long.class);
        MethodDefinition target = classDefinition.method("lambda", Long.class, capture, value).access(PRIVATE);
        target.body().ret(value);

        MethodHandleDesc implementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.VIRTUAL,
                classDefinition.type(),
                target.name(),
                target.methodType());
        DynamicCallSiteDesc callSite = lambdaCallSite(
                alternateMetafactory,
                "apply",
                MethodTypeDesc.of(classDesc(Function.class), classDefinition.type(), CD_String),
                MethodTypeDesc.of(CD_Object, CD_Object),
                implementation,
                MethodTypeDesc.of(classDesc(Long.class), classDesc(Long.class)),
                0);

        MethodDefinition get = classDefinition.method("get", Function.class).access(PUBLIC);
        get.body().ret(invokeDynamic(callSite, get.thisVariable(), constantString("capture")));

        Parameter staticValue = arg("value", Long.class);
        MethodDefinition staticTarget = classDefinition.method("staticLambda", Long.class, staticValue).access(PRIVATE, STATIC);
        staticTarget.body().ret(staticValue);
        MethodHandleDesc staticImplementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDefinition.type(),
                staticTarget.name(),
                staticTarget.methodType());
        DynamicCallSiteDesc staticCallSite = lambdaCallSite(
                alternateMetafactory,
                "apply",
                MethodTypeDesc.of(classDesc(Function.class)),
                MethodTypeDesc.of(CD_Object, CD_Object),
                staticImplementation,
                MethodTypeDesc.of(classDesc(Long.class), classDesc(Long.class)),
                0);
        MethodDefinition getStatic = classDefinition.method("getStatic", Function.class).access(PUBLIC);
        getStatic.body().ret(invokeDynamic(staticCallSite));
        classDefinition.constructor().access(PUBLIC).body().invokeSuperConstructor().ret();

        ClassModel model = classDefinition.build();
        Class<?> generated = definer.defineClass(model).lookupClass();
        Object instance = generated.getConstructor().newInstance();
        @SuppressWarnings("unchecked")
        Function<Long, Long> function = (Function<Long, Long>) generated.getMethod("get").invoke(instance);
        assertThat(function.apply(42L)).isEqualTo(42L);
        @SuppressWarnings("unchecked")
        Function<Long, Long> staticFunction = (Function<Long, Long>) generated.getMethod("getStatic").invoke(instance);
        assertThat(staticFunction.apply(99L)).isEqualTo(99L);
        return List.of(new WeakReference<>(generated), new WeakReference<>(function.getClass()));
    }

    @Test
    void testLambdaMetafactoryHiddenCaptureBoundary()
            throws Exception
    {
        assertThat(hiddenCaptureLambda(251, false).apply(42L)).isEqualTo(42L);
        assertThatThrownBy(() -> hiddenCaptureLambda(252, false))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("bad parameter count 256");
        assertThat(hiddenCaptureLambda(251, true).apply(42L)).isEqualTo(42L);
        assertThatThrownBy(() -> hiddenCaptureLambda(252, true))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("bad parameter count 256");
    }

    private static Function<Long, Long> hiddenCaptureLambda(int captureCount, boolean alternateMetafactory)
            throws Exception
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("HiddenLambdaCaptureBoundary" + captureCount)).access(PUBLIC, FINAL);
        ArrayList<Parameter> targetParameters = new ArrayList<>();
        targetParameters.add(arg("session", Object.class));
        for (int index = 0; index < captureCount; index++) {
            targetParameters.add(arg("capture" + index, Object.class));
        }
        Parameter value = arg("value", Long.class);
        targetParameters.add(value);
        MethodDefinition target = classDefinition.method("lambda", Long.class, targetParameters.toArray(Parameter[]::new)).access(PRIVATE);
        target.body().ret(value);

        MethodHandleDesc implementation = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.VIRTUAL,
                classDefinition.type(),
                target.name(),
                target.methodType());

        ArrayList<ClassDesc> capturedTypes = new ArrayList<>();
        capturedTypes.add(classDefinition.type());
        capturedTypes.add(CD_Object);
        ArrayList<BytecodeExpression> capturedValues = new ArrayList<>();
        MethodDefinition get = classDefinition.method("get", Function.class).access(PUBLIC);
        capturedValues.add(get.thisVariable());
        capturedValues.add(constantString("session"));
        for (int index = 0; index < captureCount; index++) {
            capturedTypes.add(CD_Object);
            capturedValues.add(constantString("capture" + index));
        }
        DynamicCallSiteDesc callSite = lambdaCallSite(
                alternateMetafactory,
                "apply",
                MethodTypeDesc.of(classDesc(Function.class), capturedTypes),
                MethodTypeDesc.of(CD_Object, CD_Object),
                implementation,
                MethodTypeDesc.of(classDesc(Long.class), classDesc(Long.class)),
                0);
        get.body().ret(invokeDynamic(callSite, capturedValues.toArray(BytecodeExpression[]::new)));
        classDefinition.constructor().access(PUBLIC).body().invokeSuperConstructor().ret();

        Class<?> generated = HiddenClassDefiner.builder(MethodHandles.lookup()).build().defineClass(classDefinition.build()).lookupClass();
        Object instance = generated.getConstructor().newInstance();
        @SuppressWarnings("unchecked")
        Function<Long, Long> function = (Function<Long, Long>) generated.getMethod("get").invoke(instance);
        return function;
    }

    private static DynamicCallSiteDesc lambdaCallSite(
            boolean alternateMetafactory,
            String methodName,
            MethodTypeDesc invocationType,
            MethodTypeDesc samMethodType,
            MethodHandleDesc implementation,
            MethodTypeDesc instantiatedMethodType,
            int flags)
    {
        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDesc(LambdaMetafactory.class),
                alternateMetafactory ? "altMetafactory" : "metafactory",
                alternateMetafactory
                        ? MethodTypeDesc.of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType, CD_Object.arrayType())
                        : MethodTypeDesc.of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType, CD_MethodType, CD_MethodHandle, CD_MethodType));
        ConstantDesc[] arguments = alternateMetafactory
                ? new ConstantDesc[] {samMethodType, implementation, instantiatedMethodType, flags}
                : new ConstantDesc[] {samMethodType, implementation, instantiatedMethodType};
        return DynamicCallSiteDesc.of(bootstrap, methodName, invocationType, arguments);
    }

    @Test
    void testBoundConstantInStandardAndHiddenClasses()
            throws Throwable
    {
        String boundValue = new String("runtime-bound-value");
        assertThat(boundConstant(boundValue, String.class)).hasToString("bound(String)");
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("BoundConstant")).access(PUBLIC, FINAL);
        MethodDefinition value = classDefinition.method("value", String.class).access(PUBLIC, STATIC);
        value.body().append(boundConstant(boundValue, String.class).ret());
        ClassModel definition = classDefinition.build();

        Class<?> standard = StandardClassDefiner.builder(getClass().getClassLoader())
                .build()
                .defineClass(definition);
        assertThat(standard.getMethod("value").invoke(null)).isSameAs(boundValue);

        MethodHandles.Lookup hidden = HiddenClassDefiner.builder(MethodHandles.lookup())
                .build()
                .defineClass(definition);
        Object hiddenValue = hidden.findStatic(hidden.lookupClass(), "value", MethodType.methodType(String.class)).invoke();
        assertThat(hiddenValue).isSameAs(boundValue);
    }

    @Test
    void testBoundConstantRejectsMismatchedTypesDuringCompilation()
    {
        assertThatThrownBy(() -> boundConstant("wrong", Integer.class))
                .isInstanceOf(ClassCastException.class);

        ClassDefinition definition = ClassDefinition.define(generatedClass("MismatchedBinding")).access(PUBLIC, FINAL);
        definition.method("value", Integer.class).access(PUBLIC, STATIC).body()
                .ret(boundConstant("wrong", ClassDesc.of(Integer.class.getName())));
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();

        assertThatThrownBy(() -> ClassCompiler.forTarget(definer.compilationTarget()).compileClass(definition.build()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("bound constant")
                .hasMessageContaining("java.lang.Integer");
    }

    @Test
    void testBoundConstantChecksTargetClassIdentity()
            throws IOException
    {
        DuplicateClassLoader duplicateClassLoader = new DuplicateClassLoader();
        Class<?> duplicate = duplicateClassLoader.define(classBytes(PublicValue.class));
        assertThat(duplicate).isNotSameAs(PublicValue.class);

        ClassDefinition definition = ClassDefinition.define(generatedClass("MismatchedBindingIdentity")).access(PUBLIC, FINAL);
        definition.method("value", PublicValue.class).access(PUBLIC, STATIC).body()
                .ret(boundConstant(new PublicValue(), ClassDesc.of(PublicValue.class.getName())));
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader())
                .overrideLoader(duplicateClassLoader)
                .build();

        assertThatThrownBy(() -> ClassCompiler.forTarget(definer.compilationTarget()).compileClass(definition.build()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("bound constant")
                .hasMessageContaining("does not resolve to an instance");
    }

    @Test
    void testHiddenClassWithoutRuntimeDataHasNoClassData()
            throws IllegalAccessException
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("NoClassData")).access(PUBLIC, FINAL);
        MethodHandles.Lookup hidden = HiddenClassDefiner.builder(MethodHandles.lookup())
                .build()
                .defineClass(definition.build());

        assertThat(MethodHandles.classData(hidden, "_", Object.class)).isNull();
    }

    @Test
    void testClassDataInStandardAndHiddenClasses()
            throws Throwable
    {
        String data = new String("class-data");
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("ClassData")).access(PUBLIC, FINAL);
        MethodDefinition value = classDefinition.method("value", String.class).access(PUBLIC, STATIC);
        value.body().append(classData(String.class).ret());
        ClassModel definition = classDefinition.build();
        RuntimeData runtimeData = RuntimeData.ofClassData(data);

        Class<?> standard = StandardClassDefiner.builder(getClass().getClassLoader())
                .runtimeData(runtimeData)
                .build()
                .defineClass(definition);
        assertThat(standard.getMethod("value").invoke(null)).isSameAs(data);

        MethodHandles.Lookup hidden = HiddenClassDefiner.builder(MethodHandles.lookup())
                .runtimeData(runtimeData)
                .build()
                .defineClass(definition);
        assertThat(hidden.findStatic(hidden.lookupClass(), "value", MethodType.methodType(String.class)).invoke()).isSameAs(data);
    }

    @Test
    void testRuntimeDataConflictsUseValueIdentity()
            throws Throwable
    {
        String compiledData = new String("equal-data");
        String configuredData = new String("equal-data");
        assertThat(compiledData).isEqualTo(configuredData).isNotSameAs(configuredData);

        ClassDefinition definition = ClassDefinition.define(generatedClass("RuntimeDataIdentity")).access(PUBLIC, FINAL);
        definition.method("value", String.class).access(PUBLIC, STATIC).body().ret(classData(String.class));
        ClassModel model = definition.build();

        StandardClassDefiner standard = StandardClassDefiner.builder(getClass().getClassLoader())
                .runtimeData(RuntimeData.ofClassData(configuredData))
                .build();
        ClassCompiler standardCompiler = ClassCompiler.forTarget(standard.compilationTarget()).classData(compiledData);
        assertThatThrownBy(() -> standard.defineCompiledClass(standardCompiler.compileClass(model)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Runtime data is incompatible");
        assertThatThrownBy(() -> standard.defineUnit(standardCompiler.compileUnit(model)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Runtime data is incompatible");
        assertDefinerStillUsable(standard);

        HiddenClassDefiner hidden = HiddenClassDefiner.builder(MethodHandles.lookup())
                .runtimeData(RuntimeData.ofClassData(configuredData))
                .build();
        ClassCompiler hiddenCompiler = ClassCompiler.forTarget(hidden.compilationTarget()).classData(compiledData);
        assertThatThrownBy(() -> hidden.defineCompiledClass(hiddenCompiler.compileClass(model)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Runtime data is incompatible");
        assertThatThrownBy(() -> hidden.defineUnit(hiddenCompiler.compileUnit(model)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Runtime data is incompatible");
        assertDefinerStillUsable(hidden);

        StandardClassDefiner matchingStandard = StandardClassDefiner.builder(getClass().getClassLoader())
                .runtimeData(RuntimeData.ofClassData(compiledData))
                .build();
        Class<?> standardClass = matchingStandard.defineCompiledClass(
                ClassCompiler.forTarget(matchingStandard.compilationTarget()).classData(compiledData).compileClass(model));
        assertThat(standardClass.getMethod("value").invoke(null)).isSameAs(compiledData);

        HiddenClassDefiner matchingHidden = HiddenClassDefiner.builder(MethodHandles.lookup())
                .runtimeData(RuntimeData.ofClassData(compiledData))
                .build();
        MethodHandles.Lookup hiddenClass = matchingHidden.defineCompiledClass(
                ClassCompiler.forTarget(matchingHidden.compilationTarget()).classData(compiledData).compileClass(model));
        assertThat(hiddenClass.findStatic(hiddenClass.lookupClass(), "value", MethodType.methodType(String.class)).invoke())
                .isSameAs(compiledData);

        String compiledBinding = new String("equal-binding");
        String configuredBinding = new String("equal-binding");
        assertThatThrownBy(() -> RuntimeData.merge(
                new RuntimeData(Optional.empty(), List.of(configuredBinding)),
                new RuntimeData(Optional.empty(), List.of(compiledBinding))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Runtime data is incompatible");
        assertThat(RuntimeData.merge(
                new RuntimeData(Optional.empty(), List.of(compiledBinding)),
                new RuntimeData(Optional.empty(), List.of(compiledBinding))).binding(0))
                .isSameAs(compiledBinding);
    }

    private static void assertDefinerStillUsable(StandardClassDefiner definer)
            throws ReflectiveOperationException
    {
        ClassDefinition unrelated = ClassDefinition.define(generatedClass("AfterRuntimeDataConflict")).access(PUBLIC, FINAL);
        unrelated.method("value", int.class).access(PUBLIC, STATIC).body().ret(constantInt(7));
        assertThat(definer.defineClass(unrelated.build()).getMethod("value").invoke(null)).isEqualTo(7);
    }

    private static void assertDefinerStillUsable(HiddenClassDefiner definer)
            throws Throwable
    {
        ClassDefinition unrelated = ClassDefinition.define(generatedClass("AfterRuntimeDataConflict")).access(PUBLIC, FINAL);
        unrelated.method("value", int.class).access(PUBLIC, STATIC).body().ret(constantInt(7));
        MethodHandles.Lookup defined = definer.defineClass(unrelated.build());
        assertThat(defined.findStatic(defined.lookupClass(), "value", MethodType.methodType(int.class)).invoke()).isEqualTo(7);
    }

    @Test
    void testClassDataRejectsMismatchedTypeDuringCompilation()
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("MismatchedClassData")).access(PUBLIC, FINAL);
        definition.method("value", Integer.class).access(PUBLIC, STATIC).body().ret(classData(Integer.class));
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();

        assertThatThrownBy(() -> ClassCompiler.forTarget(definer.compilationTarget())
                .classData("wrong")
                .compileClass(definition.build()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("class data")
                .hasMessageContaining("java.lang.Integer");
    }

    @Test
    void testPrecompiledClassDataIsValidatedBeforeDefinition()
            throws Throwable
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("PrecompiledClassData")).access(PUBLIC, FINAL);
        definition.method("value", Integer.class).access(PUBLIC, STATIC).body().ret(classData(Integer.class));
        ClassModel model = definition.build();

        StandardClassDefiner standard = StandardClassDefiner.builder(getClass().getClassLoader())
                .runtimeData(RuntimeData.ofClassData("wrong"))
                .build();
        CompiledClass standardClass = ClassCompiler.forTarget(standard.compilationTarget()).compileClass(model);
        assertThatThrownBy(() -> standard.defineCompiledClass(standardClass))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("runtime class data")
                .hasMessageContaining("java.lang.Integer");

        StandardClassDefiner standardUnit = StandardClassDefiner.builder(getClass().getClassLoader())
                .runtimeData(RuntimeData.ofClassData("wrong"))
                .build();
        CompiledUnit unit = ClassCompiler.forTarget(standardUnit.compilationTarget()).compileUnit(model);
        assertThatThrownBy(() -> standardUnit.defineUnit(unit))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("runtime class data")
                .hasMessageContaining("java.lang.Integer");

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        HiddenClassDefiner hidden = HiddenClassDefiner.builder(lookup)
                .runtimeData(RuntimeData.ofClassData("wrong"))
                .build();
        CompiledClass hiddenClass = ClassCompiler.forTarget(hidden.compilationTarget()).compileClass(model);
        assertThatThrownBy(() -> hidden.defineCompiledClass(hiddenClass))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("runtime class data")
                .hasMessageContaining("java.lang.Integer");

        HiddenClassDefiner hiddenUnit = HiddenClassDefiner.builder(lookup)
                .runtimeData(RuntimeData.ofClassData("wrong"))
                .build();
        CompiledUnit hiddenCompiledUnit = ClassCompiler.forTarget(hiddenUnit.compilationTarget()).compileUnit(model);
        assertThatThrownBy(() -> hiddenUnit.defineUnit(hiddenCompiledUnit))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("runtime class data")
                .hasMessageContaining("java.lang.Integer");

        StandardClassDefiner missing = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledClass missingClass = ClassCompiler.forTarget(missing.compilationTarget()).compileClass(model);
        assertThatThrownBy(() -> missing.defineCompiledClass(missingClass))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Class data is required");

        Integer expected = 37;
        StandardClassDefiner valid = StandardClassDefiner.builder(getClass().getClassLoader())
                .runtimeData(RuntimeData.ofClassData(expected))
                .build();
        Class<?> generated = valid.defineCompiledClass(ClassCompiler.forTarget(valid.compilationTarget()).compileClass(model));
        assertThat(generated.getMethod("value").invoke(null)).isSameAs(expected);
    }

    @Test
    void testRuntimeBindingsRejectGeneratedDeclaredTypes()
    {
        ClassDefinition self = ClassDefinition.define(generatedClass("SelfTypedBinding")).access(PUBLIC, FINAL);
        self.method("value", self.type()).access(PUBLIC, STATIC).body().ret(boundConstant(new Object(), self.type()));
        StandardClassDefiner selfDefiner = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        assertThatThrownBy(() -> ClassCompiler.forTarget(selfDefiner.compilationTarget()).compileClass(self.build()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("cannot be an instance of generated type");

        ClassDefinition classDataDefinition = ClassDefinition.define(generatedClass("SelfTypedClassData")).access(PUBLIC, FINAL);
        classDataDefinition.method("value", classDataDefinition.type()).access(PUBLIC, STATIC).body().ret(classData(classDataDefinition.type()));
        StandardClassDefiner classDataDefiner = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        assertThatThrownBy(() -> ClassCompiler.forTarget(classDataDefiner.compilationTarget())
                .classData(new Object())
                .compileClass(classDataDefinition.build()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("cannot be an instance of generated type");

        ClassDefinition sibling = ClassDefinition.define(generatedClass("SiblingBindingTarget")).access(PUBLIC, FINAL);
        ClassDefinition caller = ClassDefinition.define(generatedClass("SiblingBindingCaller")).access(PUBLIC, FINAL);
        caller.method("value", sibling.type()).access(PUBLIC, STATIC).body().ret(boundConstant(new Object(), sibling.type()));
        StandardClassDefiner bundleDefiner = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        assertThatThrownBy(() -> ClassCompiler.forTarget(bundleDefiner.compilationTarget())
                .compileClassBundle(List.of(caller.build(), sibling.build())))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("cannot be an instance of generated type");
    }

    @Test
    void testClassDataAndBindingsShareRuntimeData()
            throws Throwable
    {
        Object data = new Object();
        Object binding = new Object();
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("CombinedData")).access(PUBLIC, FINAL);
        MethodDefinition value = classDefinition.method("value", Object[].class).access(PUBLIC, STATIC);
        value.body().append(newArray(Object[].class, List.of(
                classData(Object.class),
                boundConstant(binding, Object.class))).ret());

        Class<?> generated = StandardClassDefiner.builder(getClass().getClassLoader())
                .runtimeData(RuntimeData.ofClassData(data))
                .build()
                .defineClass(classDefinition.build());
        assertThat((Object[]) generated.getMethod("value").invoke(null)).containsExactly(data, binding);
    }

    @Test
    void testClassBundleSharesBindingIndexes()
            throws Exception
    {
        Object firstBinding = new Object();
        Object secondBinding = new Object();

        ClassDefinition firstDefinition = ClassDefinition.define(generatedClass("FirstBinding")).access(PUBLIC, FINAL);
        MethodDefinition firstValue = firstDefinition.method("value", Object.class).access(PUBLIC, STATIC);
        firstValue.body().ret(boundConstant(firstBinding, Object.class));
        ClassModel first = firstDefinition.build();

        ClassDefinition secondDefinition = ClassDefinition.define(generatedClass("SecondBinding")).access(PUBLIC, FINAL);
        MethodDefinition secondValue = secondDefinition.method("values", Object[].class).access(PUBLIC, STATIC);
        secondValue.body().ret(newArray(Object[].class, List.of(
                boundConstant(secondBinding, Object.class),
                invokeStatic(firstValue))));
        ClassModel second = secondDefinition.build();

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        DefinedClasses classes = definer.defineClasses(List.of(first, second));
        assertThat(classes.types()).containsExactlyInAnyOrder(first.type(), second.type());
        assertThat((Object[]) classes.definedClass(second).getMethod("values").invoke(null))
                .containsExactly(secondBinding, firstBinding);

        ClassDefinition laterDefinition = ClassDefinition.define(generatedClass("LaterUnbound")).access(PUBLIC, FINAL);
        MethodDefinition laterValue = laterDefinition.method("value", CD_int).access(PUBLIC, STATIC);
        laterValue.body().ret(constantInt(7));
        assertThat(definer.defineClass(laterDefinition.build()).getMethod("value").invoke(null)).isEqualTo(7);
    }

    @Test
    void testClassBundlePreservesInitializationOrder()
            throws Exception
    {
        AtomicLong sequence = new AtomicLong();
        List<ClassModel> definitions = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            ClassDefinition definition = ClassDefinition.define(generatedClass("Ordered" + index)).access(PUBLIC, FINAL);
            FieldDefinition order = definition.field("order", long.class).access(PUBLIC, STATIC).build();
            definition.classInitializer().body().append(setStatic(
                    order,
                    boundConstant(sequence, AtomicLong.class).invoke("getAndIncrement", long.class)));
            definitions.add(definition.build());
        }

        DefinedClasses classes = StandardClassDefiner.builder(getClass().getClassLoader())
                .build()
                .defineClasses(definitions);
        for (int index = 0; index < definitions.size(); index++) {
            assertThat(classes.definedClass(definitions.get(index)).getField("order").getLong(null)).isEqualTo(index);
        }
    }

    @Test
    void testCompiledBundleReturnsDefensiveClassfileCopies()
    {
        ClassDefinition definition = ClassDefinition.define(generatedClass("CompiledBundle")).access(PUBLIC, FINAL);
        definition.method("value", int.class).access(PUBLIC, STATIC).body().ret(constantInt(7));
        ClassModel model = definition.build();

        CompiledClassBundle bundle = ClassCompiler.forTarget(CompilationTarget.forClassLoader(getClass().getClassLoader()))
                .compileClassBundle(List.of(model));
        byte[] first = bundle.classfile(model.type());
        byte original = first[0];
        first[0] = (byte) ~first[0];

        assertThat(bundle.classfile(model.type())[0]).isEqualTo(original);
        assertThatThrownBy(() -> bundle.classfile(generatedClass("MissingCompiled")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Class was not compiled:");
    }

    @Test
    void testCompiledArtifactsRequireTheirCompilationTarget()
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("TargetIdentity")).access(PUBLIC, FINAL);
        classDefinition.method("value", int.class).access(PUBLIC, STATIC).body().ret(constantInt(7));
        ClassModel definition = classDefinition.build();

        StandardClassDefiner compilationDefiner = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        ClassCompiler compiler = ClassCompiler.forTarget(compilationDefiner.compilationTarget());
        CompiledClass compiledClass = compiler.compileClass(definition);
        CompiledClassBundle compiledBundle = compiler.compileClassBundle(List.of(definition));
        CompiledUnit compiledUnit = compiler.compileUnit(definition);

        StandardClassDefiner otherDefiner = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        assertThatThrownBy(() -> otherDefiner.defineCompiledClass(compiledClass))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compiled for")
                .hasMessageContaining("cannot be defined by");
        assertThatThrownBy(() -> otherDefiner.defineCompiledClasses(compiledBundle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compiled for")
                .hasMessageContaining("cannot be defined by");
        assertThatThrownBy(() -> otherDefiner.defineUnit(compiledUnit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compiled for")
                .hasMessageContaining("cannot be defined by");
    }

    @Test
    void testHiddenArtifactsRequireCompatibleClassOptions()
            throws Throwable
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        HiddenClassDefiner nestmateDefiner = HiddenClassDefiner.builder(lookup).options(NESTMATE).build();
        ClassDefinition definition = ClassDefinition.define(generatedClass("NestmateTarget")).access(PUBLIC, FINAL);
        definition.method("value", Object.class).access(PUBLIC, STATIC).body()
                .ret(invokeStatic(TestRuntimeDefiners.class, "publicIdentity", PublicValue.class, constantNull(PublicValue.class)));
        CompiledClass compiledClass = ClassCompiler.forTarget(nestmateDefiner.compilationTarget()).compileClass(definition.build());

        MethodHandles.Lookup defined = nestmateDefiner.defineCompiledClass(compiledClass);
        assertThat(defined.findStatic(defined.lookupClass(), "value", MethodType.methodType(Object.class)).invoke()).isNull();

        HiddenClassDefiner plainDefiner = HiddenClassDefiner.builder(lookup).build();
        assertThatThrownBy(() -> plainDefiner.defineCompiledClass(compiledClass))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compiled for")
                .hasMessageContaining("cannot be defined by");
    }

    @Test
    void testRejectedBundleDoesNotPoisonDefiner()
            throws Exception
    {
        ClassDefinition freshDefinition = ClassDefinition.define(generatedClass("FreshAfterRejectedBundle")).access(PUBLIC, FINAL);
        freshDefinition.method("value", String.class).access(PUBLIC, STATIC).body().ret(boundConstant("bundle", String.class));
        ClassModel fresh = freshDefinition.build();
        ClassDefinition duplicateDefinition = ClassDefinition.define(generatedClass("DuplicateInRejectedBundle")).access(PUBLIC, FINAL);
        duplicateDefinition.method("value", int.class).access(PUBLIC, STATIC).body().ret(constantInt(7));
        ClassModel duplicate = duplicateDefinition.build();

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        ClassCompiler compiler = ClassCompiler.forTarget(definer.compilationTarget());
        CompiledClassBundle rejectedBundle = compiler.compileClassBundle(List.of(fresh, duplicate));
        definer.defineCompiledClass(compiler.compileClass(duplicate));

        assertThatThrownBy(() -> definer.defineCompiledClasses(rejectedBundle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already defined or pending");

        ClassDefinition replacementDefinition = ClassDefinition.define(fresh.type()).access(PUBLIC, FINAL);
        replacementDefinition.method("value", String.class).access(PUBLIC, STATIC).body().ret(boundConstant("replacement", String.class));
        Class<?> replacement = definer.defineCompiledClass(compiler.compileClass(replacementDefinition.build()));
        assertThat(replacement.getMethod("value").invoke(null)).isEqualTo("replacement");
    }

    @Test
    void testDefinitionFailureReleasesClassAndRuntimeDataReservations()
            throws Exception
    {
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        ClassCompiler compiler = ClassCompiler.forTarget(definer.compilationTarget());

        ClassDesc directType = generatedClass("RetryAfterDefinitionFailure");
        ClassDefinition invalidDirect = ClassDefinition.define(directType).superClass(String.class).access(PUBLIC);
        invalidDirect.method("value", String.class).access(PUBLIC, STATIC).body().ret(boundConstant("stale", String.class));
        assertThatThrownBy(() -> definer.defineCompiledClass(compiler.compileClass(invalidDirect.build())))
                .isInstanceOf(IncompatibleClassChangeError.class);

        ClassDefinition replacementDirect = ClassDefinition.define(directType).access(PUBLIC, FINAL);
        replacementDirect.method("value", String.class).access(PUBLIC, STATIC).body().ret(boundConstant("replacement", String.class));
        Class<?> directClass = definer.defineCompiledClass(compiler.compileClass(replacementDirect.build()));
        assertThat(directClass.getMethod("value").invoke(null)).isEqualTo("replacement");

        ClassDesc unitType = generatedClass("RetryUnitAfterDefinitionFailure");
        ClassDefinition invalidUnit = ClassDefinition.define(unitType).superClass(String.class).access(PUBLIC);
        invalidUnit.method("value", String.class).access(PUBLIC, STATIC).body().ret(boundConstant("stale-unit", String.class));
        assertThatThrownBy(() -> definer.defineUnit(compiler.compileUnit(invalidUnit.build())))
                .isInstanceOf(IncompatibleClassChangeError.class);

        ClassDefinition replacementUnit = ClassDefinition.define(unitType).access(PUBLIC, FINAL);
        replacementUnit.method("value", String.class).access(PUBLIC, STATIC).body().ret(boundConstant("replacement-unit", String.class));
        Class<?> unitClass = definer.defineUnit(compiler.compileUnit(replacementUnit.build())).primaryClass();
        assertThat(unitClass.getMethod("value").invoke(null)).isEqualTo("replacement-unit");

        ClassDefinition committedDefinition = ClassDefinition.define(generatedClass("CommittedBeforeDefinitionFailure")).access(PUBLIC, FINAL);
        committedDefinition.method("value", String.class).access(PUBLIC, STATIC).body().ret(boundConstant("committed", String.class));
        ClassModel committed = committedDefinition.build();
        ClassDesc failedBundleType = generatedClass("RetryBundleAfterDefinitionFailure");
        ClassDefinition invalidBundle = ClassDefinition.define(failedBundleType).superClass(String.class).access(PUBLIC);
        invalidBundle.method("value", String.class).access(PUBLIC, STATIC).body().ret(boundConstant("stale-bundle", String.class));
        ClassDesc untouchedBundleType = generatedClass("UntouchedAfterDefinitionFailure");
        ClassDefinition untouchedBundle = ClassDefinition.define(untouchedBundleType).access(PUBLIC, FINAL);
        untouchedBundle.method("value", String.class).access(PUBLIC, STATIC).body().ret(boundConstant("stale-untouched", String.class));
        assertThatThrownBy(() -> definer.defineCompiledClasses(compiler.compileClassBundle(List.of(committed, invalidBundle.build(), untouchedBundle.build()))))
                .isInstanceOf(IncompatibleClassChangeError.class);

        assertThatThrownBy(() -> definer.defineCompiledClass(compiler.compileClass(committed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already defined or pending");
        ClassDefinition replacementBundle = ClassDefinition.define(failedBundleType).access(PUBLIC, FINAL);
        replacementBundle.method("value", String.class).access(PUBLIC, STATIC).body().ret(boundConstant("replacement-bundle", String.class));
        Class<?> bundleClass = definer.defineCompiledClass(compiler.compileClass(replacementBundle.build()));
        assertThat(bundleClass.getMethod("value").invoke(null)).isEqualTo("replacement-bundle");
        ClassDefinition replacementUntouched = ClassDefinition.define(untouchedBundleType).access(PUBLIC, FINAL);
        replacementUntouched.method("value", String.class).access(PUBLIC, STATIC).body().ret(boundConstant("replacement-untouched", String.class));
        Class<?> untouchedClass = definer.defineCompiledClass(compiler.compileClass(replacementUntouched.build()));
        assertThat(untouchedClass.getMethod("value").invoke(null)).isEqualTo("replacement-untouched");
    }

    @Test
    void testInitializationFailureReleasesOnlyUntouchedReservations()
            throws Exception
    {
        ClassDefinition untouchedDefinition = ClassDefinition.define(generatedClass("UntouchedAfterFailure")).access(PUBLIC, FINAL);
        untouchedDefinition.method("value", int.class).access(PUBLIC, STATIC).body().ret(constantInt(7));
        ClassModel untouched = untouchedDefinition.build();

        ClassDefinition failingDefinition = ClassDefinition.define(generatedClass("FailingInitializer")).access(PUBLIC, FINAL);
        failingDefinition.classInitializer().body().append(BytecodeExpressions.newInstance(IllegalStateException.class).throwObject());
        ClassModel failing = failingDefinition.build();

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        assertThatThrownBy(() -> definer.defineClasses(List.of(failing, untouched)))
                .isInstanceOf(ExceptionInInitializerError.class);

        ClassDefinition replacementDefinition = ClassDefinition.define(untouched.type()).access(PUBLIC, FINAL);
        replacementDefinition.method("value", int.class).access(PUBLIC, STATIC).body().ret(constantInt(9));
        Class<?> replacement = definer.defineClass(replacementDefinition.build());
        assertThat(replacement.getMethod("value").invoke(null)).isEqualTo(9);

        ClassDefinition dependencyDefinition = ClassDefinition.define(generatedClass("LoadedDuringFailure")).access(PUBLIC, FINAL);
        dependencyDefinition.method("value", int.class).access(PUBLIC, STATIC).body().ret(constantInt(11));
        ClassModel dependency = dependencyDefinition.build();
        ClassDefinition dependentFailureDefinition = ClassDefinition.define(generatedClass("DependentFailure")).access(PUBLIC, FINAL);
        dependentFailureDefinition.classInitializer().body()
                .append(invokeStatic(dependency.type(), "value", CD_int).pop())
                .append(BytecodeExpressions.newInstance(IllegalStateException.class).throwObject());
        ClassModel dependentFailure = dependentFailureDefinition.build();

        assertThatThrownBy(() -> definer.defineClasses(List.of(dependentFailure, dependency)))
                .isInstanceOf(ExceptionInInitializerError.class);
        ClassDefinition duplicateDependency = ClassDefinition.define(dependency.type()).access(PUBLIC, FINAL);
        duplicateDependency.method("value", int.class).access(PUBLIC, STATIC).body().ret(constantInt(12));
        assertThatThrownBy(() -> definer.defineClass(duplicateDependency.build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already defined or pending");
    }

    @Test
    void testHiddenCompilationRequiresLookupPackage()
    {
        ClassDesc type = ClassDesc.of("other.package.GeneratedWrongPackage" + NEXT_CLASS_ID.incrementAndGet());
        ClassDefinition definition = ClassDefinition.define(type).access(PUBLIC, FINAL);
        definition.method("value", int.class).access(PUBLIC, STATIC).body().ret(constantInt(7));
        CompilationTarget target = HiddenClassDefiner.builder(MethodHandles.lookup()).build().compilationTarget();

        assertThatThrownBy(() -> ClassCompiler.forTarget(target).compileClass(definition.build()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("must be in lookup package")
                .hasMessageContaining(TestRuntimeDefiners.class.getPackageName());
    }

    @Test
    void testHiddenCompilationRequiresFullPrivilegeLookup()
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup().dropLookupMode(MethodHandles.Lookup.PRIVATE);

        assertThatThrownBy(() -> CompilationTarget.forLookup(lookup))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("full privilege access");
    }

    @Test
    void testStandardDefinerCanBeReusedConcurrently()
            throws InterruptedException, ExecutionException, ReflectiveOperationException
    {
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        List<Future<Class<?>>> definitions = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 100; index++) {
                ClassDefinition definition = ClassDefinition.define(generatedClass("Concurrent" + index)).access(PUBLIC, FINAL);
                definition.method("value", Integer.class)
                        .access(PUBLIC, STATIC)
                        .body()
                        .ret(boundConstant(index, Integer.class));
                ClassModel model = definition.build();
                definitions.add(executor.submit(() -> definer.defineClass(model)));
            }
        }

        for (int index = 0; index < definitions.size(); index++) {
            assertThat(definitions.get(index).get().getMethod("value").invoke(null)).isEqualTo(index);
        }
    }

    @Test
    void testCompiledArtifactsCanBeDefinedConcurrently()
            throws InterruptedException, ExecutionException
    {
        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        ClassCompiler compiler = ClassCompiler.forTarget(definer.compilationTarget());
        List<Future<Object>> definitions = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 60; index++) {
                int expected = index;
                ClassDefinition definition = ClassDefinition.define(generatedClass("ConcurrentArtifact" + index)).access(PUBLIC, FINAL);
                definition.method("value", Integer.class)
                        .access(PUBLIC, STATIC)
                        .body()
                        .ret(boundConstant(expected, Integer.class));
                ClassModel model = definition.build();

                switch (index % 3) {
                    case 0 -> {
                        CompiledClass compiledClass = compiler.compileClass(model);
                        definitions.add(executor.submit(() -> definer.defineCompiledClass(compiledClass)
                                .getMethod("value")
                                .invoke(null)));
                    }
                    case 1 -> {
                        ClassDefinition companionDefinition = ClassDefinition.define(generatedClass("ConcurrentBundleCompanion" + index)).access(PUBLIC, FINAL);
                        companionDefinition.method("value", Integer.class)
                                .access(PUBLIC, STATIC)
                                .body()
                                .ret(boundConstant(-expected, Integer.class));
                        ClassModel companion = companionDefinition.build();
                        CompiledClassBundle compiledBundle = compiler.compileClassBundle(List.of(model, companion));
                        definitions.add(executor.submit(() -> {
                            DefinedClasses classes = definer.defineCompiledClasses(compiledBundle);
                            assertThat(classes.definedClass(companion).getMethod("value").invoke(null)).isEqualTo(-expected);
                            return classes.definedClass(model).getMethod("value").invoke(null);
                        }));
                    }
                    case 2 -> {
                        CompiledUnit compiledUnit = compiler.compileUnit(model);
                        definitions.add(executor.submit(() -> definer.defineUnit(compiledUnit)
                                .primaryClass()
                                .getMethod("value")
                                .invoke(null)));
                    }
                    default -> throw new AssertionError();
                }
            }
        }

        for (int index = 0; index < definitions.size(); index++) {
            assertThat(definitions.get(index).get()).isEqualTo(index);
        }
    }

    @Test
    void testGeneratedBundleProvidesHierarchyForStackMaps()
            throws Exception
    {
        ClassDefinition baseDefinition = ClassDefinition.define(generatedClass("MergeBase")).access(PUBLIC);
        baseDefinition.defaultConstructor().access(PUBLIC);
        ClassModel base = baseDefinition.build();

        ClassDefinition leftDefinition = ClassDefinition.define(generatedClass("MergeLeft")).access(PUBLIC).superClass(base.type());
        leftDefinition.defaultConstructor().access(PUBLIC);
        ClassModel left = leftDefinition.build();

        ClassDefinition rightDefinition = ClassDefinition.define(generatedClass("MergeRight")).access(PUBLIC).superClass(base.type());
        rightDefinition.defaultConstructor().access(PUBLIC);
        ClassModel right = rightDefinition.build();

        Parameter condition = arg("condition", boolean.class);
        ClassDefinition chooserDefinition = ClassDefinition.define(generatedClass("MergeChooser")).access(PUBLIC);
        MethodDefinition choose = chooserDefinition.method("choose", base.type(), condition).access(PUBLIC, STATIC);
        Variable result = choose.body().declare(base.type(), "result");
        choose.body()
                .append(IfStatement.builder()
                        .condition(condition)
                        .then(result.set(BytecodeExpressions.newInstance(left.type(), MethodTypeDesc.of(CD_void))))
                        .otherwise(result.set(BytecodeExpressions.newInstance(right.type(), MethodTypeDesc.of(CD_void))))
                        .build())
                .ret(result);
        ClassModel chooser = chooserDefinition.build();

        DefinedClasses classes = StandardClassDefiner.builder(getClass().getClassLoader())
                .build()
                .defineClasses(List.of(chooser, right, base, left));

        Object leftValue = classes.definedClass(chooser).getMethod("choose", boolean.class).invoke(null, true);
        Object rightValue = classes.definedClass(chooser).getMethod("choose", boolean.class).invoke(null, false);
        assertThat(leftValue.getClass()).isSameAs(classes.definedClass(left));
        assertThat(rightValue.getClass()).isSameAs(classes.definedClass(right));
    }

    @Test
    void testGeneratedBundleEnforcesRuntimePackageVisibility()
    {
        long id = NEXT_CLASS_ID.incrementAndGet();
        ClassDefinition baseDefinition = ClassDefinition.define(ClassDesc.of("generated.base.PackagePrivateBase" + id));
        baseDefinition.defaultConstructor().access(PUBLIC);

        ClassDefinition childDefinition = ClassDefinition.define(ClassDesc.of("generated.child.PublicChild" + id))
                .access(PUBLIC)
                .superClass(baseDefinition.type());
        childDefinition.defaultConstructor().access(PUBLIC);

        assertThatThrownBy(() -> StandardClassDefiner.builder(getClass().getClassLoader())
                .build()
                .defineClasses(List.of(baseDefinition.build(), childDefinition.build())))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("super class")
                .hasMessageContaining("is package-private and declared in a different package");
    }

    @Test
    void testBoundMethodHandleIsAdaptedForDefinitionTarget()
            throws Throwable
    {
        MethodHandle identity = MethodHandles.lookup().findStatic(
                TestRuntimeDefiners.class,
                "hiddenIdentity",
                MethodType.methodType(HiddenValue.class, HiddenValue.class));
        Parameter value = arg("value", Object.class);
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("BoundHandle")).access(PUBLIC, FINAL);
        MethodDefinition apply = classDefinition.method("apply", Object.class, value).access(PUBLIC, STATIC);
        BoundMethodHandle boundHandle = boundMethodHandle(identity);
        assertThat(boundHandle.type()).isEqualTo(DescriptorUtils.methodType(identity.type()));
        assertThat(boundHandle).hasToString("boundMethodHandle" + DescriptorUtils.methodType(identity.type()).descriptorString());
        apply.body().ret(boundHandle.invoke(value));
        ClassModel definition = classDefinition.build();
        HiddenValue input = new HiddenValue(11);

        Class<?> standard = StandardClassDefiner.builder(getClass().getClassLoader())
                .build()
                .defineClass(definition);
        assertThat(standard.getMethod("apply", Object.class).invoke(null, input)).isSameAs(input);

        MethodHandles.Lookup hidden = HiddenClassDefiner.builder(MethodHandles.lookup())
                .build()
                .defineClass(definition);
        assertThat(hidden.findStatic(hidden.lookupClass(), "apply", MethodType.methodType(Object.class, Object.class)).invoke(input))
                .isSameAs(input);
        assertThat(boundMethodHandle(identity).invoke(value).toString()).isEqualTo("boundMethodHandle(value)");
    }

    @Test
    void testBoundMethodHandleWithInaccessibleTypeIsNotExpressionExtracted()
            throws Throwable
    {
        MethodHandle first = MethodHandles.lookup().findStatic(
                TestRuntimeDefiners.class,
                "firstHiddenValue",
                MethodType.methodType(HiddenValue.class, HiddenValue.class, HiddenValue.class));
        BytecodeExpression left = constantNull(Object.class);
        BytecodeExpression right = constantNull(Object.class);
        for (int index = 0; index < 225; index++) {
            left = left.cast(Object.class);
            right = right.cast(Object.class);
        }
        ClassDefinition definition = ClassDefinition.define(generatedClass("SplitBoundHandle")).access(PUBLIC, FINAL);
        definition.method("value", Object.class).access(PUBLIC, STATIC).body()
                .ret(boundMethodHandle(first).invoke(left, right));
        definition.method("control", boolean.class).access(PUBLIC, STATIC).body()
                .ret(invokeStatic(Objects.class, "equals", boolean.class, left, right));

        StandardClassDefiner definer = StandardClassDefiner.builder(getClass().getClassLoader()).build();
        CompiledUnit unit = ClassCompiler.forTarget(definer.compilationTarget()).compileUnit(definition.build());
        List<String> methods = unit.report().classes().stream()
                .flatMap(classInfo -> classInfo.methods().stream())
                .map(CompilationReport.MethodInfo::name)
                .toList();
        assertThat(methods).anyMatch(name -> name.startsWith("control$expression$"));
        assertThat(methods).noneMatch(name -> name.startsWith("value$expression$"));
        Class<?> generated = definer.defineUnit(unit).primaryClass();
        assertThat(generated.getMethod("value").invoke(null)).isNull();
        assertThat(generated.getMethod("control").invoke(null)).isEqualTo(true);
    }

    @Test
    void testOrdinaryInaccessibleTypeFailsWithModelPath()
    {
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("InaccessibleType")).access(PUBLIC, FINAL);
        MethodDefinition value = classDefinition.method("value", HiddenValue.class).access(PUBLIC, STATIC);
        value.body().ret(constantNull(HiddenValue.class));

        assertThatThrownBy(() -> StandardClassDefiner.builder(getClass().getClassLoader())
                .build()
                .defineClass(classDefinition.build()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Linkage validation failed")
                .hasMessageContaining("method value()Lio/airlift/classfile/TestRuntimeDefiners$HiddenValue; return type")
                .hasMessageContaining("is not accessible from class loader");
    }

    @Test
    void testBoundMethodHandleChecksExactClassIdentity()
            throws Throwable
    {
        DuplicateClassLoader duplicateClassLoader = new DuplicateClassLoader();
        Class<?> duplicate = duplicateClassLoader.define(classBytes(PublicValue.class));
        assertThat(duplicate.getName()).isEqualTo(PublicValue.class.getName());
        assertThat(duplicate).isNotSameAs(PublicValue.class);

        MethodHandle identity = MethodHandles.lookup().findStatic(
                TestRuntimeDefiners.class,
                "publicIdentity",
                MethodType.methodType(PublicValue.class, PublicValue.class));
        Parameter value = arg("value", Object.class);
        ClassDefinition classDefinition = ClassDefinition.define(generatedClass("ExactIdentity")).access(PUBLIC, FINAL);
        MethodDefinition apply = classDefinition.method("apply", Object.class, value).access(PUBLIC, STATIC);
        apply.body().ret(boundMethodHandle(identity).invoke(value));

        Class<?> generated = StandardClassDefiner.builder(getClass().getClassLoader())
                .overrideLoader(duplicateClassLoader)
                .build()
                .defineClass(classDefinition.build());
        PublicValue input = new PublicValue();
        assertThat(generated.getMethod("apply", Object.class).invoke(null, input)).isSameAs(input);
    }

    @Test
    void testGeneratedClassesTakePrecedenceOverOverrideLoader()
            throws IOException
    {
        DuplicateClassLoader duplicateClassLoader = new DuplicateClassLoader();
        Class<?> duplicate = duplicateClassLoader.define(classBytes(PublicValue.class));

        ClassDefinition definition = ClassDefinition.define(ClassDesc.of(PublicValue.class.getName())).access(PUBLIC, FINAL);
        definition.defaultConstructor().access(PUBLIC);
        Class<?> generated = StandardClassDefiner.builder(getClass().getClassLoader())
                .overrideLoader(duplicateClassLoader)
                .build()
                .defineClass(definition.build());

        assertThat(generated.getName()).isEqualTo(PublicValue.class.getName());
        assertThat(generated).isNotSameAs(PublicValue.class).isNotSameAs(duplicate);
        assertThat(generated.getClassLoader()).isNotSameAs(duplicateClassLoader);
    }

    @SuppressWarnings("UnusedMethod") // Referenced by a method handle in generated code.
    private static HiddenValue hiddenIdentity(HiddenValue value)
    {
        return value;
    }

    @SuppressWarnings("UnusedMethod") // Referenced by a method handle in generated code.
    private static HiddenValue firstHiddenValue(HiddenValue first, HiddenValue second)
    {
        return first != null ? first : second;
    }

    private record HiddenValue(int value) {}

    @SuppressWarnings("UnusedMethod") // Referenced by a method handle in generated code.
    private static PublicValue publicIdentity(PublicValue value)
    {
        return value;
    }

    public static final class PublicValue {}

    private static byte[] classBytes(Class<?> type)
            throws IOException
    {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Class resource not found: " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    private static final class DuplicateClassLoader
            extends ClassLoader
    {
        private DuplicateClassLoader()
        {
            super(null);
        }

        @SuppressWarnings("BanClassLoader") // This loader deliberately defines a duplicate class for visibility testing.
        private Class<?> define(byte[] classfile)
        {
            return defineClass(null, classfile, 0, classfile.length);
        }
    }

    private static ClassDesc generatedClass(String suffix)
    {
        return ClassDesc.of(TestRuntimeDefiners.class.getPackageName() + ".Generated" + suffix + NEXT_CLASS_ID.incrementAndGet());
    }
}
