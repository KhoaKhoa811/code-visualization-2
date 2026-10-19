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
                    Map<String, byte[]> classes, Map<String, List<Item>> plans, Map<Integer, Site> sites, String rootScope) {
        boolean traceable() { return compileErrors.isEmpty() && unsupported.isEmpty(); }
    }

    static final String FILE = "Main.java";

    /** JVM stack type: byte, short, char and boolean values are ints. */
    static String stack(String t) { return switch (t) { case "byte", "short", "char", "boolean" -> "int"; default -> t; }; }

    /** Element type of an array type. */
    static String component(String arrayType) { return arrayType.substring(0, arrayType.length() - 2); }

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
            return new Analysis(source, errors, unsupported, List.of(), Map.of(), Map.of(), Map.of(), null);
        return new Analysis(source, errors, unsupported, List.copyOf(walker.uncaptured), Map.copyOf(classes), Map.copyOf(walker.plans),
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
        // One plan per instrumented method, keyed by name + descriptor ("main([Ljava/lang/String;)V"); Part 8d.
        final Map<String, List<Item>> plans = new LinkedHashMap<>();
        private List<Item> plan = new ArrayList<>();
        private final Map<Element, Site> helpers = new HashMap<>(); // traced static methods of Main -> METHOD site
        private Site currentMethod; // null while planning main
        // Part 8e: traced classes (javaType -> CLASS site, null while being decided), their trees, field sites.
        private final Map<String, Site> classes = new LinkedHashMap<>();
        private final Map<String, ClassTree> classTrees = new HashMap<>();
        private final Map<Element, Site> fieldSites = new HashMap<>();
        final Map<Integer, Site> sites = new LinkedHashMap<>();
        private final Deque<String> scopes = new ArrayDeque<>();
        private final Map<Element, Site> declarations = new HashMap<>();
        private final List<Element> declared = new ArrayList<>(); // insertion order, for rollback
        // Per block: declarations without initializer not yet attached to a following operation (Part 8b).
        private final Deque<List<Site>> pending = new ArrayDeque<>();
        private final Map<Site, Integer> pendingMarks = new HashMap<>(); // plan position of each waiting declaration
        // Findings for the statement being planned; null outside main's statements (they block tracing).
        private List<Diagnostic> current;
        // Part 8c: result types of the enclosing traced switch expressions, and whether the value
        // being planned is a whole initializer or assigned value (javac's operand stack is empty).
        private final Deque<String> yieldTypes = new ArrayDeque<>();
        private boolean topValue;
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
            ClassTree mainClass = null;
            for (Tree type : unit.getTypeDecls()) {
                if (type instanceof ClassTree c && c.getSimpleName().contentEquals("Main")) mainClass = c;
            }
            if (mainClass == null) { unsupported(unit, "a program without class Main"); return; }
            if (mainClass.getKind() != Tree.Kind.CLASS || mainClass.getExtendsClause() != null
                    || !mainClass.getImplementsClause().isEmpty() || !mainClass.getTypeParameters().isEmpty()) {
                unsupported(mainClass, "a Main that is not a plain class");
                return;
            }
            MethodTree main = null;
            for (Tree member : mainClass.getMembers()) if (main == null && member instanceof MethodTree m && isMain(m)) main = m;
            if (main == null) { unsupported(mainClass, "a class without public static void main(String[])"); return; }
            // Part 8e: traced classes first (their names are traced types), then every traced method
            // (calls may come before declarations), then the bodies. Everything else runs untraced.
            List<ClassTree> traced = tracedClasses(mainClass);
            List<MethodTree> methods = new ArrayList<>();
            for (Tree member : mainClass.getMembers()) {
                if (member instanceof MethodTree m && m != main && helper(m, mainClass, false)) methods.add(m);
            }
            for (ClassTree c : traced) {
                for (Tree member : c.getMembers()) if (member instanceof MethodTree m && helper(m, c, true)) methods.add(m);
            }
            method(main, null);
            for (MethodTree m : methods) method(m, helpers.get(element(m)));
        }

        /**
         * Traced classes (Part 8e): plain top-level classes and static member classes of Main without
         * supertypes, subclasses or type parameters, whose instance fields all have traced types.
         */
        private List<ClassTree> tracedClasses(ClassTree mainClass) {
            List<ClassTree> candidates = new ArrayList<>();
            for (Tree type : unit.getTypeDecls()) if (type instanceof ClassTree c && c != mainClass) candidates.add(c);
            for (Tree member : mainClass.getMembers()) {
                if (member instanceof ClassTree c && c.getModifiers().getFlags().contains(Modifier.STATIC)) candidates.add(c);
            }
            Set<Element> extended = new HashSet<>();
            new TreeScanner<Void, Void>() {
                @Override public Void visitClass(ClassTree c, Void p) {
                    if (c.getExtendsClause() != null) extended.add(element(c.getExtendsClause()));
                    return super.visitClass(c, p);
                }
            }.scan(unit, null);
            candidates.removeIf(c -> c.getKind() != Tree.Kind.CLASS || c.getExtendsClause() != null || !c.getImplementsClause().isEmpty()
                    || !c.getTypeParameters().isEmpty() || c.getModifiers().getFlags().contains(Modifier.ABSTRACT)
                    || extended.contains(element(c)));
            for (ClassTree c : candidates) classes.put(javaType(c), null); // names first: fields may refer to each other
            boolean changed = true;
            while (changed) { // a class with a field of an untraced type is untraced, which may untrace others
                changed = false;
                for (Iterator<ClassTree> it = candidates.iterator(); it.hasNext(); ) {
                    ClassTree c = it.next();
                    boolean ok = true;
                    for (Tree member : c.getMembers()) {
                        if (member instanceof VariableTree v && !v.getModifiers().getFlags().contains(Modifier.STATIC) && !localType(javaType(v))) ok = false;
                    }
                    if (!ok) { classes.remove(javaType(c)); it.remove(); changed = true; }
                }
            }
            for (ClassTree c : candidates) {
                Site site = site(Kind.CLASS, c);
                site.javaType = javaType(c);
                List<Site> fields = new ArrayList<>();
                for (Tree member : c.getMembers()) {
                    if (!(member instanceof VariableTree v) || v.getModifiers().getFlags().contains(Modifier.STATIC)) continue;
                    Site field = site(Kind.FIELD, v);
                    field.name = v.getName().toString();
                    field.javaType = javaType(v);
                    field.owner = site.javaType;
                    fieldSites.put(element(v), field);
                    fields.add(field);
                }
                site.fields = List.copyOf(fields);
                classes.put(site.javaType, site);
                classTrees.put(site.javaType, c);
            }
            return candidates;
        }

        /**
         * A traced method (Parts 8d, 8e): in Main a static method; in a traced class any non-generic,
         * fixed-arity method or constructor. Parameter and result types must be traced. A constructor
         * must not start with this(...), and its class must have only simple field initializers.
         * Records its METHOD and PARAM sites (instance methods and constructors bind this first).
         */
        private boolean helper(MethodTree m, ClassTree owner, boolean tracedClass) {
            if (!(element(m) instanceof ExecutableElement e) || m.getBody() == null) return false;
            Set<Modifier> modifiers = e.getModifiers();
            boolean constructor = e.getKind() == ElementKind.CONSTRUCTOR;
            boolean instance = !modifiers.contains(Modifier.STATIC);
            if (!tracedClass && instance || modifiers.contains(Modifier.NATIVE) || modifiers.contains(Modifier.ABSTRACT)
                    || e.isVarArgs() || !e.getTypeParameters().isEmpty() || e.getKind() == ElementKind.STATIC_INIT) return false;
            if (constructor && !simpleConstructor(m, owner)) return false;
            String result = e.getReturnType().toString();
            if (!result.equals("void") && !localType(result)) return false;
            for (VariableElement p : e.getParameters()) if (!localType(p.asType().toString())) return false;
            String ownerType = javaType(owner);
            int bodyStart = start(m.getBody());
            String root = bodyStart >= 0 ? "b" + bodyStart : "c" + start(owner); // a default constructor has no body text
            scopes.push(root);
            Site site = site(Kind.METHOD, m);
            site.javaType = result;
            site.name = e.getSimpleName().toString();
            site.owner = ownerType;
            site.instance = instance;
            site.constructor = constructor;
            site.methodId = ownerType + "." + site.name + "(" + String.join(",", e.getParameters().stream().map(p -> p.asType().toString()).toList())
                    + "):" + result;
            List<Site> params = new ArrayList<>();
            if (instance) {
                Site self = site(Kind.PARAM, m);
                self.declarationId = ownerType + "." + site.name + ":this@" + start(m);
                self.name = "this";
                self.javaType = ownerType;
                params.add(self);
            }
            for (VariableTree p : m.getParameters()) {
                Site param = site(Kind.PARAM, p);
                param.declarationId = ownerType + "." + site.name + ":" + p.getName() + "@" + start(p);
                param.name = p.getName().toString();
                param.javaType = javaType(p);
                declarations.put(element(p), param);
                params.add(param);
            }
            site.params = List.copyOf(params);
            scopes.pop();
            helpers.put(e, site);
            return true;
        }

        /** No this(...) call, no instance initializer blocks, and only constant, null or constant-array field initializers. */
        private boolean simpleConstructor(MethodTree m, ClassTree owner) {
            List<? extends StatementTree> body = m.getBody().getStatements();
            if (!body.isEmpty() && body.get(0) instanceof ExpressionStatementTree s && s.getExpression() instanceof MethodInvocationTree call
                    && call.getMethodSelect() instanceof IdentifierTree id && id.getName().contentEquals("this")) return false;
            for (Tree member : owner.getMembers()) {
                if (member instanceof BlockTree b && !b.isStatic()) return false;
                if (!(member instanceof VariableTree v) || v.getModifiers().getFlags().contains(Modifier.STATIC) || v.getInitializer() == null) continue;
                if (!simpleInitializer(v.getInitializer())) return false;
            }
            return true;
        }

        private boolean simpleInitializer(ExpressionTree e) {
            e = strip(e);
            if (isConstant(e) || e.getKind() == Tree.Kind.NULL_LITERAL) return true;
            if (!(e instanceof NewArrayTree n) || !array(javaType(n))) return false;
            if (n.getInitializers() != null) return n.getInitializers().stream().allMatch(this::simpleInitializer);
            return n.getDimensions().size() <= 3 && n.getDimensions().stream().allMatch(this::isConstant);
        }

        /** Plan key: declaring class (JVM internal name), method name and JVM descriptor (this excluded). */
        private static String key(Site method) {
            return Instrumenter.key(method);
        }

        private boolean isMain(MethodTree m) {
            if (!(element(m) instanceof ExecutableElement e)) return false;
            return e.getSimpleName().contentEquals("main") && e.getModifiers().contains(Modifier.STATIC)
                    && e.getModifiers().contains(Modifier.PUBLIC) && e.getReturnType().toString().equals("void")
                    && e.getParameters().size() == 1 && e.getParameters().get(0).asType().toString().equals("java.lang.String[]");
        }

        /** Plans one method body; {@code site} is null for main. */
        private void method(MethodTree m, Site site) {
            currentMethod = site;
            plan = new ArrayList<>();
            if (site == null) {
                rootScope = "b" + start(m.getBody());
                plans.put(Instrumenter.MAIN, plan);
            } else {
                plans.put(key(site), plan);
            }
            String root = site == null ? rootScope : site.scopes.get(0);
            scopes.push(root);
            pending.push(new ArrayList<>());
            List<? extends StatementTree> body = m.getBody().getStatements();
            if (site != null && site.constructor) {
                // javac: aload_0; invokespecial Object.<init>; the field initializers; the rest of the body.
                plan.add(new Item(Expect.SUPER, site, 0, "java/lang/Object.<init>()V"));
                body = body.subList(1, body.size());
                for (Tree member : classTrees.get(site.owner).getMembers()) {
                    if (!(member instanceof VariableTree v) || v.getModifiers().getFlags().contains(Modifier.STATIC) || v.getInitializer() == null) continue;
                    Site field = fieldSites.get(element(v));
                    value(v.getInitializer(), field.javaType);
                    plan.add(Item.of(Expect.FIELD_PUT, fieldSite(Kind.FIELD_WRITE, v, field)));
                }
            }
            for (StatementTree s : body) statement(s);
            pending.pop();
            if (site != null && site.javaType.equals("void")) {
                // javac's implicit return at the closing brace (the class name for a default constructor).
                int end = end(m.getBody()), start = end - 1;
                if (end < 0) { // the class name
                    ClassTree owner = classTrees.get(site.owner);
                    String name = owner.getSimpleName().toString();
                    start = start(owner);
                    while (!sourceAt(start, name) && start < end(owner)) start++;
                    end = start + name.length();
                }
                Site implicit = new Site(sites.size() + 1, Kind.RETURN, start, end, reversedScopes());
                sites.put(implicit.id, implicit);
                implicit.javaType = "void";
                implicit.implicit = true;
                site.returnSite = implicit;
            }
            scopes.pop();
            currentMethod = null;
        }

        private Site fieldSite(Kind kind, Tree tree, Site field) {
            Site site = site(kind, tree);
            site.name = field.name;
            site.javaType = field.javaType;
            site.owner = field.owner;
            return site;
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
                pending.push(new ArrayList<>());
                for (StatementTree inner : ((BlockTree) s).getStatements()) statement(inner);
                pending.pop(); // declarations never followed by an operation are never observable
                scopes.pop();
                return;
            }
            int planMark = plan.size(), siteMark = sites.size(), uncapturedMark = uncaptured.size(), declaredMark = declared.size();
            List<Diagnostic> outer = current;
            current = new ArrayList<>();
            plain(s);
            List<Diagnostic> found = current;
            current = outer;
            if (!found.isEmpty()) {
                plan.subList(planMark, plan.size()).clear();
                sites.keySet().removeIf(id -> id > siteMark);
                uncaptured.subList(uncapturedMark, uncaptured.size()).clear();
                while (declared.size() > declaredMark) declarations.remove(declared.remove(declared.size() - 1));
                for (List<Site> waiting : pending) waiting.removeIf(d -> d.id > siteMark);
                region(s, found);
            }
            attachPending();
        }

        /**
         * Waiting declarations run just before the first recorded operation planned after them
         * (javac code layout). Nested statements are planned before their statement ends, so each
         * declaration is matched from its own plan position, not from the statement's (Part 8c:
         * {@code int k; switch (...) { case 0: k = 0; ... }} must declare k before the switch step).
         */
        private void attachPending() {
            Map<Site, List<Site>> targets = new LinkedHashMap<>();
            for (Iterator<List<Site>> outerFirst = pending.descendingIterator(); outerFirst.hasNext(); ) {
                for (Iterator<Site> it = outerFirst.next().iterator(); it.hasNext(); ) {
                    Site declaration = it.next();
                    Site first = null;
                    for (int i = pendingMarks.get(declaration); i < plan.size() && first == null; i++) first = plan.get(i).site();
                    if (first == null) continue;
                    targets.computeIfAbsent(first, s -> new ArrayList<>()).add(declaration);
                    it.remove();
                }
            }
            targets.forEach((first, waiting) -> first.declareBefore.addAll(0, waiting));
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
            // javac positions a lowered for-each (and so its range table entry) at the iterated expression.
            Tree ranged = s instanceof EnhancedForLoopTree f ? f.getExpression() : s;
            site.crtStart = crt(start(ranged));
            site.crtEnd = crt(end(ranged));
            if (currentMethod != null) { // a return inside it is recorded at this statement (Part 8d)
                site.returnSite = site(Kind.RETURN, s);
                site.returnSite.javaType = currentMethod.javaType;
            }
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
                if (fieldSites.containsKey(element(t)) || t instanceof MemberSelectTree) {
                    writes = true; // a field of a traced object (Part 8e), or another class's field
                } else if (t instanceof IdentifierTree) {
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
                case DO_WHILE_LOOP -> { // javac: body, then the test jumping back (none for a constant)
                    DoWhileLoopTree d = (DoWhileLoopTree) s;
                    statement(d.getStatement());
                    if (!isConstant(d.getCondition())) condition(d.getCondition());
                }
                case ENHANCED_FOR_LOOP -> forEach((EnhancedForLoopTree) s);
                case SWITCH -> {
                    SwitchTree w = (SwitchTree) s;
                    switchBlock(w, w.getExpression(), w.getCases(), null);
                }
                case YIELD -> {
                    if (yieldTypes.isEmpty()) { unsupported(s, "this yield"); return; }
                    value(((YieldTree) s).getValue(), yieldTypes.peek());
                }
                case LABELED_STATEMENT -> plain(((LabeledStatementTree) s).getStatement()); // labels emit no code
                case BREAK, CONTINUE, EMPTY_STATEMENT -> { } // jumps only, labeled or not
                case RETURN -> {
                    ExpressionTree result = ((ReturnTree) s).getExpression();
                    if (currentMethod == null) { if (result != null) unsupported(s, "return values"); return; } // main: only "return;"
                    if (result != null) value(result, currentMethod.javaType);
                    Site site = site(Kind.RETURN, s);
                    site.javaType = currentMethod.javaType;
                    plan.add(Item.of(Expect.RETURN, site));
                }
                default -> unsupported(s, "this statement kind (" + s.getKind() + ")");
            }
        }

        // ---- statements added in Part 8c --------------------------------------------

        /**
         * javac Lower.visitArrayForeachLoop: {@code T[] c = e; int n = c.length; for (int i = 0; i < n; i++) { V v = c[i]; body }}.
         * The hidden copy, length and index are matched but never shown; the length read and each
         * element read are steps, then the loop variable's declaration.
         */
        private void forEach(EnhancedForLoopTree f) {
            String arrayType = javaType(f.getExpression());
            VariableTree v = f.getVariable();
            String type = javaType(v);
            if (!array(arrayType)) { unsupported(f.getExpression(), "for-each over " + arrayType + " (only arrays are traced)"); return; }
            if (!localType(type)) { unsupported(v, "local variables of type " + type); return; }
            scopes.push("f" + start(f));
            value(f.getExpression(), null);
            plan.add(new Item(Expect.STORE, null, 0, "copy"));
            Site length = site(Kind.ARRAY_LENGTH, f.getExpression());
            length.javaType = arrayType;
            plan.add(Item.of(Expect.ARRAYLENGTH, length));
            plan.add(new Item(Expect.STORE, null, 0, "length"));
            plan.add(new Item(Expect.STORE, null, 0, "index"));
            plan.add(new Item(Expect.JUMP, null, 0, "index < length"));
            Site read = site(Kind.ARRAY_READ, f.getExpression());
            read.javaType = arrayType;
            plan.add(Item.of(Expect.ALOAD, read));
            coerce(component(arrayType), type);
            Site declare = declareSite(v, type);
            plan.add(Item.of(Expect.STORE, declare));
            statement(f.getStatement());
            plan.add(new Item(Expect.IINC, null, 0, "index++"));
            scopes.pop();
        }

        private static final Set<String> SELECTORS = Set.of("int", "char", "short", "byte");

        /**
         * A switch statement ({@code yieldType} null) or a switch expression yielding {@code yieldType}:
         * the selector, one tableswitch/lookupswitch, then the case bodies in source order.
         */
        private void switchBlock(Tree w, ExpressionTree selector, List<? extends CaseTree> cases, String yieldType) {
            String type = javaType(selector);
            if (!SELECTORS.contains(type)) { unsupported(selector, "switch on " + type); return; }
            if (cases.isEmpty()) { unsupported(w, "an empty switch"); return; }
            List<Plan.Case> facts = new ArrayList<>();
            for (CaseTree c : cases) {
                Plan.Case fact = caseFact(c);
                if (fact == null) return;
                facts.add(fact);
            }
            if (sharesGroupVariables(w, cases)) return;
            value(selector, null);
            Site site = site(Kind.SWITCH, strip(selector)); // a switch statement's selector includes its parentheses
            site.javaType = type;
            site.cases = List.copyOf(facts);
            plan.add(Item.of(Expect.SWITCH, site));
            scopes.push("w" + start(w));
            pending.push(new ArrayList<>());
            if (yieldType != null) yieldTypes.push(yieldType);
            for (CaseTree c : cases) {
                if (c.getCaseKind() == CaseTree.CaseKind.STATEMENT) {
                    for (StatementTree inner : c.getStatements()) statement(inner);
                } else if (c.getBody() instanceof ExpressionTree e) {
                    // A rule body expression has no statement of its own: it is planned with the switch.
                    if (yieldType != null) value(e, yieldType);
                    else expressionStatement(e);
                } else {
                    statement((StatementTree) c.getBody());
                }
            }
            if (yieldType != null) yieldTypes.pop();
            pending.pop();
            scopes.pop();
        }

        /** Folded case constants and the label's range; null (reported) for pattern or null labels. */
        private Plan.Case caseFact(CaseTree c) {
            if (c.getGuard() != null) { unsupported(c, "guarded switch cases"); return null; }
            List<Integer> values = new ArrayList<>();
            boolean isDefault = false;
            int end = start(c);
            for (CaseLabelTree label : c.getLabels()) {
                if (label instanceof DefaultCaseLabelTree) {
                    isDefault = true;
                    end = Math.max(end, start(c) + (sourceAt(start(c), "default") ? "default".length() : "case".length()));
                } else if (label instanceof ConstantCaseLabelTree constant && fold(constant.getConstantExpression()) instanceof Object v
                        && !(v instanceof String) && !(v instanceof Boolean)) {
                    values.add(v instanceof Character ch ? (int) ch : ((Number) v).intValue());
                    end = Math.max(end, end(constant.getConstantExpression()));
                } else {
                    unsupported(label, "this switch case label");
                    return null;
                }
            }
            return new Plan.Case(List.copyOf(values), isDefault, start(c), end);
        }

        /**
         * A local declared in one case group is in scope in later groups, but its declaration
         * may never have run there; such switches are not traced.
         */
        private boolean sharesGroupVariables(Tree w, List<? extends CaseTree> cases) {
            Set<Element> earlier = new HashSet<>();
            boolean[] found = {false};
            for (CaseTree c : cases) {
                if (c.getCaseKind() != CaseTree.CaseKind.STATEMENT) continue;
                if (!earlier.isEmpty()) {
                    new TreeScanner<Void, Void>() {
                        @Override public Void visitIdentifier(IdentifierTree i, Void p) {
                            if (earlier.contains(element(i))) found[0] = true;
                            return null;
                        }
                    }.scan(c.getStatements(), null);
                }
                for (StatementTree s : c.getStatements()) if (s instanceof VariableTree v) earlier.add(element(v));
            }
            if (found[0]) unsupported(w, "a variable declared in one switch case group and used in a later one");
            return found[0];
        }

        private boolean sourceAt(int position, String text) {
            try {
                CharSequence source = unit.getSourceFile().getCharContent(true);
                return position + text.length() <= source.length() && source.subSequence(position, position + text.length()).toString().equals(text);
            } catch (IOException e) {
                return false;
            }
        }

        private void loopCondition(ExpressionTree c) {
            if (isConstant(c)) {
                if (!Boolean.TRUE.equals(constantValue(c))) unsupported(c, "constant false loop conditions");
                return; // javac emits no test for a constant true condition.
            }
            condition(c);
        }

        // ---- primitive types (Part 8b) ------------------------------------------------

        private static final Set<String> SCALARS = Set.of("byte", "short", "char", "int", "long", "float", "double", "boolean");

        /** Traced local types: primitives, String, and arrays of those at any depth (Part 8c). */
        private boolean localType(String t) { return SCALARS.contains(t) || t.equals("java.lang.String") || classes.containsKey(t) || array(t); }

        private boolean array(String t) {
            if (!t.endsWith("[]")) return false;
            String base = t.substring(0, t.indexOf('['));
            return SCALARS.contains(base) || base.equals("java.lang.String") || classes.containsKey(base);
        }

        private static boolean numeric(String t) { return SCALARS.contains(t) && !t.equals("boolean"); }

        /** Unary numeric promotion. */
        private static String promote(String t) { return switch (t) { case "byte", "short", "char" -> "int"; default -> t; }; }

        /** Binary numeric promotion. */
        private static String promote(String a, String b) {
            if (a.equals("double") || b.equals("double")) return "double";
            if (a.equals("float") || b.equals("float")) return "float";
            if (a.equals("long") || b.equals("long")) return "long";
            return "int";
        }


        /** Instructions javac's Items.coerce emits between two primitive types (at most two). */
        static List<String> conversions(String from, String to) {
            if (from.equals(to) || !SCALARS.contains(from) || !SCALARS.contains(to)) return List.of();
            List<String> out = new ArrayList<>();
            String f = stack(from), t = stack(to);
            if (!f.equals(t)) out.add(letter(f) + "2" + letter(t));
            if (!t.equals(to)) out.add("I2" + letter(to));
            return out;
        }

        private static String letter(String t) {
            return switch (t) {
                case "int" -> "I"; case "long" -> "L"; case "float" -> "F"; case "double" -> "D";
                case "byte" -> "B"; case "char" -> "C"; case "short" -> "S";
                default -> throw new IllegalArgumentException(t);
            };
        }

        /** An implicit conversion: matched against the bytecode, never a step. */
        private void coerce(String from, String to) {
            for (String c : conversions(from, to)) plan.add(new Item(Expect.CONVERT, null, 0, c));
        }

        private Site arithmetic(Tree tree, String operator, String type) {
            Site site = site(Kind.ARITHMETIC, tree);
            site.operator = operator; site.javaType = type;
            return site;
        }

        private void declaration(VariableTree v) {
            String type = javaType(v);
            if (!localType(type)) {
                unsupported(v, "local variables of type " + type);
                return;
            }
            Site site = declareSite(v, type);
            // No code runs for a declaration without initializer: it is recorded just before
            // the next operation that runs after it in the same block.
            if (v.getInitializer() == null) { pending.peek().add(site); pendingMarks.put(site, plan.size()); return; }
            topValue = true;
            value(v.getInitializer(), type);
            plan.add(Item.of(Expect.STORE, site));
        }

        private Site declareSite(VariableTree v, String type) {
            Site site = site(Kind.DECLARE, v);
            site.declarationId = "Main.main:" + v.getName() + "@" + start(v);
            site.name = v.getName().toString();
            site.javaType = type;
            declarations.put(element(v), site);
            declared.add(element(v));
            return site;
        }

        private void expressionStatement(ExpressionTree e) {
            switch (e.getKind()) {
                case ASSIGNMENT -> {
                    AssignmentTree a = (AssignmentTree) e;
                    ExpressionTree target = strip(a.getVariable());
                    Site field = field(target);
                    if (field != null) { // Part 8e: p.f = e, f = e, this.f = e
                        receiver(target);
                        value(a.getExpression(), field.javaType);
                        plan.add(Item.of(Expect.FIELD_PUT, fieldSite(Kind.FIELD_WRITE, a, field)));
                    } else if (target instanceof IdentifierTree id) {
                        Site declared = declarations.get(element(id));
                        if (declared == null) { unsupported(target, "assignment to this variable"); return; }
                        Site site = assignment(a, declared);
                        topValue = true;
                        value(a.getExpression(), declared.javaType);
                        plan.add(Item.of(Expect.STORE, site));
                    } else if (target instanceof ArrayAccessTree access && array(javaType(access.getExpression()))) {
                        String arrayType = javaType(access.getExpression());
                        Site site = arraySite(Kind.ARRAY_WRITE, a, arrayType);
                        value(access.getExpression(), null);
                        value(access.getIndex(), "int");
                        value(a.getExpression(), component(arrayType));
                        plan.add(Item.of(Expect.ASTORE, site));
                    } else {
                        unsupported(target, "assignment to this target");
                    }
                }
                case PLUS_ASSIGNMENT, MINUS_ASSIGNMENT, MULTIPLY_ASSIGNMENT, DIVIDE_ASSIGNMENT, REMAINDER_ASSIGNMENT,
                     AND_ASSIGNMENT, OR_ASSIGNMENT, XOR_ASSIGNMENT, LEFT_SHIFT_ASSIGNMENT, RIGHT_SHIFT_ASSIGNMENT,
                     UNSIGNED_RIGHT_SHIFT_ASSIGNMENT -> compound((CompoundAssignmentTree) e);
                case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> update((UnaryTree) e);
                case METHOD_INVOCATION -> {
                    MethodInvocationTree call = (MethodInvocationTree) e;
                    if (helpers.containsKey(element(call))) call(call); // an ignored result is popped: no binding
                    else output(call);
                }
                case NEW_CLASS -> value(e, null); // an ignored new object is popped
                default -> unsupported(e, "this expression statement (" + e.getKind() + ")");
            }
        }

        private Site assignment(Tree tree, Site declared) {
            Site site = site(Kind.ASSIGN, tree);
            site.declarationId = declared.declarationId; site.name = declared.name; site.javaType = declared.javaType;
            return site;
        }

        /**
         * {@code x op= e} in javac's layout: an int local with a small constant +/- becomes
         * one {@code iinc}; otherwise load, operation (a step), narrowing, store (a step).
         * For a numeric or boolean array element: read (a step), operation (a step), write (a step).
         */
        private void compound(CompoundAssignmentTree c) {
            String op = switch (c.getKind()) {
                case PLUS_ASSIGNMENT -> "+"; case MINUS_ASSIGNMENT -> "-"; case MULTIPLY_ASSIGNMENT -> "*";
                case DIVIDE_ASSIGNMENT -> "/"; case REMAINDER_ASSIGNMENT -> "%"; case AND_ASSIGNMENT -> "&";
                case OR_ASSIGNMENT -> "|"; case XOR_ASSIGNMENT -> "^"; case LEFT_SHIFT_ASSIGNMENT -> "<<";
                case RIGHT_SHIFT_ASSIGNMENT -> ">>"; default -> ">>>";
            };
            ExpressionTree target = strip(c.getVariable());
            ExpressionTree rhs = c.getExpression();
            String rhsType = javaType(rhs);
            String type, arrayType = null;
            Site declared = null, fieldTarget = null;
            if (target instanceof IdentifierTree id && (declared = declarations.get(element(id))) != null && SCALARS.contains(declared.javaType)) {
                type = declared.javaType;
                Object constant = fold(rhs);
                if ((op.equals("+") || op.equals("-")) && stack(type).equals("int") && !type.equals("boolean")
                        && List.of("int", "short", "byte", "char").contains(rhsType) && constant != null) {
                    int value = constant instanceof Character ch ? ch : ((Number) constant).intValue();
                    int delta = op.equals("+") ? value : -value; // javac LocalItem.incr
                    Site assign = assignment(c, declared);
                    if (type.equals("int") && delta >= Short.MIN_VALUE && delta <= Short.MAX_VALUE) {
                        assign.operator = op; assign.operand = Integer.toString(value); assign.delta = delta;
                        plan.add(Item.of(Expect.IINC, assign));
                        return;
                    }
                    plan.add(Item.of(Expect.ARITH, arithmetic(c, delta >= 0 ? "+" : "-", "int")));
                    coerce("int", type);
                    plan.add(Item.of(Expect.STORE, assign));
                    return;
                }
            } else if (field(target) != null && SCALARS.contains(field(target).javaType)) { // Part 8e: dup, getfield
                fieldTarget = field(target);
                type = fieldTarget.javaType;
                receiver(target);
                plan.add(Item.of(Expect.FIELD_GET, fieldSite(Kind.FIELD_READ, target, fieldTarget)));
            } else if (target instanceof ArrayAccessTree access && array(javaType(access.getExpression()))
                    && SCALARS.contains(component(javaType(access.getExpression())))) {
                arrayType = javaType(access.getExpression());
                type = component(arrayType);
                value(access.getExpression(), null);
                value(access.getIndex(), "int");
                plan.add(Item.of(Expect.ALOAD, arraySite(Kind.ARRAY_READ, access, arrayType))); // after dup2: the element is read
            } else {
                unsupported(c, "this compound assignment");
                return;
            }
            if (rhsType.equals("java.lang.String") || !SCALARS.contains(rhsType)) { unsupported(c, "this compound assignment"); return; }
            boolean shift = op.startsWith("<") || op.startsWith(">");
            String opType = type.equals("boolean") ? "boolean" : shift ? promote(type) : promote(type, rhsType);
            coerce(type, opType); // the loaded variable or element
            value(rhs, shift ? "int" : opType); // javac ishll/lshll: a shift distance is coerced to int
            Site arith = arithmetic(c, op, opType);
            plan.add(Item.of(Expect.ARITH, arith));
            coerce(opType, type);
            if (declared != null) plan.add(Item.of(Expect.STORE, assignment(c, declared)));
            else if (fieldTarget != null) plan.add(Item.of(Expect.FIELD_PUT, fieldSite(Kind.FIELD_WRITE, c, fieldTarget)));
            else plan.add(Item.of(Expect.ASTORE, arraySite(Kind.ARRAY_WRITE, c, arrayType)));
        }

        private Site arraySite(Kind kind, Tree tree, String arrayType) {
            Site site = site(kind, tree);
            site.javaType = arrayType;
            return site;
        }

        /** A call to a traced helper: the arguments in order, then one invokestatic (Part 8d). */
        private void call(MethodInvocationTree call) {
            Site callee = helpers.get(element(call));
            if (callee == null) { unsupported(call, "calls to this method (only methods of Main and of traced classes with traced types are traced)"); return; }
            // An instance call evaluates its receiver first (an implicit this is a plain aload_0).
            if (callee.instance && call.getMethodSelect() instanceof MemberSelectTree select) value(select.getExpression(), null);
            List<? extends ExpressionTree> arguments = call.getArguments();
            int first = callee.instance ? 1 : 0;
            for (int i = 0; i < arguments.size(); i++) value(arguments.get(i), callee.params.get(first + i).javaType);
            Site site = site(Kind.CALL, call);
            site.callee = callee;
            site.javaType = callee.javaType;
            plan.add(new Item(Expect.CALL, site, 0, key(callee)));
        }

        /**
         * {@code new C(args)} of a traced class (Part 8e): new, dup, the arguments, invokespecial. A traced
         * constructor is a CALL whose entry also records the allocation; another runs silently and the
         * object is captured on first use.
         */
        private void newObject(NewClassTree n) {
            String type = javaType(n);
            if (!classes.containsKey(type) || n.getClassBody() != null || n.getEnclosingExpression() != null
                    || !(element(n) instanceof ExecutableElement constructor)) { unsupported(n, "creating objects of " + type); return; }
            plan.add(new Item(Expect.NEW, null, 0, Instrumenter.internal(type)));
            List<? extends ExpressionTree> arguments = n.getArguments();
            for (int i = 0; i < arguments.size(); i++) value(arguments.get(i), constructor.getParameters().get(i).asType().toString());
            Site callee = helpers.get(constructor);
            if (callee == null) {
                StringBuilder desc = new StringBuilder("(");
                for (VariableElement p : constructor.getParameters()) desc.append(Instrumenter.descriptorOf(p.asType().toString()));
                plan.add(new Item(Expect.CTOR, null, 0, Instrumenter.internal(type) + "#<init>" + desc + ")V"));
                return;
            }
            Site site = site(Kind.CALL, n);
            site.callee = callee;
            site.javaType = type;
            plan.add(new Item(Expect.CALL, site, 0, key(callee)));
        }

        /** The traced field a name or member select denotes, or null (locals, static fields, other classes). */
        private Site field(ExpressionTree e) {
            e = strip(e);
            if (!(e instanceof IdentifierTree) && !(e instanceof MemberSelectTree)) return null;
            return fieldSites.get(element(e));
        }

        /** Plans the receiver of a field access: an explicit expression, or nothing for an implicit this. */
        private void receiver(ExpressionTree e) {
            if (strip(e) instanceof MemberSelectTree m) value(m.getExpression(), null);
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
            if (!(parameters.isEmpty() || parameters.size() == 1 && (SCALARS.contains(parameters.get(0))
                    || parameters.get(0).equals("java.lang.String")))) {
                unsupported(call, "print/println(" + String.join(",", parameters) + ")");
                return;
            }
            plan.add(new Item(Expect.GETSTATIC, null, 0, stream.getSimpleName().toString()));
            for (ExpressionTree argument : call.getArguments()) value(argument, parameters.get(0));
            plan.add(new Item(Expect.INVOKE, null, 0,
                    method.getSimpleName() + (parameters.isEmpty() ? "()V" : "(" + descriptor(parameters.get(0)) + ")V")));
        }

        private static String descriptor(String type) {
            return switch (type) {
                case "int" -> "I"; case "boolean" -> "Z"; case "long" -> "J"; case "double" -> "D"; case "float" -> "F";
                case "char" -> "C"; case "byte" -> "B"; case "short" -> "S"; default -> "Ljava/lang/String;";
            };
        }

        // ---- expressions in value context --------------------------------------

        /**
         * Plans the operations of {@code e}, then javac's implicit conversion to {@code target}
         * (null: none). Constant expressions are folded by javac, already in the target type.
         */
        private void value(ExpressionTree e, String target) {
            boolean top = topValue;
            topValue = false;
            e = strip(e);
            if (isConstant(e)) return; // javac folds compile-time constants: nothing executes.
            String type = javaType(e);
            switch (e.getKind()) {
                case NULL_LITERAL -> { }
                case IDENTIFIER -> {
                    Site field = field(e);
                    if (field != null) plan.add(Item.of(Expect.FIELD_GET, fieldSite(Kind.FIELD_READ, e, field))); // this.f
                    else if (((IdentifierTree) e).getName().contentEquals("this")) {
                        if (currentMethod == null || !currentMethod.instance) unsupported(e, "this here");
                    } else if (!declarations.containsKey(element(e))) unsupported(e, "this variable (only locals and parameters of traced methods are traced)");
                }
                case NEW_CLASS -> newObject((NewClassTree) e);
                case PLUS, MINUS, MULTIPLY, DIVIDE, REMAINDER, AND, OR, XOR, LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT ->
                        binary((BinaryTree) e, type);
                case UNARY_MINUS, UNARY_PLUS, BITWISE_COMPLEMENT -> {
                    UnaryTree u = (UnaryTree) e;
                    if (!numeric(type)) { unsupported(u, "this operator on " + type); return; }
                    value(u.getExpression(), type);
                    if (e.getKind() == Tree.Kind.UNARY_MINUS) plan.add(Item.of(Expect.NEG, arithmetic(u, "-", type)));
                    if (e.getKind() == Tree.Kind.BITWISE_COMPLEMENT) plan.add(Item.of(Expect.ARITH, arithmetic(u, "~", type))); // x ^ -1
                }
                case TYPE_CAST -> cast((TypeCastTree) e);
                case CONDITIONAL_EXPRESSION -> {
                    ConditionalExpressionTree c = (ConditionalExpressionTree) e;
                    condition(c.getCondition());
                    value(c.getTrueExpression(), type);
                    value(c.getFalseExpression(), type);
                }
                case LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL, EQUAL_TO, NOT_EQUAL_TO,
                     CONDITIONAL_AND, CONDITIONAL_OR, LOGICAL_COMPLEMENT -> condition(e);
                case ARRAY_ACCESS -> {
                    ArrayAccessTree a = (ArrayAccessTree) e;
                    String arrayType = javaType(a.getExpression());
                    if (!array(arrayType)) { unsupported(a, "reads from arrays of " + arrayType); return; }
                    value(a.getExpression(), null);
                    value(a.getIndex(), "int");
                    plan.add(Item.of(Expect.ALOAD, arraySite(Kind.ARRAY_READ, a, arrayType)));
                }
                case MEMBER_SELECT -> {
                    MemberSelectTree m = (MemberSelectTree) e;
                    Site field = field(m);
                    if (field != null) {
                        value(m.getExpression(), null);
                        plan.add(Item.of(Expect.FIELD_GET, fieldSite(Kind.FIELD_READ, m, field)));
                        break;
                    }
                    if (!m.getIdentifier().contentEquals("length") || !array(javaType(m.getExpression()))) {
                        unsupported(m, "this member access");
                        return;
                    }
                    value(m.getExpression(), null);
                    plan.add(Item.of(Expect.ARRAYLENGTH, arraySite(Kind.ARRAY_LENGTH, m, javaType(m.getExpression()))));
                }
                case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> update((UnaryTree) e);
                case NEW_ARRAY -> newArray((NewArrayTree) e);
                case SWITCH_EXPRESSION -> {
                    // Elsewhere javac first spills a non-empty operand stack into hidden locals.
                    if (!top) { unsupported(e, "a switch expression that is not a whole initializer or assigned value"); return; }
                    SwitchExpressionTree w = (SwitchExpressionTree) e;
                    switchBlock(w, w.getExpression(), w.getCases(), type);
                }
                case METHOD_INVOCATION -> call((MethodInvocationTree) e);
                default -> unsupported(e, "this expression (" + e.getKind() + ")");
            }
            if (target != null) coerce(type, target);
        }

        private void binary(BinaryTree b, String type) {
            if (!SCALARS.contains(type)) { unsupported(b, "string concatenation"); return; }
            String op = symbol(b.getKind());
            boolean shift = op.startsWith("<") || op.startsWith(">");
            value(b.getLeftOperand(), type);
            if (shift) {
                value(b.getRightOperand(), "int"); // javac ishll/lshll: the distance is coerced to int
            } else {
                value(b.getRightOperand(), type);
            }
            plan.add(Item.of(Expect.ARITH, arithmetic(b, op, type)));
        }

        /** An explicit primitive cast is one step when javac emits a conversion for it. */
        private void cast(TypeCastTree c) {
            String to = javaType(c), from = javaType(c.getExpression());
            if (from.equals(to)) { value(c.getExpression(), null); return; } // no instruction
            if (!numeric(to) || !numeric(from)) { unsupported(c, "this cast"); return; }
            value(c.getExpression(), null);
            List<String> ops = conversions(from, to);
            if (ops.isEmpty()) return;
            Site site = site(Kind.CAST, c);
            site.fromType = from; site.javaType = to; site.operator = "(" + to + ")";
            plan.add(new Item(Expect.CONVERT, site, ops.size(), ops.get(0)));
            if (ops.size() == 2) plan.add(new Item(Expect.CONVERT, null, -1, ops.get(1))); // folded into the step
        }

        /**
         * An initializer is the allocation, then per element its value and its store; it is recorded
         * once, after the last store. A sized creation is one newarray, anewarray or multianewarray.
         */
        private void newArray(NewArrayTree n) {
            String type = javaType(n);
            if (!array(type)) { unsupported(n, "arrays of " + type); return; }
            Site site = arraySite(Kind.ALLOCATE, n, type);
            if (n.getInitializers() != null) {
                List<? extends ExpressionTree> elements = n.getInitializers();
                plan.add(new Item(Expect.NEWARRAY_LITERAL, site, elements.size(), null));
                for (int i = 0; i < elements.size(); i++) {
                    value(elements.get(i), component(type));
                    plan.add(new Item(Expect.LITERAL_STORE, site, i == elements.size() - 1 ? 1 : 0, null));
                }
                return;
            }
            int dims = n.getDimensions().size();
            if (dims > 3) { unsupported(n, "array creation with more than three lengths"); return; }
            for (ExpressionTree d : n.getDimensions()) value(d, "int");
            site.dims = dims;
            plan.add(Item.of(Expect.NEWARRAY, site));
        }

        /**
         * ++/--: an int local is one {@code iinc}; another numeric local is load, add or
         * subtract one, narrowing and store, recorded as one UPDATE step. On a numeric array
         * element it is a read, an addition and a write (three steps).
         */
        private void update(UnaryTree u) {
            ExpressionTree target = strip(u.getExpression());
            String operator = switch (u.getKind()) { case PREFIX_INCREMENT, POSTFIX_INCREMENT -> "++"; default -> "--"; };
            if (target instanceof ArrayAccessTree access && array(javaType(access.getExpression()))
                    && numeric(component(javaType(access.getExpression())))) {
                String arrayType = javaType(access.getExpression()), type = component(arrayType);
                value(access.getExpression(), null);
                value(access.getIndex(), "int");
                plan.add(Item.of(Expect.ALOAD, arraySite(Kind.ARRAY_READ, access, arrayType)));
                plan.add(Item.of(Expect.ARITH, arithmetic(u, operator.equals("++") ? "+" : "-", promote(type))));
                coerce(promote(type), type);
                plan.add(Item.of(Expect.ASTORE, arraySite(Kind.ARRAY_WRITE, u, arrayType)));
                return;
            }
            Site field = field(target);
            if (field != null && numeric(field.javaType)) { // Part 8e: dup, getfield, add, narrow, putfield
                receiver(target);
                plan.add(Item.of(Expect.FIELD_GET, fieldSite(Kind.FIELD_READ, target, field)));
                plan.add(Item.of(Expect.ARITH, arithmetic(u, operator.equals("++") ? "+" : "-", promote(field.javaType))));
                coerce(promote(field.javaType), field.javaType);
                plan.add(Item.of(Expect.FIELD_PUT, fieldSite(Kind.FIELD_WRITE, u, field)));
                return;
            }
            Site declared = target instanceof IdentifierTree ? declarations.get(element(target)) : null;
            if (declared == null || !numeric(declared.javaType)) { unsupported(u, "++/-- on this target"); return; }
            Site site = site(Kind.UPDATE, u);
            site.declarationId = declared.declarationId; site.name = declared.name; site.javaType = declared.javaType;
            site.operator = operator;
            site.position = switch (u.getKind()) { case PREFIX_INCREMENT, PREFIX_DECREMENT -> "prefix"; default -> "postfix"; };
            if (declared.javaType.equals("int")) { plan.add(Item.of(Expect.IINC, site)); return; }
            site.viaArith = true; // javac: load, one, add/sub, narrowing, store
            plan.add(Item.of(Expect.ARITH, site));
            coerce(promote(declared.javaType), declared.javaType);
            plan.add(Item.of(Expect.STORE, site));
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
                    if (numeric(left) && numeric(right)) operandType = promote(left, right);
                    else if (left.equals("boolean") && right.equals("boolean")) operandType = "boolean";
                    else if (isReference(left) && isReference(right) && (e.getKind() == Tree.Kind.EQUAL_TO || e.getKind() == Tree.Kind.NOT_EQUAL_TO))
                        operandType = "ref";
                    else { unsupported(b, "comparing " + left + " with " + right); return null; }
                    String target = operandType.equals("ref") ? null : operandType;
                    value(b.getLeftOperand(), target);
                    value(b.getRightOperand(), target);
                    Cond leaf = new Cond(Cond.Op.LEAF, start(b), end(b));
                    leaf.sourceOp = symbol(b.getKind());
                    return leaf(leaf, b, operandType);
                }
                case IDENTIFIER, ARRAY_ACCESS, METHOD_INVOCATION, MEMBER_SELECT -> { // a boolean variable, field, element or call, tested against zero
                    if (!"boolean".equals(javaType(e))
                            || e.getKind() == Tree.Kind.IDENTIFIER && !declarations.containsKey(element(e)) && field(e) == null) {
                        unsupported(e, "this condition");
                        return null;
                    }
                    if (e.getKind() != Tree.Kind.IDENTIFIER || field(e) != null) value(e, null);
                    return leaf(new Cond(Cond.Op.LEAF, start(e), end(e)), e, "boolean");
                }
                default -> { unsupported(e, "this condition (" + e.getKind() + ")"); return null; }
            }
        }

        private Cond leaf(Cond leaf, Tree tree, String operandType) {
            Site site = site(Kind.LEAF, tree);
            site.leaf = leaf; site.operandType = operandType; site.operator = leaf.sourceOp;
            leaf.site = site;
            // long/float/double comparisons: lcmp/fcmpl/fcmpg/dcmpl/dcmpg, then a jump on the result.
            if (List.of("long", "float", "double").contains(operandType)) plan.add(Item.of(Expect.CMP, site));
            plan.add(Item.of(Expect.JUMP, site));
            return leaf;
        }

        private static void attach(Cond parent, Cond child) {
            if (child == null) return;
            child.parent = parent;
            parent.children.add(child);
        }

        private boolean isReference(String type) {
            return type.equals("java.lang.String") || array(type) || classes.containsKey(type) || type.equals("<nulltype>") || type.equals("<null>");
        }

        // ---- helpers -------------------------------------------------------------

        private boolean isConstant(ExpressionTree e) { return fold(e) != null; }

        private Object constantValue(ExpressionTree e) { return fold(e); }

        /**
         * JLS 15.29 constant value with Java semantics (boxed; char as Character), or null
         * when javac would not fold it (including integer division by zero). Which
         * instructions javac emits depends on this, so it must be exact.
         */
        private Object fold(ExpressionTree e) {
            e = strip(e);
            String type = javaType(e);
            switch (e) {
                case LiteralTree l -> { return l.getKind() == Tree.Kind.NULL_LITERAL ? null : l.getValue(); }
                case IdentifierTree i -> { return element(i) instanceof VariableElement v ? v.getConstantValue() : null; }
                case MemberSelectTree m -> { return element(m) instanceof VariableElement v ? v.getConstantValue() : null; }
                case TypeCastTree c -> {
                    Object v = fold(c.getExpression());
                    return v == null || !(SCALARS.contains(type) || type.equals("java.lang.String")) ? null : convert(v, type);
                }
                case ConditionalExpressionTree c -> {
                    Object test = fold(c.getCondition()), yes = fold(c.getTrueExpression()), no = fold(c.getFalseExpression());
                    if (!(test instanceof Boolean t) || yes == null || no == null) return null;
                    return convert(t ? yes : no, type);
                }
                case UnaryTree u -> {
                    Object v = switch (e.getKind()) {
                        case UNARY_PLUS, UNARY_MINUS, BITWISE_COMPLEMENT, LOGICAL_COMPLEMENT -> fold(u.getExpression());
                        default -> null;
                    };
                    if (v == null) return null;
                    if (e.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) return !(Boolean) v;
                    v = convert(v, type);
                    if (e.getKind() == Tree.Kind.UNARY_PLUS) return v;
                    boolean minus = e.getKind() == Tree.Kind.UNARY_MINUS;
                    return switch (v) {
                        case Integer x -> minus ? -x : ~x;
                        case Long x -> minus ? -x : ~x;
                        case Float x -> minus ? -x : null;
                        case Double x -> minus ? -x : null;
                        default -> null;
                    };
                }
                case BinaryTree b -> { return foldBinary(b, type); }
                default -> { return null; }
            }
        }

        private Object foldBinary(BinaryTree b, String type) {
            Object l = fold(b.getLeftOperand()), r = fold(b.getRightOperand());
            if (l == null || r == null) return null;
            Tree.Kind k = b.getKind();
            if (type.equals("java.lang.String")) return String.valueOf(l) + r;
            if (l instanceof Boolean x && r instanceof Boolean y) {
                return switch (k) {
                    case CONDITIONAL_AND, AND -> x && y; case CONDITIONAL_OR, OR -> x || y; case XOR -> x ^ y;
                    case EQUAL_TO -> x.equals(y); case NOT_EQUAL_TO -> !x.equals(y); default -> null;
                };
            }
            String lt = javaType(b.getLeftOperand()), rt = javaType(b.getRightOperand());
            if (!numeric(lt) || !numeric(rt)) return null;
            boolean shift = k == Tree.Kind.LEFT_SHIFT || k == Tree.Kind.RIGHT_SHIFT || k == Tree.Kind.UNSIGNED_RIGHT_SHIFT;
            if (shift) {
                long distance = ((Number) convert(r, "long")).longValue();
                Object v = convert(l, promote(lt));
                if (v instanceof Integer x) {
                    int d = (int) distance;
                    return k == Tree.Kind.LEFT_SHIFT ? x << d : k == Tree.Kind.RIGHT_SHIFT ? x >> d : x >>> d;
                }
                long x = (Long) v;
                return k == Tree.Kind.LEFT_SHIFT ? x << distance : k == Tree.Kind.RIGHT_SHIFT ? x >> distance : x >>> distance;
            }
            String p = promote(lt, rt);
            Object a = convert(l, p), c = convert(r, p);
            return switch (p) {
                case "int" -> { int x = (Integer) a, y = (Integer) c; yield switch (k) {
                    case PLUS -> x + y; case MINUS -> x - y; case MULTIPLY -> x * y;
                    case DIVIDE -> y == 0 ? null : x / y; case REMAINDER -> y == 0 ? null : x % y;
                    case AND -> x & y; case OR -> x | y; case XOR -> x ^ y;
                    case LESS_THAN -> x < y; case LESS_THAN_EQUAL -> x <= y; case GREATER_THAN -> x > y;
                    case GREATER_THAN_EQUAL -> x >= y; case EQUAL_TO -> x == y; case NOT_EQUAL_TO -> x != y;
                    default -> null; }; }
                case "long" -> { long x = (Long) a, y = (Long) c; yield switch (k) {
                    case PLUS -> x + y; case MINUS -> x - y; case MULTIPLY -> x * y;
                    case DIVIDE -> y == 0 ? null : x / y; case REMAINDER -> y == 0 ? null : x % y;
                    case AND -> x & y; case OR -> x | y; case XOR -> x ^ y;
                    case LESS_THAN -> x < y; case LESS_THAN_EQUAL -> x <= y; case GREATER_THAN -> x > y;
                    case GREATER_THAN_EQUAL -> x >= y; case EQUAL_TO -> x == y; case NOT_EQUAL_TO -> x != y;
                    default -> null; }; }
                case "float" -> { float x = (Float) a, y = (Float) c; yield switch (k) {
                    case PLUS -> x + y; case MINUS -> x - y; case MULTIPLY -> x * y; case DIVIDE -> x / y; case REMAINDER -> x % y;
                    case LESS_THAN -> x < y; case LESS_THAN_EQUAL -> x <= y; case GREATER_THAN -> x > y;
                    case GREATER_THAN_EQUAL -> x >= y; case EQUAL_TO -> x == y; case NOT_EQUAL_TO -> x != y;
                    default -> null; }; }
                default -> { double x = (Double) a, y = (Double) c; yield switch (k) {
                    case PLUS -> x + y; case MINUS -> x - y; case MULTIPLY -> x * y; case DIVIDE -> x / y; case REMAINDER -> x % y;
                    case LESS_THAN -> x < y; case LESS_THAN_EQUAL -> x <= y; case GREATER_THAN -> x > y;
                    case GREATER_THAN_EQUAL -> x >= y; case EQUAL_TO -> x == y; case NOT_EQUAL_TO -> x != y;
                    default -> null; }; }
            };
        }

        /** Java primitive conversion of a folded constant (casts and promotions). */
        private static Object convert(Object v, String to) {
            if (to.equals("java.lang.String") || to.equals("boolean")) return v;
            if (v instanceof Boolean || v instanceof String) return null;
            Number n = v instanceof Character ch ? Integer.valueOf(ch) : (Number) v;
            boolean integral = n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte;
            long asLong = integral ? n.longValue() : n instanceof Float f ? (long) f.floatValue() : (long) n.doubleValue();
            int asInt = integral ? (int) n.longValue() : n instanceof Float f ? (int) f.floatValue() : (int) n.doubleValue();
            return switch (to) {
                case "int" -> asInt;
                case "long" -> asLong;
                case "byte" -> (byte) asInt;
                case "short" -> (short) asInt;
                case "char" -> (char) asInt;
                case "float" -> integral ? (float) n.longValue() : n.floatValue();
                default -> integral ? (double) n.longValue() : n.doubleValue();
            };
        }

        private static String symbol(Tree.Kind kind) {
            return switch (kind) {
                case PLUS -> "+"; case MINUS -> "-"; case MULTIPLY -> "*"; case DIVIDE -> "/"; case REMAINDER -> "%";
                case AND -> "&"; case OR -> "|"; case XOR -> "^";
                case LEFT_SHIFT -> "<<"; case RIGHT_SHIFT -> ">>"; case UNSIGNED_RIGHT_SHIFT -> ">>>";
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
            if ((kind == Kind.DECLARE || kind == Kind.FIELD_WRITE && tree instanceof VariableTree) && end > start && unit.getSourceFile() != null) {
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
