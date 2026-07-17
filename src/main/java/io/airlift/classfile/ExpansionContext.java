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

/// High-level services available while a consumer-defined expression is expanded.
///
/// Runtime-bound constants are added with the runtime binding layer. This type never exposes
/// classfile builders, labels, or local slots.
public final class ExpansionContext
{
    static final ExpansionContext INSTANCE = new ExpansionContext();

    private ExpansionContext() {}

    public BytecodeExpression boundConstant(Object value, Class<?> type)
    {
        return BytecodeExpressions.boundConstant(value, DescriptorUtils.classDesc(type));
    }

    public BytecodeExpression boundConstant(Object value, ClassDesc type)
    {
        return BytecodeExpressions.boundConstant(value, type);
    }
}
