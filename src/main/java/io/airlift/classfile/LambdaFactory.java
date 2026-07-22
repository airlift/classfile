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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import static io.airlift.classfile.BytecodeExpressions.boundMethodHandle;
import static io.airlift.classfile.DescriptorUtils.classDesc;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.SYNTHETIC;
import static java.util.Objects.requireNonNull;

/// Creates weak hidden lambda adapters for hidden classes defined from one host lookup.
///
/// LambdaMetafactory defines a strong hidden proxy tied to its caller lookup, so using a weak hidden
/// generated class as that caller retains the generated class after linkage. The adapter instead
/// keeps the implementation handle in its own class data and instances contain only authored
/// captures. Neither the adapter nor its implementation is retained by this loader-owned factory.
final class LambdaFactory
{
    private final MethodHandles.Lookup hostLookup;

    LambdaFactory(MethodHandles.Lookup hostLookup)
    {
        this.hostLookup = requireNonNull(hostLookup, "hostLookup is null");
    }

    MethodHandle factory(
            String methodName,
            MethodType factoryType,
            MethodType samMethodType,
            MethodHandle implementation,
            MethodType instantiatedMethodType)
            throws Throwable
    {
        ClassDesc adapterType = ClassDesc.of(hostLookup.lookupClass().getPackageName(), "ClassfileLambdaAdapter");
        ClassDefinition adapter = ClassDefinition.define(adapterType)
                .access(FINAL, SYNTHETIC)
                .addInterface(factoryType.returnType());

        List<Parameter> captures = parameters("capture", factoryType.parameterArray());
        List<FieldDefinition> captureFields = new ArrayList<>();
        for (Parameter capture : captures) {
            captureFields.add(adapter.field(capture.name(), capture.type()).access(PRIVATE, FINAL).build());
        }

        MethodDefinition constructor = adapter.constructor(captures.toArray(Parameter[]::new)).access(PRIVATE);
        constructor.body().invokeSuperConstructor();
        for (int index = 0; index < captures.size(); index++) {
            constructor.body().append(constructor.thisVariable().setField(captureFields.get(index), captures.get(index)));
        }
        constructor.body().ret();

        List<Parameter> arguments = parameters("argument", samMethodType.parameterArray());
        MethodDefinition samMethod = adapter.method(methodName, classDesc(samMethodType.returnType()), arguments.toArray(Parameter[]::new))
                .access(PUBLIC, FINAL);
        List<BytecodeExpression> invocationArguments = new ArrayList<>();
        captureFields.forEach(field -> invocationArguments.add(samMethod.thisVariable().getField(field)));
        invocationArguments.addAll(arguments);

        MethodType instantiatedInvocationType = MethodType.methodType(
                instantiatedMethodType.returnType(),
                concat(factoryType.parameterArray(), instantiatedMethodType.parameterArray()));
        MethodType samInvocationType = MethodType.methodType(
                samMethodType.returnType(),
                concat(factoryType.parameterArray(), samMethodType.parameterArray()));
        MethodHandle adaptedImplementation = implementation
                .asType(instantiatedInvocationType)
                .asType(samInvocationType);
        BytecodeExpression invocation = boundMethodHandle(adaptedImplementation)
                .invoke(invocationArguments.toArray(BytecodeExpression[]::new));
        if (samMethodType.returnType() == void.class) {
            samMethod.body().append(invocation).ret();
        }
        else {
            samMethod.body().ret(invocation);
        }

        CompiledClass compiled = ClassCompiler.forTarget(CompilationTarget.forLookup(hostLookup))
                .compileClass(adapter.build());
        compiled.validateRuntimeData(compiled.runtimeData());
        MethodHandles.Lookup defined = hostLookup.defineHiddenClassWithClassData(
                compiled.classfile(),
                compiled.runtimeData(),
                true);
        MethodHandle factory = defined.findConstructor(
                defined.lookupClass(),
                MethodType.methodType(void.class, factoryType.parameterArray()));
        return factory.asType(factoryType);
    }

    private static List<Parameter> parameters(String prefix, Class<?>[] types)
    {
        List<Parameter> parameters = new ArrayList<>();
        for (int index = 0; index < types.length; index++) {
            parameters.add(Parameter.arg(prefix + index, types[index]));
        }
        return parameters;
    }

    private static Class<?>[] concat(Class<?>[] first, Class<?>[] second)
    {
        Class<?>[] result = new Class<?>[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
