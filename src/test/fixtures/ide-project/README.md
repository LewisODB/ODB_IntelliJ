# Finite-probe UI smoke

`./gradlew runIde` opens this project with the test-only probe wired into the sandbox IDE.

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
   arg0=one
   arg1=two words
   arg2=السلام
   stdin=from-stdin
   fixture-stderr
   ODB recording started.
   ODB debugger ready.
   ```

5. Delete `out/`, rerun **Run with ODB**, and confirm the before-run Make step recreates `out/production/odb-fixture`.
6. Run **ODB Fixture** with the normal Run executor. Confirm it prints the same target output and its command line starts with `org.lewisodb.fixture.FixtureMain`, not the probe launcher.

Close the sandbox IDE to finish the Gradle task.
