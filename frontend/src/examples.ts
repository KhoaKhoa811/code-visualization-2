// Starter programs are conveniences only; tracing never depends on their text.
export const EXAMPLES: Record<string, string> = {
  'Bubble sort': `public class Main {
    public static void main(String[] args) {
        int[] a = {5, 2, 4, 1, 3};
        for (int i = 0; i < a.length - 1; i++) {
            for (int j = 0; j < a.length - 1 - i; j++) {
                if (a[j] > a[j + 1]) {
                    int t = a[j];
                    a[j] = a[j + 1];
                    a[j + 1] = t;
                }
            }
        }
        for (int k = 0; k < a.length; k++) System.out.println(a[k]);
    }
}
`,
  'Number types': `public class Main {
    public static void main(String[] args) {
        long factorial = 1;
        for (int n = 1; n <= 15; n++) {
            factorial *= n;
        }
        double average = factorial / 15.0;
        char grade = average > 1e10 ? 'A' : 'B';
        int low = (int) (factorial & 0xFF);
        System.out.println(factorial);
        System.out.println(grade);
        System.out.println(low);
    }
}
`,
  'Strings and collections': `import java.util.*;

public class Main {
    public static void main(String[] args) {
        String text = "the cat and the hat";
        Map<String, Integer> counts = new TreeMap<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == ' ') {
                String w = word.toString();
                counts.put(w, counts.getOrDefault(w, 0) + 1);
                word.setLength(0);
            } else {
                word.append(text.charAt(i));
            }
        }
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(1);
        stack.push(2);
        stack.push(3);
        int top = stack.pop();
        Queue<String> queue = new LinkedList<>();
        queue.offer("first");
        queue.offer("second");
        String front = queue.poll();
        PriorityQueue<Integer> pq = new PriorityQueue<>();
        pq.add(5);
        pq.add(1);
        pq.add(3);
        System.out.println("the appears " + counts.get("the") + " times");
        System.out.println(top + " " + front + " " + pq.peek());
    }
}
`,
  'Linked list and tree': `public class Main {
    static class Node {
        int value;
        Node next;
        Node(int value) { this.value = value; }
    }

    static class Tree {
        int key;
        Tree left, right;
        Tree(int key) { this.key = key; }

        void insert(int k) {
            if (k < key) {
                if (left == null) left = new Tree(k); else left.insert(k);
            } else {
                if (right == null) right = new Tree(k); else right.insert(k);
            }
        }
    }

    public static void main(String[] args) {
        Node head = new Node(3);
        head.next = new Node(1);
        head.next.next = new Node(4);
        int sum = 0;
        for (Node n = head; n != null; n = n.next) sum += n.value;

        Tree root = new Tree(5);
        root.insert(2);
        root.insert(8);
        root.insert(6);
        System.out.println(sum);
        System.out.println(root.right.left.key);
    }
}
`,
  'Tree sort': `public class Main {
    static class Node {
        int key;
        Node left, right;
        Node(int key) { this.key = key; }
    }

    static Node insert(Node root, int key) {
        if (root == null) return new Node(key);
        if (key < root.key) root.left = insert(root.left, key);
        else root.right = insert(root.right, key);
        return root;
    }

    static int inorder(Node node, int[] out, int i) {
        if (node == null) return i;
        i = inorder(node.left, out, i);
        out[i] = node.key;
        i++;
        return inorder(node.right, out, i);
    }

    public static void main(String[] args) {
        int[] values = {5, 2, 8, 6, 1};
        Node root = null;
        for (int v : values) root = insert(root, v);
        int[] sorted = new int[values.length];
        inorder(root, sorted, 0);
        for (int s : sorted) System.out.print(s + " ");
        System.out.println();
    }
}
`,
  'Recursion and helpers': `public class Main {
    static int factorial(int n) {
        if (n <= 1) return 1;
        return n * factorial(n - 1);
    }

    static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    public static void main(String[] args) {
        int f = factorial(4);
        int[] a = {3, 1, 2};
        swap(a, 0, 2);
        System.out.println(f);
        System.out.println(a[0]);
    }
}
`,
  'Grid and switch': `public class Main {
    public static void main(String[] args) {
        int[][] grid = new int[3][4];
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid[r].length; c++) {
                grid[r][c] = r * c;
            }
        }
        int total = 0;
        for (int[] row : grid) {
            for (int v : row) total += v;
        }
        String[] names = {"zero", "one", "many"};
        int kind;
        switch (total) {
            case 0: kind = 0; break;
            case 1: kind = 1; break;
            default: kind = 2;
        }
        char grade = switch (kind) { case 0 -> 'C'; case 1 -> 'B'; default -> 'A'; };
        int countdown = 3;
        do {
            countdown--;
        } while (countdown > 0);
        System.out.println(total);
        System.out.println(names[kind]);
        System.out.println(grade);
    }
}
`,
  'Helper and library calls': `public class Main {
    static int square(int v) {
        return v * v;
    }

    public static void main(String[] args) {
        int[] a = {4, 2, 3, 1};
        java.util.Arrays.sort(a);
        int total = 0;
        for (int i = 0; i < a.length; i++) {
            total += square(a[i]);
        }
        System.out.println("total = " + total);
    }
}
`,
  'Exceptions': `public class Main {
    static class InsufficientFunds extends RuntimeException {
        InsufficientFunds(String message) { super(message); }
    }

    static int withdraw(int balance, int amount) {
        if (amount > balance) throw new InsufficientFunds("short by " + (amount - balance));
        return balance - amount;
    }

    public static void main(String[] args) {
        int balance = 100;
        int[] requests = {30, 500, 20};
        for (int amount : requests) {
            try {
                balance = withdraw(balance, amount);
            } catch (InsufficientFunds e) {
                System.out.println("refused: " + e.getMessage());
            } finally {
                System.out.println("balance " + balance);
            }
        }
    }
}
`,
  'Read input': `import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        System.out.print("Enter n: ");
        int n = in.nextInt();
        System.out.println(n * 2);
    }
}
`,
  'Running total': `public class Main {
    public static void main(String[] args) {
        int[] values = new int[4];
        int total = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] = i * i;
            total = total + values[i];
        }
        boolean big = total > 10 && values[3] != 0;
        System.out.println(total);
        System.out.println(big);
    }
}
`,
};
