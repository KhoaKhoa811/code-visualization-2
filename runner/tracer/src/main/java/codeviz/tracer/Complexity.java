package codeviz.tracer;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Part 15a (specs/complexity.md): a best-effort time-complexity estimate per method, from the
 * shape of the code only, for comparing algorithms. It is never a runtime fact and is kept out
 * of the recorded steps. Costs are in one size n (every size expression counts as n). Rules are
 * about language pieces, not algorithms: a loop's count from its header, nesting multiplies,
 * sequences take the larger, a call costs its callee, library calls have listed costs, and
 * direct recursion is solved from how many self-calls it makes and how its argument shrinks.
 * Whatever is guessed (a while loop, an argument it cannot read) marks the result approximate.
 */
final class Complexity {
    private Complexity() { }

    /** Growth n^poly · log^log n, or base^n when base > 0. */
    record Cost(int base, double poly, int log) implements Comparable<Cost> {
        static final Cost ONE = new Cost(0, 0, 0), N = new Cost(0, 1, 0), LOG = new Cost(0, 0, 1);

        Cost times(Cost o) {
            if (base > 0 || o.base > 0) return new Cost(Math.max(base, o.base), 0, 0);
            return new Cost(0, poly + o.poly, log + o.log);
        }

        Cost max(Cost o) { return compareTo(o) >= 0 ? this : o; }

        @Override public int compareTo(Cost o) {
            if (base != o.base) return Integer.compare(base, o.base);
            if (poly != o.poly) return Double.compare(poly, o.poly);
            return Integer.compare(log, o.log);
        }

        String text() {
            if (base > 0) return "O(" + base + "ⁿ)";
            if (poly == 0 && log == 0) return "O(1)";
            String p = poly == 0 ? "" : poly == 1 ? "n" : poly == Math.rint(poly) && poly < 10 ? "n" + "⁰¹²³⁴⁵⁶⁷⁸⁹".charAt((int) poly)
                    : "n^" + String.format(java.util.Locale.ROOT, "%.2f", poly);
            String l = log == 0 ? "" : log == 1 ? "log n" : "log" + "⁰¹²³⁴⁵⁶⁷⁸⁹".charAt(Math.min(log, 9)) + " n";
            return "O(" + (p.isEmpty() ? l : l.isEmpty() ? p : p + " " + l) + ")";
        }
    }

    /** One method's estimate: methodId as in trace frames, the bound, whether guessed, reasons, the name's range. */
    record Estimate(String methodId, String name, Cost cost, boolean approximate, List<String> reasons, int start, int end) { }

    /** Estimates for every method declared in the source; an analysis failure gives none (it never stops a run). */
    static List<Estimate> estimate(CompilationUnitTree unit, Trees trees) {
        try {
            return new Walk(unit, trees).all();
        } catch (RuntimeException | StackOverflowError e) {
            return List.of();
        }
    }

    private static final class Walk {
        final CompilationUnitTree unit;
        final Trees trees;
        final Map<ExecutableElement, MethodTree> methods = new HashMap<>();
        final Map<ExecutableElement, Result> done = new HashMap<>();
        final Set<ExecutableElement> running = new HashSet<>();

        Walk(CompilationUnitTree unit, Trees trees) {
            this.unit = unit;
            this.trees = trees;
            new TreeScanner<Void, Void>() {
                @Override public Void visitMethod(MethodTree m, Void p) {
                    if (element(m) instanceof ExecutableElement e && m.getBody() != null) methods.put(e, m);
                    return super.visitMethod(m, p);
                }
                @Override public Void visitLambdaExpression(LambdaExpressionTree l, Void p) { return null; }
            }.scan(unit, null);
        }

        record Result(Cost cost, boolean approximate, List<String> reasons) { }

        List<Estimate> all() {
            List<Estimate> out = new ArrayList<>();
            List<Map.Entry<ExecutableElement, MethodTree>> ordered = new ArrayList<>(methods.entrySet());
            ordered.sort((a, b) -> Long.compare(pos(a.getValue()), pos(b.getValue())));
            for (var entry : ordered) {
                ExecutableElement e = entry.getKey();
                if (e.getKind() == ElementKind.CONSTRUCTOR) continue;
                Result r = of(e);
                MethodTree m = entry.getValue();
                int start = (int) pos(m);
                String text = unit.getSourceFile() == null ? "" : source();
                int name = text.indexOf(m.getName().toString() + "(", start);
                if (name < 0) name = start;
                out.add(new Estimate(methodId(e), e.getSimpleName().toString(), r.cost, r.approximate, r.reasons, name, name + m.getName().length()));
            }
            return out;
        }

        String sourceCache;
        String source() {
            if (sourceCache == null) {
                try { sourceCache = unit.getSourceFile().getCharContent(true).toString(); }
                catch (java.io.IOException e) { sourceCache = ""; }
            }
            return sourceCache;
        }

        long pos(Tree t) { return trees.getSourcePositions().getStartPosition(unit, t); }

        String text(Tree t) {
            long s = pos(t), e = trees.getSourcePositions().getEndPosition(unit, t);
            String src = source();
            return s < 0 || e > src.length() ? t.toString() : src.substring((int) s, (int) e);
        }

        Element element(Tree t) {
            TreePath path = TreePath.getPath(unit, t);
            return path == null ? null : trees.getElement(path);
        }

        static String methodId(ExecutableElement e) {
            String owner = e.getEnclosingElement() instanceof TypeElement t ? t.asType().toString() : "?";
            String params = String.join(",", e.getParameters().stream().map(p -> p.asType().toString()).toList());
            return owner + "." + e.getSimpleName() + "(" + params + "):" + e.getReturnType();
        }

        Result of(ExecutableElement e) {
            Result known = done.get(e);
            if (known != null) return known;
            if (!running.add(e)) return new Result(Cost.N, true, List.of("calls back into a method still being estimated (guessed O(n))"));
            try {
                Ctx ctx = new Ctx(e);
                Cost work = ctx.stmt(methods.get(e).getBody());
                Result r = ctx.recursion(work);
                done.put(e, r);
                return r;
            } finally {
                running.remove(e);
            }
        }

        /** The walk of one method body. */
        final class Ctx {
            final ExecutableElement self;
            final Set<String> reasons = new LinkedHashSet<>();
            boolean approximate;
            /** Loop variables in scope and the size of their range. */
            final Map<String, Cost> loopVars = new HashMap<>();
            /** Local variables assigned from a halving expression ((lo + hi) / 2): their use in a self-call halves. */
            final Set<String> halves = new HashSet<>();
            int selfCallsDepth; // loops around the current point
            final List<SelfCall> selfCalls = new ArrayList<>();

            Ctx(ExecutableElement self) { this.self = self; }

            void guess(String reason) { approximate = true; reasons.add(reason); }

            // ---- statements -----------------------------------------------------------------

            Cost stmt(StatementTree s) {
                if (s == null) return Cost.ONE;
                return switch (s) {
                    case BlockTree b -> { Cost c = Cost.ONE; for (StatementTree x : b.getStatements()) c = c.max(stmt(x)); yield c; }
                    case ExpressionStatementTree x -> expr(x.getExpression());
                    case VariableTree v -> {
                        if (v.getInitializer() != null && isHalving(v.getInitializer())) halves.add(v.getName().toString());
                        yield v.getInitializer() == null ? Cost.ONE : expr(v.getInitializer());
                    }
                    case IfTree i -> expr(i.getCondition()).max(branch(i.getThenStatement())).max(branch(i.getElseStatement()));
                    case ReturnTree r -> r.getExpression() == null ? Cost.ONE : expr(r.getExpression());
                    case ThrowTree t -> expr(t.getExpression());
                    case ForLoopTree f -> forLoop(f);
                    case EnhancedForLoopTree f -> loop(Cost.N, "for each element of " + text(f.getExpression()) + ": n times", f.getStatement(), expr(f.getExpression()));
                    case WhileLoopTree w -> whileLoop(w.getCondition(), w.getStatement());
                    case DoWhileLoopTree d -> whileLoop(d.getCondition(), d.getStatement());
                    case SwitchTree w -> { Cost c = expr(w.getExpression()); for (CaseTree k : w.getCases()) c = c.max(caseCost(k)); yield c; }
                    case TryTree t -> {
                        Cost c = stmt(t.getBlock());
                        for (CatchTree k : t.getCatches()) c = c.max(stmt(k.getBlock()));
                        yield c.max(stmt(t.getFinallyBlock()));
                    }
                    case LabeledStatementTree l -> stmt(l.getStatement());
                    case SynchronizedTree y -> stmt(y.getBlock());
                    default -> Cost.ONE;
                };
            }

            /** A branch: its self-calls are alternatives of the other branch, not added to them. */
            Cost branch(StatementTree s) {
                if (s == null) return Cost.ONE;
                int before = selfCalls.size();
                Cost c = stmt(s);
                for (int i = before; i < selfCalls.size(); i++) selfCalls.get(i).branch = true;
                return c;
            }

            Cost caseCost(CaseTree k) {
                if (k.getBody() != null) return k.getBody() instanceof StatementTree s ? branch(s) : expr((ExpressionTree) k.getBody());
                Cost c = Cost.ONE;
                for (StatementTree x : k.getStatements()) c = c.max(branch(x));
                return c;
            }

            Cost loop(Cost count, String reason, StatementTree body, Cost header) {
                if (reason != null) reasons.add(reason);
                selfCallsDepth++;
                Cost inside;
                try { inside = stmt(body); } finally { selfCallsDepth--; }
                return header.max(count.times(inside));
            }

            Cost forLoop(ForLoopTree f) {
                Cost header = Cost.ONE;
                for (StatementTree x : f.getInitializer()) header = header.max(stmt(x));
                String var = null;
                ExpressionTree from = null;
                if (f.getInitializer().size() == 1 && f.getInitializer().get(0) instanceof VariableTree v) { var = v.getName().toString(); from = v.getInitializer(); }
                else if (f.getInitializer().size() == 1 && f.getInitializer().get(0) instanceof ExpressionStatementTree es
                        && es.getExpression() instanceof AssignmentTree a && a.getVariable() instanceof IdentifierTree id) { var = id.getName().toString(); from = a.getExpression(); }
                ExpressionTree bound = var == null ? null : otherSide(f.getCondition(), var);
                String step = var == null || f.getUpdate().size() != 1 ? null : stepKind(f.getUpdate().get(0).getExpression(), var);
                Cost count;
                String reason;
                if (bound == null || step == null) {
                    count = Cost.N;
                    reason = "for loop " + header(f) + ": its count is not read from the header (guessed n)";
                    approximate = true;
                } else {
                    Cost range = size(bound).max(from == null ? Cost.ONE : size(from));
                    if (step.equals("linear")) {
                        count = range;
                        reason = "for loop " + header(f) + ": " + (range.equals(Cost.ONE) ? "a fixed number of times" : "n times");
                    } else {
                        count = range.equals(Cost.ONE) ? Cost.ONE : Cost.LOG;
                        reason = "for loop " + header(f) + ": " + (range.equals(Cost.ONE) ? "a fixed number of times" : "log n times (the variable is multiplied or divided)");
                    }
                }
                if (var != null) loopVars.put(var, count.equals(Cost.LOG) ? size(bound == null ? f.getCondition() : bound) : count);
                try {
                    return loop(count, reason, f.getStatement(), header.max(f.getCondition() == null ? Cost.ONE : expr(f.getCondition())));
                } finally {
                    if (var != null) loopVars.remove(var);
                }
            }

            String header(ForLoopTree f) {
                String cond = f.getCondition() == null ? "" : text(f.getCondition());
                return "(" + cond + ")";
            }

            /** while/do: guessed from what the body does to the condition's variables. */
            Cost whileLoop(ExpressionTree condition, StatementTree body) {
                ExpressionTree bare = condition;
                while (bare instanceof ParenthesizedTree p) bare = p.getExpression();
                String cond = text(bare);
                Set<String> vars = new HashSet<>();
                new TreeScanner<Void, Void>() {
                    @Override public Void visitIdentifier(IdentifierTree id, Void p) { vars.add(id.getName().toString()); return null; }
                }.scan(condition, null);
                String kind = updates(body, vars);
                Cost count;
                if (kind.equals("halving")) {
                    count = Cost.LOG;
                    guess("while (" + cond + "): the range is halved each time: about log n times");
                } else if (kind.equals("linear")) {
                    count = Cost.N;
                    guess("while (" + cond + "): a condition variable moves step by step: about n times");
                } else if (cond.contains("isEmpty()") || cond.contains("hasNext()") || cond.contains("size()")) {
                    count = Cost.N;
                    guess("while (" + cond + "): runs until the collection is used up: about n times");
                } else {
                    count = Cost.N;
                    guess("while (" + cond + "): its count depends on the data (guessed n)");
                }
                return loop(count, null, body, expr(condition)); // the guess above is its reason
            }

            /** How a loop body changes the condition's variables: halving, linear, or unknown. */
            String updates(StatementTree body, Set<String> vars) {
                String[] kind = {"unknown"};
                Set<String> mids = new HashSet<>();
                new TreeScanner<Void, Void>() {
                    @Override public Void visitVariable(VariableTree v, Void p) {
                        if (v.getInitializer() != null && isHalving(v.getInitializer())) mids.add(v.getName().toString());
                        return super.visitVariable(v, p);
                    }
                    @Override public Void visitUnary(UnaryTree u, Void p) {
                        if (u.getExpression() instanceof IdentifierTree id && vars.contains(id.getName().toString()) && !kind[0].equals("halving")) kind[0] = "linear";
                        return super.visitUnary(u, p);
                    }
                    @Override public Void visitCompoundAssignment(CompoundAssignmentTree a, Void p) {
                        if (a.getVariable() instanceof IdentifierTree id && vars.contains(id.getName().toString())) {
                            switch (a.getKind()) {
                                case MULTIPLY_ASSIGNMENT, DIVIDE_ASSIGNMENT, RIGHT_SHIFT_ASSIGNMENT, LEFT_SHIFT_ASSIGNMENT, UNSIGNED_RIGHT_SHIFT_ASSIGNMENT -> kind[0] = "halving";
                                default -> { if (!kind[0].equals("halving")) kind[0] = "linear"; }
                            }
                        }
                        return super.visitCompoundAssignment(a, p);
                    }
                    @Override public Void visitAssignment(AssignmentTree a, Void p) {
                        if (a.getVariable() instanceof IdentifierTree id && vars.contains(id.getName().toString())) {
                            ExpressionTree value = a.getExpression();
                            if (isHalving(value) || mentions(value, mids)) kind[0] = "halving";
                            else if (!kind[0].equals("halving") && mentions(value, Set.of(id.getName().toString()))) kind[0] = "linear";
                        }
                        return super.visitAssignment(a, p);
                    }
                    @Override public Void visitLambdaExpression(LambdaExpressionTree l, Void p) { return null; }
                }.scan(body, null);
                return kind[0];
            }

            // ---- expressions ----------------------------------------------------------------

            Cost expr(ExpressionTree e) {
                if (e == null) return Cost.ONE;
                Cost[] c = {Cost.ONE};
                int[] calls = {0};
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethodInvocation(MethodInvocationTree call, Void p) {
                        super.visitMethodInvocation(call, p);
                        Element target = element(call);
                        if (target instanceof ExecutableElement m && m.equals(self)) {
                            selfCalls.add(new SelfCall(call, selfCallsDepth > 0));
                            calls[0]++;
                            return null;
                        }
                        c[0] = c[0].max(call(call, target));
                        return null;
                    }
                    @Override public Void visitNewArray(NewArrayTree n, Void p) {
                        Cost a = Cost.ONE;
                        for (ExpressionTree d : n.getDimensions()) a = a.times(size(d));
                        if (!a.equals(Cost.ONE)) reasons.add(text(n) + ": " + a.text() + " to fill");
                        c[0] = c[0].max(a);
                        return super.visitNewArray(n, p);
                    }
                    @Override public Void visitNewClass(NewClassTree n, Void p) {
                        if (element(n) instanceof ExecutableElement ctor && methods.containsKey(ctor)) c[0] = c[0].max(of(ctor).cost);
                        return super.visitNewClass(n, p);
                    }
                    @Override public Void visitConditionalExpression(ConditionalExpressionTree t, Void p) {
                        scan(t.getCondition(), p);
                        int before = selfCalls.size();
                        scan(t.getTrueExpression(), p);
                        scan(t.getFalseExpression(), p);
                        for (int i = before; i < selfCalls.size(); i++) selfCalls.get(i).branch = true;
                        return null;
                    }
                    @Override public Void visitLambdaExpression(LambdaExpressionTree l, Void p) { return null; }
                }.scan(e, null);
                return c[0];
            }

            /** The cost of one call that is not a self-call. */
            Cost call(MethodInvocationTree call, Element target) {
                if (!(target instanceof ExecutableElement m)) return Cost.ONE;
                if (methods.containsKey(m)) {
                    Result r = of(m);
                    if (r.approximate) approximate = true;
                    if (!r.cost.equals(Cost.ONE)) reasons.add("calls " + m.getSimpleName() + ": " + r.cost.text());
                    return r.cost;
                }
                String owner = m.getEnclosingElement() instanceof TypeElement t ? t.getQualifiedName().toString() : "";
                String receiver = call.getMethodSelect() instanceof MemberSelectTree s ? receiverType(s.getExpression()) : owner;
                Cost cost = library(owner, receiver, m.getSimpleName().toString(), m.getParameters().size());
                if (!cost.equals(Cost.ONE)) reasons.add(text(call.getMethodSelect()) + "(…): " + cost.text() + " (library)");
                return cost;
            }

            String receiverType(ExpressionTree e) {
                TreePath path = TreePath.getPath(unit, e);
                var type = path == null ? null : trees.getTypeMirror(path);
                if (type == null) return "";
                String t = type.toString();
                int g = t.indexOf('<');
                return g < 0 ? t : t.substring(0, g);
            }

            /** The size an expression stands for: constants O(1), loop variables their range, anything else n. */
            Cost size(ExpressionTree e) {
                return switch (e) {
                    case null -> Cost.ONE;
                    case LiteralTree l -> Cost.ONE;
                    case ParenthesizedTree p -> size(p.getExpression());
                    case TypeCastTree t -> size(t.getExpression());
                    case IdentifierTree id -> loopVars.getOrDefault(id.getName().toString(),
                            element(id) instanceof javax.lang.model.element.VariableElement v && v.getConstantValue() != null ? Cost.ONE : Cost.N);
                    case BinaryTree b -> switch (b.getKind()) {
                        case MULTIPLY -> size(b.getLeftOperand()).times(size(b.getRightOperand()));
                        case DIVIDE, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT, REMAINDER -> size(b.getLeftOperand());
                        default -> size(b.getLeftOperand()).max(size(b.getRightOperand()));
                    };
                    case UnaryTree u -> size(u.getExpression());
                    case MethodInvocationTree call when call.getArguments().isEmpty() -> Cost.N; // x.size(), s.length()
                    case MethodInvocationTree call -> { // Math.min(a, b), Math.sqrt(n): the largest argument
                        Cost c = Cost.ONE;
                        for (ExpressionTree a : call.getArguments()) c = c.max(size(a));
                        yield c;
                    }
                    default -> Cost.N; // a.length, a field
                };
            }

            // ---- recursion ------------------------------------------------------------------

            Result recursion(Cost work) {
                if (selfCalls.isEmpty()) return new Result(work, approximate, List.copyOf(reasons));
                boolean inLoop = selfCalls.stream().anyMatch(s -> s.inLoop);
                long alternatives = selfCalls.stream().filter(s -> s.branch).count();
                long always = selfCalls.size() - alternatives;
                // Calls in different branches are alternatives: one of them runs.
                int a = (int) Math.max(1, always + (alternatives > 0 ? 1 : 0));
                String shrink = "unknown";
                for (SelfCall s : selfCalls) {
                    String k = shrink(s.call);
                    if (k.equals("divide")) shrink = "divide";
                    else if (k.equals("subtract") && !shrink.equals("divide")) shrink = "subtract";
                }
                if (inLoop) {
                    guess("calls itself inside a loop: exponential (guessed)");
                    return new Result(new Cost(2, 0, 0), true, List.copyOf(reasons));
                }
                if (shrink.equals("unknown")) {
                    guess("calls itself with arguments it cannot read (guessed one step smaller)");
                    shrink = "subtract";
                }
                Cost cost;
                if (shrink.equals("subtract")) {
                    if (a == 1) {
                        cost = Cost.N.times(work);
                        reasons.add("calls itself once on a smaller argument (n - c): n levels × " + work.text());
                    } else {
                        cost = new Cost(a, 0, 0);
                        reasons.add("calls itself " + a + " times on n - c: " + cost.text());
                    }
                } else {
                    // T(n) = a T(n / 2) + n^d log^k n (master theorem, halving).
                    double crit = Math.log(a) / Math.log(2), d = work.poly;
                    if (work.base > 0) cost = work;
                    else if (Math.abs(d - crit) < 1e-9) cost = new Cost(0, d, work.log + 1);
                    else if (d > crit) cost = work;
                    else cost = new Cost(0, Math.round(crit * 100) / 100.0, 0);
                    reasons.add("calls itself " + (a == 1 ? "once" : a + " times") + " on half the input, " + work.text() + " work besides: " + cost.text());
                }
                return new Result(cost, approximate, List.copyOf(reasons));
            }

            /**
             * How a self-call's arguments shrink: divide ((lo + hi) / 2, n / 2, mid), subtract (a parameter
             * minus a constant, n - 1), or unknown. A range split at a computed point (quick sort's
             * p - 1, p + 1) is taken as halves, the average case, and marked as a guess.
             */
            String shrink(MethodInvocationTree call) {
                String kind = "unknown";
                Set<String> params = new HashSet<>();
                for (var p : self.getParameters()) params.add(p.getSimpleName().toString());
                for (ExpressionTree a : call.getArguments()) {
                    if (isHalving(a) || mentions(a, halves)) return "divide";
                    if (a instanceof BinaryTree b && (b.getKind() == Tree.Kind.MINUS || b.getKind() == Tree.Kind.PLUS)
                            && b.getRightOperand() instanceof LiteralTree && b.getLeftOperand() instanceof IdentifierTree id) {
                        if (params.contains(id.getName().toString())) kind = "subtract";
                        else { guess("splits its range at a computed point (" + text(a) + "): taken as halves, the average case"); return "divide"; }
                    }
                    if (a instanceof MethodInvocationTree m && m.toString().contains("substring")) kind = "subtract";
                }
                return kind;
            }
        }

        final class SelfCall {
            final MethodInvocationTree call;
            final boolean inLoop;
            boolean branch;
            SelfCall(MethodInvocationTree call, boolean inLoop) { this.call = call; this.inLoop = inLoop; }
        }

        /** An expression that halves something: x / 2, x >> 1, (lo + hi) / 2, lo + (hi - lo) / 2. */
        static boolean isHalving(ExpressionTree e) {
            boolean[] found = {false};
            new TreeScanner<Void, Void>() {
                @Override public Void visitBinary(BinaryTree b, Void p) {
                    if ((b.getKind() == Tree.Kind.DIVIDE || b.getKind() == Tree.Kind.RIGHT_SHIFT || b.getKind() == Tree.Kind.UNSIGNED_RIGHT_SHIFT)
                            && b.getRightOperand() instanceof LiteralTree l && l.getValue() instanceof Number n && n.intValue() >= 1) found[0] = true;
                    return super.visitBinary(b, p);
                }
            }.scan(e, null);
            return found[0];
        }

        static boolean mentions(ExpressionTree e, Set<String> names) {
            if (names.isEmpty()) return false;
            boolean[] found = {false};
            new TreeScanner<Void, Void>() {
                @Override public Void visitIdentifier(IdentifierTree id, Void p) { if (names.contains(id.getName().toString())) found[0] = true; return null; }
            }.scan(e, null);
            return found[0];
        }

        /** The other side of a comparison with {@code var}: the loop's bound. */
        static ExpressionTree otherSide(ExpressionTree condition, String var) {
            if (!(condition instanceof BinaryTree b)) return null;
            switch (b.getKind()) {
                case LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL, NOT_EQUAL_TO -> { }
                case CONDITIONAL_AND -> { // i < n && a[i] != x: the comparison with the variable bounds it
                    ExpressionTree left = otherSide(b.getLeftOperand(), var);
                    return left != null ? left : otherSide(b.getRightOperand(), var);
                }
                default -> { return null; }
            }
            if (isVar(b.getLeftOperand(), var)) return b.getRightOperand();
            if (isVar(b.getRightOperand(), var)) return b.getLeftOperand();
            // i * i <= n: the variable grows to sqrt(n); counted as n (an upper bound).
            if (mentions(b.getLeftOperand(), Set.of(var))) return b.getRightOperand();
            return null;
        }

        static boolean isVar(ExpressionTree e, String var) {
            while (e instanceof ParenthesizedTree p) e = p.getExpression();
            return e instanceof IdentifierTree id && id.getName().contentEquals(var);
        }

        /** linear (i++, i -= 2), geometric (i *= 2, i /= 2, i >>= 1, i = i * 2), or null. */
        static String stepKind(ExpressionTree update, String var) {
            switch (update) {
                case UnaryTree u when isVar(u.getExpression(), var) -> { return "linear"; }
                case CompoundAssignmentTree a when isVar(a.getVariable(), var) -> {
                    return switch (a.getKind()) {
                        case PLUS_ASSIGNMENT, MINUS_ASSIGNMENT -> "linear";
                        case MULTIPLY_ASSIGNMENT, DIVIDE_ASSIGNMENT, LEFT_SHIFT_ASSIGNMENT, RIGHT_SHIFT_ASSIGNMENT, UNSIGNED_RIGHT_SHIFT_ASSIGNMENT -> "geometric";
                        default -> null;
                    };
                }
                case AssignmentTree a when isVar(a.getVariable(), var) && a.getExpression() instanceof BinaryTree b -> {
                    return switch (b.getKind()) {
                        case PLUS, MINUS -> "linear";
                        case MULTIPLY, DIVIDE, LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> "geometric";
                        default -> null;
                    };
                }
                default -> { return null; }
            }
        }

        private static final Set<String> SORTED = Set.of("java.util.TreeMap", "java.util.TreeSet", "java.util.PriorityQueue");
        private static final Set<String> HASHED = Set.of("java.util.HashMap", "java.util.HashSet", "java.util.LinkedHashMap", "java.util.LinkedHashSet",
                "java.util.Map", "java.util.Set");

        /** Listed costs of library calls, in the size of the receiver (or the argument for static utilities). */
        static Cost library(String owner, String receiver, String name, int params) {
            Cost n = Cost.N, nLogN = new Cost(0, 1, 1), log = Cost.LOG, one = Cost.ONE;
            switch (owner) {
                case "java.util.Arrays" -> { return name.equals("sort") || name.equals("parallelSort") ? nLogN : name.equals("binarySearch") ? log : n; }
                case "java.util.Collections" -> { return name.equals("sort") ? nLogN : name.equals("binarySearch") ? log : n; }
                case "java.lang.Math", "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Character", "java.lang.Boolean",
                     "java.io.PrintStream", "java.util.Scanner", "java.io.BufferedReader", "java.util.Objects" -> { return one; }
                default -> { }
            }
            if (owner.equals("java.lang.String") || receiver.equals("java.lang.String")) {
                return Set.of("length", "charAt", "isEmpty", "hashCode").contains(name) && !name.equals("hashCode") ? one : name.equals("valueOf") ? one : n;
            }
            if (receiver.equals("java.lang.StringBuilder")) return Set.of("append", "length", "charAt", "setCharAt", "setLength").contains(name) ? one : n;
            if (owner.startsWith("java.util.stream.")) return name.equals("sorted") ? nLogN : Set.of("filter", "map", "mapToInt", "mapToObj", "boxed", "limit", "skip", "peek",
                    "distinct", "of", "range", "rangeClosed", "stream").contains(name) ? one : n;
            if (name.equals("stream") || name.equals("forEach") || name.equals("removeIf") || name.equals("replaceAll") || name.equals("toString")
                    || name.equals("equals") || name.equals("hashCode") || name.equals("addAll") || name.equals("toArray")) return n;
            if (name.equals("sort")) return nLogN;
            if (SORTED.contains(receiver)) {
                if (receiver.equals("java.util.PriorityQueue")) return name.equals("peek") || name.equals("size") || name.equals("isEmpty") ? one
                        : name.equals("contains") || name.equals("remove") && params == 1 ? n : log;
                return Set.of("size", "isEmpty", "clear").contains(name) ? one : log;
            }
            if (HASHED.contains(receiver)) return name.equals("containsValue") ? n : one;
            if (receiver.equals("java.util.LinkedList")) return Set.of("get", "set", "contains", "indexOf", "remove").contains(name) && params == 1
                    && !name.equals("remove") ? n : name.equals("remove") && params == 1 ? n : one;
            if (receiver.endsWith("List") || receiver.equals("java.util.Stack") || receiver.equals("java.util.Vector")) {
                if (name.equals("add") && params == 2 || name.equals("remove") || name.equals("contains") || name.equals("indexOf")
                        || name.equals("lastIndexOf") || name.equals("clear") || name.equals("search")) return n;
                return one;
            }
            if (receiver.endsWith("Deque") || receiver.endsWith("Queue")) return name.equals("contains") || name.equals("remove") && params == 1 ? n : one;
            return one;
        }
    }
}
