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
                    Map<String, byte[]> classes, Map<String, List<Item>> plans, Map<Integer, Site> sites, String rootScope,
                    boolean interactive, List<Complexity.Estimate> complexity) {
        /** Part 8g: the source refers to System.in, so the run gets standard input and the interactive deadline. */
        boolean traceable() { return compileErrors.isEmpty() && unsupported.isEmpty(); }
    }

    static final String FILE = "Main.java";

    /** JVM stack type: byte, short, char and boolean values are ints. */
    static String stack(String t) { return switch (t) { case "byte", "short", "char", "boolean" -> "int"; default -> t; }; }

    /** Element type of an array type. */
    static String component(String arrayType) { return arrayType.substring(0, arrayType.length() - 2); }

    /** A type name without its generic arguments (Part 8f). */
    static String erasure(String t) { int i = t.indexOf('<'); return i < 0 ? t : t.substring(0, i); }

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
                List.of("-g", "-Xjcov", "-XDstringConcat=inline", "--release", "21", "-proc:none", "-implicit:none"), null, List.of(file));
        List<Diagnostic> errors = new ArrayList<>();
        List<Diagnostic> unsupported = new ArrayList<>();
        Walker walker = null;
        List<Complexity.Estimate> complexity = List.of();
        try {
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            errors.addAll(compileErrors(collector));
            if (errors.isEmpty()) {
                CompilationUnitTree unit = units.iterator().next();
                walker = new Walker(unit, Trees.instance(task), task.getElements(), task.getTypes(), unsupported);
                walker.unit();
                complexity = Complexity.estimate(unit, Trees.instance(task)); // Part 15a: from the code's shape only
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
            return new Analysis(source, errors, unsupported, List.of(), Map.of(), Map.of(), Map.of(), null, false, complexity);
        return new Analysis(source, errors, unsupported, List.copyOf(walker.uncaptured), Map.copyOf(classes), Map.copyOf(walker.plans),
                Map.copyOf(walker.sites), walker.rootScope, walker.interactive, complexity);
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
        private final javax.lang.model.util.Types types;
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
        private final Set<String> plainClasses = new HashSet<>(); // traced classes usable as collection elements (Part 8f)
        final Map<Integer, Site> sites = new LinkedHashMap<>();
        private Deque<String> scopes = new ArrayDeque<>(); // swapped while planning a lambda body (Part 10d)
        private final Map<Element, Site> declarations = new HashMap<>();
        private final List<Element> declared = new ArrayList<>(); // insertion order, for rollback
        // Per block: declarations without initializer not yet attached to a following operation (Part 8b).
        private Deque<List<Site>> pending = new ArrayDeque<>();
        // Part 8h: statements a jump can leave (loops, switch statements, labeled statements, switch
        // expressions) and enclosing finally blocks, innermost first; Throwable classes of the source
        // (javaType -> binary name) and those simple enough to be created by traced code.
        private Deque<Object> exits = new ArrayDeque<>();
        private record Finalizer(List<Item> items) { }
        private final Map<String, String> exceptionClasses = new HashMap<>();
        private final Set<String> simpleExceptions = new HashSet<>();
        private int jumpingRegions; // uncaptured statements with a jump planned while a finally block encloses them
        private final Map<Site, Integer> pendingMarks = new HashMap<>(); // plan position of each waiting declaration
        // Findings for the statement being planned; null outside main's statements (they block tracing).
        private List<Diagnostic> current;
        // Part 8c: result types of the enclosing traced switch expressions, and whether the value
        // being planned is a whole initializer or assigned value (javac's operand stack is empty).
        private final Deque<String> yieldTypes = new ArrayDeque<>();
        private boolean topValue;
        boolean interactive; // Part 8g: System.in appears in the source
        String rootScope;

        Walker(CompilationUnitTree unit, Trees trees, Elements elements, javax.lang.model.util.Types types, List<Diagnostic> unsupported) {
            this.unit = unit; this.trees = trees; this.elements = elements; this.types = types;
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
                @Override public Void visitMemberSelect(MemberSelectTree m, Void p) {
                    check(m);
                    if (element(m) instanceof VariableElement v && v.getSimpleName().contentEquals("in")
                            && v.getEnclosingElement() instanceof TypeElement t && t.getQualifiedName().contentEquals("java.lang.System")) interactive = true;
                    return super.visitMemberSelect(m, p);
                }
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
            exceptionClasses(mainClass); // Part 8h
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
                boolean identity = true; // equals/hashCode/compareTo would run user code inside library calls
                for (Tree member : c.getMembers()) {
                    if (member instanceof MethodTree m && List.of("equals", "hashCode", "compareTo").contains(m.getName().toString())) identity = false;
                }
                if (identity) plainClasses.add(site.javaType);
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
            exits.clear();
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
                plans.keySet().removeIf(k -> k.startsWith("lambda:") && Integer.parseInt(k.substring(7)) > siteMark); // Part 10d
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
            // javac copies finally blocks into a jump's code; inside an uncaptured statement they would run unseen (Part 8h).
            if (exits.stream().anyMatch(Finalizer.class::isInstance) && containsJump(s, false)) jumpingRegions++;
            Site site = site(Kind.REGION, s);
            Effects effects = new Effects();
            effects.scan(s, null);
            site.unknownDeclarations = List.copyOf(effects.assigned);
            site.mayWriteArrays = effects.writes;
            site.mayAdvanceIterators = effects.iterators; // Part 12c
            // javac positions a lowered for-each (and so its range table entry) at the iterated expression.
            Tree ranged = s instanceof EnhancedForLoopTree f ? f.getExpression() : s;
            site.crtStart = crt(start(ranged));
            site.crtEnd = crt(end(ranged));
            // javac lowers try-with-resources: the first resource's declaration has its own range entry
            // before the try statement's, which starts only after the resources are opened.
            if (s instanceof TryTree t && !t.getResources().isEmpty() && t.getResources().get(0) instanceof VariableTree resource) {
                site.crtFirstStart = crt(start(resource));
                site.crtFirstEnd = crt(end(resource));
            }
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
            boolean iterators; // Part 12c: the statement uses an iterator, which its code may advance unseen

            @Override public Void visitIdentifier(IdentifierTree id, Void p) {
                if (element(id) instanceof VariableElement && iteratorType(javaType(id))) iterators = true;
                return super.visitIdentifier(id, p);
            }

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
                    exits.push(s);
                    try { statement(w.getStatement()); } finally { exits.pop(); }
                }
                case FOR_LOOP -> {
                    ForLoopTree f = (ForLoopTree) s;
                    scopes.push("f" + start(f));
                    for (StatementTree init : f.getInitializer()) plain(init); // an unsupported init degrades the whole loop
                    if (f.getCondition() != null) loopCondition(f.getCondition());
                    exits.push(s);
                    try { statement(f.getStatement()); } finally { exits.pop(); }
                    for (ExpressionStatementTree update : f.getUpdate()) expressionStatement(update.getExpression());
                    scopes.pop();
                }
                case DO_WHILE_LOOP -> { // javac: body, then the test jumping back (none for a constant)
                    DoWhileLoopTree d = (DoWhileLoopTree) s;
                    exits.push(s);
                    try { statement(d.getStatement()); } finally { exits.pop(); }
                    if (!isConstant(d.getCondition())) condition(d.getCondition());
                }
                case ENHANCED_FOR_LOOP -> forEach((EnhancedForLoopTree) s);
                case SWITCH -> {
                    SwitchTree w = (SwitchTree) s;
                    switchBlock(w, w.getExpression(), w.getCases(), null);
                }
                case YIELD -> {
                    if (yieldTypes.isEmpty()) { unsupported(s, "this yield"); return; }
                    for (Object exit : exits) { // Part 8h: a yield never leaves a finally block here
                        if (exit instanceof SwitchExpressionTree) break;
                        if (exit instanceof Finalizer) { unsupported(s, "a yield out of a try statement with finally"); return; }
                    }
                    value(((YieldTree) s).getValue(), yieldTypes.peek());
                }
                case LABELED_STATEMENT -> { // labels emit no code
                    exits.push(s);
                    try { plain(((LabeledStatementTree) s).getStatement()); } finally { exits.pop(); }
                }
                case BREAK, CONTINUE -> jump(s);
                case EMPTY_STATEMENT -> { }
                case RETURN -> {
                    ExpressionTree result = ((ReturnTree) s).getExpression();
                    List<Finalizer> crossed = finalizers(null);
                    if (currentMethod == null) { // main: only "return;"
                        if (result != null) { unsupported(s, "return values"); return; }
                        copies(crossed);
                        return;
                    }
                    if (result != null) {
                        value(result, currentMethod.javaType);
                        // javac keeps the value in a hidden local while the finally blocks run.
                        if (!crossed.isEmpty()) plan.add(new Item(Expect.STORE, null, 0, "result"));
                    }
                    copies(crossed);
                    Site site = site(Kind.RETURN, s);
                    site.javaType = currentMethod.javaType;
                    plan.add(Item.of(Expect.RETURN, site));
                }
                case TRY -> tryStatement((TryTree) s);
                case THROW -> throwStatement((ThrowTree) s);
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
            if (!array(arrayType)) { iterableForEach(f); return; } // Part 10d
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
            exits.push(f);
            try { statement(f.getStatement()); } finally { exits.pop(); }
            plan.add(new Item(Expect.IINC, null, 0, "index++"));
            scopes.pop();
        }

        /**
         * Part 10d. javac Lower.visitIterableForeachLoop: {@code for (Iterator i = c.iterator(); i.hasNext(); ) { V v = (V) i.next(); body }}
         * over a covered list, set or deque, or a map's keySet(), values() or entrySet(). The ITERATE site records
         * the collection before iterator() (or before the view call); each next() is a NEXT step with the element,
         * then the loop variable's declaration. The iterator itself is never shown.
         */
        private void iterableForEach(EnhancedForLoopTree f) {
            ExpressionTree e = strip(f.getExpression());
            VariableTree v = f.getVariable();
            String type = javaType(v), collection = javaType(e), view = "elements", element;
            ExpressionTree source = e;
            if (e instanceof MethodInvocationTree call && call.getArguments().isEmpty() && call.getMethodSelect() instanceof MemberSelectTree select
                    && MAPS.contains(erasure(javaType(select.getExpression()))) && libraryType(javaType(select.getExpression()))
                    && Set.of("keySet", "values", "entrySet").contains(select.getIdentifier().toString())) {
                source = select.getExpression();
                collection = javaType(source);
                List<String> kv = typeArguments(collection);
                view = switch (select.getIdentifier().toString()) { case "keySet" -> "keys"; case "values" -> "values"; default -> "entries"; };
                element = switch (view) { case "keys" -> kv.get(0); case "values" -> kv.get(1); default -> ENTRY + "<" + kv.get(0) + "," + kv.get(1) + ">"; };
            } else if ((LISTS.contains(erasure(collection)) || SETS.contains(erasure(collection)) || DEQUES.contains(erasure(collection)))
                    && libraryType(collection)) {
                element = typeArguments(collection).get(0);
            } else {
                unsupported(f.getExpression(), "for-each over " + collection + " (only arrays, lists, sets, deques and map views are traced)");
                return;
            }
            if (!localType(type)) { unsupported(v, "local variables of type " + type); return; }
            scopes.push("f" + start(f));
            value(source, null);
            String raw = erasure(collection), owner = Instrumenter.internal(raw);
            TypeElement ownerType = elements.getTypeElement(raw);
            Site iterate = site(Kind.ITERATE, f.getExpression());
            iterate.name = view;
            iterate.javaType = collection;
            iterate.operator = ownerType != null && ownerType.getKind() == ElementKind.INTERFACE ? "interface" : "virtual";
            if (view.equals("elements")) {
                plan.add(new Item(Expect.ITER, iterate, 0, owner + "#iterator()Ljava/util/Iterator;"));
            } else {
                String call = view.equals("keys") ? "keySet()Ljava/util/Set;" : view.equals("values") ? "values()Ljava/util/Collection;" : "entrySet()Ljava/util/Set;";
                plan.add(new Item(Expect.ITER, iterate, 0, owner + "#" + call));
                plan.add(new Item(Expect.LIB, null, 0, (view.equals("values") ? "java/util/Collection" : "java/util/Set") + "#iterator()Ljava/util/Iterator;"));
            }
            plan.add(new Item(Expect.STORE, null, 0, "iterator"));
            plan.add(new Item(Expect.LIB, null, 0, "java/util/Iterator#hasNext()Z"));
            plan.add(new Item(Expect.JUMP, null, 0, "hasNext"));
            Site next = site(Kind.NEXT, f.getExpression());
            next.callee = iterate;
            next.name = view;
            next.javaType = element;
            plan.add(new Item(Expect.NEXT, next, 0, "java/util/Iterator#next()Ljava/lang/Object;"));
            coerce(element, type);
            Site declare = declareSite(v, type);
            plan.add(Item.of(Expect.STORE, declare));
            exits.push(f);
            try { statement(f.getStatement()); } finally { exits.pop(); }
            scopes.pop();
        }

        // ---- Part 10d: lambdas and method references ------------------------------------------

        /** Functional interfaces a traced program may use; their functions are recorded, never guessed. */
        static final Set<String> FUNCTIONAL = Set.of("java.util.function.Function", "java.util.function.BiFunction",
                "java.util.function.UnaryOperator", "java.util.function.BinaryOperator", "java.util.function.Predicate",
                "java.util.function.BiPredicate", "java.util.function.Consumer", "java.util.function.BiConsumer",
                "java.util.function.Supplier", "java.util.function.IntPredicate", "java.util.function.IntFunction",
                "java.util.function.IntUnaryOperator", "java.util.function.IntBinaryOperator", "java.util.function.ToIntFunction",
                "java.util.function.ToIntBiFunction", "java.util.function.IntConsumer", "java.util.Comparator", "java.lang.Runnable");

        /** A covered functional interface type whose type arguments are traced types. */
        private boolean functionalType(String t) {
            if (!FUNCTIONAL.contains(erasure(t))) return false;
            for (String a : typeArguments(t)) {
                String bound = a.startsWith("? super ") ? a.substring(8) : a.startsWith("? extends ") ? a.substring(10) : a;
                if (!elementType(bound) && !functionalType(bound)) return false;
            }
            return true;
        }

        /** JDK methods a method reference may name: pure, with value results, or System.out/err printing. */
        private static final Set<String> JDK_REFERENCES = Set.of("java.lang.Integer.sum", "java.lang.Integer.max", "java.lang.Integer.min",
                "java.lang.Integer.compare", "java.lang.Integer.intValue", "java.lang.Integer.parseInt", "java.lang.Math.max", "java.lang.Math.min",
                "java.lang.Math.abs", "java.lang.String.length", "java.lang.String.isEmpty", "java.lang.String.compareTo",
                "java.lang.Character.isDigit", "java.lang.Character.isLetter", "java.io.PrintStream.println", "java.io.PrintStream.print");

        /**
         * A lambda or method reference in value context: one invokedynamic, matched as a LAMBDA item
         * whose site names the function. A lambda's body is planned as its own method; the instrumenter
         * learns its synthetic name from the invokedynamic. Only static code of Main creates functions.
         */
        private void function(ExpressionTree e) {
            String type = javaType(e);
            if (!functionalType(type)) { unsupported(e, "a lambda or method reference of type " + type); return; }
            if (currentMethod != null && (currentMethod.instance || !currentMethod.owner.equals("Main"))) {
                unsupported(e, "lambdas outside the static methods of Main");
                return;
            }
            Site site = site(Kind.LAMBDA, e);
            site.javaType = type;
            if (e instanceof LambdaExpressionTree l) {
                Site body = lambdaBody(l);
                if (body == null) return;
                site.callee = body;
                site.name = "lambda at " + body.methodId.substring("Main.lambda@".length(), body.methodId.indexOf('('));
                plan.add(Item.of(Expect.LAMBDA, site));
                return;
            }
            MemberReferenceTree r = (MemberReferenceTree) e;
            site.name = sourceText(r);
            if (r.getMode() != MemberReferenceTree.ReferenceMode.INVOKE || !(element(r) instanceof ExecutableElement m)
                    || !(m.getEnclosingElement() instanceof TypeElement owner)) { unsupported(r, "this method reference"); return; }
            Site helper = helpers.get(m);
            if (helper != null && !helper.instance && helper.owner.equals("Main")) { // Main::isPrime: a traced helper called through the library
                site.callee = helper;
                plan.add(new Item(Expect.LAMBDA, site, 0, key(helper)));
                return;
            }
            String qualified = owner.getQualifiedName() + "." + m.getSimpleName();
            if (!JDK_REFERENCES.contains(qualified)) { unsupported(r, "a reference to " + qualified); return; }
            boolean printing = owner.getQualifiedName().contentEquals("java.io.PrintStream");
            if (printing) { // System.out::println: the bound receiver is checked for null before the invokedynamic
                if (!(strip(r.getQualifierExpression()) instanceof MemberSelectTree out) || !(element(out) instanceof VariableElement stream)
                        || !(stream.getEnclosingElement() instanceof TypeElement system) || !system.getQualifiedName().contentEquals("java.lang.System")) {
                    unsupported(r, "a method reference bound to this receiver");
                    return;
                }
                plan.add(new Item(Expect.GETSTATIC, null, 0, stream.getSimpleName().toString()));
                plan.add(new Item(Expect.LIB, null, 0, "java/util/Objects#requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;"));
            }
            List<String> erased = m.getParameters().stream().map(p -> types.erasure(p.asType()).toString()).toList();
            site.methodId = owner.getQualifiedName() + "." + m.getSimpleName() + "(" + String.join(",", erased) + ")";
            site.operator = printing ? "print" : m.getModifiers().contains(Modifier.STATIC) ? "static" : "instance";
            StringBuilder desc = new StringBuilder("(");
            for (String p : erased) desc.append(Instrumenter.descriptorOf(p));
            String result = types.erasure(m.getReturnType()).toString();
            desc.append(')').append(result.equals("void") ? "V" : Instrumenter.descriptorOf(result));
            plan.add(new Item(Expect.LAMBDA, site, 0, Instrumenter.internal(owner.getQualifiedName().toString()) + "#" + m.getSimpleName() + desc));
        }

        private String sourceText(Tree t) {
            try { return unit.getSourceFile().getCharContent(true).subSequence(start(t), end(t)).toString(); }
            catch (IOException e) { return ""; }
        }

        /** The single abstract method of a functional interface type, or null. */
        private ExecutableElement functionalMethod(javax.lang.model.type.TypeMirror type) {
            if (!(types.asElement(type) instanceof TypeElement iface)) return null;
            ExecutableElement found = null;
            for (Element member : elements.getAllMembers(iface)) {
                if (!(member instanceof ExecutableElement m) || !m.getModifiers().contains(Modifier.ABSTRACT)) continue;
                if (m.getEnclosingElement() instanceof TypeElement o && o.getQualifiedName().contentEquals("java.lang.Object")) continue;
                if (List.of("equals", "hashCode", "toString").contains(m.getSimpleName().toString())) continue; // Comparator redeclares equals
                if (found != null) return null;
                found = m;
            }
            return found;
        }

        /**
         * Plans a lambda body as a method (Part 10d): its METHOD site lists the captured locals and then
         * the lambda's parameters (the instrumenter orders them by the synthetic method's local variable
         * table), its result is the instantiated functional descriptor's. Returns null when not covered.
         */
        private Site lambdaBody(LambdaExpressionTree l) {
            javax.lang.model.type.TypeMirror target = trees.getTypeMirror(TreePath.getPath(unit, l));
            ExecutableElement sam = functionalMethod(target);
            if (sam == null || !(target instanceof javax.lang.model.type.DeclaredType declared)) { unsupported(l, "this lambda's target type"); return null; }
            var descriptor = (javax.lang.model.type.ExecutableType) types.asMemberOf(declared, sam);
            String result = descriptor.getReturnType().toString();
            if (!result.equals("void") && !localType(result)) { unsupported(l, "lambdas returning " + result); return null; }
            // Captured locals: names of the enclosing method used in the body, in order of first use.
            Map<Element, String> captured = new LinkedHashMap<>();
            Set<Element> own = new HashSet<>();
            new TreeScanner<Void, Void>() {
                @Override public Void visitVariable(VariableTree v, Void p) { own.add(element(v)); return super.visitVariable(v, p); }
                @Override public Void visitIdentifier(IdentifierTree i, Void p) {
                    if (element(i) instanceof VariableElement v && !own.contains(v) && declarations.containsKey(v)
                            && (v.getKind() == ElementKind.LOCAL_VARIABLE || v.getKind() == ElementKind.PARAMETER || v.getKind() == ElementKind.EXCEPTION_PARAMETER))
                        captured.putIfAbsent(v, v.getSimpleName().toString());
                    return super.visitIdentifier(i, p);
                }
            }.scan(l, null);
            for (Element v : captured.keySet()) {
                if (!localType(v.asType().toString())) { unsupported(l, "lambdas capturing a " + v.asType()); return null; }
            }
            for (VariableTree p : l.getParameters()) if (!localType(javaType(p))) { unsupported(p, "lambda parameters of type " + javaType(p)); return null; }
            // A new planning context: the lambda's own plan, scopes, exits and pending declarations.
            List<Item> outerPlan = plan;
            Site outerMethod = currentMethod;
            Deque<String> outerScopes = scopes;
            Deque<Object> outerExits = exits;
            Deque<List<Site>> outerPending = pending;
            Map<Element, Site> shadowed = new HashMap<>();
            Tree body = l.getBody();
            String root = (body instanceof BlockTree ? "b" : "l") + start(body);
            scopes = new ArrayDeque<>();
            scopes.push(root);
            Site method = site(Kind.METHOD, l);
            long line = unit.getLineMap().getLineNumber(start(l)), column = unit.getLineMap().getColumnNumber(start(l));
            List<String> declaredTypes = l.getParameters().stream().map(this::javaType).toList();
            method.methodId = "Main.lambda@" + line + ":" + column + "(" + String.join(",", declaredTypes) + "):" + result;
            method.owner = "Main";
            method.javaType = result;
            method.lambda = true;
            List<Site> params = new ArrayList<>();
            for (var c : captured.entrySet()) {
                Site param = site(Kind.PARAM, l);
                param.name = c.getValue();
                param.javaType = c.getKey().asType().toString();
                param.declarationId = method.methodId + ":" + param.name + "@captured";
                shadowed.put(c.getKey(), declarations.put(c.getKey(), param));
                params.add(param);
            }
            for (VariableTree p : l.getParameters()) {
                Site param = site(Kind.PARAM, p);
                param.name = p.getName().toString();
                param.javaType = javaType(p);
                param.declarationId = method.methodId + ":" + param.name + "@" + start(p);
                declarations.put(element(p), param);
                params.add(param);
            }
            method.params = List.copyOf(params);
            plan = new ArrayList<>();
            plans.put("lambda:" + method.id, plan);
            currentMethod = method;
            exits = new ArrayDeque<>();
            pending = new ArrayDeque<>();
            pending.push(new ArrayList<>());
            boolean covered = true;
            try {
                if (body instanceof BlockTree block) {
                    for (StatementTree s : block.getStatements()) statement(s);
                    if (result.equals("void")) {
                        Site implicit = site(Kind.RETURN, block);
                        implicit.javaType = "void";
                        implicit.implicit = true;
                        method.returnSite = implicit;
                    }
                } else {
                    List<Diagnostic> outer = current;
                    current = new ArrayList<>();
                    ExpressionTree e = (ExpressionTree) body;
                    if (result.equals("void")) {
                        expressionStatement(e);
                        Site implicit = site(Kind.RETURN, e);
                        implicit.javaType = "void";
                        implicit.implicit = true;
                        method.returnSite = implicit;
                    } else {
                        value(e, result);
                        Site ret = site(Kind.RETURN, e);
                        ret.javaType = result;
                        plan.add(Item.of(Expect.RETURN, ret));
                    }
                    attachPending();
                    List<Diagnostic> found = current;
                    current = outer;
                    if (!found.isEmpty()) { // an expression body cannot run partly uncaptured: the enclosing statement does
                        covered = false;
                        if (current != null) current.addAll(found);
                        else unsupported.addAll(found);
                    }
                }
            } finally {
                plan = outerPlan;
                currentMethod = outerMethod;
                scopes = outerScopes;
                exits = outerExits;
                pending = outerPending;
                for (var s : shadowed.entrySet()) {
                    if (s.getValue() == null) declarations.remove(s.getKey()); else declarations.put(s.getKey(), s.getValue());
                }
            }
            return covered ? method : null;
        }

        // ---- Part 8h: exceptions ------------------------------------------------------------

        private javax.lang.model.type.TypeMirror throwable() { return elements.getTypeElement("java.lang.Throwable").asType(); }

        /** A class of the java.lang or java.util package itself (no subpackage). */
        private static boolean jdkPackage(String qualified) {
            int dot = qualified.lastIndexOf('.');
            String pkg = dot < 0 ? "" : qualified.substring(0, dot);
            return pkg.equals("java.lang") || pkg.equals("java.util");
        }

        /**
         * Throwable classes of the source (top level or static members of Main) get an EXCEPTION_CLASS
         * site so the builder can check what a catch clause caught. Those that only declare constructors
         * passing their parameters to super(...) (and constants) are simple: traced code may create them,
         * and creating one runs no code beyond the JDK's.
         */
        private void exceptionClasses(ClassTree mainClass) {
            List<ClassTree> candidates = new ArrayList<>();
            for (Tree type : unit.getTypeDecls()) if (type instanceof ClassTree c && c != mainClass) candidates.add(c);
            for (Tree member : mainClass.getMembers()) {
                if (member instanceof ClassTree c && c.getModifiers().getFlags().contains(Modifier.STATIC)) candidates.add(c);
            }
            Map<String, ClassTree> trees = new LinkedHashMap<>();
            Map<String, TypeElement> supers = new HashMap<>();
            for (ClassTree c : candidates) {
                if (!(element(c) instanceof TypeElement e) || !types.isSubtype(e.asType(), throwable())) continue;
                String binary = elements.getBinaryName(e).toString();
                TypeElement superclass = (TypeElement) types.asElement(e.getSuperclass());
                Site site = site(Kind.EXCEPTION_CLASS, c);
                site.name = binary;
                site.owner = elements.getBinaryName(superclass).toString();
                exceptionClasses.put(javaType(c), binary);
                trees.put(binary, c);
                supers.put(binary, superclass);
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (var entry : trees.entrySet()) {
                    TypeElement superclass = supers.get(entry.getKey());
                    String superName = superclass.getQualifiedName().toString();
                    boolean superOk = jdkPackage(superName) || simpleExceptions.contains(elements.getBinaryName(superclass).toString());
                    if (!simpleExceptions.contains(entry.getKey()) && superOk && plainException(entry.getValue())) {
                        simpleExceptions.add(entry.getKey());
                        changed = true;
                    }
                }
            }
        }

        private boolean plainException(ClassTree c) {
            if (c.getKind() != Tree.Kind.CLASS || !c.getImplementsClause().isEmpty() || !c.getTypeParameters().isEmpty()) return false;
            for (Tree member : c.getMembers()) {
                if (member instanceof VariableTree v) {
                    Set<Modifier> flags = v.getModifiers().getFlags();
                    if (flags.contains(Modifier.STATIC) && flags.contains(Modifier.FINAL) && element(v) instanceof VariableElement ve
                            && ve.getConstantValue() != null) continue; // such as serialVersionUID
                    return false;
                }
                if (!(member instanceof MethodTree m) || !(element(m) instanceof ExecutableElement e) || e.getKind() != ElementKind.CONSTRUCTOR
                        || m.getBody() == null) return false;
                List<? extends StatementTree> body = m.getBody().getStatements();
                if (body.size() != 1 || !(body.get(0) instanceof ExpressionStatementTree s) || !(s.getExpression() instanceof MethodInvocationTree call)
                        || !(call.getMethodSelect() instanceof IdentifierTree id) || !id.getName().contentEquals("super")) return false;
                for (ExpressionTree a : call.getArguments()) {
                    if (!(strip(a) instanceof IdentifierTree arg) || !(element(arg) instanceof VariableElement p) || p.getKind() != ElementKind.PARAMETER)
                        return false;
                }
            }
            return true;
        }

        /** A Throwable type traced code may name: a JDK class of java.lang or java.util, or a Throwable class of the source. */
        private TypeElement exceptionElement(String javaType) {
            TypeElement e = elements.getTypeElement(erasure(javaType));
            if (e == null || !types.isSubtype(e.asType(), throwable())) return null;
            return jdkPackage(e.getQualifiedName().toString()) || exceptionClasses.containsValue(elements.getBinaryName(e).toString()) ? e : null;
        }

        /** A Throwable type traced code may create and throw (a source class must be simple). */
        private boolean throwableType(String javaType) {
            TypeElement e = exceptionElement(javaType);
            return e != null && (jdkPackage(e.getQualifiedName().toString()) || simpleExceptions.contains(elements.getBinaryName(e).toString()));
        }

        /** {@code throw new E(...)} or {@code throw e} of a caught exception: a THROW step recorded before athrow. */
        private void throwStatement(ThrowTree t) {
            ExpressionTree e = strip(t.getExpression());
            String type = javaType(e);
            if (!throwableType(type)) { unsupported(e, "throwing " + type); return; }
            if (e instanceof NewClassTree n) {
                if (n.getClassBody() != null || n.getEnclosingExpression() != null || !(element(n) instanceof ExecutableElement constructor)) {
                    unsupported(n, "creating this exception");
                    return;
                }
                List<String> params = constructor.getParameters().stream().map(p -> types.erasure(p.asType()).toString()).toList();
                if (!params.isEmpty() && !params.equals(List.of("java.lang.String"))) { unsupported(n, "this exception constructor"); return; }
                plan.add(new Item(Expect.NEW, null, 0, Instrumenter.internal(type)));
                for (ExpressionTree a : n.getArguments()) value(a, "java.lang.String");
                plan.add(new Item(Expect.CTOR, null, 0, Instrumenter.internal(type) + "#<init>(" + (params.isEmpty() ? "" : "Ljava/lang/String;") + ")V"));
            } else if (!(e instanceof IdentifierTree) || !declarations.containsKey(element(e))) { // a catch parameter
                unsupported(e, "this thrown expression");
                return;
            }
            Site site = site(Kind.THROW, t);
            site.javaType = type;
            plan.add(Item.of(Expect.THROW, site));
        }

        /**
         * try/catch/finally in javac's layout: the try block, its finally copy and a jump; per catch the
         * parameter store, the block, its finally copy and a jump; then the finally handler (store of the
         * exception in flight, the finally block, the rethrow). Jumps out of the try or a catch block run
         * a finally copy first. Finally blocks are fully captured and contain no jumps.
         */
        private void tryStatement(TryTree t) {
            if (!t.getResources().isEmpty()) { unsupported(t, "try-with-resources"); return; }
            if (!emitsCode(t.getBlock())) { unsupported(t.getBlock(), "an empty try block"); return; }
            BlockTree fin = t.getFinallyBlock();
            List<Item> finallyItems = null;
            if (fin != null) {
                if (containsJump(fin, true)) { unsupported(fin, "a finally block with jumps, throw or try statements"); return; }
                List<Item> outerPlan = plan;
                Deque<List<Site>> outerPending = pending;
                plan = new ArrayList<>();
                pending = new ArrayDeque<>(); // waiting declarations outside never attach inside the copies
                try { statement(fin); } finally { finallyItems = plan; plan = outerPlan; pending = outerPending; }
                if (finallyItems.stream().anyMatch(i -> i.expect() == Expect.REGION)) {
                    unsupported(fin, "a finally block with statements that run without capture");
                    return;
                }
            }
            Finalizer finalizer = fin == null ? null : new Finalizer(List.copyOf(finallyItems));
            int regionsBefore = jumpingRegions;
            if (finalizer != null) exits.push(finalizer);
            try { statement(t.getBlock()); } finally { if (finalizer != null) exits.pop(); }
            if (finalizer != null && canComplete(t.getBlock())) plan.addAll(finalizer.items());
            for (CatchTree c : t.getCatches()) {
                scopes.push("k" + start(c));
                try {
                    Site parameter = catchSite(c);
                    if (parameter == null) return;
                    plan.add(Item.of(Expect.STORE, parameter));
                    if (finalizer != null) exits.push(finalizer);
                    try { statement(c.getBlock()); } finally { if (finalizer != null) exits.pop(); }
                    if (finalizer != null && canComplete(c.getBlock())) plan.addAll(finalizer.items());
                } finally {
                    scopes.pop();
                }
            }
            if (finalizer != null) {
                if (jumpingRegions != regionsBefore) {
                    unsupported(t, "jumps inside statements that run without capture within a try statement with finally");
                    return;
                }
                plan.add(Item.of(Expect.STORE, site(Kind.FINALLY, fin)));
                plan.addAll(finalizer.items());
                plan.add(new Item(Expect.THROW, null, 0, "rethrow"));
            }
        }

        /** A catch parameter's declaration (Kind.CATCH); its range is {@code catch (T e)}. Null (reported) for an untraced type. */
        private Site catchSite(CatchTree c) {
            VariableTree p = c.getParameter();
            List<? extends Tree> alternatives = p.getType() instanceof UnionTypeTree u ? u.getTypeAlternatives() : List.of(p.getType());
            List<String> caught = new ArrayList<>(), names = new ArrayList<>();
            for (Tree alternative : alternatives) {
                TypeElement e = exceptionElement(javaType(alternative));
                if (e == null) { unsupported(alternative, "catching " + javaType(alternative)); return null; }
                caught.add(elements.getBinaryName(e).toString());
                names.add(javaType(alternative));
            }
            int end = end(p);
            while (end < end(c) && !sourceAt(end, ")")) end++;
            Site site = new Site(sites.size() + 1, Kind.CATCH, start(c), end + 1, reversedScopes());
            sites.put(site.id, site);
            site.declarationId = "Main.main:" + p.getName() + "@" + start(p);
            site.name = p.getName().toString();
            site.javaType = String.join(" | ", names);
            site.caught = List.copyOf(caught);
            declarations.put(element(p), site);
            declared.add(element(p));
            return site;
        }

        /** Finally blocks a jump leaves, innermost first, up to its target ({@code target} null: the whole method). */
        private List<Finalizer> finalizers(Object target) {
            List<Finalizer> out = new ArrayList<>();
            for (Object exit : exits) {
                if (exit == target) break;
                if (exit instanceof Finalizer f) out.add(f);
            }
            return out;
        }

        private void copies(List<Finalizer> crossed) {
            for (Finalizer f : crossed) plan.addAll(f.items());
        }

        /** break/continue: no code but a jump, after the finally blocks it leaves. */
        private void jump(StatementTree s) {
            javax.lang.model.element.Name label = s instanceof BreakTree b ? b.getLabel() : ((ContinueTree) s).getLabel();
            boolean isContinue = s.getKind() == Tree.Kind.CONTINUE;
            for (Object exit : exits) {
                if (exit instanceof SwitchExpressionTree) break;
                boolean target = label != null ? exit instanceof LabeledStatementTree l && l.getLabel().contentEquals(label)
                        : loop(exit) || !isContinue && exit instanceof SwitchTree;
                if (target) { copies(finalizers(exit)); return; }
            }
            unsupported(s, "this jump");
        }

        private static boolean loop(Object tree) {
            return tree instanceof WhileLoopTree || tree instanceof DoWhileLoopTree || tree instanceof ForLoopTree || tree instanceof EnhancedForLoopTree;
        }

        /** Whether javac emits any instruction for a statement. */
        private static boolean emitsCode(StatementTree s) {
            return switch (s) {
                case BlockTree b -> b.getStatements().stream().anyMatch(Walker::emitsCode);
                case VariableTree v -> v.getInitializer() != null;
                case LabeledStatementTree l -> emitsCode(l.getStatement());
                case ClassTree c -> false;
                default -> s.getKind() != Tree.Kind.EMPTY_STATEMENT;
            };
        }

        /** Jump statements (and, with {@code all}, throw and try) outside nested classes and lambdas. */
        private static boolean containsJump(Tree tree, boolean all) {
            boolean[] found = {false};
            new TreeScanner<Void, Void>() {
                @Override public Void visitReturn(ReturnTree r, Void p) { found[0] = true; return null; }
                @Override public Void visitBreak(BreakTree b, Void p) { found[0] = true; return null; }
                @Override public Void visitContinue(ContinueTree c, Void p) { found[0] = true; return null; }
                @Override public Void visitYield(YieldTree y, Void p) { found[0] = true; return null; }
                @Override public Void visitThrow(ThrowTree t, Void p) { if (all) found[0] = true; return super.visitThrow(t, p); }
                @Override public Void visitTry(TryTree t, Void p) { if (all) found[0] = true; return super.visitTry(t, p); }
                @Override public Void visitClass(ClassTree c, Void p) { return null; }
                @Override public Void visitLambdaExpression(LambdaExpressionTree l, Void p) { return null; }
            }.scan(tree, null);
            return found[0];
        }

        /**
         * JLS 14.22 "can complete normally" for the statements traced here, which is when javac's code
         * after them is alive (it emits a finally copy and a jump only then). Constant if conditions
         * are never traced, so the rules for loops and switches are the only constant-sensitive ones.
         */
        private boolean canComplete(StatementTree s) {
            return switch (s.getKind()) {
                case BLOCK -> ((BlockTree) s).getStatements().stream().allMatch(this::canComplete);
                case RETURN, THROW, BREAK, CONTINUE, YIELD -> false;
                case IF -> { // javac emits only the taken branch of a constant condition (such statements run uncaptured)
                    IfTree i = (IfTree) s;
                    Object constant = constantValue(i.getCondition());
                    if (Boolean.TRUE.equals(constant)) yield canComplete(i.getThenStatement());
                    if (Boolean.FALSE.equals(constant)) yield i.getElseStatement() == null || canComplete(i.getElseStatement());
                    yield i.getElseStatement() == null || canComplete(i.getThenStatement()) || canComplete(i.getElseStatement());
                }
                case WHILE_LOOP -> !Boolean.TRUE.equals(constantValue(((WhileLoopTree) s).getCondition())) || breaksTo(s);
                case FOR_LOOP -> {
                    ExpressionTree c = ((ForLoopTree) s).getCondition();
                    yield c != null && !Boolean.TRUE.equals(constantValue(c)) || breaksTo(s);
                }
                case DO_WHILE_LOOP -> {
                    DoWhileLoopTree d = (DoWhileLoopTree) s;
                    yield (canComplete(d.getStatement()) || continuesTo(d)) && !Boolean.TRUE.equals(constantValue(d.getCondition())) || breaksTo(s);
                }
                case LABELED_STATEMENT -> canComplete(((LabeledStatementTree) s).getStatement()) || breaksTo(s);
                case SWITCH -> {
                    SwitchTree w = (SwitchTree) s;
                    boolean hasDefault = w.getCases().stream().anyMatch(c -> c.getLabels().stream().anyMatch(DefaultCaseLabelTree.class::isInstance));
                    if (!hasDefault || breaksTo(s)) yield true;
                    List<? extends CaseTree> cases = w.getCases();
                    if (cases.get(0).getCaseKind() == CaseTree.CaseKind.RULE) {
                        yield cases.stream().anyMatch(c -> !(c.getBody() instanceof StatementTree body) || canComplete(body));
                    }
                    yield cases.get(cases.size() - 1).getStatements().stream().allMatch(this::canComplete);
                }
                case TRY -> {
                    TryTree t = (TryTree) s;
                    boolean any = canComplete(t.getBlock()) || t.getCatches().stream().anyMatch(c -> canComplete(c.getBlock()));
                    yield any && (t.getFinallyBlock() == null || canComplete(t.getFinallyBlock()));
                }
                default -> true;
            };
        }

        /** A reachable-looking break that leaves {@code target} (a loop, switch or labeled statement). */
        private static boolean breaksTo(StatementTree target) { return jumpsTo(target, false); }

        private static boolean continuesTo(StatementTree loop) { return jumpsTo(loop, true); }

        /**
         * Whether a break (or continue) inside {@code target} jumps to it: a labeled one naming its label,
         * or an unlabeled one whose innermost enclosing loop (or, for break, switch) is the target itself.
         */
        private static boolean jumpsTo(StatementTree target, boolean wantContinue) {
            StatementTree inner = target instanceof LabeledStatementTree l ? l.getStatement() : target;
            javax.lang.model.element.Name label = target instanceof LabeledStatementTree l ? l.getLabel() : null;
            boolean unlabeledTarget = loop(inner) || !wantContinue && inner instanceof SwitchTree;
            boolean[] found = {false};
            new TreeScanner<Void, Integer>() { // depth: statements between here and the target that catch an unlabeled jump
                private boolean catches(Tree t) {
                    return loop(t) || t instanceof SwitchExpressionTree || !wantContinue && t instanceof SwitchTree;
                }
                @Override public Void scan(Tree tree, Integer depth) {
                    if (tree == null) return null;
                    return super.scan(tree, tree != inner && catches(tree) ? depth + 1 : depth);
                }
                @Override public Void visitBreak(BreakTree b, Integer depth) {
                    if (!wantContinue && (b.getLabel() == null ? unlabeledTarget && depth == 0 : b.getLabel().equals(label))) found[0] = true;
                    return null;
                }
                @Override public Void visitContinue(ContinueTree c, Integer depth) {
                    if (wantContinue && (c.getLabel() == null ? unlabeledTarget && depth == 0 : c.getLabel().equals(label))) found[0] = true;
                    return null;
                }
                @Override public Void visitClass(ClassTree c, Integer depth) { return null; }
                @Override public Void visitLambdaExpression(LambdaExpressionTree l, Integer depth) { return null; }
            }.scan(inner, 0);
            return found[0];
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
            exits.push(w); // break leaves a switch statement; a switch expression bounds yield (Part 8h)
            try {
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
            } finally {
                exits.pop();
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
        private boolean localType(String t) {
            return SCALARS.contains(t) || t.equals("java.lang.String") || classes.containsKey(t) || array(t)
                    || BOXES.containsKey(t) || libraryType(t) // Part 8f
                    || functionalType(t) || streamType(t) // Part 10d
                    || iteratorType(t); // Part 12c
        }

        // ---- Part 8f: boxed values, strings and library collections --------------------------

        private static final Map<String, String> BOXES = Map.of("java.lang.Integer", "int", "java.lang.Long", "long",
                "java.lang.Double", "double", "java.lang.Float", "float", "java.lang.Short", "short", "java.lang.Byte", "byte",
                "java.lang.Character", "char", "java.lang.Boolean", "boolean");

        private static String boxOf(String primitive) {
            for (var e : BOXES.entrySet()) if (e.getValue().equals(primitive)) return e.getKey();
            throw new IllegalArgumentException(primitive);
        }


        /** Top-level type arguments of a parameterized type name. */
        private static List<String> typeArguments(String t) {
            int open = t.indexOf('<');
            if (open < 0 || !t.endsWith(">")) return List.of();
            List<String> out = new ArrayList<>();
            int depth = 0, from = open + 1;
            for (int i = from; i < t.length() - 1; i++) {
                char ch = t.charAt(i);
                if (ch == '<') depth++;
                else if (ch == '>') depth--;
                else if (ch == ',' && depth == 0) { out.add(t.substring(from, i).trim()); from = i + 1; }
            }
            out.add(t.substring(from, t.length() - 1).trim());
            return out;
        }

        private static final Set<String> LISTS = Set.of("java.util.List", "java.util.ArrayList", "java.util.LinkedList");
        private static final Set<String> DEQUES = Set.of("java.util.Deque", "java.util.Queue", "java.util.ArrayDeque", "java.util.Stack");
        private static final Set<String> MAPS = Set.of("java.util.Map", "java.util.HashMap", "java.util.TreeMap", "java.util.LinkedHashMap");
        private static final Set<String> SETS = Set.of("java.util.Set", "java.util.HashSet", "java.util.TreeSet", "java.util.LinkedHashSet");
        private static final String PQ = "java.util.PriorityQueue", BUILDER = "java.lang.StringBuilder", SCANNER = "java.util.Scanner",
                READER = "java.io.BufferedReader", // Part 8i
                ENTRY = "java.util.Map.Entry"; // Part 10d: a map entry seen by iterating entrySet()

        /** A covered library type with covered type arguments (Part 8f). */
        private boolean libraryType(String t) {
            String raw = erasure(t);
            if (raw.equals(BUILDER) || raw.equals(SCANNER) || raw.equals(READER)) return t.equals(raw);
            List<String> args = typeArguments(t);
            if (raw.equals(ENTRY)) return args.size() == 2 && keyType(args.get(0)) && elementType(args.get(1)); // Part 10d
            if (MAPS.contains(raw)) return args.size() == 2 && keyType(args.get(0)) && elementType(args.get(1));
            if (SETS.contains(raw) || raw.equals(PQ)) return args.size() == 1 && keyType(args.get(0));
            if (LISTS.contains(raw) || DEQUES.contains(raw)) return args.size() == 1 && elementType(args.get(0));
            return false;
        }

        /** Keys of hashed or sorted collections: equality and order come from the JDK, never user code. */
        private static boolean keyType(String t) { return BOXES.containsKey(t) || t.equals("java.lang.String"); }

        private boolean elementType(String t) { return keyType(t) || plainClasses.contains(t) || array(t) || libraryType(t); }

        /** Part 10d: sequential streams and the optionals their terminal operations return (values without contents). */
        private static final Set<String> STREAMS = Set.of("java.util.stream.Stream", "java.util.stream.IntStream",
                "java.util.Optional", "java.util.OptionalInt", "java.util.OptionalDouble");

        private boolean streamType(String t) {
            String raw = erasure(t);
            if (!STREAMS.contains(raw)) return false;
            List<String> args = typeArguments(t);
            return raw.equals("java.util.stream.Stream") || raw.equals("java.util.Optional") ? args.size() == 1 && elementType(args.get(0)) : args.isEmpty();
        }

        /** Part 12c: an iterator of a covered list, set or deque, used directly (hasNext, next, remove). */
        private boolean iteratorType(String t) {
            List<String> args = typeArguments(t);
            return erasure(t).equals("java.util.Iterator") && args.size() == 1 && elementType(args.get(0));
        }

        private static final Map<String, Set<String>> FAMILY_METHODS = Map.ofEntries(
                Map.entry("string", Set.of("length", "charAt", "substring", "indexOf", "lastIndexOf", "equals", "compareTo", "contains",
                        "startsWith", "endsWith", "isEmpty", "toUpperCase", "toLowerCase", "trim", "toCharArray", "split", "chars")), // 10d: arrays, streams
                Map.entry("builder", Set.of("append", "insert", "reverse", "toString", "length", "charAt", "setCharAt", "deleteCharAt", "setLength")),
                Map.entry("list", Set.of("add", "get", "set", "remove", "size", "isEmpty", "contains", "indexOf", "clear",
                        "forEach", "removeIf", "replaceAll", "sort", "stream", "iterator")), // 10d: calls that run functions; 12c: iterator
                Map.entry("deque", Set.of("push", "pop", "peek", "offer", "poll", "add", "remove", "addFirst", "addLast", "offerFirst", "offerLast",
                        "pollFirst", "pollLast", "peekFirst", "peekLast", "removeFirst", "removeLast", "size", "isEmpty", "empty", "clear",
                        "forEach", "removeIf", "stream", "iterator")),
                Map.entry("map", Set.of("put", "get", "getOrDefault", "containsKey", "remove", "size", "isEmpty", "clear",
                        "forEach", "merge", "computeIfAbsent", "computeIfPresent", "compute")),
                Map.entry("set", Set.of("add", "contains", "remove", "size", "isEmpty", "clear", "forEach", "removeIf", "stream", "iterator")),
                Map.entry("iterator", Set.of("hasNext", "next", "remove")), // Part 12c
                // Part 10d: sequential streams; a terminal operation runs the pipeline's functions.
                Map.entry("stream", Set.of("filter", "map", "mapToInt", "mapToObj", "boxed", "sorted", "distinct", "limit", "skip", "peek",
                        "sum", "count", "min", "max", "average", "reduce", "anyMatch", "allMatch", "noneMatch", "findFirst", "forEach",
                        "toList", "collect")),
                Map.entry("optional", Set.of("getAsInt", "getAsDouble", "get", "orElse", "isPresent", "isEmpty")),
                Map.entry("java.util.stream.IntStream", Set.of("range", "rangeClosed")),
                Map.entry("java.util.stream.Stream", Set.of("of")), // Part 12b
                Map.entry("java.util.stream.Collectors", Set.of("toList", "toSet", "joining", "groupingBy", "counting", "toMap")),
                // Part 10d: calling a function value, or composing functions.
                Map.entry("function", Set.of("apply", "test", "accept", "get", "applyAsInt", "compare", "run",
                        "andThen", "compose", "negate", "and", "or", "reversed", "thenComparing")),
                Map.entry("java.util.Comparator", Set.of("comparing", "comparingInt", "naturalOrder", "reverseOrder")),
                Map.entry("pq", Set.of("add", "offer", "poll", "peek", "remove", "size", "isEmpty", "clear")),
                Map.entry("entry", Set.of("getKey", "getValue")), // Part 10d; setValue is not covered
                Map.entry("scanner", Set.of("nextInt", "nextLong", "nextDouble", "nextBoolean", "next", "nextLine", "hasNext", "hasNextInt",
                        "hasNextLine", "close")),
                // Part 8i: ready() is left out, its answer depends on when input arrives, not on the input.
                Map.entry("reader", Set.of("readLine", "read", "close")),
                Map.entry("java.lang.Math", Set.of("max", "min", "abs", "sqrt", "pow")), // Part 12a: pow
                Map.entry("java.lang.Integer", Set.of("parseInt")),
                Map.entry("java.lang.Character", Set.of("isDigit", "isLetter", "isUpperCase", "isLowerCase", "toUpperCase", "toLowerCase")),
                // Part 10d: one step each, replayed on a copy; a sort shows only its before and after contents.
                Map.entry("java.util.Arrays", Set.of("sort", "toString", "equals", "stream", "fill")), // Part 12a: fill
                Map.entry("java.util.Collections", Set.of("sort")));

        /** The library call a method invocation denotes, or null when it is not covered. */
        private ExecutableElement library(MethodInvocationTree call) {
            if (!(element(call) instanceof ExecutableElement m) || !(m.getEnclosingElement() instanceof TypeElement owner)) return null;
            boolean isStatic = m.getModifiers().contains(Modifier.STATIC);
            String name = m.getSimpleName().toString();
            Set<String> names;
            if (isStatic) {
                String o = owner.getQualifiedName().toString();
                names = o.equals("java.lang.String") ? Set.of("valueOf", "format") : FAMILY_METHODS.get(o); // Part 12b: format
            } else {
                if (!(call.getMethodSelect() instanceof MemberSelectTree select)) return null;
                String receiver = javaType(select.getExpression());
                String raw = erasure(receiver);
                // Part 8h: the message of a caught exception, replayed from the recorded exception object.
                if (exceptionElement(receiver) != null && name.equals("getMessage") && m.getParameters().isEmpty()) return m;
                if (!raw.equals("java.lang.String") && !libraryType(receiver) && !functionalType(receiver) && !streamType(receiver)
                        && !iteratorType(receiver)) return null;
                String family = iteratorType(receiver) ? "iterator" : streamType(receiver) ? (raw.startsWith("java.util.stream.") ? "stream" : "optional")
                        : functionalType(receiver) ? "function" : raw.equals("java.lang.String") ? "string" : raw.equals(BUILDER) ? "builder" : raw.equals(SCANNER) ? "scanner"
                        : raw.equals(READER) ? "reader" : raw.equals(ENTRY) ? "entry"
                        : LISTS.contains(raw) ? "list"
                        : DEQUES.contains(raw) ? "deque" : MAPS.contains(raw) ? "map" : SETS.contains(raw) ? "set" : "pq";
                names = new HashSet<>(FAMILY_METHODS.get(family));
                if (raw.equals("java.util.LinkedList")) names.addAll(FAMILY_METHODS.get("deque"));
                if (raw.equals("java.util.Stack")) names.addAll(FAMILY_METHODS.get("list"));
            }
            if (names == null || !names.contains(name)) return null;
            if (isStatic && arrayUtility(owner.getQualifiedName().toString(), m, call)) return m; // Part 10d
            // Part 12b: String.format(fmt, args...) and Stream.of(values...) with their arguments written out.
            boolean varargs = varargs(m, call);
            if (m.isVarArgs() && !varargs && call.getArguments().size() == m.getParameters().size()) return null; // an array passed whole
            if (varargs) {
                boolean format = name.equals("format");
                for (ExpressionTree a : call.getArguments().subList(m.getParameters().size() - 1, call.getArguments().size())) {
                    String t = javaType(a);
                    // format runs toString of each value: boxed values, strings and null only.
                    boolean ok = SCALARS.contains(t) || keyType(t) || t.equals("<nulltype>") || t.equals("<null>") || !format && elementType(t);
                    if (!ok) return null;
                }
            }
            // No argument may make the library run user code (toString, equals, compareTo of user objects).
            for (VariableElement p : varargs ? m.getParameters().subList(0, m.getParameters().size() - 1) : m.getParameters()) {
                String t = types.erasure(p.asType()).toString();
                if (FUNCTIONAL.contains(t) || t.equals("java.util.stream.Collector")) continue; // Part 10d: a function (or collector) the call runs
                if (!SCALARS.contains(t) && !t.equals("java.lang.String") && !t.equals("java.lang.Object") && !t.equals("java.lang.CharSequence")) return null;
                if (t.equals("java.lang.Object") && !name.equals("equals") // String.equals(Object) runs no user code
                        && (owner.getQualifiedName().contentEquals("java.lang.String") || owner.getQualifiedName().contentEquals(BUILDER))) return null;
            }
            for (ExpressionTree a : call.getArguments()) {
                String t = javaType(a);
                if (!SCALARS.contains(t) && !keyType(t) && !elementType(t) && !functionalType(t) && !erasure(t).equals("java.util.stream.Collector")
                        && !t.equals("<nulltype>") && !t.equals("<null>")) return null;
            }
            return m;
        }

        /** Part 12b: a varargs method called with its last arguments written out, which javac packs into an array. */
        private boolean varargs(ExecutableElement m, MethodInvocationTree call) {
            if (!m.isVarArgs()) return false;
            int n = m.getParameters().size();
            if (call.getArguments().size() != n) return true;
            String last = javaType(call.getArguments().get(n - 1));
            return !last.endsWith("[]") && !last.equals("<nulltype>") && !last.equals("<null>");
        }

        /**
         * {@code Arrays.sort/toString/equals} over one-dimensional primitive or String arrays, and
         * {@code Collections.sort} of a list of boxed values or strings (Part 10d). Natural order only,
         * so no user code runs; other overloads (comparators, Object arrays of user types) are not covered.
         */
        private boolean arrayUtility(String owner, ExecutableElement m, MethodInvocationTree call) {
            if (!owner.equals("java.util.Arrays") && !owner.equals("java.util.Collections")) return false;
            // Part 12a: Arrays.fill(a, v) and fill(a, from, to, v); the value is the last parameter.
            boolean fill = m.getSimpleName().contentEquals("fill");
            int last = m.getParameters().size() - 1;
            for (int i = 0; i <= last; i++) {
                String t = types.erasure(m.getParameters().get(i).asType()).toString();
                if (fill && i == last) {
                    if (!SCALARS.contains(t) && !t.equals("java.lang.Object")) return false;
                    String a = javaType(call.getArguments().get(i));
                    // Object[] overload: only a String array, filled with a string or null (no user objects).
                    if (t.equals("java.lang.Object") && !a.equals("java.lang.String") && !a.equals("<nulltype>") && !a.equals("<null>")) return false;
                    continue;
                }
                if (t.equals("java.util.Comparator")) continue; // Part 10d: a comparator the sort runs
                if (m.getSimpleName().contentEquals("stream") && m.getParameters().size() != 1) return false; // Arrays.stream(a) only
                if (!t.equals("int") && !(owner.equals("java.util.Arrays") ? t.endsWith("[]") && !t.endsWith("[][]") : t.equals("java.util.List"))) return false;
            }
            for (int i = 0; i < call.getArguments().size(); i++) {
                String t = javaType(call.getArguments().get(i));
                if (t.equals("int") || functionalType(t) || fill && i == last) continue; // the fill value was checked above
                if (owner.equals("java.util.Arrays") ? !array(t) || t.endsWith("[][]") || !(SCALARS.contains(component(t)) || component(t).equals("java.lang.String"))
                        : !(LISTS.contains(erasure(t)) && libraryType(t) && (keyType(typeArguments(t).get(0)) // natural order, or a comparator (10d)
                                || m.getParameters().size() == 2 && elementType(typeArguments(t).get(0))))) return false;
            }
            return true;
        }

        /** A covered library call (Part 8f): receiver, arguments, one invocation; a LIBRARY step. */
        private void libraryCall(MethodInvocationTree call, ExecutableElement m) {
            boolean isStatic = m.getModifiers().contains(Modifier.STATIC);
            String qualifying = isStatic ? ((TypeElement) m.getEnclosingElement()).getQualifiedName().toString()
                    : erasure(javaType(((MemberSelectTree) call.getMethodSelect()).getExpression()));
            if (!isStatic) value(((MemberSelectTree) call.getMethodSelect()).getExpression(), null);
            var instantiated = (javax.lang.model.type.ExecutableType) trees.getTypeMirror(TreePath.getPath(unit, call.getMethodSelect()));
            List<? extends ExpressionTree> arguments = call.getArguments();
            boolean varargs = varargs(m, call);
            int fixed = varargs ? m.getParameters().size() - 1 : arguments.size();
            for (int i = 0; i < fixed; i++) value(arguments.get(i), instantiated.getParameterTypes().get(i).toString());
            if (varargs) { // Part 12b: javac's array for the written-out arguments: anewarray, then one aastore each
                String packed = instantiated.getParameterTypes().get(fixed).toString();
                String component = packed.endsWith("...") ? packed.substring(0, packed.length() - 3) : packed.substring(0, packed.length() - 2);
                plan.add(Item.of(Expect.NEWARRAY, null));
                for (int i = fixed; i < arguments.size(); i++) {
                    value(arguments.get(i), component);
                    plan.add(Item.of(Expect.ASTORE, null));
                }
            }
            List<String> erased = m.getParameters().stream().map(p -> types.erasure(p.asType()).toString()).toList();
            String result = types.erasure(m.getReturnType()).toString();
            Site site = site(Kind.LIBRARY, call);
            site.owner = qualifying;
            site.name = m.getSimpleName().toString();
            site.instance = !isStatic;
            site.varargs = varargs;
            site.methodId = qualifying + "." + site.name + "(" + String.join(",", erased) + ")";
            site.javaType = javaType(call);
            TypeElement owner = elements.getTypeElement(qualifying);
            site.operator = isStatic ? "static" : owner != null && owner.getKind() == ElementKind.INTERFACE ? "interface" : "virtual";
            // Part 10d: a call that may run a function (an argument, or a function receiver) announces its callbacks.
            site.callbacks = erased.stream().anyMatch(FUNCTIONAL::contains) || FUNCTIONAL.contains(qualifying)
                    || qualifying.startsWith("java.util.stream.") || erased.contains("java.util.stream.Collector");
            StringBuilder desc = new StringBuilder("(");
            for (String p : erased) desc.append(Instrumenter.descriptorOf(p));
            desc.append(')').append(result.equals("void") ? "V" : Instrumenter.descriptorOf(result));
            plan.add(new Item(Expect.LIB, site, 0, Instrumenter.internal(qualifying) + "#" + site.name + desc));
        }

        private static final Set<String> CONSTRUCTIBLE = Set.of("java.util.ArrayList", "java.util.LinkedList", "java.util.ArrayDeque",
                "java.util.Stack", "java.util.HashMap", "java.util.TreeMap", "java.util.LinkedHashMap", "java.util.HashSet",
                "java.util.TreeSet", "java.util.LinkedHashSet", "java.util.PriorityQueue", BUILDER, SCANNER, READER);

        /** Part 17: classes whose copy constructor (from a collection or map) is covered; a PriorityQueue may adopt a comparator. */
        private static final Set<String> COPYABLE = Set.of("java.util.ArrayList", "java.util.LinkedList", "java.util.ArrayDeque",
                "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap");

        /** {@code new ArrayList<>()} and friends: an ALLOCATE step after the library constructor runs (Part 8f). */
        private boolean libraryNew(NewClassTree n) {
            String type = javaType(n), raw = erasure(type);
            if (!CONSTRUCTIBLE.contains(raw) || !libraryType(type) || n.getClassBody() != null
                    || !(element(n) instanceof ExecutableElement constructor)) return false;
            List<String> params = constructor.getParameters().stream().map(p -> types.erasure(p.asType()).toString()).toList();
            if (raw.equals(SCANNER)) { // Part 8g: only new Scanner(System.in)
                if (!params.equals(List.of("java.io.InputStream")) || !(strip(n.getArguments().get(0)) instanceof MemberSelectTree in)
                        || !in.getIdentifier().contentEquals("in") || !(element(in) instanceof VariableElement v)
                        || !(v.getEnclosingElement() instanceof TypeElement system) || !system.getQualifiedName().contentEquals("java.lang.System")) return false;
                plan.add(new Item(Expect.NEW, null, 0, "java/util/Scanner"));
                plan.add(new Item(Expect.GETSTATIC, null, 0, "in"));
                Site site = site(Kind.ALLOCATE, n);
                site.javaType = type;
                plan.add(new Item(Expect.LIBNEW, site, 0, "java/util/Scanner#<init>(Ljava/io/InputStream;)V"));
                return true;
            }
            if (raw.equals(READER)) { // Part 8i: only new BufferedReader(new InputStreamReader(System.in))
                if (!params.equals(List.of("java.io.Reader")) || !(strip(n.getArguments().get(0)) instanceof NewClassTree inner)
                        || inner.getClassBody() != null || !"java.io.InputStreamReader".equals(javaType(inner)) || inner.getArguments().size() != 1
                        || !systemIn(inner.getArguments().get(0))) return false;
                plan.add(new Item(Expect.NEW, null, 0, "java/io/BufferedReader"));
                plan.add(new Item(Expect.NEW, null, 0, "java/io/InputStreamReader"));
                plan.add(new Item(Expect.GETSTATIC, null, 0, "in"));
                plan.add(new Item(Expect.CTOR, null, 0, "java/io/InputStreamReader#<init>(Ljava/io/InputStream;)V"));
                Site site = site(Kind.ALLOCATE, n);
                site.javaType = type;
                plan.add(new Item(Expect.LIBNEW, site, 0, "java/io/BufferedReader#<init>(Ljava/io/Reader;)V"));
                return true;
            }
            if (params.equals(List.of("java.util.Comparator"))) { // Part 10d: only the JDK's reverse natural order
                String owner = reverseOrder(n.getArguments().get(0));
                if (owner == null || !Library.REVERSIBLE.contains(raw)) return false;
                plan.add(new Item(Expect.NEW, null, 0, Instrumenter.internal(raw)));
                plan.add(new Item(Expect.LIB, null, 0, owner + "#reverseOrder()Ljava/util/Comparator;"));
                Site site = site(Kind.ALLOCATE, n);
                site.javaType = type;
                plan.add(new Item(Expect.LIBNEW, site, 0, Instrumenter.internal(raw) + "#<init>(Ljava/util/Comparator;)V"));
                return true;
            }
            // Part 17: a copy of a covered collection or map (res.add(new ArrayList<>(path))), replayed on its mirror.
            if (COPYABLE.contains(raw) && (params.equals(List.of("java.util.Collection")) || params.equals(List.of("java.util.Map")))) {
                String from = javaType(n.getArguments().get(0));
                if (!libraryType(from) || erasure(from).equals(PQ) || erasure(from).equals(SCANNER) || erasure(from).equals(READER)
                        || erasure(from).equals(BUILDER) || erasure(from).equals(ENTRY)) return false;
                plan.add(new Item(Expect.NEW, null, 0, Instrumenter.internal(raw)));
                value(n.getArguments().get(0), params.get(0));
                Site site = site(Kind.ALLOCATE, n);
                site.javaType = type;
                site.operator = "copy";
                plan.add(new Item(Expect.LIBNEW, site, 0, Instrumenter.internal(raw) + "#<init>(" + Instrumenter.descriptorOf(params.get(0)) + ")V"));
                return true;
            }
            if (!params.isEmpty() && !params.equals(List.of("int")) && !(raw.equals(BUILDER) && params.equals(List.of("java.lang.String")))) return false;
            plan.add(new Item(Expect.NEW, null, 0, Instrumenter.internal(raw)));
            for (ExpressionTree a : n.getArguments()) value(a, params.get(0));
            Site site = site(Kind.ALLOCATE, n);
            site.javaType = type;
            plan.add(new Item(Expect.LIBNEW, site, 0, Instrumenter.internal(raw) + "#<init>(" + (params.isEmpty() ? "" : Instrumenter.descriptorOf(params.get(0))) + ")V"));
            return true;
        }

        /** The internal owner of a {@code Collections.reverseOrder()} or {@code Comparator.reverseOrder()} call, else null. */
        private String reverseOrder(ExpressionTree e) {
            if (!(strip(e) instanceof MethodInvocationTree call) || !call.getArguments().isEmpty()
                    || !(element(call) instanceof ExecutableElement m) || !m.getSimpleName().contentEquals("reverseOrder")
                    || !(m.getEnclosingElement() instanceof TypeElement owner)) return null;
            String o = owner.getQualifiedName().toString();
            return o.equals("java.util.Collections") ? "java/util/Collections" : o.equals("java.util.Comparator") ? "java/util/Comparator" : null;
        }

        /** The expression {@code System.in}. */
        private boolean systemIn(ExpressionTree e) {
            return strip(e) instanceof MemberSelectTree in && in.getIdentifier().contentEquals("in") && element(in) instanceof VariableElement v
                    && v.getEnclosingElement() instanceof TypeElement system && system.getQualifiedName().contentEquals("java.lang.System");
        }

        /** The operands of a flattened string concatenation, as javac collects them. */
        private List<ExpressionTree> flatten(ExpressionTree e) {
            e = strip(e);
            if (e.getKind() == Tree.Kind.PLUS && "java.lang.String".equals(javaType(e)) && !isConstant(e)) {
                BinaryTree b = (BinaryTree) e;
                List<ExpressionTree> out = new ArrayList<>(flatten(b.getLeftOperand()));
                out.addAll(flatten(b.getRightOperand()));
                return out;
            }
            return List.of(e);
        }

        /**
         * javac's inline concatenation: new StringBuilder, one append per operand, toString. One
         * EXPRESSION step with every operand; operands must not run user toString().
         */
        private void concat(Tree whole, List<ExpressionTree> leaves) {
            for (ExpressionTree leaf : leaves) {
                String t = javaType(leaf);
                if (!SCALARS.contains(t) && !keyType(t) && !printable(t) && !t.equals("<nulltype>") && !t.equals("<null>")) { // 10d: library values
                    unsupported(leaf, "concatenating " + t + " (its toString() is not traced)");
                    return;
                }
            }
            plan.add(new Item(Expect.NEW, null, 0, "java/lang/StringBuilder"));
            plan.add(new Item(Expect.CTOR, null, 0, "java/lang/StringBuilder#<init>()V"));
            Site end = site(Kind.CONCAT, whole);
            end.javaType = "java.lang.String";
            for (ExpressionTree leaf : leaves) {
                value(leaf, null);
                String t = javaType(leaf);
                String arg = switch (t) {
                    case "int", "short", "byte" -> "I"; case "long" -> "J"; case "float" -> "F"; case "double" -> "D";
                    case "char" -> "C"; case "boolean" -> "Z"; case "java.lang.String" -> "Ljava/lang/String;";
                    case BUILDER -> "Ljava/lang/CharSequence;"; // javac picks append(CharSequence) for a builder
                    default -> "Ljava/lang/Object;";
                };
                Site a = site(Kind.CONCAT_ARG, leaf);
                a.callee = end;
                a.javaType = t;
                plan.add(new Item(Expect.APPEND, a, 0, "java/lang/StringBuilder#append(" + arg + ")Ljava/lang/StringBuilder;"));
            }
            plan.add(new Item(Expect.TOSTRING, end, 0, "java/lang/StringBuilder#toString()Ljava/lang/String;"));
        }

        private boolean array(String t) {
            if (!t.endsWith("[]")) return false;
            String base = t.substring(0, t.indexOf('['));
            return SCALARS.contains(base) || base.equals("java.lang.String") || classes.containsKey(base) || BOXES.containsKey(base);
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
            if (SCALARS.contains(from) && to != null && !SCALARS.contains(to)) { // boxing (Part 8f): valueOf, never a step
                String box = boxOf(from);
                plan.add(new Item(Expect.BOX, null, 0, Instrumenter.internal(box) + "#valueOf(" + Instrumenter.descriptorOf(from) + ")L"
                        + Instrumenter.internal(box) + ";"));
                return;
            }
            if (BOXES.containsKey(from) && to != null && SCALARS.contains(to)) { // unboxing, then any widening
                String p = BOXES.get(from);
                plan.add(new Item(Expect.UNBOX, null, 0, Instrumenter.internal(from) + "#" + p + "Value()" + Instrumenter.descriptorOf(p)));
                from = p;
            }
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
                    ExecutableElement library = library(call);
                    if (helpers.containsKey(element(call))) call(call); // an ignored result is popped: no binding
                    else if (library != null) libraryCall(call, library);
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
            if (c.getKind() == Tree.Kind.PLUS_ASSIGNMENT && "java.lang.String".equals(javaType(target))) { // Part 8f: s += x
                Site local = target instanceof IdentifierTree ? declarations.get(element(target)) : null;
                if (local == null) { unsupported(c, "+= on this string target"); return; }
                List<ExpressionTree> leaves = new ArrayList<>(List.of(target));
                leaves.addAll(flatten(rhs));
                concat(c, leaves);
                plan.add(Item.of(Expect.STORE, assignment(c, local)));
                return;
            }
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
            if (BOXES.containsKey(rhsType)) rhsType = BOXES.get(rhsType); // Part 10d: total += map.get(k) unboxes the right side
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
                    || parameters.get(0).equals("java.lang.String")
                    || parameters.get(0).equals("java.lang.Object") && printable(javaType(call.getArguments().get(0)))))) {
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
                case "char" -> "C"; case "byte" -> "B"; case "short" -> "S"; case "java.lang.Object" -> "Ljava/lang/Object;";
                default -> "Ljava/lang/String;";
            };
        }

        /**
         * Whether printing a value of this type runs only JDK code (Part 10d): boxed values, strings,
         * builders, and covered collections and maps holding only those (no user toString).
         */
        private boolean printable(String t) {
            if (keyType(t) || t.equals("<nulltype>") || t.equals("<null>") || t.equals(BUILDER)) return true;
            if (!libraryType(t) || erasure(t).equals(SCANNER) || erasure(t).equals(READER)) return false;
            for (String a : typeArguments(t)) if (!printable(a)) return false;
            return true;
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
            if (isConstant(e)) { // javac folds compile-time constants: nothing executes but boxing (Part 8f)
                if (target != null && SCALARS.contains(javaType(e)) && !SCALARS.contains(target) && !target.equals("java.lang.String")) coerce(javaType(e), target);
                return;
            }
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
                case NEW_CLASS -> { if (!libraryNew((NewClassTree) e)) newObject((NewClassTree) e); }
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
                case LAMBDA_EXPRESSION, MEMBER_REFERENCE -> function(e); // Part 10d
                case METHOD_INVOCATION -> {
                    ExecutableElement library = library((MethodInvocationTree) e);
                    if (library != null) libraryCall((MethodInvocationTree) e, library);
                    else call((MethodInvocationTree) e);
                }
                default -> unsupported(e, "this expression (" + e.getKind() + ")");
            }
            if (target != null) coerce(type, target);
        }

        private void binary(BinaryTree b, String type) {
            if (type.equals("java.lang.String") && b.getKind() == Tree.Kind.PLUS) { concat(b, flatten(b)); return; } // Part 8f
            if (!SCALARS.contains(type)) { unsupported(b, "this operator on " + type); return; }
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
                    boolean equality = e.getKind() == Tree.Kind.EQUAL_TO || e.getKind() == Tree.Kind.NOT_EQUAL_TO;
                    if (BOXES.containsKey(left) && BOXES.containsKey(right) && equality) { unsupported(b, "== between boxed values (identity)"); return null; }
                    boolean nullTest = left.startsWith("<null") || right.startsWith("<null");
                    String l = nullTest ? left : BOXES.getOrDefault(left, left), r = nullTest ? right : BOXES.getOrDefault(right, right); // unboxed (Part 8f)
                    String operandType;
                    if (numeric(l) && numeric(r)) operandType = promote(l, r);
                    else if (l.equals("boolean") && r.equals("boolean")) operandType = "boolean";
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
            return type.equals("java.lang.String") || array(type) || classes.containsKey(type) || BOXES.containsKey(type) || libraryType(type)
                    || type.equals("<nulltype>") || type.equals("<null>");
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
