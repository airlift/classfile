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

/// An immutable structured `while` statement produced by [WhileLoop#builder()].
///
/// Break and continue statements are obtained from the builder so their target is explicit and
/// remains correct when the completed loop is reused.
public final class WhileLoop
        implements Statement
{
    private final Object target;
    private final BytecodeExpression condition;
    private final CodeBlock body;
    private final String description;

    private WhileLoop(Object target, BytecodeExpression condition, CodeBlock body, String description)
    {
        this.target = requireNonNull(target, "target is null");
        this.condition = requireNonNull(condition, "condition is null");
        this.body = requireNonNull(body, "body is null");
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

    public CodeBlock body()
    {
        return body;
    }

    public Object target()
    {
        return target;
    }

    public String description()
    {
        return description;
    }

    WhileLoop rewrite(BytecodeExpression condition, CodeBlock body)
    {
        if (same(this.condition, condition) && same(this.body, body)) {
            return this;
        }
        return new WhileLoop(target, condition, body, description);
    }

    @Override
    public String toString()
    {
        return (description == null ? "" : "// " + description + "\n") + "while (" + condition + ") " + body;
    }

    public static final class Builder
    {
        private final Object target = new Object();
        private BytecodeExpression condition;
        private CodeBlock body;
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

        public Builder body(Statement body)
        {
            if (this.body != null) {
                throw new IllegalStateException("body is already set");
            }
            requireNonNull(body, "body is null");
            this.body = body instanceof CodeBlock codeBlock ? codeBlock : block(body);
            return this;
        }

        public Statement breakLoop()
        {
            return new LoopJump(LoopJump.Kind.BREAK, target);
        }

        public Statement continueLoop()
        {
            return new LoopJump(LoopJump.Kind.CONTINUE, target);
        }

        public WhileLoop build()
        {
            if (condition == null) {
                throw new IllegalStateException("condition is not set");
            }
            if (body == null) {
                throw new IllegalStateException("body is not set");
            }
            return new WhileLoop(target, condition, body, description);
        }
    }
}
