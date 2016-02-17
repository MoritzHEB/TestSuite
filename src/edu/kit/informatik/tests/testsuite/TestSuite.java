package edu.kit.informatik.tests.testsuite;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Testing class providing a text file with command inputs instead of
 * manual input.
 * The text file is formatted as follows:
 * <ul>
 * <li>- '&lt;arg1;arg2&gt;' at the start of the file with arg1 and arg2 as
 * command-line-arguments of testing file.</li>
 * <li>- '#' at the start of a line marks a line-comment.</li>
 * <li>- "&lt;expectedOutput&gt; : \"&lt;input&gt;\""</li>
 * </ul>
 *
 * @author Moritz Hepp
 * @version 1.0
 */
public final class TestSuite {
    /**
     * Prefix for error messages. ExpectionOutputStream will ignore output with this prefix.
     */
    public static final String ERR_PREF = "$Error: ";
    /**
     * Prefix for test messages. ExpectionOutputStream will ignore output with this prefix.
     */
    public static final String DEF_PREF = "Test: ";

    private static final String LINE_REGEX
            = "(null|00err|true|false|\"[\\w\\s]+\"|-?[\\d]+|-?[\\d]+\\.[\\d]+)\\s:\\s\"([\\w\\s;]+)\"";
    private static final String CMD_LINE_ARGS_REGEX
            = "\"[\\w\\\\/:_\\-\\.]+\"(;\"[\\w\\\\/:_\\-\\.]+\")*";

    private static final String CMD_LINE_ARGS = "$CMD_LINE_ARGS$";

    private static ExpectionInputStream sysIn;
    private static ExpectionOutputStream sysOut;

    private static File logFile;

    private TestSuite() {
    }

    /**
     * Test main to perform the tests located at $ProjectRoot/tests
     * and named *.test.
     *
     * @param args Command line arguments.
     */
    public static void main(String... args) {
        //Init
        Scanner scan = new Scanner(System.in);

        System.out.print("Path to test-Directory: ");

        File file = null;
        while (file == null || !file.exists() || !file.isDirectory()) {
            String sPathTest = scan.nextLine();
            file = new File(sPathTest);

            if (file.exists() && file.isDirectory())
                break;
            else
                System.err.println(ERR_PREF + "Given path doesn't exist or is not a directory!");
        }

        System.out.print("Name of Test-class inside same package: ");

        Class<?> cl = null;
        while (cl == null) {
            String sClassName = scan.nextLine();

            try {
                cl = TestSuite.class.getClassLoader().loadClass("edu.kit.informatik." + sClassName);
                if (cl == null) {
                    System.exit(-2);
                } else if (cl.getMethod("main", String[].class) != null) {
                    break;
                }
            } catch (ClassNotFoundException e) {
                System.err.println(ERR_PREF + "Class " + sClassName + " doesn't exist in this package!");
                cl = null;
            } catch (NoSuchMethodException ex) {
                System.err.println(ERR_PREF + "Class " + sClassName + " doesn't have a static main function or "
                        + "default contructor!");
                cl = null;
            } catch (NoClassDefFoundError exx) {
                System.err.println(ERR_PREF + exx.getMessage());
                cl = null;
            }
        }

        File logDir = new File(file.getAbsolutePath() + "/logs/");
        logDir.mkdir();

        File[] files = file.listFiles((dir, name) -> name.endsWith(".test"));
        if (files != null && files.length > 0) {
            for (File f : files) {
                System.out.println(DEF_PREF + "## file: " + f.getName());
                String[] fileLines = readTestFile(f.getPath());
                if (fileLines != null) {
                    Map<String, ?> cas = convert(fileLines);

                    File logFile = new File(logDir.getAbsoluteFile() + "/" + f.getName().replace(".test", "Test.log"));

                    testFile(cl, cas, logFile);
                } else {
                    System.err.println(ERR_PREF + "Bad formatted file: " + f.getName());
                }
            }
            System.exit(1);
        } else {
            System.err.println(ERR_PREF + "Directory \"" + file.getAbsolutePath() + "\" doesn't contain test files!");
        }
    }

    /**
     * Performs the tests one file is representing.
     *
     * @param testClass Class to be tested.
     * @param cas       Assertions with in and output expected.
     * @param logFile   File to store the output of this test.
     */
    public static void testFile(Class<?> testClass, Map<String, ?> cas, File logFile) {
        if (!cas.isEmpty()) {
            try {
                Method main = testClass.getMethod("main", String[].class);

                Object arguments = null;
                if (cas.containsKey(CMD_LINE_ARGS)) {
                    arguments = cas.get(CMD_LINE_ARGS);
                    cas.remove(CMD_LINE_ARGS);
                }
                initInOutput(cas, logFile);

                main.invoke(null, (Object) arguments);

                resetInOutputSettings();
            } catch (NoSuchMethodException | InvocationTargetException
                    | IllegalAccessException | IOException e) {
                System.err.println(ERR_PREF + "Something went wrong while testing!");
                e.printStackTrace();
            }
        } else {
            System.err.println(ERR_PREF + "Empty test-file!");
        }
    }

    private static void resetInOutputSettings() {
        int count = sysOut.getCount();
        if (count < sysOut.getExpectationSize()) {
            System.err.println(ERR_PREF + "Expected output count: " + count
                    + ", actual: " + sysOut.getExpectationSize());
        } else if (sysIn.isExpecting()) {
            System.err.println(ERR_PREF + "Expected input!");
        } else {
            System.setOut(sysOut.getNextStream());
            sysOut = null;
            sysIn = null;
        }
    }

    private static <E> void initInOutput(Map<String, E> map, File logFile) throws IOException {
        List<E> expected = new LinkedList<>();
        List<String> inputs = new LinkedList<>();

        map.keySet().stream().filter(input -> input != null).forEach(input -> {
            inputs.add(input);
            if (map.get(input) != null)
                expected.add(map.get(input));
        });

        TestSuite.sysOut = new ExpectionOutputStream(expected, System.out, logFile);
        TestSuite.sysIn = new ExpectionInputStream(inputs);

        try {
            sysIn.connect(sysOut);
            sysIn.setExpecting(true);
        } catch (IOException e) {
            System.err.println(ERR_PREF + e.getMessage());
            System.exit(-1);
            return;
        }

        System.setOut(new PrintStream(TestSuite.sysOut));
        System.setIn(TestSuite.sysIn);
    }

    private static String[] readTestFile(String path) {
        String[] lines = null;
        if (path != null) {
            File testFile;
            if (path.matches("[\\w]+")) {
                testFile = new File(System.getProperty("user.dir")
                        + File.pathSeparatorChar + path);
            } else {
                testFile = new File(path);
            }
            if (testFile.exists()) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(testFile));
                    List<String> list = new LinkedList<>();
                    while (reader.ready()) {
                        String nLine = reader.readLine();
                        if (nLine != null) {
                            if (nLine.matches("\"[\\w\\s]+") && reader.ready()) {
                                String next;
                                boolean cont = true;
                                while (cont) {
                                    next = reader.readLine();
                                    nLine += "\n" + next;
                                    if (next.matches("[\\w\\s]+\"\\s:\\s\"[\\w\\s;]+\"")) {
                                        cont = false;
                                    } else if (!reader.ready()) {
                                        nLine = "";
                                        cont = false;
                                    }
                                }
                            }
                            nLine = nLine.replace(System.lineSeparator(), "\\n");
                            if (nLine.matches(LINE_REGEX)) {
                                list.add(nLine);
                            } else if (nLine.matches("<" + CMD_LINE_ARGS_REGEX + ">")) {
                                if (list.size() == 0) {
                                    String args = nLine.replace("<", "").replace(">", "");
                                    list.add(args);
                                } else {
                                    list = null;
                                    break;
                                }
                            } else if (!nLine.matches("#.*") && !nLine.isEmpty()) {
                                list = null;
                                break;
                            }
                        }
                    }
                    if (list != null) {
                        lines = list.toArray(new String[list.size()]);
                    }
                } catch (IOException e) {
                    System.err.println(ERR_PREF + "Something went wrong while reading test File: " + e.getMessage());
                }
            }
        }
        return lines;
    }

    private static Map<String, ?> convert(String[] lines) {
        Map<String, Object> cases = null;
        if (lines != null) {
            cases = new LinkedHashMap<>();
            for (String line : lines) {
                if (line != null) {
                    if (line.matches(CMD_LINE_ARGS_REGEX)) {
                        cases.put(CMD_LINE_ARGS, line.replace("\"", "").split(";"));
                    } else {
                        Pattern pat = Pattern.compile(LINE_REGEX);
                        Matcher match = pat.matcher(line);
                        if (match.matches() && match.groupCount() == 2) {
                            String expected = match.group(1);
                            String input = match.group(2);

                            cases.put(input, expected.replace("\"", ""));
                        }
                    }
                }
            }
            if (cases.size() == 0)
                cases = null;
        }
        return cases;
    }
}