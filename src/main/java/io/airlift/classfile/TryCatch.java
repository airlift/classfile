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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import static io.airlift.classfile.CodeBlock.block;
import static java.util.Objects.requireNonNull;

/// An immutable structured `try`/`catch`/`finally` statement produced by [TryCatch#builder()].
public final class TryCatch
        implements Statement
{
    private final CodeBlock tryBlock;
    private final List<CatchClause> catches;
    private final Optional<CodeBlock> finallyBlock;
    private final String description;

    private TryCatch(CodeBlock tryBlock, List<CatchClause> catches, Optional<CodeBlock> finallyBlock, String description)
    {
        this.tryBlock = requireNonNull(tryBlock, "tryBlock is null");
        this.catches = List.copyOf(requireNonNull(catches, "catches is null"));
        this.finallyBlock = requireNonNull(finallyBlock, "finallyBlock is null");
        this.description = description;
        if (catches.isEmpty() && finallyBlock.isEmpty()) {
            throw new IllegalArgumentException("Try statement must have a catch or finally block");
        }
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public CodeBlock tryBlock()
    {
        return tryBlock;
    }

    public List<CatchClause> catches()
    {
        return catches;
    }

    public Optional<CodeBlock> finallyBlock()
    {
        return finallyBlock;
    }

    public String description()
    {
        return description;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder(description == null ? "" : "// " + description + "\n").append("try ").append(tryBlock);
        catches.forEach(catchClause -> builder.append(" catch (")
                .append(catchClause.exceptionType().displayName())
                .append(' ')
                .append(catchClause.variable().name())
                .append(") ")
                .append(catchClause.body()));
        finallyBlock.ifPresent(block -> builder.append(" finally ").append(block));
        return builder.toString();
    }

    public record CatchClause(ClassDesc exceptionType, Variable variable, CodeBlock body)
    {
        public CatchClause
        {
            requireNonNull(exceptionType, "exceptionType is null");
            requireNonNull(variable, "variable is null");
            requireNonNull(body, "body is null");
            if (!exceptionType.isClassOrInterface()) {
                throw new IllegalArgumentException("exceptionType is not a class: " + exceptionType.displayName());
            }
        }
    }

    public static final class Builder
    {
        private CodeBlock tryBlock;
        private final List<CatchClause> catches = new ArrayList<>();
        private CodeBlock finallyBlock;
        private String description;

        private Builder() {}

        public Builder description(String description)
        {
            if (this.description != null) {
                throw new IllegalStateException("description is already set");
            }
            this.description = requireNonNull(description, "description is null");
            return this;
        }

        public Builder tryBlock(Statement tryBlock)
        {
            if (this.tryBlock != null) {
                throw new IllegalStateException("try block is already set");
            }
            requireNonNull(tryBlock, "tryBlock is null");
            this.tryBlock = tryBlock instanceof CodeBlock codeBlock ? codeBlock : block(tryBlock);
            return this;
        }

        public Builder catching(Class<? extends Throwable> exceptionType, String variableName, BiConsumer<CodeBlock.Builder, Variable> body)
        {
            return catching(DescriptorUtils.classDesc(exceptionType), variableName, body);
        }

        public Builder catching(ClassDesc exceptionType, String variableName, BiConsumer<CodeBlock.Builder, Variable> body)
        {
            requireNonNull(exceptionType, "exceptionType is null");
            requireNonNull(body, "body is null");
            if (catches.stream().anyMatch(catchClause -> catchClause.exceptionType().equals(exceptionType))) {
                throw new IllegalArgumentException("Exception type is already caught: " + exceptionType.displayName());
            }
            Variable variable = new Variable(variableName, exceptionType, this);
            CodeBlock.Builder block = CodeBlock.blockBuilder();
            body.accept(block, variable);
            catches.add(new CatchClause(exceptionType, variable, block.build()));
            return this;
        }

        public Builder finallyBlock(Statement finallyBlock)
        {
            if (this.finallyBlock != null) {
                throw new IllegalStateException("finally block is already set");
            }
            requireNonNull(finallyBlock, "finallyBlock is null");
            this.finallyBlock = finallyBlock instanceof CodeBlock codeBlock ? codeBlock : block(finallyBlock);
            return this;
        }

        public TryCatch build()
        {
            if (tryBlock == null) {
                throw new IllegalStateException("try block is not set");
            }
            return new TryCatch(tryBlock, catches, Optional.ofNullable(finallyBlock), description);
        }
    }
}
