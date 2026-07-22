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

import static io.airlift.classfile.CodeBlock.block;
import static io.airlift.classfile.Identity.same;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.util.Objects.requireNonNull;

/// An immutable structured `if`/`else` statement produced by [IfStatement#builder()].
public final class IfStatement
        implements Statement
{
    private final BytecodeExpression condition;
    private final CodeBlock ifTrue;
    private final CodeBlock ifFalse;
    private final String description;

    private IfStatement(BytecodeExpression condition, CodeBlock ifTrue, CodeBlock ifFalse, String description)
    {
        this.condition = requireNonNull(condition, "condition is null");
        this.ifTrue = requireNonNull(ifTrue, "ifTrue is null");
        this.ifFalse = requireNonNull(ifFalse, "ifFalse is null");
        this.description = description;
        if (!condition.type().equals(CD_boolean)) {
            throw new IllegalArgumentException("condition is not boolean: " + condition.type().displayName());
        }
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public BytecodeExpression condition()
    {
        return condition;
    }

    public CodeBlock ifTrue()
    {
        return ifTrue;
    }

    public CodeBlock ifFalse()
    {
        return ifFalse;
    }

    public String description()
    {
        return description;
    }

    IfStatement rewrite(BytecodeExpression condition, CodeBlock ifTrue, CodeBlock ifFalse)
    {
        if (same(this.condition, condition) && same(this.ifTrue, ifTrue) && same(this.ifFalse, ifFalse)) {
            return this;
        }
        return new IfStatement(condition, ifTrue, ifFalse, description);
    }

    @Override
    public String toString()
    {
        if (ifFalse.isEmpty()) {
            return prefix() + "if (" + condition + ") " + ifTrue;
        }
        return prefix() + "if (" + condition + ") " + ifTrue + " else " + ifFalse;
    }

    private String prefix()
    {
        return description == null ? "" : "// " + description + "\n";
    }

    public static final class Builder
    {
        private BytecodeExpression condition;
        private CodeBlock ifTrue;
        private CodeBlock ifFalse;
        private String description;

        private Builder() {}

        public Builder condition(BytecodeExpression condition)
        {
            if (this.condition != null) {
                throw new IllegalStateException("condition is already set");
            }
            this.condition = requireNonNull(condition, "condition is null");
            return this;
        }

        public Builder description(String description)
        {
            if (this.description != null) {
                throw new IllegalStateException("description is already set");
            }
            this.description = requireNonNull(description, "description is null");
            return this;
        }

        public Builder then(Statement statement)
        {
            if (ifTrue != null) {
                throw new IllegalStateException("then statement is already set");
            }
            ifTrue = asBlock(statement, "then statement");
            return this;
        }

        public Builder otherwise(Statement statement)
        {
            if (ifFalse != null) {
                throw new IllegalStateException("otherwise statement is already set");
            }
            ifFalse = asBlock(statement, "otherwise statement");
            return this;
        }

        private static CodeBlock asBlock(Statement statement, String name)
        {
            requireNonNull(statement, name + " is null");
            return statement instanceof CodeBlock codeBlock ? codeBlock : block(statement);
        }

        public IfStatement build()
        {
            if (condition == null) {
                throw new IllegalStateException("condition is not set");
            }
            if ((ifTrue == null || ifTrue.isEmpty()) && (ifFalse == null || ifFalse.isEmpty())) {
                throw new IllegalStateException("then or otherwise statement is not set");
            }
            return new IfStatement(
                    condition,
                    ifTrue == null ? block() : ifTrue,
                    ifFalse == null ? block() : ifFalse,
                    description);
        }
    }
}
