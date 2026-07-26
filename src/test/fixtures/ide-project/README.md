# Real ODB UI smoke

`./gradlew runIde` builds the adjacent ODB checkout and opens this project with that generated runtime wired into the sandbox IDE.

1. If IntelliJ reports no project SDK, open **File | Project Structure | Project** and select the installed JDK 8. On this development machine it is `~/.sdkman/candidates/java/8.0.492-zulu`.
2. Select **ODB Fixture** in the run-configuration widget.
3. Open the executor menu beside the run widget and choose **Run with ODB**.
4. Confirm the Run console contains:

   ```text
   Bundled ODB runtime prepared.
   Loading org.lewisodb.fixture.FixtureMain with ODB...
   ODB target loaded.
   property=kept
   java=1.8.0_492
   env=kept
   cwd=<this fixture's work dir>
   classpath-contains-fixture=true
   integration-token-cleared=true
   integration-state-cleared=true
   arg0=one
   arg1=two words
   arg2=السلام
   stdin=from-stdin
   fixture-stderr
   ODB recording started.
   ODB debugger ready.
   ```

   Confirm the ODB controller and debugger windows remain usable after the target main returns.

5. Click **Stop**. Confirm the shared JVM and both ODB windows close.
6. Rerun **Run with ODB**, close an ODB window, then rerun again. Confirm each launch starts fresh.
7. Delete `out/`, rerun **Run with ODB**, and confirm the before-run Make step recreates `out/production/odb-fixture`.
8. Run **ODB Fixture** with the normal Run executor. Confirm it prints the same target output and its command line starts with `org.lewisodb.fixture.FixtureMain`, not the ODB launcher.
9. Close the project during an ODB session. Confirm its process and windows close without affecting the original run configuration.

Close the sandbox IDE to finish the Gradle task.

This is a local functional check. It does not create a distributable plugin ZIP.
