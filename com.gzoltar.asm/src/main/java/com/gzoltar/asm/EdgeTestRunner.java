/**
 * Copyright (C) 2020 GZoltar contributors.
 *
 * This file is part of GZoltar.
 *
 * GZoltar is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * GZoltar is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with GZoltar. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package com.gzoltar.asm;

import com.gzoltar.asm.runtime.GlobalProbes;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * Test runner for edge-instrumented classes.
 * Runs tests, collects edge coverage, recovers node coverage, and generates ranking.
 */
public class EdgeTestRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: java EdgeTestRunner <instrumentedDir> <testClassDir> <probeCount> <testClass#method> ...");
            System.exit(1);
        }

        File instrumentedDir = new File(args[0]);
        File testClassDir = new File(args[1]);
        int probeCount = Integer.parseInt(args[2]);

        // Initialize probes
        GlobalProbes.init(probeCount);
        System.out.println("[EdgeRunner] Initialized " + probeCount + " edge probes");

        // Build classpath
        List<URL> urls = new ArrayList<>();
        urls.add(instrumentedDir.toURI().toURL());
        urls.add(testClassDir.toURI().toURL());

        String classpath = System.getProperty("java.class.path");
        for (String cp : classpath.split(File.pathSeparator)) {
            urls.add(new File(cp).toURI().toURL());
        }

        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]),
            EdgeTestRunner.class.getClassLoader());

        // Results
        List<String> testNames = new ArrayList<>();
        List<Boolean> testResults = new ArrayList<>();  // true = pass
        List<boolean[]> edgeCoverageResults = new ArrayList<>();

        // Run each test
        for (int i = 3; i < args.length; i++) {
            String testSpec = args[i].trim();
            if (testSpec.isEmpty()) continue;

            String[] parts = testSpec.split("#");
            String className = parts[0];
            String methodName = parts.length > 1 ? parts[1] : null;

            // Reset probes
            GlobalProbes.reset();

            boolean passed = false;
            String failReason = "";

            try {
                Class<?> testClass = loader.loadClass(className);
                Object testInstance = testClass.getDeclaredConstructor().newInstance();

                // Run @Before methods
                for (Method m : testClass.getMethods()) {
                    if (hasAnnotation(m, "org.junit.Before")) {
                        m.invoke(testInstance);
                    }
                }

                // Run test method
                Method method = testClass.getMethod(methodName);
                Class<?> expectedException = getExpectedException(method);

                try {
                    method.invoke(testInstance);
                    if (expectedException != null) {
                        passed = false;
                        failReason = "Expected " + expectedException.getSimpleName();
                    } else {
                        passed = true;
                    }
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (expectedException != null && expectedException.isInstance(cause)) {
                        passed = true;
                    } else if (cause instanceof AssertionError) {
                        passed = false;
                        failReason = "assertion";
                    } else {
                        passed = false;
                        failReason = cause.getClass().getSimpleName();
                    }
                }

                // Run @After methods
                for (Method m : testClass.getMethods()) {
                    if (hasAnnotation(m, "org.junit.After")) {
                        try { m.invoke(testInstance); } catch (Exception e) {}
                    }
                }

            } catch (Exception e) {
                passed = false;
                failReason = e.getClass().getSimpleName();
            }

            // Get edge coverage
            boolean[] edgeCoverage = GlobalProbes.snapshot();

            // Store results
            testNames.add(testSpec);
            testResults.add(passed);
            edgeCoverageResults.add(edgeCoverage);

            int coveredEdges = 0;
            for (boolean b : edgeCoverage) if (b) coveredEdges++;

            System.out.println("[EdgeRunner] " + (passed ? "PASS" : "FAIL") + " " + testSpec +
                " (edges: " + coveredEdges + "/" + probeCount + ")");
        }

        loader.close();

        // Output tests.csv
        System.out.println("\n=== tests.csv ===");
        System.out.println("name,outcome,runtime,stacktrace");
        for (int i = 0; i < testNames.size(); i++) {
            System.out.println(testNames.get(i) + "," + (testResults.get(i) ? "PASS" : "FAIL") + ",0,");
        }

        // Output matrix.txt (edge coverage - direct, no recovery)
        System.out.println("\n=== matrix.txt ===");
        for (int i = 0; i < edgeCoverageResults.size(); i++) {
            boolean[] coverage = edgeCoverageResults.get(i);
            StringBuilder sb = new StringBuilder();
            for (boolean b : coverage) {
                sb.append(b ? "1 " : "0 ");
            }
            sb.append(testResults.get(i) ? "-" : "+");
            System.out.println(sb.toString());
        }

        System.out.println("\n=== Done ===");
    }

    private static boolean hasAnnotation(Method method, String annotationName) {
        for (java.lang.annotation.Annotation ann : method.getAnnotations()) {
            if (ann.annotationType().getName().equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> getExpectedException(Method method) {
        try {
            org.junit.Test testAnn = method.getAnnotation(org.junit.Test.class);
            if (testAnn != null) {
                Class<?> expected = testAnn.expected();
                if (!expected.equals(org.junit.Test.None.class)) {
                    return expected;
                }
            }
        } catch (Exception e) {}
        return null;
    }
}
