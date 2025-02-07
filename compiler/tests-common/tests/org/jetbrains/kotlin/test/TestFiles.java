/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test;

import com.google.common.collect.Lists;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.TestHelperGeneratorKt;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.jetbrains.kotlin.test.InTextDirectivesUtils.isDirectiveDefined;
import static org.jetbrains.kotlin.test.KotlinTestUtils.parseDirectives;

public class TestFiles {
    /**
     * Syntax:
     *
     * // MODULE: name(dependency1, dependency2, ...)
     *
     * // FILE: name
     *
     * Several files may follow one module
     */
    private static final String MODULE_DELIMITER = ",\\s*";

    private static final Pattern MODULE_PATTERN = Pattern.compile("//\\s*MODULE:\\s*([^()\\n]+)(?:\\(([^()]+(?:" + MODULE_DELIMITER + "[^()]+)*)\\))?\\s*(?:\\(([^()]+(?:" + MODULE_DELIMITER + "[^()]+)*)\\))?\\s*(?:\\((\\d+(?:" + MODULE_DELIMITER + "\\d+)*)\\))?\n");
    private static final Pattern FILE_PATTERN = Pattern.compile("//\\s*FILE:\\s*(.*)\n");

    private static final Pattern LINE_SEPARATOR_PATTERN = Pattern.compile("\\r\\n|\\r|\\n");

    @NotNull
    public static <M extends KotlinBaseTest.TestModule, F> List<F> createTestFiles(@Nullable String testFileName, String expectedText, TestFileFactory<M, F> factory) {
        return createTestFiles(testFileName, expectedText, factory, false);
    }

    @NotNull
    public static <M extends KotlinBaseTest.TestModule, F> List<F> createTestFiles(String testFileName, String expectedText, TestFileFactory<M , F> factory,
            boolean preserveLocations) {
        return createTestFiles(testFileName, expectedText, factory, preserveLocations, false);
    }

    @NotNull
    public static <M extends KotlinBaseTest.TestModule, F> List<F> createTestFiles(String testFileName, String expectedText, TestFileFactory<M , F> factory,
            boolean preserveLocations, boolean parseDirectivesPerFile) {
        Map<String, M> modules = new HashMap<>();
        List<F> testFiles = Lists.newArrayList();
        Matcher fileMatcher = FILE_PATTERN.matcher(expectedText);
        Matcher moduleMatcher = MODULE_PATTERN.matcher(expectedText);
        boolean hasModules = false;
        String commonPrefixOrWholeFile;

        boolean fileFound = fileMatcher.find();
        boolean moduleFound = moduleMatcher.find();
        if (!fileFound && !moduleFound) {
            assert testFileName != null : "testFileName should not be null if no FILE directive defined";
            // One file
            testFiles.add(factory.createFile(null, testFileName, expectedText, parseDirectives(expectedText)));
            commonPrefixOrWholeFile = expectedText;
        }
        else {
            Directives allFilesOrCommonPrefixDirectives = parseDirectivesPerFile ? null : parseDirectives(expectedText);
            int processedChars = 0;
            M module = null;
            boolean firstFileProcessed = false;

            int commonStart;
            if (moduleFound) {
                commonStart = moduleMatcher.start();
            } else {
                commonStart = fileMatcher.start();
            }

            commonPrefixOrWholeFile = expectedText.substring(0, commonStart);

            // Many files
            while (true) {
                if (moduleFound) {
                    String moduleName = moduleMatcher.group(1);
                    String moduleDependencies = moduleMatcher.group(2);
                    String moduleFriends = moduleMatcher.group(3);
                    String abiVersions = moduleMatcher.group(4);
                    if (moduleName != null) {
                        moduleName = moduleName.trim();
                        hasModules = true;
                        module = factory.createModule(moduleName, parseModuleList(moduleDependencies), parseModuleList(moduleFriends), parseAbiVersionsList(abiVersions));
                        M oldValue = modules.put(moduleName, module);
                        assert oldValue == null : "Module with name " + moduleName + " already present in file";
                    }
                }

                boolean nextModuleExists = moduleMatcher.find();
                moduleFound = nextModuleExists;
                while (true) {
                    String fileName = fileMatcher.group(1);
                    int start = processedChars;

                    boolean nextFileExists = fileMatcher.find();
                    int end;
                    if (nextFileExists && nextModuleExists) {
                        end = Math.min(fileMatcher.start(), moduleMatcher.start());
                    }
                    else if (nextFileExists) {
                        end = fileMatcher.start();
                    }
                    else {
                        end = expectedText.length();
                    }
                    String fileText = preserveLocations ?
                                      substringKeepingLocations(expectedText, start, end) :
                                      expectedText.substring(start, end);


                    String expectedText1 = firstFileProcessed ? commonPrefixOrWholeFile + fileText : fileText;
                    testFiles.add(factory.createFile(module, fileName, fileText,
                                                     parseDirectivesPerFile ?
                                                     parseDirectives(expectedText1)
                                                                            : allFilesOrCommonPrefixDirectives));
                    processedChars = end;
                    firstFileProcessed = true;
                    if (!nextFileExists && !nextModuleExists) break;
                    if (nextModuleExists && fileMatcher.start() > moduleMatcher.start()) break;
                }
                if (!nextModuleExists) break;
            }
            assert processedChars == expectedText.length() : "Characters skipped from " +
                                                             processedChars +
                                                             " to " +
                                                             (expectedText.length() - 1);
        }

        if (isDirectiveDefined(expectedText, "WITH_COROUTINES")) {
            M supportModule = hasModules ? factory.createModule("support", Collections.emptyList(), Collections.emptyList(), Collections.emptyList()) : null;
            if (supportModule != null) {
                M oldValue = modules.put(supportModule.name, supportModule);
                assert oldValue == null : "Module with name " + supportModule.name + " already present in file";
            }

            boolean checkStateMachine = isDirectiveDefined(expectedText, "CHECK_STATE_MACHINE");
            boolean checkTailCallOptimization = isDirectiveDefined(expectedText, "CHECK_TAIL_CALL_OPTIMIZATION");

            testFiles.add(
                    factory.createFile(
                            supportModule,
                            "CoroutineUtil.kt",
                            TestHelperGeneratorKt.createTextForCoroutineHelpers(checkStateMachine, checkTailCallOptimization),
                            parseDirectives(commonPrefixOrWholeFile)
                    ));
        }

        for (M module : modules.values()) {
            if (module != null) {
                module.getDependencies().addAll(module.dependenciesSymbols.stream()
                                                        .map(modules::get)
                                                        .filter(Objects::nonNull)
                                                        .collect(Collectors.toList()));

                module.getFriends().addAll(module.friendsSymbols.stream()
                                                   .map(modules::get)
                                                   .filter(Objects::nonNull)
                                                   .collect(Collectors.toList()));
            }
        }


        return testFiles;
    }

    private static String substringKeepingLocations(String string, int start, int end) {
        Matcher matcher = LINE_SEPARATOR_PATTERN.matcher(string);
        StringBuilder prefix = new StringBuilder();
        int lastLineOffset = 0;
        while (matcher.find()) {
            if (matcher.end() > start) {
                break;
            }

            lastLineOffset = matcher.end();
            prefix.append('\n');
        }

        while (lastLineOffset++ < start) {
            prefix.append(' ');
        }

        return prefix + string.substring(start, end);
    }

    private static List<String> parseModuleList(@Nullable String dependencies) {
        if (dependencies == null) return Collections.emptyList();
        return kotlin.text.StringsKt.split(dependencies, Pattern.compile(MODULE_DELIMITER), 0);
    }

    private static List<Integer> parseAbiVersionsList(@Nullable String versions) {
        if (versions == null) return Collections.emptyList();
        List<String> splitted = kotlin.text.StringsKt.split(versions, Pattern.compile(MODULE_DELIMITER), 0);
        List<Integer> result = new ArrayList<>(splitted.size());
        for (String s : splitted) {
            result.add(Integer.parseInt(s));
        }
        return result;
    }

    public interface TestFileFactory<M, F> {
        F createFile(@Nullable M module, @NotNull String fileName, @NotNull String text, @NotNull Directives directives);
        M createModule(@NotNull String name, @NotNull List<String> dependencies, @NotNull List<String> friends, @NotNull List<Integer> abiVersions);
    }

    public static abstract class TestFileFactoryNoModules<F> implements TestFileFactory<KotlinBaseTest.TestModule, F> {
        @Override
        public final F createFile(
                @Nullable KotlinBaseTest.TestModule module,
                @NotNull String fileName,
                @NotNull String text,
                @NotNull Directives directives
        ) {
            return create(fileName, text, directives);
        }

        @NotNull
        public abstract F create(@NotNull String fileName, @NotNull String text, @NotNull Directives directives);

        @Override
        public KotlinBaseTest.TestModule createModule(@NotNull String name, @NotNull List<String> dependencies, @NotNull List<String> friends, @NotNull List<Integer> abiVersions) {
            return null;
        }
    }
}
