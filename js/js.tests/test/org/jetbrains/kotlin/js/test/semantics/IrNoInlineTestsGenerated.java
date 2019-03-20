/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.js.test.semantics;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.generators.tests.TestsPackage}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("compiler/testData/codegen/boxInline/noInline")
@TestDataPath("$PROJECT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
public class IrNoInlineTestsGenerated extends AbstractIrNoInlineTests {
    private void runTest(String testDataFilePath) throws Exception {
        KotlinTestUtils.runTest0(this::doTest, TargetBackend.JS_IR, testDataFilePath);
    }

    public void testAllFilesPresentInNoInline() throws Exception {
        KotlinTestUtils.assertAllTestsPresentByMetadata(this.getClass(), new File("compiler/testData/codegen/boxInline/noInline"), Pattern.compile("^(.+)\\.kt$"), TargetBackend.JS_IR, true);
    }

    @TestMetadata("extensionReceiver.kt")
    public void testExtensionReceiver() throws Exception {
        runTest("compiler/testData/codegen/boxInline/noInline/extensionReceiver.kt");
    }

    @TestMetadata("lambdaAsGeneric.kt")
    public void testLambdaAsGeneric() throws Exception {
        runTest("compiler/testData/codegen/boxInline/noInline/lambdaAsGeneric.kt");
    }

    @TestMetadata("lambdaAsNonFunction.kt")
    public void testLambdaAsNonFunction() throws Exception {
        runTest("compiler/testData/codegen/boxInline/noInline/lambdaAsNonFunction.kt");
    }

    @TestMetadata("noInline.kt")
    public void testNoInline() throws Exception {
        runTest("compiler/testData/codegen/boxInline/noInline/noInline.kt");
    }

    @TestMetadata("noInlineLambdaChain.kt")
    public void testNoInlineLambdaChain() throws Exception {
        runTest("compiler/testData/codegen/boxInline/noInline/noInlineLambdaChain.kt");
    }

    @TestMetadata("noInlineLambdaChainWithCapturedInline.kt")
    public void testNoInlineLambdaChainWithCapturedInline() throws Exception {
        runTest("compiler/testData/codegen/boxInline/noInline/noInlineLambdaChainWithCapturedInline.kt");
    }

    @TestMetadata("withoutInline.kt")
    public void testWithoutInline() throws Exception {
        runTest("compiler/testData/codegen/boxInline/noInline/withoutInline.kt");
    }
}