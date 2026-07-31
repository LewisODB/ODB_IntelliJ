# Real ODB UI demo

`./gradlew runIde` builds the adjacent ODB checkout and opens this project with that runtime.

## Start the demo

1. If IntelliJ reports no project SDK, open **File | Project Structure | Project** and select an installed JDK 8. On this development machine it is `~/.sdkman/candidates/java/8.0.492-zulu`.
2. Select **ODB Reverse Linked List Demo** in the run-configuration widget.
3. Open the executor menu beside the run widget and choose **Run with ODB**.
4. Confirm ODB displays [`ReverseLinkedListDemo.java`](../java8/application/org/lewisodb/demo/ReverseLinkedListDemo.java) without opening the **Source File** chooser.
5. Confirm the Run console contains:

   ```text
   Bundled ODB runtime prepared.
   Loading org.lewisodb.demo.ReverseLinkedListDemo with ODB...
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
   === QuixBugs: Reverse Linked List ===
   Input:    5 -> 4 -> 3 -> 2 -> 1
   Expected: 1 -> 2 -> 3 -> 4 -> 5
   step=1 current=5 next=4 previous=null
   step=2 current=4 next=3 previous=null
   step=3 current=3 next=2 previous=null
   step=4 current=2 next=1 previous=null
   step=5 current=1 next=null previous=null
   Actual:   null
   Links:    5->null, 4->null, 3->null, 2->null, 1->null
   Result:   BUG REPRODUCED
   demo-stderr
   ODB recording started.
   ODB debugger ready.
   ```

The loop clears each node's `successor` but never advances `previous`. It returns `null` and leaves all five nodes disconnected.

## Explore the recording

1. Select `ReverseLinkedListDemo.main` in the Trace pane. The `this` pane shows the class and its static `head`, `result`, and `nodes` fields.
2. Double-click `head` or `nodes` to add the retained objects to the Objects pane.
3. Expand `head`, select `successor`, then use **Previous Value** and **Next Value**. It changes from node `4` to `null`.
4. Inspect the other entries in `nodes`. Each `successor` changes from the next node to `null`.
5. Select `reverseLinkedList` in the Trace pane. Step through its calls and locals. `previous` remains `null` during every loop iteration.
6. Select a `step=` line in the I/O pane to jump to that iteration.

This recording exercises object creation, linked objects, an object array, local variables, repeated field changes, method tracing, output history, and backward or forward navigation.

## Plugin smoke checks

1. Click **Stop**. Confirm the shared JVM and both ODB windows close.
2. Rerun **Run with ODB**, close an ODB window, then rerun again. Confirm each launch starts fresh.
3. Delete `out/`, rerun **Run with ODB**, and confirm the before-run Make step recreates `out/production/odb-fixture`.
4. Run **ODB Reverse Linked List Demo** with normal Run. Confirm it prints the same target output and its command line starts with `org.lewisodb.demo.ReverseLinkedListDemo`, not the ODB launcher.
5. Close the project during an ODB session. Confirm its process and windows close without affecting the original run configuration.

Close the sandbox IDE to finish the Gradle task.

This is a local functional check. It does not create a distributable plugin ZIP.
