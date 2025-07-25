/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.drivers;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.StringJoiner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ErrorReporter;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.tools.shell.Global;
import org.mozilla.javascript.tools.shell.Main;
import org.mozilla.javascript.tools.shell.ShellContextFactory;

/**
 * @version $Id: ShellTest.java,v 1.14 2011/03/29 15:17:49 hannes%helma.at Exp $
 */
public class ShellTest {
    private static File frameworkFile;
    private static Script frameworkScript;

    public static void cacheFramework() {
        frameworkFile = new File("testsrc/tests/shell.js");
        if (!frameworkFile.exists()) {
            throw new AssertionError("Can't find test framework file " + frameworkFile);
        }

        try (Context cx = Context.enter()) {
            frameworkScript = cx.compileReader(new FileReader(frameworkFile), "shell.js", 1, null);
        } catch (IOException ioe) {
            throw new AssertionError("Can't read test framework file " + frameworkFile);
        }
    }

    public static final FileFilter DIRECTORY_FILTER =
            pathname -> pathname.isDirectory() && !pathname.getName().equals("CVS");

    public static final FileFilter TEST_FILTER =
            pathname ->
                    pathname.getName().endsWith(".js")
                            && !pathname.getName().equals("shell.js")
                            && !pathname.getName().equals("browser.js")
                            && !pathname.getName().equals("template.js");

    public static String getStackTrace(Throwable t) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        t.printStackTrace(new PrintStream(bytes));
        return bytes.toString();
    }

    private static void runFileIfExists(Context cx, Scriptable global, File f) {
        if (frameworkFile.equals(f)) {
            try {
                frameworkScript.exec(cx, global, global);
            } catch (RhinoException re) {
                // Error in test framework means that the whole world is broken.
                throw new AssertionError(re);
            }
        } else {
            if (f.isFile()) {
                Main.processFileNoThrow(cx, global, f.getPath());
            }
        }
    }

    private static class TestState {
        boolean finished;
        ErrorReporterWrapper errors;
        int exitCode = 0;
    }

    public abstract static class Status {
        private boolean negative;

        public final void setNegative() {
            this.negative = true;
        }

        public final boolean isNegative() {
            return this.negative;
        }

        public final void hadErrors(JsError[] errors) {
            if (!negative && errors.length > 0) {
                failed("JavaScript errors:\n" + JsError.toString(errors));
            } else if (negative && errors.length == 0) {
                failed("Should have produced runtime error.");
            }
        }

        public final void hadErrors(File jsFile, JsError[] errors) {
            if (!negative && errors.length > 0) {
                failed("JavaScript errors in " + jsFile + ":\n" + JsError.toString(errors));
            } else if (negative && errors.length == 0) {
                failed("Should have produced runtime error in " + jsFile + ".");
            }
        }

        public abstract void running(File jsFile);

        public abstract void failed(String s);

        public abstract void threw(Throwable t);

        public abstract void timedOut(long timeoutMillis);

        public abstract void exitCodesWere(int expected, int actual);

        public abstract void outputWas(String s);

        static Status compose(final Status[] array) {
            return new Status() {
                @Override
                public void running(File file) {
                    for (Status status : array) {
                        status.running(file);
                    }
                }

                @Override
                public void threw(Throwable t) {
                    for (Status status : array) {
                        status.threw(t);
                    }
                }

                @Override
                public void failed(String s) {
                    for (Status status : array) {
                        status.failed(s);
                    }
                }

                @Override
                public void exitCodesWere(int expected, int actual) {
                    for (Status status : array) {
                        status.exitCodesWere(expected, actual);
                    }
                }

                @Override
                public void outputWas(String s) {
                    for (Status status : array) {
                        status.outputWas(s);
                    }
                }

                @Override
                public void timedOut(long timeoutMillis) {
                    for (Status status : array) {
                        status.timedOut(timeoutMillis);
                    }
                }
            };
        }

        static class JsError {
            static String toString(JsError[] e) {
                var joiner = new StringJoiner("\n");
                for (var error : e) {
                    joiner.add(error.toString());
                }
                return joiner.toString();
            }

            private String message;
            private String sourceName;
            private int line;
            private String lineSource;
            private int lineOffset;

            JsError(
                    String message,
                    String sourceName,
                    int line,
                    String lineSource,
                    int lineOffset) {
                this.message = message;
                this.sourceName = sourceName;
                this.line = line;
                this.lineSource = lineSource;
                this.lineOffset = lineOffset;
            }

            @Override
            public String toString() {
                String locationLine = "";
                if (sourceName != null) locationLine += sourceName + ":";
                if (line != 0) locationLine += line + ": ";
                locationLine += message;
                String sourceLine = this.lineSource;
                String errCaret = null;
                if (lineSource != null) {
                    errCaret = "";
                    for (int i = 0; i < lineSource.length(); i++) {
                        char c = lineSource.charAt(i);
                        if (i < lineOffset - 1) {
                            if (c == '\t') {
                                errCaret += "\t";
                            } else {
                                errCaret += " ";
                            }
                        } else if (i == lineOffset - 1) {
                            errCaret += "^";
                        }
                    }
                }
                String rv = locationLine;
                if (sourceLine != null) {
                    rv += "\n" + sourceLine;
                }
                if (errCaret != null) {
                    rv += "\n" + errCaret;
                }
                return rv;
            }

            String getMessage() {
                return message;
            }

            String getSourceName() {
                return sourceName;
            }

            int getLine() {
                return line;
            }

            String getLineSource() {
                return lineSource;
            }

            int getLineOffset() {
                return lineOffset;
            }
        }
    }

    private static class ErrorReporterWrapper implements ErrorReporter {
        private ErrorReporter original;
        private ArrayList<Status.JsError> errors = new ArrayList<Status.JsError>();

        ErrorReporterWrapper(ErrorReporter original) {
            this.original = original;
        }

        private void addError(String string, String string0, int i, String string1, int i0) {
            errors.add(new Status.JsError(string, string0, i, string1, i0));
        }

        @Override
        public void warning(String string, String string0, int i, String string1, int i0) {
            original.warning(string, string0, i, string1, i0);
        }

        @Override
        public EvaluatorException runtimeError(
                String string, String string0, int i, String string1, int i0) {
            return original.runtimeError(string, string0, i, string1, i0);
        }

        @Override
        public void error(String string, String string0, int i, String string1, int i0) {
            addError(string, string0, i, string1, i0);
        }
    }

    public abstract static class Parameters {
        public abstract int getTimeoutMilliseconds();
    }

    @SuppressWarnings(value = {"deprecation"})
    private static void callStop(Thread t) {
        t.stop();
    }

    public static void run(
            final ShellContextFactory shellContextFactory,
            final File jsFile,
            final Parameters parameters,
            final Status status)
            throws Exception {
        final Global global = new Global();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintStream p = new PrintStream(out);
        global.setOut(p);
        global.setErr(p);
        // test suite expects keywords to be disallowed as identifiers
        shellContextFactory.setAllowReservedKeywords(false);
        final TestState testState = new TestState();
        if (jsFile.getName().endsWith("-n.js")) {
            status.setNegative();
        }
        final Throwable[] thrown = {null};

        Thread thread =
                new Thread(
                        () -> {
                            try (var cx = shellContextFactory.enterContext()) {
                                status.running(jsFile);
                                testState.errors = new ErrorReporterWrapper(cx.getErrorReporter());
                                cx.setErrorReporter(testState.errors);
                                global.init(cx);

                                // invoke after init(...) to make sure ClassCache is available for
                                // FunctionObject
                                global.defineFunctionProperties(
                                        new String[] {"options"},
                                        ShellTest.class,
                                        ScriptableObject.DONTENUM
                                                | ScriptableObject.PERMANENT
                                                | ScriptableObject.READONLY);

                                try {
                                    runFileIfExists(
                                            cx,
                                            global,
                                            new File(
                                                    jsFile.getParentFile()
                                                            .getParentFile()
                                                            .getParentFile(),
                                                    "shell.js"));
                                    runFileIfExists(
                                            cx,
                                            global,
                                            new File(
                                                    jsFile.getParentFile().getParentFile(),
                                                    "shell.js"));
                                    runFileIfExists(
                                            cx,
                                            global,
                                            new File(jsFile.getParentFile(), "shell.js"));
                                    runFileIfExists(cx, global, jsFile);
                                    status.hadErrors(
                                            jsFile,
                                            testState.errors.errors.toArray(new Status.JsError[0]));
                                } catch (ThreadDeath e) {
                                } catch (Throwable t) {
                                    status.threw(t);
                                }
                            } catch (Error t) {
                                thrown[0] = t;
                            } catch (RuntimeException t) {
                                thrown[0] = t;
                            } finally {
                                synchronized (testState) {
                                    testState.finished = true;
                                }
                            }
                        },
                        jsFile.getPath());
        thread.setDaemon(true);
        thread.start();
        thread.join(parameters.getTimeoutMilliseconds());
        synchronized (testState) {
            if (!testState.finished) {
                callStop(thread);
                status.timedOut(parameters.getTimeoutMilliseconds());
            }
        }
        int expectedExitCode = 0;
        p.flush();
        status.outputWas(out.toString());
        BufferedReader r =
                new BufferedReader(
                        new InputStreamReader(new ByteArrayInputStream(out.toByteArray())));
        String failures = "";
        for (; ; ) {
            String s = r.readLine();
            if (s == null) {
                break;
            }
            if (s.contains("FAILED!")) {
                failures += s + '\n';
            }
            int expex = s.indexOf("EXPECT EXIT CODE ");
            if (expex != -1) {
                expectedExitCode = s.charAt(expex + "EXPECT EXIT CODE ".length()) - '0';
            }
        }
        if (thrown[0] != null) {
            status.threw(thrown[0]);
        }
        status.exitCodesWere(expectedExitCode, testState.exitCode);
        if (!failures.isEmpty()) {
            status.failed(failures);
        }
    }

    public static void runNoFork(
            final ShellContextFactory shellContextFactory,
            final File jsFile,
            final Parameters parameters,
            final Status status)
            throws Exception {
        final Global global = new Global();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintStream p = new PrintStream(out);
        global.setOut(p);
        global.setErr(p);
        // test suite expects keywords to be disallowed as identifiers
        shellContextFactory.setAllowReservedKeywords(false);
        final TestState testState = new TestState();
        if (jsFile.getName().endsWith("-n.js")) {
            status.setNegative();
        }
        final Throwable[] thrown = {null};

        try (var cx = shellContextFactory.enterContext()) {
            status.running(jsFile);
            testState.errors = new ErrorReporterWrapper(cx.getErrorReporter());
            cx.setErrorReporter(testState.errors);
            global.init(cx);

            // invoke after init(...) to make sure ClassCache is available for FunctionObject
            global.defineFunctionProperties(
                    new String[] {"options"},
                    ShellTest.class,
                    ScriptableObject.DONTENUM
                            | ScriptableObject.PERMANENT
                            | ScriptableObject.READONLY);

            try {
                runFileIfExists(
                        cx,
                        global,
                        new File(
                                jsFile.getParentFile().getParentFile().getParentFile(),
                                "shell.js"));
                runFileIfExists(
                        cx, global, new File(jsFile.getParentFile().getParentFile(), "shell.js"));
                runFileIfExists(cx, global, new File(jsFile.getParentFile(), "shell.js"));
                runFileIfExists(cx, global, jsFile);
                status.hadErrors(jsFile, testState.errors.errors.toArray(new Status.JsError[0]));
            } catch (Throwable t) {
                status.threw(t);
            }
        } catch (Error t) {
            thrown[0] = t;
        } catch (RuntimeException t) {
            thrown[0] = t;
        } finally {
            testState.finished = true;
        }

        int expectedExitCode = 0;
        p.flush();
        status.outputWas(out.toString());
        BufferedReader r =
                new BufferedReader(
                        new InputStreamReader(new ByteArrayInputStream(out.toByteArray())));
        String failures = "";
        for (; ; ) {
            String s = r.readLine();
            if (s == null) {
                break;
            }
            if (s.contains("FAILED!")) {
                failures += s + '\n';
            }
            int expex = s.indexOf("EXPECT EXIT CODE ");
            if (expex != -1) {
                expectedExitCode = s.charAt(expex + "EXPECT EXIT CODE ".length()) - '0';
            }
        }
        if (thrown[0] != null) {
            status.threw(thrown[0]);
        }
        status.exitCodesWere(expectedExitCode, testState.exitCode);
        if (!failures.isEmpty()) {
            status.failed(failures);
        }
    }

    // Global function to mimic options() function in spidermonkey.
    // It looks like this toggles jit compiler mode in spidermonkey
    // when called with "jit" as argument. Our version is a no-op
    // and returns an empty string.
    public static String options() {
        return "";
    }
}
