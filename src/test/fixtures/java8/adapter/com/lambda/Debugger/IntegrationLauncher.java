package com.lambda.Debugger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class IntegrationLauncher {
    private IntegrationLauncher() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Missing target main class");
        }
        String targetMain = args[0];
        String[] targetArgs = Arrays.copyOfRange(args, 1, args.length);
        Method main = Class.forName(targetMain).getMethod("main", String[].class);
        try {
            main.invoke(null, new Object[] {targetArgs});
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }
}
