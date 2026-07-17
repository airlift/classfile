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

import java.lang.classfile.TypeKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static io.airlift.classfile.CodeBlock.block;
import static java.util.Objects.requireNonNull;

/// An immutable integer `switch` statement produced by [SwitchStatement#builder()].
///
/// Cases are independent lexical blocks. Control does not fall through from one case to another.
public final class SwitchStatement
        implements Statement
{
    private final BytecodeExpression expression;
    private final List<Case> cases;
    private final CodeBlock defaultCase;
    private final String description;

    private SwitchStatement(BytecodeExpression expression, List<Case> cases, CodeBlock defaultCase, String description)
    {
        this.expression = requireNonNull(expression, "expression is null");
        this.cases = List.copyOf(requireNonNull(cases, "cases is null"));
        this.defaultCase = requireNonNull(defaultCase, "defaultCase is null");
        this.description = description;
        if (TypeKind.from(expression.type()).asLoadable() != TypeKind.INT) {
            throw new IllegalArgumentException("switch expression is not int-like: " + expression.type().displayName());
        }
        for (int index = 1; index < cases.size(); index++) {
            if (cases.get(index - 1).key() >= cases.get(index).key()) {
                throw new IllegalArgumentException("switch cases must be unique and ordered");
            }
        }
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public BytecodeExpression expression()
    {
        return expression;
    }

    public List<Case> cases()
    {
        return cases;
    }

    public CodeBlock defaultCase()
    {
        return defaultCase;
    }

    public String description()
    {
        return description;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder(description == null ? "" : "// " + description + "\n")
                .append("switch (").append(expression).append(") {");
        cases.forEach(caseValue -> builder.append("\ncase ").append(caseValue.key()).append(": ").append(caseValue.body()));
        if (!defaultCase.isEmpty()) {
            builder.append("\ndefault: ").append(defaultCase);
        }
        return builder.append("\n}").toString();
    }

    public record Case(int key, CodeBlock body)
    {
        public Case
        {
            requireNonNull(body, "body is null");
        }
    }

    public static final class Builder
    {
        private BytecodeExpression expression;
        private final List<Case> cases = new ArrayList<>();
        private CodeBlock defaultCase;
        private String description;

        private Builder() {}

        public Builder expression(BytecodeExpression expression)
        {
            if (this.expression != null) {
                throw new IllegalStateException("expression is already set");
            }
            this.expression = requireNonNull(expression, "expression is null");
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

        public Builder caseValue(int key, Statement body)
        {
            if (cases.stream().anyMatch(existing -> existing.key() == key)) {
                throw new IllegalArgumentException("case already exists: " + key);
            }
            requireNonNull(body, "body is null");
            cases.add(new Case(key, body instanceof CodeBlock codeBlock ? codeBlock : block(body)));
            cases.sort(Comparator.comparingInt(Case::key));
            return this;
        }

        public Builder defaultCase(Statement body)
        {
            if (defaultCase != null) {
                throw new IllegalStateException("default case is already set");
            }
            requireNonNull(body, "body is null");
            defaultCase = body instanceof CodeBlock codeBlock ? codeBlock : block(body);
            return this;
        }

        public SwitchStatement build()
        {
            if (expression == null) {
                throw new IllegalStateException("expression is not set");
            }
            return new SwitchStatement(expression, cases, defaultCase == null ? block() : defaultCase, description);
        }
    }
}
