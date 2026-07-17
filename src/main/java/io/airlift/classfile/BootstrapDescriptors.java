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
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;

import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.DirectMethodHandleDesc.Kind.STATIC;

final class BootstrapDescriptors
{
    private static final ClassDesc RUNTIME_BOOTSTRAPS = ClassDesc.of("io.airlift.classfile.runtime.RuntimeBootstraps");
    private static final DirectMethodHandleDesc BINDING_CONSTANT = MethodHandleDesc.ofMethod(
            STATIC,
            RUNTIME_BOOTSTRAPS,
            "bindingConstant",
            MethodTypeDesc.of(CD_Object, CD_MethodHandles_Lookup, CD_String, CD_Class, CD_int));
    private static final DirectMethodHandleDesc CLASS_DATA_CONSTANT = MethodHandleDesc.ofMethod(
            STATIC,
            RUNTIME_BOOTSTRAPS,
            "classDataConstant",
            MethodTypeDesc.of(CD_Object, CD_MethodHandles_Lookup, CD_String, CD_Class));
    private static final DirectMethodHandleDesc RECORD_OBJECT_METHOD = MethodHandleDesc.ofMethod(
            STATIC,
            RUNTIME_BOOTSTRAPS,
            "recordObjectMethod",
            MethodTypeDesc.of(
                    CD_CallSite,
                    CD_MethodHandles_Lookup,
                    CD_String,
                    CD_MethodType,
                    CD_String,
                    CD_String,
                    CD_String.arrayType()));

    private BootstrapDescriptors() {}

    static DirectMethodHandleDesc bindingConstant()
    {
        return BINDING_CONSTANT;
    }

    static DirectMethodHandleDesc classDataConstant()
    {
        return CLASS_DATA_CONSTANT;
    }

    static DirectMethodHandleDesc recordObjectMethod()
    {
        return RECORD_OBJECT_METHOD;
    }
}
