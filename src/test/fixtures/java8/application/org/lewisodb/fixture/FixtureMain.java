package org.lewisodb.fixture;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;

public final class FixtureMain {
    private FixtureMain() {}

    public static void main(String[] args) throws Exception {
        System.out.println("property=" + System.getProperty("fixture.property"));
        System.out.println("java=" + System.getProperty("java.version"));
        System.out.println("env=" + System.getenv("FIXTURE_ENV"));
        System.out.println("cwd=" + System.getProperty("user.dir"));
        System.out.println("integration-token-cleared=" + (System.getProperty("com.lambda.Debugger.integration.token") == null));
        System.out.println("integration-state-cleared=" + (System.getProperty("com.lambda.Debugger.integration.stateDir") == null));
        for (int i = 0; i < args.length; i++) {
            System.out.println("arg" + i + "=" + args[i]);
        }
        if (args.length > 0 && "--read-stdin".equals(args[args.length - 1])) {
            System.out.println("stdin=" + new BufferedReader(new InputStreamReader(System.in, "UTF-8")).readLine());
        }
        if (java.util.Arrays.asList(args).contains("--replace-stderr")) {
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
        }
        System.err.println("fixture-stderr");
    }
}
