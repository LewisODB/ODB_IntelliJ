package org.lewisodb.demo;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/*
 * Adapted from QuixBugs REVERSE_LINKED_LIST.java.
 * https://github.com/jkoppel/QuixBugs
 *
 * The benchmark defect is intentionally preserved for this debugger demo.
 * QuixBugs is available under the MIT License.
 */
public final class ReverseLinkedListDemo {
    public static Node head;
    public static Node result;
    public static Node[] nodes;

    private ReverseLinkedListDemo() {}

    public static void main(String[] args) throws Exception {
        printLaunchInputs(args);
        createInput();

        System.out.println("=== QuixBugs: Reverse Linked List ===");
        System.out.println("Input:    " + describeList(head));
        System.out.println("Expected: 1 -> 2 -> 3 -> 4 -> 5");

        result = reverseLinkedList(head);

        System.out.println("Actual:   " + describeList(result));
        System.out.println("Links:    " + describeLinks());
        System.out.println("Result:   " + (result == null ? "BUG REPRODUCED" : "UNEXPECTED PASS"));
        System.err.println("demo-stderr");
    }

    private static void printLaunchInputs(String[] args) throws Exception {
        System.out.println("property=" + System.getProperty("fixture.property"));
        System.out.println("java=" + System.getProperty("java.version"));
        System.out.println("env=" + System.getenv("FIXTURE_ENV"));
        System.out.println("cwd=" + System.getProperty("user.dir"));
        System.out.println("classpath-contains-fixture=" + System.getProperty("java.class.path").contains("odb-fixture"));
        System.out.println("integration-token-cleared=" + (System.getProperty("com.lambda.Debugger.integration.token") == null));
        System.out.println("integration-state-cleared=" + (System.getProperty("com.lambda.Debugger.integration.stateDir") == null));
        for (int i = 0; i < args.length; i++) {
            System.out.println("arg" + i + "=" + args[i]);
        }
        if (args.length > 0 && "--read-stdin".equals(args[args.length - 1])) {
            System.out.println("stdin=" + new BufferedReader(new InputStreamReader(System.in, "UTF-8")).readLine());
        }
    }

    private static void createInput() {
        Node one = new Node("1", null);
        Node two = new Node("2", one);
        Node three = new Node("3", two);
        Node four = new Node("4", three);
        Node five = new Node("5", four);

        head = five;
        nodes = new Node[] {five, four, three, two, one};
    }

    public static Node reverseLinkedList(Node node) {
        Node previous = null;
        Node next;
        int step = 0;

        while (node != null) {
            step++;
            next = node.successor;
            System.out.println(
                "step=" + step
                    + " current=" + valueOf(node)
                    + " next=" + valueOf(next)
                    + " previous=" + valueOf(previous)
            );
            node.successor = previous;
            node = next;
        }

        return previous;
    }

    private static String describeList(Node node) {
        if (node == null) {
            return "null";
        }

        StringBuilder value = new StringBuilder();
        while (node != null) {
            if (value.length() > 0) {
                value.append(" -> ");
            }
            value.append(node.value);
            node = node.successor;
        }
        return value.toString();
    }

    private static String describeLinks() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < nodes.length; i++) {
            if (i > 0) {
                value.append(", ");
            }
            value.append(nodes[i].value)
                .append("->")
                .append(valueOf(nodes[i].successor));
        }
        return value.toString();
    }

    private static String valueOf(Node node) {
        return node == null ? "null" : node.value;
    }

    public static final class Node {
        final String value;
        Node successor;

        Node(String value, Node successor) {
            this.value = value;
            this.successor = successor;
        }
    }
}
