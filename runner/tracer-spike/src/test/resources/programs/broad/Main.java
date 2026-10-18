import java.util.*;
import java.util.stream.*;

public class Main {
    static final class Node {
        int value;
        Node left, right;
        Node(int value) { this.value = value; }
    }

    static Node insert(Node root, int value) {
        if (root == null) return new Node(value);
        if (value < root.value) root.left = insert(root.left, value);
        else root.right = insert(root.right, value);
        return root;
    }

    static void inorder(Node node, List<Integer> out) {
        if (node == null) return;
        inorder(node.left, out);
        out.add(node.value);
        inorder(node.right, out);
    }

    static String describe(String kind) {
        switch (kind) {
            case "even": return "E";
            case "odd": return "O";
            default: return "?";
        }
    }

    static int risky(int[] a, int i) {
        try {
            return a[i];
        } catch (ArrayIndexOutOfBoundsException e) {
            return -1;
        } finally {
            a[0]++;
        }
    }

    public static void main(String[] args) {
        int[] data = {5, 3, 8, 1, 9, 2};
        Node root = null;
        for (int v : data) root = insert(root, v);
        List<Integer> sorted = new ArrayList<>();
        inorder(root, sorted);

        Map<String, Integer> counts = new TreeMap<>();
        for (int v : sorted) counts.merge(describe(v % 2 == 0 ? "even" : "odd"), 1, Integer::sum);

        long big = 1L << 40;
        double avg = IntStream.of(data).average().orElse(0);
        String joined = sorted.stream().map(String::valueOf).collect(Collectors.joining(","));
        char[] letters = "abc".toCharArray();
        letters[1] = 'z';
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(1); stack.push(2);
        Runnable r = () -> System.out.println("lambda " + stack.peek());
        r.run();
        int caught = risky(data, 99);
        StringBuilder sb = new StringBuilder();
        for (int i = data.length - 1; i >= 0; i--) sb.append(data[i]).append(i > 0 ? "-" : "");
        System.out.println(joined + " " + counts + " " + big + " " + avg + " " + new String(letters)
                + " " + caught + " " + sb + " " + (data[0] > 4 ? "big" : "small"));
    }
}
