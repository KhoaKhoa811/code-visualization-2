package codeviz.tracer;

import codeviz.tracer.Plan.Cond;
import codeviz.tracer.Plan.Diagnostic;
import codeviz.tracer.Plan.Expect;
import codeviz.tracer.Plan.Item;
import codeviz.tracer.Plan.Kind;
import codeviz.tracer.Plan.Site;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.*;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.tools.*;

/**
 * Compiles the exact submitted text once and plans the operations to trace.
 * Never executes code. Unsupported constructs make capture unavailable rather
 * than producing a partial plan.
 */
final class Analyzer {
    /**
     * {@code unsupported} blocks tracing of the whole program (output only);
     * {@code uncaptured} lists statements that run without capture inside a traced program (Part 8a).
     */
    record Analysis(String source, List<Diagnostic> compileErrors, List<Diagnostic> unsupported, List<Diagnostic> uncaptured,
                    Map<String, byte[]> classes, List<Item> plan, Map<Integer, Site> sites, String rootScope) {
        boolean traceable() { return compileErrors.isEmpty() && unsupported.isEmpty(); }
    }

    static final String FILE = "Main.java";

    static Analysis analyze(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
        var classes = new LinkedHashMap<String, byte[]>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + FILE), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        var standard = compiler.getStandardFileManager(collector, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8);
        try {
            // Submitted code sees only the JDK, never the tracer or recorder classes.
            standard.setLocation(StandardLocation.CLASS_PATH, List.of());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        JavaFileManager memory = new ForwardingJavaFileManager<>(standard) {
            @Override public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                                 JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className + kind.extension), kind) {
                    @Override public OutputStream openOutputStream() {
                        return new ByteArrayOutputStream() {
                            @Override public void close() { classes.put(className, toByteArray()); }
                        };
                    }
                };
            }
        };
        JavacTask task = (JavacTask) compiler.getTask(null, memory, collector,
                List.of("-g", "-Xjcov", "--release", "21", "-proc:none", "-implicit:none"), null, List.of(file));
        List<Diagnostic> errors = new ArrayList<>();
        List<Diagnostic> unsupported = new ArrayList<>();
        Walker walker = null;
        try {
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            errors.addAll(compileErrors(collector));
            if (errors.isEmpty()) {
                CompilationUnitTree unit = units.iterator().next();
                walker = new Walker(unit, Trees.instance(task), task.getElements(), unsupported);
                walker.unit();
                task.generate();
                errors.clear(); // diagnostics accumulate; take the full list again
                errors.addAll(compileErrors(collector));
            }
        } catch (IOException e) {
            errors.add(new Diagnostic("compile", "COMPILER_FAILURE", e.getMessage(), null, null));
        } finally {
            try { memory.close(); } catch (IOException ignored) { }
        }
        if (walker == null || !errors.isEmpty())
            return new Analysis(source, errors, unsupported, List.of(), Map.of(), List.of(), Map.of(), null);
        return new Analysis(source, errors, unsupported, List.copyOf(walker.uncaptured), Map.copyOf(classes), List.copyOf(walker.plan),
                Map.copyOf(walker.sites), walker.rootScope);
    }

    private static List<Diagnostic> compileErrors(DiagnosticCollector<JavaFileObject> collector) {
        List<Diagnostic> out = new ArrayList<>();
        for (var d : collector.getDiagnostics()) {
            if (d.getKind() != javax.tools.Diagnostic.Kind.ERROR) continue;
            boolean ranged = d.getStartPosition() >= 0 && d.getEndPosition() >= d.getStartPosition();
            out.add(new Diagnostic("compile", "COMPILE_ERROR", d.getMessage(Locale.ROOT),
                    ranged ? (int) d.getStartPosition() : null, ranged ? (int) d.getEndPosition() : null));
        }
        return out;
    }

    /** Walks supported trees in javac code-generation order. */
    private static final class Walker {
        private final CompilationUnitTree unit;
        private final Trees trees;
        private final Elements elements;
        private final SourcePositions positions;
        private final List<Diagnostic> unsupported;
        final List<Diagnostic> uncaptured = new ArrayList<>();
        final List<Item> plan = new ArrayList<>();
        final Map<Integer, Site> sites = new LinkedHashMap<>();
        private final Deque<String> scopes = new ArrayDeque<>();
        private final Map<Element, Site> declarations = new HashMap<>();
        private final List<Element> declared = new ArrayList<>(); // insertion order, for rollback
        // Findings for the statement being planned; null outside main's statements (they block tracing).
        private List<Diagnostic> current;
        String rootScope;

        Walker(CompilationUnitTree unit, Trees trees, Elements elements, List<Diagnostic> unsupported) {
            this.unit = unit; this.trees = trees; this.elements = elements;
            this.positions = trees.getSourcePositions(); this.unsupported = unsupported;
        }

        // ---- structure ---------------------------------------------------------

        /**
         * Uncaptured code runs in the same JVM as the recorder (Part 8a). APIs that could
         * reach the recorder or its record file, or run code concurrently with main, keep the
         * whole program output-only. A guard against accidental interference, not a sandbox.
         */
        private static final List<String> INTEGRITY = List.of("java.lang.reflect.", "java.lang.invoke.",
                "java.lang.Class", "java.lang.ClassLoader", "java.lang.Module", "java.lang.Runtime", "java.lang.Process", "java.lang.ProcessBuilder",
                "java.lang.Thread", "java.lang.ThreadGroup", "java.lang.StackWalker", "java.util.concurrent.", "java.util.Timer",
                "java.util.ServiceLoader", "java.io.File", "java.io.RandomAccessFile", "java.nio.file.", "java.nio.channels.",
                "java.lang.instrument.", "java.lang.management.", "java.net.", "sun.", "jdk.", "com.sun.");

        private void integrity() {
            Set<String> reported = new HashSet<>();
            new TreeScanner<Void, Void>() {
                private void check(Tree t) {
                    Element e = element(t);
                    if (e == null) return;
                    Element type = e instanceof TypeElement ? e : e.getEnclosingElement();
                    if (!(type instanceof TypeElement owner)) return;
                    String name = owner.getQualifiedName().toString();
                    for (String denied : INTEGRITY) {
                        boolean match = denied.endsWith(".") ? name.startsWith(denied)
                                : name.equals(denied) || name.startsWith(denied + ".") || name.startsWith(denied + "$");
                        if (match) {
                            if (!reported.add(name)) return;
                            unsupported.add(new Diagnostic("capture", "TRACE_INTEGRITY", "Visualization is not offered for programs "
                                    + "that use " + name + " (it could interfere with recording); the program can still run with output only.",
                                    start(t), end(t)));
                            return;
                        }
                    }
                }
                @Override public Void visitMemberSelect(MemberSelectTree m, Void p) { check(m); return super.visitMemberSelect(m, p); }
                @Override public Void visitIdentifier(IdentifierTree i, Void p) { check(i); return super.visitIdentifier(i, p); }
                @Override public Void visitNewClass(NewClassTree n, Void p) { check(n.getIdentifier()); return super.visitNewClass(n, p); }
            }.scan(unit, null);
        }

        void unit() {
            integrity();
            if (unit.getPackageName() != null) unsupported(unit.getPackageName(), "package declarations");
            boolean sawMainClass = false;
            for (Tree type : unit.getTypeDecls()) {
                // Other types, helper methods and fields run untraced (Part 8a); calls to them
                // from main become uncaptured statements.
                if (!(type instanceof ClassTree c) || !c.getSimpleName().contentEquals("Main")) continue;
                sawMainClass = true;
                if (c.getKind() != Tree.Kind.CLASS || c.getExtendsClause() != null
                        || !c.getImplementsClause().isEmpty() || !c.getTypeParameters().isEmpty()) {
                    unsupported(type, "a Main that is not a plain class");
                    continue;
                }
                MethodTree main = null;
                for (Tree member : c.getMembers()) if (main == null && member instanceof MethodTree m && isMain(m)) main = m;
                if (main == null) unsupported(c, "a class without public static void main(String[])");
                else method(main);
            }
            if (!sawMainClass) unsupported(unit, "a program without class Main");
        }

        private boolean isMain(MethodTree m) {
            if (!(element(m) instanceof ExecutableElement e)) return false;
            return e.getSimpleName().contentEquals("main") && e.getModifiers().contains(Modifier.STATIC)
                    && e.getModifiers().contains(Modifier.PUBLIC) && e.getReturnType().toString().equals("void")
                    && e.getParameters().size() == 1 && e.getParameters().get(0).asType().toString().equals("java.lang.String[]");
        }

        private void method(MethodTree m) {
            rootScope = "b" + start(m.getBody());
            scopes.push(rootScope);
            for (StatementTree s : m.getBody().getStatements()) statement(s);
            scopes.pop();
        }

        // ---- statements --------------------------------------------------------

        /**
         * Plans one statement in main. If anything at its own level is unsupported, its
         * partial plan is rolled back and it becomes one uncaptured region (Part 8a).
         * Nested statements (branches, loop bodies, block members) degrade on their own.
         */
        private void statement(StatementTree s) {
            if (s.getKind() == Tree.Kind.BLOCK) { // blocks never degrade as a whole
                scopes.push("b" + start(s));
                for (StatementTree inner : ((BlockTree) s).getStatements()) statement(inner);
                scopes.pop();
                return;
            }
            int planMark = plan.size(), siteMark = sites.size(), uncapturedMark = uncaptured.size(), declaredMark = declared.size();
            List<Diagnostic> outer = current;
            current = new ArrayList<>();
            plain(s);
            List<Diagnostic> found = current;
            current = outer;
            if (found.isEmpty()) return;
            plan.subList(planMark, plan.size()).clear();
            sites.keySet().removeIf(id -> id > siteMark);
            uncaptured.subList(uncapturedMark, uncaptured.size()).clear();
            while (declared.size() > declaredMark) declarations.remove(declared.remove(declared.size() - 1));
            region(s, found);
        }

        private void region(StatementTree s, List<Diagnostic> found) {
            uncaptured.addAll(found);
            // Statements that generate no code (a declaration without initializer, a local
            // class) leave no step; later uses of what they declare are uncaptured too.
            if (s instanceof VariableTree v && v.getInitializer() == null || s instanceof ClassTree) return;
            Site site = site(Kind.REGION, s);
            Effects effects = new Effects();
            effects.scan(s, null);
            site.unknownDeclarations = List.copyOf(effects.assigned);
            site.mayWriteArrays = effects.writes;
            site.crtStart = crt(start(s));
            site.crtEnd = crt(end(s));
            plan.add(Item.of(Expect.REGION, site));
        }

        /** javac CharacterRangeTable position: line << 10 | column, or -1 when not encodable. */
        private int crt(int position) {
            LineMap lines = unit.getLineMap();
            long line = lines.getLineNumber(position), column = lines.getColumnNumber(position);
            return column > 1023 || line >= (1 << 21) ? -1 : (int) (line << 10 | column);
        }

        /** What an uncaptured statement may change, from its source; conservative. */
        private final class Effects extends TreeScanner<Void, Void> {
            final Set<String> assigned = new LinkedHashSet<>();
            boolean writes;

            private void target(ExpressionTree t) {
                t = strip(t);
                if (t instanceof IdentifierTree) {
                    Site d = declarations.get(element(t));
                    if (d != null) assigned.add(d.declarationId);
                } else if (t instanceof ArrayAccessTree) {
                    writes = true;
                }
            }

            @Override public Void visitAssignment(AssignmentTree a, Void p) { target(a.getVariable()); return super.visitAssignment(a, p); }

            @Override public Void visitCompoundAssignment(CompoundAssignmentTree a, Void p) {
                target(a.getVariable());
                if (a.getKind() == Tree.Kind.PLUS_ASSIGNMENT && !simple(javaType(a.getExpression()))) writes = true; // toString()
                return super.visitCompoundAssignment(a, p);
            }

            @Override public Void visitUnary(UnaryTree u, Void p) {
                switch (u.getKind()) {
                    case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> target(u.getExpression());
                    default -> { }
                }
                return super.visitUnary(u, p);
            }

            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void p) {
                if (!harmless(call)) writes = true;
                return super.visitMethodInvocation(call, p);
            }

            @Override public Void visitNewClass(NewClassTree n, Void p) { writes = true; return super.visitNewClass(n, p); }

            @Override public Void visitBinary(BinaryTree b, Void p) {
                if (b.getKind() == Tree.Kind.PLUS && "java.lang.String".equals(javaType(b))
                        && !(simple(javaType(b.getLeftOperand())) && simple(javaType(b.getRightOperand())))) writes = true;
                return super.visitBinary(b, p);
            }

            @Override public Void visitEnhancedForLoop(EnhancedForLoopTree f, Void p) {
                if (!javaType(f.getExpression()).endsWith("[]")) writes = true; // iterator() runs code
                return super.visitEnhancedForLoop(f, p);
            }

            @Override public Void visitTry(TryTree t, Void p) {
                if (!t.getResources().isEmpty()) writes = true; // close() runs code
                return super.visitTry(t, p);
            }

            @Override public Void visitDeconstructionPattern(DeconstructionPatternTree d, Void p) {
                writes = true; // record accessors run code
                return super.visitDeconstructionPattern(d, p);
            }

            @Override public Void visitMemberSelect(MemberSelectTree m, Void p) {
                // First use of another class's static field may run its static initializer.
                if (element(m) instanceof VariableElement v && v.getKind() == ElementKind.FIELD && v.getConstantValue() == null
                        && v.getEnclosingElement() instanceof TypeElement owner && !owner.getQualifiedName().toString().startsWith("java."))
                    writes = true;
                return super.visitMemberSelect(m, p);
            }
        }

        private static final Set<String> PURE_OWNERS = Set.of("java.lang.Math", "java.lang.StrictMath", "java.lang.String",
                "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float", "java.lang.Short",
                "java.lang.Byte", "java.lang.Character", "java.lang.Boolean");

        /** Calls that cannot change any array: System.out/err printing and pure java.lang helpers. */
        private boolean harmless(MethodInvocationTree call) {
            if (!(element(call) instanceof ExecutableElement method) || !(method.getEnclosingElement() instanceof TypeElement owner))
                return false;
            if (!method.getParameters().stream().allMatch(p -> simple(p.asType().toString()))) return false;
            String name = owner.getQualifiedName().toString();
            if (name.equals("java.io.PrintStream")) {
                return (method.getSimpleName().contentEquals("print") || method.getSimpleName().contentEquals("println"))
                        && systemStream(call);
            }
            return PURE_OWNERS.contains(name) && (method.getModifiers().contains(Modifier.STATIC) || name.equals("java.lang.String"));
        }

        private boolean systemStream(MethodInvocationTree call) {
            return call.getMethodSelect() instanceof MemberSelectTree select
                    && element(select.getExpression()) instanceof VariableElement stream
                    && stream.getEnclosingElement() instanceof TypeElement system
                    && system.getQualifiedName().contentEquals("java.lang.System")
                    && (stream.getSimpleName().contentEquals("out") || stream.getSimpleName().contentEquals("err"));
        }

        private static boolean simple(String type) {
            return switch (type) {
                case "byte", "short", "int", "long", "char", "float", "double", "boolean", "java.lang.String" -> true;
                default -> false;
            };
        }

        /** Plans a statement's own parts; findings go to the enclosing statement's frame. */
        private void plain(StatementTree s) {
            switch (s.getKind()) {
                case VARIABLE -> declaration((VariableTree) s);
                case EXPRESSION_STATEMENT -> expressionStatement(((ExpressionStatementTree) s).getExpression());
                case BLOCK -> statement(s);
                case IF -> {
                    IfTree i = (IfTree) s;
                    if (isConstant(i.getCondition())) { unsupported(i.getCondition(), "constant if conditions"); return; }
                    condition(i.getCondition());
                    statement(i.getThenStatement());
                    if (i.getElseStatement() != null) statement(i.getElseStatement());
                }
                case WHILE_LOOP -> {
                    WhileLoopTree w = (WhileLoopTree) s;
                    loopCondition(w.getCondition());
                    statement(w.getStatement());
                }
                case FOR_LOOP -> {
                    ForLoopTree f = (ForLoopTree) s;
                    scopes.push("f" + start(f));
                    for (StatementTree init : f.getInitializer()) plain(init); // an unsupported init degrades the whole loop
                    if (f.getCondition() != null) loopCondition(f.getCondition());
                    statement(f.getStatement());
                    for (ExpressionStatementTree update : f.getUpdate()) expressionStatement(update.getExpression());
                    scopes.pop();
                }
                case BREAK -> { if (((BreakTree) s).getLabel() != null) unsupported(s, "labeled break"); }
                case CONTINUE -> { if (((ContinueTree) s).getLabel() != null) unsupported(s, "labeled continue"); }
                case RETURN -> { if (((ReturnTree) s).getExpression() != null) unsupported(s, "return values"); }
                case EMPTY_STATEMENT -> { }
                default -> unsupported(s, "this statement kind (" + s.getKind() + ")");
            }
        }

        private void loopCondition(ExpressionTree c) {
            if (isConstant(c)) {
                if (!Boolean.TRUE.equals(constantValue(c))) unsupported(c, "constant false loop conditions");
                return; // javac emits no test for a constant true condition.
            }
            condition(c);
        }

        private void declaration(VariableTree v) {
            String type = javaType(v);
            if (!List.of("int", "boolean", "java.lang.String", "int[]").contains(type)) {
                unsupported(v, "local variables of type " + type);
                return;
            }
            if (v.getInitializer() == null) { unsupported(v, "declarations without an initializer"); return; }
            Site site = site(Kind.DECLARE, v);
            site.declarationId = "Main.main:" + v.getName() + "@" + start(v);
            site.name = v.getName().toString();
            site.javaType = type;
            declarations.put(element(v), site);
            declared.add(element(v));
            value(v.getInitializer());
            plan.add(Item.of(Expect.STORE, site));
        }

        private void expressionStatement(ExpressionTree e) {
            switch (e.getKind()) {
                case ASSIGNMENT -> {
                    AssignmentTree a = (AssignmentTree) e;
                    ExpressionTree target = strip(a.getVariable());
                    if (target instanceof IdentifierTree id) {
                        Site declared = declarations.get(element(id));
                        if (declared == null) { unsupported(target, "assignment to this variable"); return; }
                        Site site = site(Kind.ASSIGN, a);
                        site.declarationId = declared.declarationId; site.name = declared.name; site.javaType = declared.javaType;
                        value(a.getExpression());
                        plan.add(Item.of(Expect.STORE, site));
                    } else if (target instanceof ArrayAccessTree access && "int[]".equals(javaType(access.getExpression()))) {
                        Site site = site(Kind.ARRAY_WRITE, a);
                        value(access.getExpression());
                        value(access.getIndex());
                        value(a.getExpression());
                        plan.add(Item.of(Expect.IASTORE, site));
                    } else {
                        unsupported(target, "assignment to this target");
                    }
                }
                case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> update((UnaryTree) e);
                case METHOD_INVOCATION -> output((MethodInvocationTree) e);
                default -> unsupported(e, "this expression statement (" + e.getKind() + ")");
            }
        }

        private void output(MethodInvocationTree call) {
            if (!(element(call) instanceof ExecutableElement method)
                    || !(method.getEnclosingElement() instanceof TypeElement owner)
                    || !owner.getQualifiedName().contentEquals("java.io.PrintStream")
                    || !(method.getSimpleName().contentEquals("println") || method.getSimpleName().contentEquals("print"))
                    || !(call.getMethodSelect() instanceof MemberSelectTree select)
                    || !(element(select.getExpression()) instanceof VariableElement stream)
                    || !(stream.getEnclosingElement() instanceof TypeElement system)
                    || !system.getQualifiedName().contentEquals("java.lang.System")
                    || !(stream.getSimpleName().contentEquals("out") || stream.getSimpleName().contentEquals("err"))) {
                unsupported(call, "method calls other than System.out/err print/println (library adapters are planned)");
                return;
            }
            List<String> parameters = method.getParameters().stream().map(p -> p.asType().toString()).toList();
            if (!(parameters.isEmpty() || List.of(List.of("int"), List.of("boolean"), List.of("java.lang.String")).contains(parameters))) {
                unsupported(call, "print/println(" + String.join(",", parameters) + ")");
                return;
            }
            plan.add(new Item(Expect.GETSTATIC, null, 0, stream.getSimpleName().toString()));
            for (ExpressionTree argument : call.getArguments()) value(argument);
            plan.add(new Item(Expect.INVOKE, null, 0,
                    method.getSimpleName() + (parameters.isEmpty() ? "()V" : "(" + descriptor(parameters.get(0)) + ")V")));
        }

        private static String descriptor(String type) {
            return switch (type) { case "int" -> "I"; case "boolean" -> "Z"; default -> "Ljava/lang/String;"; };
        }

        // ---- expressions in value context --------------------------------------

        private void value(ExpressionTree e) {
            e = strip(e);
            if (isConstant(e)) return; // javac folds compile-time constants: nothing executes.
            switch (e.getKind()) {
                case NULL_LITERAL, STRING_LITERAL, INT_LITERAL, BOOLEAN_LITERAL -> { }
                case IDENTIFIER -> {
                    if (!declarations.containsKey(element(e))) unsupported(e, "this variable (only locals declared in main are traced)");
                }
                case PLUS, MINUS, MULTIPLY, DIVIDE, REMAINDER -> {
                    BinaryTree b = (BinaryTree) e;
                    if (!"int".equals(javaType(b))) { unsupported(b, "non-int arithmetic or string concatenation"); return; }
                    value(b.getLeftOperand());
                    value(b.getRightOperand());
                    Site site = site(Kind.ARITHMETIC, b);
                    site.operator = symbol(b.getKind());
                    plan.add(Item.of(Expect.ARITH, site));
                }
                case UNARY_MINUS -> {
                    UnaryTree u = (UnaryTree) e;
                    if (!"int".equals(javaType(u))) { unsupported(u, "non-int negation"); return; }
                    value(u.getExpression());
                    Site site = site(Kind.ARITHMETIC, u);
                    site.operator = "-";
                    plan.add(Item.of(Expect.INEG, site));
                }
                case LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL, EQUAL_TO, NOT_EQUAL_TO,
                     CONDITIONAL_AND, CONDITIONAL_OR, LOGICAL_COMPLEMENT -> condition(e);
                case ARRAY_ACCESS -> {
                    ArrayAccessTree a = (ArrayAccessTree) e;
                    if (!"int[]".equals(javaType(a.getExpression()))) { unsupported(a, "reads from non-int arrays"); return; }
                    value(a.getExpression());
                    value(a.getIndex());
                    plan.add(Item.of(Expect.IALOAD, site(Kind.ARRAY_READ, a)));
                }
                case MEMBER_SELECT -> {
                    MemberSelectTree m = (MemberSelectTree) e;
                    if (!m.getIdentifier().contentEquals("length") || !"int[]".equals(javaType(m.getExpression()))) {
                        unsupported(m, "this member access");
                        return;
                    }
                    value(m.getExpression());
                    plan.add(Item.of(Expect.ARRAYLENGTH, site(Kind.ARRAY_LENGTH, m)));
                }
                case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> update((UnaryTree) e);
                case NEW_ARRAY -> newArray((NewArrayTree) e);
                default -> unsupported(e, "this expression (" + e.getKind() + ")");
            }
        }

        private void newArray(NewArrayTree n) {
            if (!"int[]".equals(javaType(n))) { unsupported(n, "arrays other than int[]"); return; }
            Site site = site(Kind.ALLOCATE, n);
            if (n.getInitializers() != null) {
                for (ExpressionTree element : n.getInitializers()) {
                    if (!isConstant(element)) { unsupported(element, "non-constant array initializer elements"); return; }
                }
                plan.add(new Item(Expect.NEWARRAY_LITERAL, site, n.getInitializers().size(), null));
            } else if (n.getDimensions().size() == 1) {
                value(n.getDimensions().get(0));
                plan.add(Item.of(Expect.NEWARRAY, site));
            } else {
                unsupported(n, "multi-dimensional arrays");
            }
        }

        private void update(UnaryTree u) {
            ExpressionTree target = strip(u.getExpression());
            Site declared = target instanceof IdentifierTree ? declarations.get(element(target)) : null;
            if (declared == null || !declared.javaType.equals("int")) { unsupported(u, "++/-- on anything but an int local"); return; }
            Site site = site(Kind.UPDATE, u);
            site.declarationId = declared.declarationId; site.name = declared.name; site.javaType = "int";
            site.operator = switch (u.getKind()) { case PREFIX_INCREMENT, POSTFIX_INCREMENT -> "++"; default -> "--"; };
            site.position = switch (u.getKind()) { case PREFIX_INCREMENT, PREFIX_DECREMENT -> "prefix"; default -> "postfix"; };
            plan.add(Item.of(Expect.IINC, site));
        }

        // ---- conditions (javac genCond) ------------------------------------------

        private void condition(ExpressionTree e) {
            Cond root = cond(e);
            if (root != null) root.parent = null;
        }

        private Cond cond(ExpressionTree e) {
            e = strip(e);
            if (isConstant(e)) { unsupported(e, "constant operands inside conditions"); return null; }
            switch (e.getKind()) {
                case CONDITIONAL_AND, CONDITIONAL_OR -> {
                    BinaryTree b = (BinaryTree) e;
                    Cond node = new Cond(e.getKind() == Tree.Kind.CONDITIONAL_AND ? Cond.Op.AND : Cond.Op.OR, start(b), end(b));
                    attach(node, cond(b.getLeftOperand()));
                    attach(node, cond(b.getRightOperand()));
                    return node;
                }
                case LOGICAL_COMPLEMENT -> {
                    UnaryTree u = (UnaryTree) e;
                    Cond node = new Cond(Cond.Op.NOT, start(u), end(u));
                    attach(node, cond(u.getExpression()));
                    return node;
                }
                case LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL, EQUAL_TO, NOT_EQUAL_TO -> {
                    BinaryTree b = (BinaryTree) e;
                    String left = javaType(b.getLeftOperand()), right = javaType(b.getRightOperand());
                    String operandType;
                    if (left.equals("int") && right.equals("int")) operandType = "int";
                    else if (left.equals("boolean") && right.equals("boolean")) operandType = "boolean";
                    else if (isReference(left) && isReference(right) && (e.getKind() == Tree.Kind.EQUAL_TO || e.getKind() == Tree.Kind.NOT_EQUAL_TO))
                        operandType = "ref";
                    else { unsupported(b, "comparing " + left + " with " + right); return null; }
                    if (isConstant(b.getLeftOperand())) { unsupported(b, "a constant left comparison operand"); return null; }
                    value(b.getLeftOperand());
                    value(b.getRightOperand());
                    Cond leaf = new Cond(Cond.Op.LEAF, start(b), end(b));
                    leaf.sourceOp = symbol(b.getKind());
                    return leaf(leaf, b, operandType);
                }
                case IDENTIFIER -> {
                    if (!declarations.containsKey(element(e)) || !"boolean".equals(javaType(e))) {
                        unsupported(e, "this condition");
                        return null;
                    }
                    return leaf(new Cond(Cond.Op.LEAF, start(e), end(e)), e, "boolean");
                }
                default -> { unsupported(e, "this condition (" + e.getKind() + ")"); return null; }
            }
        }

        private Cond leaf(Cond leaf, Tree tree, String operandType) {
            Site site = site(Kind.LEAF, tree);
            site.leaf = leaf; site.operandType = operandType; site.operator = leaf.sourceOp;
            leaf.site = site;
            plan.add(Item.of(Expect.JUMP, site));
            return leaf;
        }

        private static void attach(Cond parent, Cond child) {
            if (child == null) return;
            child.parent = parent;
            parent.children.add(child);
        }

        private static boolean isReference(String type) {
            return type.equals("java.lang.String") || type.equals("int[]") || type.equals("<nulltype>") || type.equals("<null>");
        }

        // ---- helpers -------------------------------------------------------------

        private boolean isConstant(ExpressionTree e) { return constantValue(e) != null; }

        /** JLS 15.29 constant value as computed by javac, or null. */
        private Object constantValue(ExpressionTree e) {
            e = strip(e);
            if (e instanceof LiteralTree l) return l.getKind() == Tree.Kind.NULL_LITERAL ? null : l.getValue();
            if (e instanceof IdentifierTree && element(e) instanceof VariableElement v) return v.getConstantValue();
            if (e instanceof UnaryTree u && e.getKind() != Tree.Kind.PREFIX_INCREMENT && e.getKind() != Tree.Kind.PREFIX_DECREMENT
                    && e.getKind() != Tree.Kind.POSTFIX_INCREMENT && e.getKind() != Tree.Kind.POSTFIX_DECREMENT) {
                Object operand = constantValue(u.getExpression());
                if (operand == null) return null;
                return switch (e.getKind()) {
                    case UNARY_MINUS -> operand instanceof Integer i ? -i : null;
                    case UNARY_PLUS -> operand;
                    case LOGICAL_COMPLEMENT -> operand instanceof Boolean b ? !b : null;
                    case BITWISE_COMPLEMENT -> operand instanceof Integer i ? ~i : null;
                    default -> null;
                };
            }
            if (e instanceof BinaryTree b) {
                Object l = constantValue(b.getLeftOperand()), r = constantValue(b.getRightOperand());
                if (l == null || r == null) return null;
                // Constant-ness is what matters; exact folding of every operator is not needed.
                if (l instanceof Integer x && r instanceof Integer y) {
                    return switch (b.getKind()) {
                        case PLUS -> x + y; case MINUS -> x - y; case MULTIPLY -> x * y;
                        case DIVIDE -> y == 0 ? null : x / y; case REMAINDER -> y == 0 ? null : x % y;
                        case LESS_THAN -> x < y; case LESS_THAN_EQUAL -> x <= y; case GREATER_THAN -> x > y;
                        case GREATER_THAN_EQUAL -> x >= y; case EQUAL_TO -> x.equals(y); case NOT_EQUAL_TO -> !x.equals(y);
                        default -> Boolean.FALSE; // other constant int operators: still constant
                    };
                }
                if (l instanceof Boolean x && r instanceof Boolean y) {
                    return switch (b.getKind()) {
                        case CONDITIONAL_AND, AND -> x && y; case CONDITIONAL_OR, OR -> x || y;
                        case EQUAL_TO -> x == y; case NOT_EQUAL_TO -> x != y; case XOR -> x ^ y; default -> null;
                    };
                }
                return b.getKind() == Tree.Kind.PLUS ? String.valueOf(l) + r : null;
            }
            return null;
        }

        private static String symbol(Tree.Kind kind) {
            return switch (kind) {
                case PLUS -> "+"; case MINUS -> "-"; case MULTIPLY -> "*"; case DIVIDE -> "/"; case REMAINDER -> "%";
                case LESS_THAN -> "<"; case LESS_THAN_EQUAL -> "<="; case GREATER_THAN -> ">";
                case GREATER_THAN_EQUAL -> ">="; case EQUAL_TO -> "=="; case NOT_EQUAL_TO -> "!=";
                default -> throw new IllegalArgumentException(kind.toString());
            };
        }

        private static ExpressionTree strip(ExpressionTree e) {
            while (e instanceof ParenthesizedTree p) e = p.getExpression();
            return e;
        }

        private Site site(Kind kind, Tree tree) {
            int start = start(tree), end = end(tree);
            if (kind == Kind.DECLARE && end > start && unit.getSourceFile() != null) {
                // javac's declaration range includes the terminating semicolon; highlight without it.
                try {
                    CharSequence text = unit.getSourceFile().getCharContent(true);
                    if (text.charAt(end - 1) == ';') end--;
                } catch (IOException ignored) { }
            }
            Site site = new Site(sites.size() + 1, kind, start, end, reversedScopes());
            sites.put(site.id, site);
            return site;
        }

        private List<String> reversedScopes() {
            List<String> chain = new ArrayList<>(scopes);
            Collections.reverse(chain);
            return chain;
        }

        private int start(Tree t) { return (int) positions.getStartPosition(unit, t); }
        private int end(Tree t) { return (int) positions.getEndPosition(unit, t); }
        private Element element(Tree t) { return trees.getElement(TreePath.getPath(unit, t)); }

        private String javaType(Tree t) {
            var type = trees.getTypeMirror(TreePath.getPath(unit, t));
            return type == null ? "?" : type.toString();
        }

        private void unsupported(Tree t, String what) {
            if (current != null) { // inside main: only this statement runs without capture
                current.add(new Diagnostic("capture", "UNSUPPORTED_CONSTRUCT",
                        "Visualization does not yet cover " + what + "; this statement runs without capture.", start(t), end(t)));
                return;
            }
            unsupported.add(new Diagnostic("capture", "UNSUPPORTED_CONSTRUCT",
                    "Visualization does not yet cover " + what + "; the program can still run with output only.",
                    start(t), end(t)));
        }
    }
}
