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
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.LambdaMetafactory;
import java.util.ArrayList;
import java.util.Optional;

import static io.airlift.classfile.DescriptorUtils.classDesc;
import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.DirectMethodHandleDesc.Kind.STATIC;

/// Rewrites caller-sensitive linkage shapes that cannot name the VM identity of a hidden class.
///
/// A LambdaMetafactory implementation handle owned by the class being defined embeds the logical
/// class name in the constant pool. That name cannot resolve to the VM-created hidden identity.
/// The runtime bootstrap instead resolves the implementation with the defining lookup and places
/// it in the class data of a weak hidden adapter whose instances contain only authored captures.
final class HiddenClassLinkage
{
    private static final ClassDesc LAMBDA_METAFACTORY = classDesc(LambdaMetafactory.class);
    private static final DirectMethodHandleDesc METAFACTORY = MethodHandleDesc.ofMethod(
            STATIC,
            LAMBDA_METAFACTORY,
            "metafactory",
            MethodTypeDesc.of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType, CD_MethodType, CD_MethodHandle, CD_MethodType));
    private static final DirectMethodHandleDesc ALT_METAFACTORY = MethodHandleDesc.ofMethod(
            STATIC,
            LAMBDA_METAFACTORY,
            "altMetafactory",
            MethodTypeDesc.of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType, CD_Object.arrayType()));

    private HiddenClassLinkage() {}

    static DynamicCallSiteDesc rewrite(DynamicCallSiteDesc callSite, ClassDesc currentOwner)
    {
        Optional<LambdaSite> lambdaSite = lambdaSite(callSite, currentOwner);
        if (lambdaSite.isEmpty()) {
            return callSite;
        }
        if (callSite.invocationType().returnType().equals(currentOwner)) {
            throw new CompilationException("A generated hidden type cannot be the LambdaMetafactory functional interface; define and use a nominal interface instead: " + currentOwner.displayName());
        }
        MethodTypeDesc samMethodType = lambdaSite.orElseThrow().samMethodType();
        DirectMethodHandleDesc implementation = lambdaSite.orElseThrow().implementation();
        MethodTypeDesc instantiatedMethodType = lambdaSite.orElseThrow().instantiatedMethodType();

        MethodTypeDesc safeInvocationType = safeInvocationType(callSite.invocationType(), currentOwner);
        return DynamicCallSiteDesc.of(
                BootstrapDescriptors.hiddenClassLambdaMetafactory(),
                callSite.invocationName(),
                safeInvocationType,
                currentOwner.descriptorString(),
                implementation.refKind(),
                implementation.methodName(),
                implementation.lookupDescriptor(),
                samMethodType.descriptorString(),
                instantiatedMethodType.descriptorString());
    }

    private static Optional<LambdaSite> lambdaSite(DynamicCallSiteDesc callSite, ClassDesc currentOwner)
    {
        if (!(callSite.bootstrapMethod() instanceof DirectMethodHandleDesc bootstrap) ||
                (!bootstrap.equals(METAFACTORY) && !bootstrap.equals(ALT_METAFACTORY))) {
            return Optional.empty();
        }

        ConstantDesc[] arguments = callSite.bootstrapArgs();
        if (arguments.length < 2 ||
                !(arguments[1] instanceof DirectMethodHandleDesc implementation) ||
                !implementation.owner().equals(currentOwner)) {
            return Optional.empty();
        }
        if (bootstrap.equals(ALT_METAFACTORY)) {
            if (arguments.length != 4 ||
                    !(arguments[0] instanceof MethodTypeDesc) ||
                    !(arguments[2] instanceof MethodTypeDesc) ||
                    !(arguments[3] instanceof Integer flags)) {
                throw new CompilationException("Hidden-class LambdaMetafactory.altMetafactory site has a malformed argument list");
            }
            if (flags != 0) {
                throw new CompilationException("Hidden-class LambdaMetafactory.altMetafactory flags " + flags + " are not supported");
            }
        }
        else if (arguments.length != 3 ||
                !(arguments[0] instanceof MethodTypeDesc) ||
                !(arguments[2] instanceof MethodTypeDesc)) {
            return Optional.empty();
        }
        MethodTypeDesc samMethodType = (MethodTypeDesc) arguments[0];
        MethodTypeDesc instantiatedMethodType = (MethodTypeDesc) arguments[2];
        switch (implementation.kind()) {
            case STATIC,
                 INTERFACE_STATIC,
                 VIRTUAL,
                 INTERFACE_VIRTUAL,
                 SPECIAL,
                 INTERFACE_SPECIAL,
                 CONSTRUCTOR -> {}
            default -> throw new CompilationException("LambdaMetafactory implementation handle is not a method invocation: " + implementation.kind());
        }
        return Optional.of(new LambdaSite(samMethodType, implementation, instantiatedMethodType));
    }

    private static MethodTypeDesc safeInvocationType(MethodTypeDesc type, ClassDesc currentOwner)
    {
        ClassDesc returnType = safeType(type.returnType(), currentOwner);
        ArrayList<ClassDesc> parameterTypes = new ArrayList<>(type.parameterCount());
        for (ClassDesc parameterType : type.parameterList()) {
            parameterTypes.add(safeType(parameterType, currentOwner));
        }
        return MethodTypeDesc.of(returnType, parameterTypes);
    }

    private static ClassDesc safeType(ClassDesc type, ClassDesc currentOwner)
    {
        ClassDesc componentType = type;
        while (componentType.isArray()) {
            componentType = componentType.componentType();
        }
        return componentType.equals(currentOwner) ? CD_Object : type;
    }

    private record LambdaSite(MethodTypeDesc samMethodType, DirectMethodHandleDesc implementation, MethodTypeDesc instantiatedMethodType) {}
}
