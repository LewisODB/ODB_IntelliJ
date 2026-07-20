package com.lambda.Debugger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.PrintStream;
import java.util.Arrays;

public final class IntegrationLauncher {
    private static final String PREFIX = "@@ODB-INTEGRATION@@\t";
    private static long sequence;
    private static PrintStream eventOutput;

    private IntegrationLauncher() {}

    public static void main(String[] args) throws Exception {
        eventOutput = System.err;
        if (args.length == 0) {
            throw new IllegalArgumentException("Missing target main class");
        }
        String targetMain = args[0];
        String[] targetArgs = Arrays.copyOfRange(args, 1, args.length);
        String token = System.getProperty("com.lambda.Debugger.integration.token");
        String stateDirectory = System.getProperty("com.lambda.Debugger.integration.stateDir");
        System.clearProperty("com.lambda.Debugger.integration.token");
        System.clearProperty("com.lambda.Debugger.integration.stateDir");
        requireToken(token);
        requireStateDirectory(stateDirectory);
        event(token, "runtime-ready", "\"target\":\"" + escape(targetMain) + "\"");
        String mode = mode(targetArgs);
        if ("fatal".equals(mode)) {
            event(token, "fatal", "\"code\":\"NO_RECORDING\",\"message\":\"No usable recording\"");
            System.exit(1);
        }
        if ("crash".equals(mode)) System.exit(7);
        if ("bad-protocol".equals(mode)) {
            eventOutput.println(PREFIX + "ffffffffffffffffffffffffffffffff\t{\"version\":1,\"sequence\":2,\"type\":\"debugger-ready\",\"created\":2,\"retained\":2}");
            eventOutput.println(PREFIX + token + "\t{bad json}");
        }
        Method main = Class.forName(targetMain).getMethod("main", String[].class);
        event(token, "target-loaded", "\"target\":\"" + escape(targetMain) + "\"");
        try {
            main.invoke(null, new Object[] {targetArgs});
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
        event(token, "recording-started", "\"created\":3,\"retained\":2");
        event(token, "debugger-ready", "\"created\":4,\"retained\":3");
        if ("wait-for-stop".equals(mode)) {
            Thread.sleep(15000L);
            System.exit(124);
        }
    }

    private static void requireToken(String token) {
        if (token == null || !token.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid token");
    }

    private static void requireStateDirectory(String value) {
        if (value == null || !new java.io.File(value).isDirectory() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalArgumentException("Invalid state directory");
        }
    }

    private static String mode(String[] args) {
        for (String arg : args) if (arg.startsWith("--odb-probe-mode=")) return arg.substring(17);
        return "success";
    }

    private static void event(String token, String type, String data) {
        eventOutput.println(PREFIX + token + "\t{\"version\":1,\"sequence\":" + (++sequence) +
                ",\"type\":\"" + type + "\"," + data + "}");
        eventOutput.flush();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') escaped.append("\\\\");
            else if (character == '\"') escaped.append("\\\"");
            else if (character < 0x20 || character > 0x7e) escaped.append(String.format("\\u%04x", (int) character));
            else escaped.append(character);
        }
        return escaped.toString();
    }
}
