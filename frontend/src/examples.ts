// Starter programs are conveniences only; tracing never depends on their text.
export const EXAMPLES: Record<string, string> = {
  // Part 16: a blank program with the common imports and main already written.
  'New program (template)': `import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Main {
    // Helper methods go here, for example:
    // static int square(int x) { return x * x; }

    public static void main(String[] args) {
        // Write your code here.
        // To read input: Scanner in = new Scanner(System.in);

    }
}
`,
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
  'Heap sort': `public class Main {
    static void siftDown(int[] a, int i, int n) {
        while (2 * i + 1 < n) {
            int child = 2 * i + 1;
            if (child + 1 < n && a[child + 1] > a[child]) child++;
            if (a[i] >= a[child]) return;
            int t = a[i];
            a[i] = a[child];
            a[child] = t;
            i = child;
        }
    }

    public static void main(String[] args) {
        int[] a = {4, 10, 3, 5, 1, 8};
        for (int i = a.length / 2 - 1; i >= 0; i--) siftDown(a, i, a.length);
        for (int end = a.length - 1; end > 0; end--) {
            int t = a[0];
            a[0] = a[end];
            a[end] = t;
            siftDown(a, 0, end);
        }
        for (int v : a) System.out.print(v + " ");
        System.out.println();
    }
}
`,
  'Custom list and ring buffer': `public class Main {
    static class Cell {
        int item;
        Cell after;
        Cell(int item, Cell after) { this.item = item; this.after = after; }
    }

    static class Ring {
        int[] slots = new int[4];
        int head, tail, count;
        void put(int v) { slots[tail] = v; tail = (tail + 1) % slots.length; count++; }
        int take() { int v = slots[head]; head = (head + 1) % slots.length; count--; return v; }
    }

    public static void main(String[] args) {
        Cell first = null;
        for (int i = 1; i <= 3; i++) first = new Cell(i * 10, first);
        Ring ring = new Ring();
        ring.put(1);
        ring.put(2);
        ring.put(3);
        int a = ring.take();
        int b = ring.take();
        ring.put(4);
        ring.put(5);
        System.out.println(first.item + " " + a + " " + b + " " + ring.count);
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
  'Fibonacci (call tree)': `public class Main {
    static int fib(int n) {
        if (n < 2) return n;
        return fib(n - 1) + fib(n - 2);
    }

    public static void main(String[] args) {
        int result = fib(4);
        System.out.println(result);
    }
}
`,
  'Graph BFS (matrix)': `import java.util.*;

public class Main {
    public static void main(String[] args) {
        int n = 5;
        int[][] g = new int[n][n];
        for (int[] row : g) Arrays.fill(row, -1);
        int[][] roads = {{0, 1, 4}, {0, 2, 1}, {2, 1, 2}, {1, 3, 5}, {2, 3, 8}, {3, 4, 3}};
        for (int[] r : roads) {
            g[r[0]][r[1]] = r[2];
            g[r[1]][r[0]] = r[2];
        }
        boolean[] visited = new boolean[n];
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        visited[0] = true;
        List<Integer> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            int u = queue.poll();
            order.add(u);
            for (int v = 0; v < n; v++) {
                if (g[u][v] != -1 && !visited[v]) {
                    visited[v] = true;
                    queue.add(v);
                }
            }
        }
        System.out.println(order);
    }
}
`,
  'Graph DFS (lists)': `import java.util.*;

public class Main {
    static void dfs(List<List<Integer>> adj, boolean[] seen, int u, List<Integer> order) {
        seen[u] = true;
        order.add(u);
        for (int v : adj.get(u)) {
            if (!seen[v]) dfs(adj, seen, v, order);
        }
    }

    public static void main(String[] args) {
        int n = 6;
        int[][] edges = {{0, 1}, {0, 2}, {1, 3}, {2, 3}, {3, 4}, {5, 4}};
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int[] e : edges) {
            adj.get(e[0]).add(e[1]);
            adj.get(e[1]).add(e[0]);
        }
        boolean[] seen = new boolean[n];
        List<Integer> order = new ArrayList<>();
        dfs(adj, seen, 0, order);
        System.out.println(order);
    }
}
`,
  'Compare algorithms': `public class Main {
    static int linearSearch(int[] a, int x) {
        for (int i = 0; i < a.length; i++) if (a[i] == x) return i;
        return -1;
    }

    static int binarySearch(int[] a, int x) {
        int lo = 0, hi = a.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (a[mid] == x) return mid;
            if (a[mid] < x) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }

    static void bubbleSort(int[] a) {
        for (int i = 0; i < a.length - 1; i++)
            for (int j = 0; j < a.length - 1 - i; j++)
                if (a[j] > a[j + 1]) { int t = a[j]; a[j] = a[j + 1]; a[j + 1] = t; }
    }

    static void mergeSort(int[] a, int lo, int hi) {
        if (lo >= hi) return;
        int mid = (lo + hi) / 2;
        mergeSort(a, lo, mid);
        mergeSort(a, mid + 1, hi);
        int[] t = new int[hi - lo + 1];
        int i = lo, j = mid + 1, k = 0;
        while (i <= mid && j <= hi) t[k++] = a[i] <= a[j] ? a[i++] : a[j++];
        while (i <= mid) t[k++] = a[i++];
        while (j <= hi) t[k++] = a[j++];
        for (k = 0; k < t.length; k++) a[lo + k] = t[k];
    }

    static int fib(int n) {
        if (n < 2) return n;
        return fib(n - 1) + fib(n - 2);
    }

    public static void main(String[] args) {
        int[] a = {5, 2, 8, 1, 9, 3};
        int[] b = {5, 2, 8, 1, 9, 3};
        bubbleSort(a);
        mergeSort(b, 0, b.length - 1);
        System.out.println(linearSearch(a, 8) + " " + binarySearch(b, 8) + " " + fib(6));
    }
}
`,
  'DP and two pointers': `public class Main {
    public static void main(String[] args) {
        // Grid paths: each cell is computed from the cell above and the cell to the left.
        int n = 4, m = 5;
        int[][] paths = new int[n][m];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < m; c++) {
                if (r == 0 || c == 0) paths[r][c] = 1;
                else paths[r][c] = paths[r - 1][c] + paths[r][c - 1];
            }
        }
        // Two pointers moving towards each other.
        int[] a = {1, 3, 4, 7, 9, 12, 15};
        int want = 19, left = 0, right = a.length - 1;
        while (left < right) {
            int sum = a[left] + a[right];
            if (sum == want) break;
            if (sum < want) left++; else right--;
        }
        // A palindrome check over the characters of a string.
        String s = "racecar";
        int i = 0, j = s.length() - 1;
        boolean palindrome = true;
        while (i < j) {
            if (s.charAt(i) != s.charAt(j)) { palindrome = false; break; }
            i++;
            j--;
        }
        System.out.println(paths[n - 1][m - 1] + " " + left + " " + right + " " + palindrome);
    }
}
`,
  'Mixed structures': `import java.util.*;

public class Main {
    // An LRU cache: a map into a doubly linked list.
    static class Node { int key, val; Node prev, next; Node(int k, int v) { key = k; val = v; } }
    static class LRU {
        int cap;
        Map<Integer, Node> map = new HashMap<>();
        Node head = new Node(0, 0), tail = new Node(0, 0);
        LRU(int c) { cap = c; head.next = tail; tail.prev = head; }
        void remove(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }
        void addFront(Node n) { n.next = head.next; n.prev = head; head.next.prev = n; head.next = n; }
        int get(int k) { Node n = map.get(k); if (n == null) return -1; remove(n); addFront(n); return n.val; }
        void put(int k, int v) {
            Node n = map.get(k);
            if (n != null) { n.val = v; remove(n); addFront(n); return; }
            if (map.size() == cap) { Node last = tail.prev; remove(last); map.remove(last.key); }
            n = new Node(k, v);
            map.put(k, n);
            addFront(n);
        }
    }

    // A trie: children kept in an array by letter.
    static class TrieNode { TrieNode[] kids = new TrieNode[26]; boolean end; }
    static void insert(TrieNode root, String w) {
        TrieNode cur = root;
        for (int i = 0; i < w.length(); i++) {
            int c = w.charAt(i) - 'a';
            if (cur.kids[c] == null) cur.kids[c] = new TrieNode();
            cur = cur.kids[c];
        }
        cur.end = true;
    }

    // A hash table with chaining: an array of linked entries.
    static class Entry { int key, val; Entry next; Entry(int k, int v, Entry n) { key = k; val = v; next = n; } }
    static class Table {
        Entry[] buckets = new Entry[4];
        void put(int k, int v) {
            int b = k % 4;
            for (Entry e = buckets[b]; e != null; e = e.next) if (e.key == k) { e.val = v; return; }
            buckets[b] = new Entry(k, v, buckets[b]);
        }
    }

    public static void main(String[] args) {
        LRU cache = new LRU(3);
        cache.put(1, 10); cache.put(2, 20); cache.put(3, 30);
        cache.get(1);
        cache.put(4, 40);
        TrieNode root = new TrieNode();
        insert(root, "to");
        insert(root, "tea");
        Table table = new Table();
        table.put(1, 10); table.put(5, 50); table.put(2, 20);
        System.out.println(cache.get(2) + " " + cache.get(1) + " " + root.kids[19].kids[4].kids[0].end + " " + table.buckets[1].val);
    }
}
`,
  'DP: Fibonacci with a memo': `import java.util.*;

public class Main {
    // Top-down: compute once, store in memo, reuse the stored value next time.
    static long fib(int n, long[] memo) {
        if (n < 2) return n;
        if (memo[n] != -1) return memo[n];
        long r = fib(n - 1, memo) + fib(n - 2, memo);
        memo[n] = r;
        return r;
    }

    public static void main(String[] args) {
        long[] memo = new long[8];
        Arrays.fill(memo, -1);
        System.out.println(fib(7, memo));
    }
}
`,
  'DP: 0/1 knapsack': `public class Main {
    public static void main(String[] args) {
        int[] weight = {1, 3, 4};
        int[] value = {15, 20, 30};
        int cap = 4;
        // best[i][c]: the best value using the first i items with capacity c.
        int[][] best = new int[weight.length + 1][cap + 1];
        for (int i = 1; i <= weight.length; i++) {
            for (int c = 0; c <= cap; c++) {
                best[i][c] = best[i - 1][c];
                if (weight[i - 1] <= c) {
                    best[i][c] = Math.max(best[i][c], best[i - 1][c - weight[i - 1]] + value[i - 1]);
                }
            }
        }
        System.out.println(best[weight.length][cap]);
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
        System.arraycopy(a, 3, a, 2, 1);
        int total = 0;
        for (int i = 0; i < a.length; i++) {
            total += square(a[i]);
        }
        System.out.println("total = " + total);
    }
}
`,
  'Iterator and format': `import java.util.*;
import java.util.stream.*;

public class Main {
    public static void main(String[] args) {
        int[] scores = new int[5];
        Arrays.fill(scores, 10);
        scores[2] = 3;
        List<Integer> kept = new ArrayList<>();
        for (int s : scores) kept.add(s);
        Iterator<Integer> it = kept.iterator();
        while (it.hasNext()) {
            int s = it.next();
            if (s < 5) it.remove();
        }
        double area = Math.pow(1.5, 2);
        List<String> tags = Stream.of("b", "a", "c").sorted().toList();
        System.out.println(String.format("%d kept, area %.2f, first %s", kept.size(), area, tags.get(0)));
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
  'Word count (map)': `import java.util.*;

public class Main {
    public static void main(String[] args) {
        String text = "the cat and the hat";
        Map<String, Integer> counts = new TreeMap<>();
        for (String word : text.split(" ")) {
            counts.merge(word, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            System.out.println(e.getKey() + ": " + e.getValue());
        }
    }
}
`,
  'Anagram check': `import java.util.Arrays;

public class Main {
    public static void main(String[] args) {
        char[] a = "listen".toCharArray();
        char[] b = "silent".toCharArray();
        Arrays.sort(a);
        Arrays.sort(b);
        System.out.println(Arrays.equals(a, b) ? "anagrams" : "different");
    }
}
`,
  'Stream pipeline': `import java.util.*;

public class Main {
    public static void main(String[] args) {
        List<Integer> scores = new ArrayList<>();
        scores.add(72);
        scores.add(45);
        scores.add(90);
        scores.add(61);
        int total = scores.stream().filter(s -> s >= 60).mapToInt(s -> s / 10).sum();
        System.out.println(total);
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
