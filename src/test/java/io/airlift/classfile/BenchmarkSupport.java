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

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.CommandLineOptionException;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.io.IOException;
import java.util.regex.Pattern;

final class BenchmarkSupport
{
    private BenchmarkSupport() {}

    public static void run(Class<?> benchmarkClass, String[] args)
            throws CommandLineOptionException, IOException, RunnerException
    {
        CommandLineOptions commandLine = new CommandLineOptions(args);
        ChainedOptionsBuilder builder = new OptionsBuilder()
                .parent(commandLine)
                .verbosity(VerboseMode.NORMAL)
                .shouldFailOnError(true);
        if (commandLine.getIncludes().isEmpty()) {
            builder.include(".*" + Pattern.quote(benchmarkClass.getSimpleName()) + ".*");
        }
        Options options = builder.build();
        Runner runner = new Runner(options);
        if (commandLine.shouldHelp()) {
            commandLine.showHelp();
        }
        else if (commandLine.shouldList()) {
            runner.list();
        }
        else if (commandLine.shouldListWithParams()) {
            runner.listWithParams(commandLine);
        }
        else if (commandLine.shouldListProfilers()) {
            commandLine.listProfilers();
        }
        else if (commandLine.shouldListResultFormats()) {
            commandLine.listResultFormats();
        }
        else {
            runner.run();
        }
    }
}
