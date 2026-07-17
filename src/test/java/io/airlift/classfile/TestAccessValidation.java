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

import org.junit.jupiter.api.Test;

import java.lang.constant.ClassDesc;

import static java.lang.reflect.AccessFlag.ABSTRACT;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.lang.reflect.AccessFlag.SUPER;
import static java.lang.reflect.AccessFlag.VOLATILE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAccessValidation
{
    private static final ClassDesc TYPE = ClassDesc.of("test.AccessValidation");

    @Test
    void testFieldAccess()
    {
        ClassDefinition definition = ClassDefinition.define(TYPE);
        assertThatThrownBy(() -> definition.field("invalid", int.class).access(SUPER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Access flag is not valid for a field: SUPER");
        assertThatThrownBy(() -> definition.field("visibility", int.class).access(PUBLIC, PRIVATE).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Field has conflicting visibility: visibility");
        assertThatThrownBy(() -> definition.field("value", int.class).access(FINAL, VOLATILE).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Field cannot be both final and volatile: value");
    }

    @Test
    void testMethodAccess()
    {
        ClassDefinition definition = ClassDefinition.define(TYPE);
        assertThatThrownBy(() -> definition.method("invalid", void.class).access(SUPER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Access flag is not valid for a method: SUPER");
        assertThatThrownBy(() -> definition.method("visibility", void.class).access(PUBLIC, PRIVATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Method has conflicting visibility: visibility");
        assertThatThrownBy(() -> definition.method("abstractStatic", void.class).access(ABSTRACT, STATIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Abstract method has incompatible access flags: abstractStatic");
    }
}
