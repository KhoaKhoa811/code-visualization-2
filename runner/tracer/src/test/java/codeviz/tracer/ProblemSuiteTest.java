package codeviz.tracer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 10d, AC-36: the agreed problem suite ({@code specs/problem-suite.md}). Every program, written
 * in its own style, must trace with complete capture and no uncaptured statement, behave exactly
 * like the original program, give the expected output, pass the shared trace-v1 checker, and
 * survive the plan round trip. No program is recognized: they all use the same capabilities.
 * The out-of-scope forms must still run, with their statement uncaptured and diagnosed.
 */
class ProblemSuiteTest {
    @TempDir Path temp;

    static String main(String body) { return "import java.util.*;\npublic class Main {\n" + body + "\n}\n"; }

    static final Map<String, String> P = new LinkedHashMap<>();
    static {
        P.put("A1 insertion sort", main("""
            public static void main(String[] args) {
                int[] a = {5, 2, 4, 6, 1, 3};
                for (int i = 1; i < a.length; i++) {
                    int key = a[i]; int j = i - 1;
                    while (j >= 0 && a[j] > key) { a[j + 1] = a[j]; j--; }
                    a[j + 1] = key;
                }
                System.out.println(Arrays.toString(a));
            }"""));
        P.put("A1b insertion sort, no toString", main("""
            public static void main(String[] args) {
                int[] a = {5, 2, 4, 6, 1, 3};
                for (int i = 1; i < a.length; i++) {
                    int key = a[i]; int j = i - 1;
                    while (j >= 0 && a[j] > key) { a[j + 1] = a[j]; j--; }
                    a[j + 1] = key;
                }
                for (int x : a) System.out.print(x + " ");
            }"""));
        P.put("A2 selection sort with swap helper", main("""
            static void swap(int[] a, int i, int j) { int t = a[i]; a[i] = a[j]; a[j] = t; }
            public static void main(String[] args) {
                int[] nums = {3, 1, 2, 5, 4};
                for (int i = 0; i < nums.length - 1; i++) {
                    int m = i;
                    for (int j = i + 1; j < nums.length; j++) if (nums[j] < nums[m]) m = j;
                    if (m != i) swap(nums, i, m);
                }
                System.out.println(nums[0] + " " + nums[4]);
            }"""));
        P.put("A3 quicksort", main("""
            static int partition(int[] a, int lo, int hi) {
                int p = a[hi], i = lo - 1;
                for (int j = lo; j < hi; j++) if (a[j] <= p) { i++; int t = a[i]; a[i] = a[j]; a[j] = t; }
                int t = a[i + 1]; a[i + 1] = a[hi]; a[hi] = t;
                return i + 1;
            }
            static void sort(int[] a, int lo, int hi) {
                if (lo >= hi) return;
                int q = partition(a, lo, hi);
                sort(a, lo, q - 1); sort(a, q + 1, hi);
            }
            public static void main(String[] args) {
                int[] a = {9, 4, 7, 1, 8, 2};
                sort(a, 0, a.length - 1);
                System.out.println(a[0] + "," + a[5]);
            }"""));
        P.put("A4 merge sort", main("""
            static void mergeSort(int[] a) {
                if (a.length < 2) return;
                int mid = a.length / 2;
                int[] l = new int[mid], r = new int[a.length - mid];
                for (int i = 0; i < mid; i++) l[i] = a[i];
                for (int i = mid; i < a.length; i++) r[i - mid] = a[i];
                mergeSort(l); mergeSort(r);
                int i = 0, j = 0, k = 0;
                while (i < l.length && j < r.length) a[k++] = l[i] <= r[j] ? l[i++] : r[j++];
                while (i < l.length) a[k++] = l[i++];
                while (j < r.length) a[k++] = r[j++];
            }
            public static void main(String[] args) {
                int[] a = {38, 27, 43, 3, 9, 82, 10};
                mergeSort(a);
                System.out.println(a[0] + " " + a[6]);
            }"""));
        P.put("A5 binary search", main("""
            public static void main(String[] args) {
                int[] a = {1, 3, 5, 7, 9, 11};
                int target = 7, lo = 0, hi = a.length - 1, found = -1;
                while (lo <= hi) {
                    int mid = lo + (hi - lo) / 2;
                    if (a[mid] == target) { found = mid; break; }
                    else if (a[mid] < target) lo = mid + 1; else hi = mid - 1;
                }
                System.out.println(found);
            }"""));
        P.put("A6 matrix rotate", main("""
            public static void main(String[] args) {
                int[][] m = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}};
                int n = m.length;
                int[][] r = new int[n][n];
                for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) r[j][n - 1 - i] = m[i][j];
                System.out.println(r[0][0] + " " + r[2][2]);
            }"""));
        P.put("A7 Arrays.sort", main("""
            public static void main(String[] args) {
                int[] a = {3, 1, 2};
                Arrays.sort(a);
                System.out.println(a[0]);
            }"""));
        P.put("S1 palindrome", main("""
            public static void main(String[] args) {
                String s = "racecar";
                int i = 0, j = s.length() - 1; boolean ok = true;
                while (i < j) { if (s.charAt(i) != s.charAt(j)) { ok = false; break; } i++; j--; }
                System.out.println(ok);
            }"""));
        P.put("S2 reverse words split", main("""
            public static void main(String[] args) {
                String[] w = "the quick brown fox".split(" ");
                StringBuilder sb = new StringBuilder();
                for (int i = w.length - 1; i >= 0; i--) { sb.append(w[i]); if (i > 0) sb.append(' '); }
                System.out.println(sb.toString());
            }"""));
        P.put("S3 char counts toCharArray", main("""
            public static void main(String[] args) {
                int[] count = new int[26];
                for (char c : "banana".toCharArray()) count[c - 'a']++;
                System.out.println(count[0] + " " + count[1] + " " + count[13]);
            }"""));
        P.put("S3b char counts charAt", main("""
            public static void main(String[] args) {
                String s = "banana"; int[] count = new int[26];
                for (int i = 0; i < s.length(); i++) count[s.charAt(i) - 'a']++;
                System.out.println(count[0]);
            }"""));
        P.put("S4 anagram sort", main("""
            public static void main(String[] args) {
                char[] a = "listen".toCharArray(), b = "silent".toCharArray();
                Arrays.sort(a); Arrays.sort(b);
                System.out.println(Arrays.equals(a, b));
            }"""));
        P.put("S5 compress", main("""
            public static void main(String[] args) {
                String s = "aaabccdd"; StringBuilder out = new StringBuilder();
                int run = 1;
                for (int i = 1; i <= s.length(); i++) {
                    if (i < s.length() && s.charAt(i) == s.charAt(i - 1)) run++;
                    else { out.append(s.charAt(i - 1)).append(run); run = 1; }
                }
                System.out.println(out);
            }"""));
        P.put("M1 word count getOrDefault", main("""
            public static void main(String[] args) {
                String[] words = {"a", "b", "a", "c", "b", "a"};
                Map<String, Integer> freq = new HashMap<>();
                for (String w : words) freq.put(w, freq.getOrDefault(w, 0) + 1);
                System.out.println(freq.get("a") + " " + freq.size());
            }"""));
        P.put("M1b word count containsKey", main("""
            public static void main(String[] args) {
                String[] words = {"a", "b", "a"};
                Map<String, Integer> freq = new TreeMap<>();
                for (int i = 0; i < words.length; i++) {
                    if (freq.containsKey(words[i])) freq.put(words[i], freq.get(words[i]) + 1);
                    else freq.put(words[i], 1);
                }
                System.out.println(freq);
            }"""));
        P.put("M2 two sum", main("""
            public static void main(String[] args) {
                int[] nums = {2, 7, 11, 15}; int target = 9;
                Map<Integer, Integer> seen = new HashMap<>();
                for (int i = 0; i < nums.length; i++) {
                    int need = target - nums[i];
                    if (seen.containsKey(need)) { System.out.println(seen.get(need) + " " + i); return; }
                    seen.put(nums[i], i);
                }
            }"""));
        P.put("M3 entrySet loop", main("""
            public static void main(String[] args) {
                Map<Character, Integer> m = new TreeMap<>();
                String s = "hello";
                for (int i = 0; i < s.length(); i++) m.put(s.charAt(i), m.getOrDefault(s.charAt(i), 0) + 1);
                for (Map.Entry<Character, Integer> e : m.entrySet()) System.out.println(e.getKey() + "=" + e.getValue());
            }"""));
        P.put("M4 keySet loop", main("""
            public static void main(String[] args) {
                Map<String, Integer> m = new HashMap<>();
                m.put("x", 1); m.put("y", 2);
                int total = 0;
                for (String k : m.keySet()) total += m.get(k);
                System.out.println(total);
            }"""));
        P.put("M5 merge", main("""
            public static void main(String[] args) {
                Map<String, Integer> m = new HashMap<>();
                m.merge("x", 1, Integer::sum); m.merge("x", 1, Integer::sum);
                System.out.println(m.get("x"));
            }"""));
        P.put("T1 dedup HashSet", main("""
            public static void main(String[] args) {
                int[] a = {3, 1, 3, 2, 1};
                Set<Integer> seen = new HashSet<>(); List<Integer> out = new ArrayList<>();
                for (int x : a) if (seen.add(x)) out.add(x);
                System.out.println(out);
            }"""));
        P.put("T2 set intersection foreach", main("""
            public static void main(String[] args) {
                Set<Integer> a = new TreeSet<>(); Set<Integer> b = new HashSet<>();
                a.add(1); a.add(2); a.add(3); b.add(2); b.add(3); b.add(4);
                Set<Integer> both = new TreeSet<>();
                for (int x : a) if (b.contains(x)) both.add(x);
                System.out.println(both);
            }"""));
        P.put("T3 first duplicate char", main("""
            public static void main(String[] args) {
                String s = "abcbad"; Set<Character> seen = new HashSet<>();
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (!seen.add(c)) { System.out.println(c); break; }
                }
            }"""));
        P.put("L1 list foreach sum", main("""
            public static void main(String[] args) {
                List<Integer> xs = new ArrayList<>();
                for (int i = 1; i <= 4; i++) xs.add(i * i);
                int sum = 0;
                for (int x : xs) sum += x;
                System.out.println(sum);
            }"""));
        P.put("L2 Collections.sort", main("""
            public static void main(String[] args) {
                List<Integer> xs = new ArrayList<>(); xs.add(3); xs.add(1); xs.add(2);
                Collections.sort(xs);
                System.out.println(xs.get(0));
            }"""));
        P.put("R1 BST recursive insert/inorder", main("""
            static class Node { int key; Node left, right; Node(int k) { key = k; } }
            static Node insert(Node n, int k) {
                if (n == null) return new Node(k);
                if (k < n.key) n.left = insert(n.left, k); else n.right = insert(n.right, k);
                return n;
            }
            static void inorder(Node n, StringBuilder sb) {
                if (n == null) return;
                inorder(n.left, sb); sb.append(n.key).append(' '); inorder(n.right, sb);
            }
            public static void main(String[] args) {
                Node root = null;
                int[] keys = {50, 30, 70, 20, 40, 60, 80};
                for (int k : keys) root = insert(root, k);
                StringBuilder sb = new StringBuilder(); inorder(root, sb);
                System.out.println(sb);
            }"""));
        P.put("R2 BST iterative + height", main("""
            static class TreeNode { int val; TreeNode left; TreeNode right; TreeNode(int v) { val = v; } }
            static int height(TreeNode t) { return t == null ? 0 : 1 + Math.max(height(t.left), height(t.right)); }
            public static void main(String[] args) {
                TreeNode root = new TreeNode(8);
                int[] vals = {3, 10, 1, 6, 14};
                for (int v : vals) {
                    TreeNode cur = root;
                    while (true) {
                        if (v < cur.val) { if (cur.left == null) { cur.left = new TreeNode(v); break; } cur = cur.left; }
                        else { if (cur.right == null) { cur.right = new TreeNode(v); break; } cur = cur.right; }
                    }
                }
                System.out.println(height(root));
            }"""));
        P.put("R3 level order with queue", main("""
            static class Node { int v; Node l, r; Node(int v) { this.v = v; } }
            public static void main(String[] args) {
                Node root = new Node(1); root.l = new Node(2); root.r = new Node(3); root.l.l = new Node(4);
                Deque<Node> q = new ArrayDeque<>(); q.offer(root);
                StringBuilder sb = new StringBuilder();
                while (!q.isEmpty()) {
                    Node n = q.poll(); sb.append(n.v);
                    if (n.l != null) q.offer(n.l);
                    if (n.r != null) q.offer(n.r);
                }
                System.out.println(sb);
            }"""));
        P.put("H1 heap sort", main("""
            static void sift(int[] a, int i, int n) {
                while (true) {
                    int big = i, l = 2 * i + 1, r = 2 * i + 2;
                    if (l < n && a[l] > a[big]) big = l;
                    if (r < n && a[r] > a[big]) big = r;
                    if (big == i) return;
                    int t = a[i]; a[i] = a[big]; a[big] = t; i = big;
                }
            }
            public static void main(String[] args) {
                int[] a = {4, 10, 3, 5, 1};
                for (int i = a.length / 2 - 1; i >= 0; i--) sift(a, i, a.length);
                for (int end = a.length - 1; end > 0; end--) { int t = a[0]; a[0] = a[end]; a[end] = t; sift(a, 0, end); }
                System.out.println(a[0] + " " + a[4]);
            }"""));
        P.put("H2 min-heap class", main("""
            static class MinHeap {
                int[] data = new int[16]; int size;
                void push(int x) {
                    int i = size++; data[i] = x;
                    while (i > 0 && data[(i - 1) / 2] > data[i]) { int p = (i - 1) / 2; int t = data[p]; data[p] = data[i]; data[i] = t; i = p; }
                }
                int pop() {
                    int top = data[0]; data[0] = data[--size]; int i = 0;
                    while (true) {
                        int s = i, l = 2 * i + 1, r = l + 1;
                        if (l < size && data[l] < data[s]) s = l;
                        if (r < size && data[r] < data[s]) s = r;
                        if (s == i) break;
                        int t = data[s]; data[s] = data[i]; data[i] = t; i = s;
                    }
                    return top;
                }
            }
            public static void main(String[] args) {
                MinHeap h = new MinHeap();
                h.push(5); h.push(3); h.push(8); h.push(1);
                System.out.println(h.pop() + " " + h.pop());
            }"""));
        P.put("H3 PriorityQueue top-k", main("""
            public static void main(String[] args) {
                int[] a = {7, 2, 9, 4, 1};
                PriorityQueue<Integer> pq = new PriorityQueue<>();
                for (int x : a) { pq.offer(x); if (pq.size() > 3) pq.poll(); }
                System.out.println(pq.peek());
            }"""));
        P.put("H4 PQ comparator", main("""
            public static void main(String[] args) {
                PriorityQueue<Integer> pq = new PriorityQueue<>(Collections.reverseOrder());
                pq.add(1); pq.add(5); pq.add(3);
                System.out.println(pq.poll());
            }"""));
        P.put("F1 forEach lambda", main("""
            public static void main(String[] args) {
                List<Integer> xs = new ArrayList<>(); xs.add(1); xs.add(2);
                int[] total = {0};
                xs.forEach(x -> total[0] += x);
                System.out.println(total[0]);
            }"""));
        P.put("F2 stream filter map sum", main("""
            public static void main(String[] args) {
                List<Integer> xs = new ArrayList<>(); xs.add(1); xs.add(2); xs.add(3); xs.add(4);
                int s = xs.stream().filter(x -> x % 2 == 0).mapToInt(x -> x * x).sum();
                System.out.println(s);
            }"""));
        P.put("F3 list sort comparator", main("""
            public static void main(String[] args) {
                List<String> ws = new ArrayList<>(); ws.add("ccc"); ws.add("a"); ws.add("bb");
                ws.sort((p, q) -> p.length() - q.length());
                System.out.println(ws.get(0));
            }"""));
        P.put("F4 lambda calls helper", main("""
            static int sq(int v) { return v * v; }
            public static void main(String[] args) {
                int[] a = {1, 2, 3};
                int m = Arrays.stream(a).map(v -> sq(v)).max().getAsInt();
                System.out.println(m);
            }"""));
        P.put("F6 groupingBy", main("""
            public static void main(String[] args) {
                String[] words = {"aa", "b", "cc", "ddd"};
                Map<Integer, List<String>> byLength = Arrays.stream(words).collect(java.util.stream.Collectors.groupingBy(String::length));
                System.out.println(byLength);
            }"""));
        P.put("F9 primes", main("""
            static boolean isPrime(int n) {
                if (n < 2) return false;
                for (int d = 2; d * d <= n; d++) if (n % d == 0) return false;
                return true;
            }
            public static void main(String[] args) {
                List<Integer> ps = java.util.stream.IntStream.rangeClosed(2, 12).filter(Main::isPrime).boxed().collect(java.util.stream.Collectors.toList());
                System.out.println(ps);
            }"""));
        P.put("F11 text pipeline", main("""
            public static void main(String[] args) {
                String[] words = {"b", "a", "b", "c"};
                String s = Arrays.stream(words).map(w -> w.toUpperCase()).distinct().sorted().collect(java.util.stream.Collectors.joining(","));
                System.out.println(s);
            }"""));
        P.put("F12 toList average anyMatch", main("""
            public static void main(String[] args) {
                List<Integer> xs = new ArrayList<>(); xs.add(3); xs.add(8); xs.add(5);
                List<Integer> big = xs.stream().filter(x -> x > 4).toList();
                double avg = xs.stream().mapToInt(x -> x).average().orElse(0);
                boolean any = xs.stream().anyMatch(x -> x > 7);
                long n = "hello".chars().filter(c -> c == 'l').count();
                System.out.println(big + " " + avg + " " + any + " " + n);
            }"""));
        P.put("K1 balanced brackets", main("""
            public static void main(String[] args) {
                String s = "{[()]}("; Deque<Character> st = new ArrayDeque<>(); boolean ok = true;
                for (int i = 0; i < s.length() && ok; i++) {
                    char c = s.charAt(i);
                    if (c == '(' || c == '[' || c == '{') st.push(c);
                    else if (st.isEmpty()) ok = false;
                    else { char o = st.pop(); ok = (o == '(' && c == ')') || (o == '[' && c == ']') || (o == '{' && c == '}'); }
                }
                System.out.println(ok && st.isEmpty());
            }"""));
        // ---- programs added for the suite (10d.5): F7, F8, F10 and renamed or reformatted variants ----
        P.put("F7 adjacency lists", main("""
            public static void main(String[] args) {
                Map<Integer, List<Integer>> g = new TreeMap<>();
                int[][] edges = {{1, 2}, {1, 3}, {2, 3}};
                for (int[] e : edges) {
                    g.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
                    g.computeIfAbsent(e[1], k -> new ArrayList<>()).add(e[0]);
                }
                System.out.println(g);
            }"""));
        P.put("F8 remove negatives", main("""
            public static void main(String[] args) {
                List<Integer> xs = new ArrayList<>();
                xs.add(-2); xs.add(5); xs.add(-1); xs.add(7);
                xs.removeIf(x -> x < 0);
                System.out.println(xs);
            }"""));
        P.put("F10 stored functions", main("""
            public static void main(String[] args) {
                java.util.function.Function<Integer, Integer> inc = x -> x + 1;
                java.util.function.Function<Integer, Integer> twice = inc.andThen(x -> x * 2);
                java.util.function.Predicate<Integer> odd = x -> x % 2 == 1;
                System.out.println(twice.apply(3) + " " + odd.test(5) + " " + odd.negate().test(5));
            }"""));
        P.put("A1v insertion sort, renamed", main("""
            public static void main(String[] args) {
                int[] data = {9, 7, 8};
                for (int p = 1; p < data.length; p++) {
                    int cur = data[p];
                    int q = p;
                    for (; q > 0 && data[q - 1] > cur; q--) data[q] = data[q - 1];
                    data[q] = cur;
                }
                System.out.println(Arrays.toString(data));
            }"""));
        P.put("R1v BST, void insert and list walk", main("""
            static class Leaf { int v; Leaf l, r; Leaf(int v) { this.v = v; } }
            static void put(Leaf at, int v) {
                if (v < at.v) { if (at.l == null) at.l = new Leaf(v); else put(at.l, v); }
                else { if (at.r == null) at.r = new Leaf(v); else put(at.r, v); }
            }
            static void walk(Leaf at, List<Integer> out) {
                if (at == null) return;
                walk(at.l, out); out.add(at.v); walk(at.r, out);
            }
            public static void main(String[] args) {
                Leaf root = new Leaf(5);
                for (int v : new int[]{3, 8, 1, 4}) put(root, v);
                List<Integer> out = new ArrayList<>();
                walk(root, out);
                System.out.println("in order: " + out);
            }"""));
        P.put("M1v word count, split and entrySet", main("""
            public static void main(String[] args) {
                String text = "to be or not to be";
                Map<String, Integer> c = new TreeMap<>();
                for (String w : text.split(" ")) c.put(w, c.containsKey(w) ? c.get(w) + 1 : 1);
                for (Map.Entry<String, Integer> e : c.entrySet()) System.out.println(e.getKey() + " " + e.getValue());
            }"""));
        P.put("F2v sum of even squares, IntStream", main("""
            public static void main(String[] args) {
                int total = java.util.stream.IntStream.rangeClosed(1, 6).filter(n -> n % 2 == 0).map(n -> n * n).sum();
                System.out.println(total);
            }"""));
    }

    // The frontend's 10d examples (frontend/src/examples.ts), exactly as offered in the editor.
    static {
        P.put("E1 word count example", """
            import java.util.*;

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
            """);
        P.put("E2 anagram example", """
            import java.util.Arrays;

            public class Main {
                public static void main(String[] args) {
                    char[] a = "listen".toCharArray();
                    char[] b = "silent".toCharArray();
                    Arrays.sort(a);
                    Arrays.sort(b);
                    System.out.println(Arrays.equals(a, b) ? "anagrams" : "different");
                }
            }
            """);
        // Part 17: backtracking (choose, recurse, undo), including a copy of the path.
        P.put("B1 permutations", main("""
            static void permute(int[] a, boolean[] used, int[] cur, int k) {
                if (k == a.length) { System.out.println(Arrays.toString(cur)); return; }
                for (int i = 0; i < a.length; i++) {
                    if (used[i]) continue;
                    used[i] = true; cur[k] = a[i];
                    permute(a, used, cur, k + 1);
                    used[i] = false;
                }
            }
            public static void main(String[] args) { int[] a = {1, 2, 3}; permute(a, new boolean[3], new int[3], 0); }"""));
        P.put("B2 subsets with a path list", main("""
            static void subsets(int[] a, int i, List<Integer> path, List<List<Integer>> out) {
                if (i == a.length) { out.add(new ArrayList<>(path)); return; }
                subsets(a, i + 1, path, out);
                path.add(a[i]);
                subsets(a, i + 1, path, out);
                path.remove(path.size() - 1);
            }
            public static void main(String[] args) { List<List<Integer>> out = new ArrayList<>(); subsets(new int[]{1, 2}, 0, new ArrayList<>(), out); System.out.println(out); }"""));
        P.put("B3 n-queens", main("""
            static boolean safe(int[] col, int r, int c) {
                for (int i = 0; i < r; i++) if (col[i] == c || Math.abs(col[i] - c) == r - i) return false;
                return true;
            }
            static int solve(int[] col, int r, int n) {
                if (r == n) return 1;
                int count = 0;
                for (int c = 0; c < n; c++) {
                    if (safe(col, r, c)) { col[r] = c; count += solve(col, r + 1, n); col[r] = -1; }
                }
                return count;
            }
            public static void main(String[] args) { int n = 4; int[] col = new int[n]; Arrays.fill(col, -1); System.out.println(solve(col, 0, n)); }"""));
        P.put("B4 parentheses with a StringBuilder", main("""
            static void gen(StringBuilder sb, int open, int close, int n, List<String> out) {
                if (sb.length() == 2 * n) { out.add(sb.toString()); return; }
                if (open < n) { sb.append('('); gen(sb, open + 1, close, n, out); sb.deleteCharAt(sb.length() - 1); }
                if (close < open) { sb.append(')'); gen(sb, open, close + 1, n, out); sb.deleteCharAt(sb.length() - 1); }
            }
            public static void main(String[] args) { List<String> out = new ArrayList<>(); gen(new StringBuilder(), 0, 0, 2, out); System.out.println(out); }"""));
        P.put("E3 stream pipeline example", """
            import java.util.*;

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
            """);
    }

    /** Expected output of each program (also checked equal to the original program's own output). */
    static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("A1", "[1, 2, 3, 4, 5, 6]"), Map.entry("A1b", "1 2 3 4 5 6"), Map.entry("A2", "1 5"), Map.entry("A3", "1,9"),
            Map.entry("A4", "3 82"), Map.entry("A5", "3"), Map.entry("A6", "7 3"), Map.entry("A7", "1"), Map.entry("S1", "true"),
            Map.entry("S2", "fox brown quick the"), Map.entry("S3", "3 1 2"), Map.entry("S3b", "3"), Map.entry("S4", "true"),
            Map.entry("S5", "a3b1c2d2"), Map.entry("M1", "3 3"), Map.entry("M1b", "{a=2, b=1}"), Map.entry("M2", "0 1"),
            Map.entry("M3", "e=1\nh=1\nl=2\no=1"), Map.entry("M4", "3"), Map.entry("M5", "2"), Map.entry("T1", "[3, 1, 2]"),
            Map.entry("T2", "[2, 3]"), Map.entry("T3", "b"), Map.entry("L1", "30"), Map.entry("L2", "1"),
            Map.entry("R1", "20 30 40 50 60 70 80"), Map.entry("R2", "3"), Map.entry("R3", "1234"), Map.entry("H1", "1 10"),
            Map.entry("H2", "1 3"), Map.entry("H3", "4"), Map.entry("H4", "5"), Map.entry("F1", "3"), Map.entry("F2", "20"),
            Map.entry("F3", "a"), Map.entry("F4", "9"), Map.entry("F6", "{1=[b], 2=[aa, cc], 3=[ddd]}"), Map.entry("F9", "[2, 3, 5, 7, 11]"),
            Map.entry("F11", "A,B,C"), Map.entry("F12", "[8, 5] 5.333333333333333 true 2"), Map.entry("K1", "false"),
            Map.entry("F7", "{1=[2, 3], 2=[1, 3], 3=[1, 2]}"), Map.entry("F8", "[5, 7]"), Map.entry("F10", "8 true false"),
            Map.entry("A1v", "[7, 8, 9]"), Map.entry("R1v", "in order: [1, 3, 4, 5, 8]"), Map.entry("M1v", "be 2\nnot 1\nor 1\nto 2"),
            Map.entry("F2v", "56"), Map.entry("E1", "and: 1\ncat: 1\nhat: 1\nthe: 2"), Map.entry("E2", "anagrams"), Map.entry("E3", "22"),
            Map.entry("B1", "[1, 2, 3]\n[1, 3, 2]\n[2, 1, 3]\n[2, 3, 1]\n[3, 1, 2]\n[3, 2, 1]"), Map.entry("B2", "[[], [2], [1], [1, 2]]"),
            Map.entry("B3", "2"), Map.entry("B4", "[(()), ()()]"));

    @TestFactory Stream<DynamicTest> everyProgramTracesCompletelyWithoutUncapturedStatements() {
        return P.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
            String id = e.getKey().substring(0, e.getKey().indexOf(' '));
            String source = e.getValue();
            Tracer.Prepared prepared = Tracer.prepare(source);
            assertTrue(prepared.compiled() && prepared.traced(), id + " should be traceable: " + prepared.analysis().unsupported());
            HostRun.Result traced = HostRun.run(prepared.classes(), true, temp, null);
            HostRun.Result original = HostRun.run(prepared.analysis().classes(), false, temp, null);
            assertEquals(original.stdout(), traced.stdout(), id + ": output must match the original");
            assertEquals(original.exit(), traced.exit(), id + ": exit status must match the original");
            String expected = EXPECTED.get(id);
            assertNotNull(expected, id + " has an expected output");
            assertEquals(expected, traced.stdout().replace("\r\n", "\n").stripTrailing(), id + ": expected output");
            String json = Tracer.trace(prepared, "suite-" + id, traced.raw(), traced.exit(), new byte[0]);
            assertEquals(json, Tracer.trace(Tracer.fromPlan(source, Tracer.plan(prepared)), "suite-" + id, traced.raw(), traced.exit(), new byte[0]),
                    id + ": plan round trip");
            validate("suite-" + id, json);
            Map<String, Object> trace = JsonReader.object(json);
            assertEquals("complete", map(trace.get("capture")).get("status"), id + ": " + map(trace.get("capture")).get("reason"));
            assertEquals("completed", map(trace.get("execution")).get("status"), id);
            assertEquals(List.of(), events(trace).stream().filter(ev -> "UNCAPTURED".equals(ev.get("kind"))).toList(), id + ": no uncaptured statement");
            assertTrue(((List<?>) trace.get("diagnostics")).isEmpty(), id + ": no diagnostic " + trace.get("diagnostics"));
        }));
    }

    /** Out of scope (specs/problem-suite.md): each still runs, its statement uncaptured and diagnosed. */
    @Test void outOfScopeFormsRunUncapturedWithADiagnostic() throws Exception {
        Map<String, String> forms = Map.of(
                "user comparator in a priority queue", "PriorityQueue<Integer> q = new PriorityQueue<>((a, b) -> b - a); q.add(1); System.out.println(q.size());",
                "Stream.of of a whole array (12b covers written-out values)", "Integer[] v = {1, 2}; System.out.println(java.util.stream.Stream.of(v).count());",
                "JDK reference returning a new object", "List<String> ws = new ArrayList<>(); ws.add(\"a\"); System.out.println(ws.stream().map(String::toUpperCase).count());",
                "parallel stream", "List<Integer> xs = new ArrayList<>(); xs.add(1); System.out.println(xs.parallelStream().count());");
        for (var f : forms.entrySet()) {
            String source = main("public static void main(String[] args) {\n" + f.getValue() + "\n}");
            Tracer.Prepared prepared = Tracer.prepare(source);
            assertTrue(prepared.traced(), f.getKey());
            HostRun.Result traced = HostRun.run(prepared.classes(), true, temp, null);
            HostRun.Result original = HostRun.run(prepared.analysis().classes(), false, temp, null);
            assertEquals(original.stdout(), traced.stdout(), f.getKey());
            Map<String, Object> trace = JsonReader.object(Tracer.trace(prepared, "out-of-scope", traced.raw(), traced.exit(), new byte[0]));
            assertTrue(events(trace).stream().anyMatch(ev -> "UNCAPTURED".equals(ev.get("kind"))), f.getKey() + " should run uncaptured");
            assertTrue(((List<Map<String, Object>>) (List<?>) trace.get("diagnostics")).stream().anyMatch(d -> "UNSUPPORTED_CONSTRUCT".equals(d.get("code"))), f.getKey());
        }
    }

    private void validate(String name, String json) throws Exception {
        Path dir = Path.of("target", "traces");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        Process node = new ProcessBuilder("node", Path.of("../../contracts/trace/check.cjs").toString(), file.toString()).redirectErrorStream(true).start();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(node.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, node.exitValue(), "trace-v1 check failed: " + output);
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> events(Map<String, Object> t) { return (List<Map<String, Object>>) t.get("events"); }
}