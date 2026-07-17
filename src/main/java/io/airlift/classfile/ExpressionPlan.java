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

import static java.util.Objects.requireNonNull;

/// The standard-model expansion of a [SyntheticExpression].
///
/// @param setup statements evaluated before the value at each expression attachment
/// @param value the resulting expression value
public record ExpressionPlan(CodeBlock setup, BytecodeExpression value)
{
    public ExpressionPlan
    {
        requireNonNull(setup, "setup is null");
        requireNonNull(value, "value is null");
    }

    /// Creates an expansion with no setup statements.
    public static ExpressionPlan value(BytecodeExpression value)
    {
        return new ExpressionPlan(CodeBlock.blockBuilder().build(), value);
    }
}
