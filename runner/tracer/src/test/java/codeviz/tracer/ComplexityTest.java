package codeviz.tracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Part 15a: best-effort estimates from the code's shape (specs/complexity.md). */
class ComplexityTest {
    private static Map<String, Complexity.Estimate> estimates(String body) {
        String source = "import java.util.*;\npublic class Main {\n" + body + "\n    public static void main(String[] args) { }\n}\n";
        Analyzer.Analysis a = Analyzer.analyze(source);
        assertEquals(List.of(), a.compileErrors());
        return a.complexity().stream().collect(Collectors.toMap(Complexity.Estimate::name, e -> e, (x, y) -> x));
    }

    private static void bound(Map<String, Complexity.Estimate> all, String name, String bound, boolean approximate) {
        Complexity.Estimate e = all.get(name);
        assertEquals(bound, e.cost().text(), name + ": " + e.reasons());
        assertEquals(approximate, e.approximate(), name + " approximate: " + e.reasons());
        assertFalse(e.reasons().isEmpty() && !bound.equals("O(1)"), name + " gives a reason");
    }

    @Test void loopsNestSequencesAndCallsCompose() {
        var all = estimates("""
                static int first(int[] a) { return a[0]; }
                static int sum(int[] a) { int s = 0; for (int i = 0; i < a.length; i++) s += a[i]; return s; }
                static int sumEach(List<Integer> xs) { int s = 0; for (int x : xs) s += x; return s; }
                static void bubble(int[] a) {
                    for (int i = 0; i < a.length - 1; i++)
                        for (int j = 0; j < a.length - 1 - i; j++)
                            if (a[j] > a[j + 1]) { int t = a[j]; a[j] = a[j + 1]; a[j + 1] = t; }
                }
                static int pairs(int n) { int c = 0; for (int i = 0; i < n; i++) for (int j = 0; j < i; j++) c++; return c; }
                static int triple(int n) { int c = 0; for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) for (int k = 0; k < n; k++) c++; return c; }
                static int doubling(int n) { int c = 0; for (int i = 1; i < n; i *= 2) c++; return c; }
                static int down(int n) { int c = 0; for (int i = n; i > 0; i /= 2) c++; return c; }
                static int fixed() { int c = 0; for (int i = 0; i < 10; i++) c++; return c; }
                static int twice(int[] a) { return sum(a) + sum(a); }
                static int sumAll(int[] a) { int s = 0; for (int i = 0; i < a.length; i++) s += sum(a); return s; }
                static void sorted(int[] a) { Arrays.sort(a); }
                static boolean has(List<Integer> xs, int n) { for (int i = 0; i < n; i++) if (xs.contains(i)) return true; return false; }
                static int[][] grid(int n) { return new int[n][n]; }
                """);
        bound(all, "first", "O(1)", false);
        bound(all, "sum", "O(n)", false);
        bound(all, "sumEach", "O(n)", false);
        bound(all, "bubble", "O(n²)", false);
        bound(all, "pairs", "O(n²)", false);
        bound(all, "triple", "O(n³)", false);
        bound(all, "doubling", "O(log n)", false);
        bound(all, "down", "O(log n)", false);
        bound(all, "fixed", "O(1)", false);
        bound(all, "twice", "O(n)", false);
        bound(all, "sumAll", "O(n²)", false);
        bound(all, "sorted", "O(n log n)", false);
        bound(all, "has", "O(n²)", false);
        bound(all, "grid", "O(n²)", false);
        assertTrue(all.get("sumAll").reasons().stream().anyMatch(r -> r.contains("calls sum")), all.get("sumAll").reasons().toString());
    }

    @Test void whileLoopsAreGuessedFromWhatTheBodyDoes() {
        var all = estimates("""
                static int search(int[] a, int x) {
                    int lo = 0, hi = a.length - 1;
                    while (lo <= hi) {
                        int mid = (lo + hi) / 2;
                        if (a[mid] == x) return mid;
                        if (a[mid] < x) lo = mid + 1; else hi = mid - 1;
                    }
                    return -1;
                }
                static int digits(int n) { int c = 0; while (n > 0) { n /= 10; c++; } return c; }
                static int scan(int[] a, int x) { int i = 0; while (i < a.length && a[i] != x) i++; return i; }
                static int drain(Deque<Integer> q) { int s = 0; while (!q.isEmpty()) s += q.poll(); return s; }
                """);
        bound(all, "search", "O(log n)", true);
        bound(all, "digits", "O(log n)", true);
        bound(all, "scan", "O(n)", true);
        bound(all, "drain", "O(n)", true);
    }

    @Test void recursionIsSolvedFromItsCallsAndHowTheArgumentShrinks() {
        var all = estimates("""
                static int fact(int n) { if (n <= 1) return 1; return n * fact(n - 1); }
                static int fib(int n) { if (n < 2) return n; return fib(n - 1) + fib(n - 2); }
                static void hanoi(int n, int a, int b, int c) { if (n == 0) return; hanoi(n - 1, a, c, b); hanoi(n - 1, c, b, a); }
                static int find(int[] a, int lo, int hi, int x) {
                    if (lo > hi) return -1;
                    int mid = (lo + hi) / 2;
                    if (a[mid] == x) return mid;
                    if (a[mid] < x) return find(a, mid + 1, hi, x);
                    else return find(a, lo, mid - 1, x);
                }
                static void merge(int[] a, int lo, int mid, int hi) { int[] t = new int[hi - lo + 1]; for (int i = lo; i <= hi; i++) t[i - lo] = a[i]; }
                static void mergeSort(int[] a, int lo, int hi) {
                    if (lo >= hi) return;
                    int mid = lo + (hi - lo) / 2;
                    mergeSort(a, lo, mid);
                    mergeSort(a, mid + 1, hi);
                    merge(a, lo, mid, hi);
                }
                static int partition(int[] a, int lo, int hi) { int p = lo; for (int i = lo; i < hi; i++) if (a[i] < a[hi]) p++; return p; }
                static void quick(int[] a, int lo, int hi) {
                    if (lo >= hi) return;
                    int p = partition(a, lo, hi);
                    quick(a, lo, p - 1);
                    quick(a, p + 1, hi);
                }
                static int power(int b, int e) { if (e == 0) return 1; int h = power(b, e / 2); return e % 2 == 0 ? h * h : h * h * b; }
                static int sumTo(int n) { return n == 0 ? 0 : n + sumTo(n - 1); }
                static void perms(int[] a, int k) { if (k == a.length) return; for (int i = k; i < a.length; i++) perms(a, k + 1); }
                static void gen(StringBuilder sb, int open, int close, int n) {
                    if (sb.length() == 2 * n) return;
                    if (open < n) { sb.append('('); gen(sb, open + 1, close, n); sb.deleteCharAt(sb.length() - 1); }
                    if (close < open) { sb.append(')'); gen(sb, open, close + 1, n); sb.deleteCharAt(sb.length() - 1); }
                }
                static int pick(int n, boolean left) { if (n == 0) return 0; return left ? pick(n - 1, true) : pick(n - 1, false); }
                """);
        bound(all, "fact", "O(n)", false);
        bound(all, "fib", "O(2ⁿ)", false);
        bound(all, "hanoi", "O(2ⁿ)", false);
        bound(all, "find", "O(log n)", false);
        bound(all, "mergeSort", "O(n log n)", false);
        bound(all, "quick", "O(n log n)", true);
        bound(all, "power", "O(log n)", false);
        bound(all, "sumTo", "O(n)", false);
        bound(all, "perms", "O(2ⁿ)", true);
        // Part 17: two separate ifs can both run: two self-calls; then/else and ?: are alternatives.
        bound(all, "gen", "O(2ⁿ)", false);
        bound(all, "pick", "O(n)", false);
    }

    @Test void estimatesTravelInThePlanAndTheTraceNamesFramesTheSameWay() throws Exception {
        String source = "public class Main {\n    static int sq(int n) { int c = 0; for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) c++; return c; }\n"
                + "    public static void main(String[] args) { System.out.println(sq(3)); }\n}\n";
        Tracer.Prepared prepared = Tracer.prepare(source);
        Tracer.Prepared decoded = Tracer.fromPlan(source, Tracer.plan(prepared));
        assertEquals(prepared.analysis().complexity(), decoded.analysis().complexity());
        Complexity.Estimate sq = decoded.analysis().complexity().get(0);
        assertEquals("Main.sq(int):int", sq.methodId());
        assertEquals("sq", source.substring(sq.start(), sq.end()));
        assertEquals("O(n²)", sq.cost().text());
        // main calls sq: it costs what sq costs.
        assertEquals("O(n²)", decoded.analysis().complexity().get(1).cost().text());
    }
}
