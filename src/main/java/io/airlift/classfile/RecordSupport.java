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
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.constant.ConstantDescs.CD_Object;

final class RecordSupport
{
    private RecordSupport() {}

    static BytecodeExpression objectMethod(
            String name,
            ClassDesc returnType,
            ClassDesc recordType,
            List<RecordComponentDefinition> components,
            BytecodeExpression... arguments)
    {
        ArrayList<ConstantDesc> bootstrapArguments = new ArrayList<>(components.size() + 2);
        bootstrapArguments.add(components.stream().map(RecordComponentDefinition::name).collect(Collectors.joining(";")));
        bootstrapArguments.add(recordType.descriptorString());
        components.stream()
                .map(component -> component.type().descriptorString())
                .forEach(bootstrapArguments::add);

        MethodTypeDesc invocationType = MethodTypeDesc.of(
                returnType,
                IntStream.range(0, arguments.length)
                        .mapToObj(index -> index == 0 ? CD_Object : arguments[index].type())
                        .toList());
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(
                BootstrapDescriptors.recordObjectMethod(),
                name,
                invocationType,
                bootstrapArguments.toArray(ConstantDesc[]::new));
        return BytecodeExpressions.invokeDynamic(callSite, arguments);
    }
}
