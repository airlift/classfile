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

import static org.assertj.core.api.Assertions.assertThat;

class TestGeneratedExecutionBenchmark
{
    @Test
    void testLambdaBenchmarkShapes()
            throws ReflectiveOperationException
    {
        BenchmarkGeneratedExecution benchmark = new BenchmarkGeneratedExecution();
        for (BenchmarkGeneratedExecution.LambdaShape shape : BenchmarkGeneratedExecution.LambdaShape.values()) {
            for (BenchmarkGeneratedExecution.LambdaBoundary boundary : BenchmarkGeneratedExecution.LambdaBoundary.values()) {
                BenchmarkGeneratedExecution.LambdaData data = new BenchmarkGeneratedExecution.LambdaData();
                data.lambdaShape = shape;
                data.lambdaBoundary = boundary;
                data.setup();
                assertThat(benchmark.hiddenLambda(data)).isNotZero();
            }
        }
    }

    @Test
    void testFlatHashBenchmarkShapes()
            throws ReflectiveOperationException
    {
        BenchmarkGeneratedExecution benchmark = new BenchmarkGeneratedExecution();
        for (BenchmarkGeneratedExecution.FlatHashShape shape : BenchmarkGeneratedExecution.FlatHashShape.values()) {
            for (BenchmarkGeneratedExecution.FlatHashPlanning planning : BenchmarkGeneratedExecution.FlatHashPlanning.values()) {
                for (int fieldCount : new int[] {32, 50, 64}) {
                    BenchmarkGeneratedExecution.FlatHashData data = new BenchmarkGeneratedExecution.FlatHashData();
                    data.flatHashShape = shape;
                    data.planning = planning;
                    data.fieldCount = fieldCount;
                    data.setup();
                    assertThat(benchmark.flatHashLike(data)).isTrue();
                }
            }
        }
    }
}
