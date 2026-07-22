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

/// An immutable structured `for` statement produced by [ForLoop#builder()].
///
/// Break and continue statements are obtained from the builder so their target is explicit and
/// remains correct when the completed loop is reused.
public final class ForLoop
        implements Statement
{
    private final Object target;
    private final CodeBlock initializer;
    private final BytecodeExpression condition;
    private final CodeBlock update;
    private final CodeBlock body;
    private final String description;

    private ForLoop(Object target, CodeBlock initializer, BytecodeExpression condition, CodeBlock update, CodeBlock body, String description)
    {
        this.target = requireNonNull(target, "target is null");
        this.initializer = requireNonNull(initializer, "initializer is null");
        this.condition = requireNonNull(condition, "condition is null");
        this.update = requireNonNull(update, "update is null");
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

    public CodeBlock initializer()
    {
        return initializer;
    }

    public BytecodeExpression condition()
    {
        return condition;
    }

    public CodeBlock update()
    {
        return update;
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

    ForLoop rewrite(CodeBlock initializer, BytecodeExpression condition, CodeBlock update, CodeBlock body)
    {
        if (same(this.initializer, initializer) && same(this.condition, condition) && same(this.update, update) && same(this.body, body)) {
            return this;
        }
        return new ForLoop(target, initializer, condition, update, body, description);
    }

    @Override
    public String toString()
    {
        return (description == null ? "" : "// " + description + "\n") +
                "for (" + compact(initializer) + "; " + condition + "; " + compact(update) + ") " + body;
    }

    private static String compact(CodeBlock block)
    {
        return block.toInlineString();
    }

    public static final class Builder
    {
        private final Object target = new Object();
        private CodeBlock initializer;
        private BytecodeExpression condition;
        private CodeBlock update;
        private CodeBlock body;
        private String description;

        private Builder() {}

        public Builder initialize(Statement initializer)
        {
            if (this.initializer != null) {
                throw new IllegalStateException("initializer is already set");
            }
            this.initializer = asBlock(initializer, "initializer");
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

        public Builder condition(BytecodeExpression condition)
        {
            if (this.condition != null) {
                throw new IllegalStateException("condition is already set");
            }
            this.condition = requireNonNull(condition, "condition is null");
            return this;
        }

        public Builder update(Statement update)
        {
            if (this.update != null) {
                throw new IllegalStateException("update is already set");
            }
            this.update = asBlock(update, "update");
            return this;
        }

        public Builder body(Statement body)
        {
            if (this.body != null) {
                throw new IllegalStateException("body is already set");
            }
            this.body = asBlock(body, "body");
            return this;
        }

        private static CodeBlock asBlock(Statement statement, String name)
        {
            requireNonNull(statement, name + " is null");
            return statement instanceof CodeBlock codeBlock ? codeBlock : block(statement);
        }

        public Statement breakLoop()
        {
            return new LoopJump(LoopJump.Kind.BREAK, target);
        }

        public Statement continueLoop()
        {
            return new LoopJump(LoopJump.Kind.CONTINUE, target);
        }

        public ForLoop build()
        {
            if (condition == null) {
                throw new IllegalStateException("condition is not set");
            }
            if (body == null) {
                throw new IllegalStateException("body is not set");
            }
            return new ForLoop(
                    target,
                    initializer == null ? block() : initializer,
                    condition,
                    update == null ? block() : update,
                    body,
                    description);
        }
    }
}
