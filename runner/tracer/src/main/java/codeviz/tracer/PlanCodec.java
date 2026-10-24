package codeviz.tracer;

import codeviz.tracer.Analyzer.Analysis;
import codeviz.tracer.Plan.Cond;
import codeviz.tracer.Plan.Diagnostic;
import codeviz.tracer.Plan.Kind;
import codeviz.tracer.Plan.Site;
import java.util.*;

/**
 * Serializes what the trace builder needs from an analysis (diagnostics, sites,
 * condition trees, scopes) so it can leave the runner container. The file is
 * written during the compile phase, before any submitted code runs, and is read
 * by the backend before the runtime phase starts. It carries the analyzed
 * source's SHA-256 so the reader can reject a plan for different text.
 */
final class PlanCodec {
    static final int VERSION = 12; // 2: regions (8a); 3: primitives (8b); 4: switch, arrays (8c); 5: methods (8d); 6: objects (8e); 7: interactive (8g); 8: exceptions (8h); 9: iteration (10d); 10: lambdas (10d); 11: varargs, iterators (12); 12: complexity estimates (15)

    private PlanCodec() { }

    static String write(Analysis a, boolean traced, String sourceSha256) {
        Map<Cond, Integer> condIds = new LinkedHashMap<>();
        for (Site s : a.sites().values()) if (s.leaf != null) number(s.leaf.root(), condIds);
        List<Object> sites = new ArrayList<>();
        for (Site s : a.sites().values()) {
            Map<String, Object> m = TraceBuilder.ordered("id", s.id, "kind", s.kind.name(), "start", s.start, "end", s.end,
                    "scopes", new ArrayList<>(s.scopes));
            put(m, "operator", s.operator); put(m, "position", s.position);
            put(m, "declarationId", s.declarationId); put(m, "name", s.name); put(m, "javaType", s.javaType);
            put(m, "operandType", s.operandType);
            put(m, "fromType", s.fromType); put(m, "operand", s.operand);
            if (s.operand != null) m.put("delta", s.delta);
            if (s.viaArith) m.put("viaArith", true);
            if (s.dims != 0) m.put("dims", s.dims);
            put(m, "methodId", s.methodId);
            if (!s.params.isEmpty()) m.put("params", s.params.stream().map(p -> (Object) p.id).toList());
            if (s.callee != null) m.put("callee", s.callee.id);
            if (s.returnSite != null) m.put("returnSite", s.returnSite.id);
            if (s.implicit) m.put("implicit", true);
            put(m, "owner", s.owner);
            if (s.instance) m.put("instance", true);
            if (s.constructor) m.put("constructor", true);
            if (s.lambda) m.put("lambda", true); // Part 10d
            if (s.callbacks) m.put("callbacks", true);
            if (s.varargs) m.put("varargs", true);
            if (!s.fields.isEmpty()) m.put("fields", s.fields.stream().map(f -> (Object) f.id).toList());
            if (!s.cases.isEmpty()) m.put("cases", s.cases.stream().map(c -> (Object) TraceBuilder.ordered("values",
                    new ArrayList<Object>(c.values()), "default", c.isDefault(), "start", c.start(), "end", c.end())).toList());
            if (!s.declareBefore.isEmpty()) m.put("declareBefore", s.declareBefore.stream().map(d -> (Object) d.id).toList());
            if (!s.caught.isEmpty()) m.put("caught", new ArrayList<Object>(s.caught));
            if (s.kind == Kind.REGION) {
                m.put("unknownDeclarations", new ArrayList<Object>(s.unknownDeclarations));
                m.put("mayWriteArrays", s.mayWriteArrays);
                if (s.mayAdvanceIterators) m.put("mayAdvanceIterators", true); // Part 12c
            }
            if (s.leaf != null) {
                m.put("leaf", condIds.get(s.leaf));
                m.put("jumpOpcode", s.jumpOpcode);
                m.put("jumpMeansTrue", s.jumpMeansTrue);
            }
            sites.add(m);
        }
        List<Object> conds = new ArrayList<>();
        for (var entry : condIds.entrySet()) {
            Cond c = entry.getKey();
            Map<String, Object> m = TraceBuilder.ordered("id", entry.getValue(), "op", c.op.name(), "start", c.start, "end", c.end,
                    "parent", c.parent == null ? null : condIds.get(c.parent),
                    "children", c.children.stream().map(condIds::get).map(Object.class::cast).toList());
            put(m, "sourceOp", c.sourceOp);
            if (c.site != null) m.put("site", c.site.id);
            conds.add(m);
        }
        return Json.write(TraceBuilder.ordered(
                "version", VERSION,
                "sourceSha256", sourceSha256,
                "traced", traced,
                "rootScope", a.rootScope(),
                "interactive", a.interactive(),
                "compileErrors", diagnostics(a.compileErrors()),
                "unsupported", diagnostics(a.unsupported()),
                "uncaptured", diagnostics(a.uncaptured()),
                "sites", sites,
                "conds", conds,
                "complexity", a.complexity().stream().map(PlanCodec::estimate).toList()));
    }

    /** Part 15a: one estimate in the plan (the same fields the trace shows, plus the growth). */
    private static Map<String, Object> estimate(Complexity.Estimate e) {
        return TraceBuilder.ordered("methodId", e.methodId(), "name", e.name(), "base", e.cost().base(), "poly", Double.toString(e.cost().poly()),
                "log", e.cost().log(), "approximate", e.approximate(), "reasons", e.reasons(), "start", e.start(), "end", e.end());
    }

    @SuppressWarnings("unchecked")
    private static Complexity.Estimate readEstimate(Map<String, Object> m) {
        return new Complexity.Estimate((String) m.get("methodId"), (String) m.get("name"),
                new Complexity.Cost(((Long) m.get("base")).intValue(), Double.parseDouble((String) m.get("poly")), ((Long) m.get("log")).intValue()),
                Boolean.TRUE.equals(m.get("approximate")), ((List<Object>) m.get("reasons")).stream().map(String.class::cast).toList(),
                ((Long) m.get("start")).intValue(), ((Long) m.get("end")).intValue());
    }

    record Decoded(Analysis analysis, boolean traced) { }

    @SuppressWarnings("unchecked")
    static Decoded read(String source, String json) {
        Map<String, Object> root = JsonReader.object(json);
        if (((Long) root.get("version")).intValue() != VERSION) throw new IllegalArgumentException("plan version");
        if (!TraceBuilder.sha256(source).equals(root.get("sourceSha256")))
            throw new IllegalArgumentException("plan was produced for different source text");
        Map<Integer, Site> sites = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> rawSites = new LinkedHashMap<>();
        for (Object o : (List<Object>) root.get("sites")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Site s = new Site(integer(m, "id"), Kind.valueOf((String) m.get("kind")), integer(m, "start"), integer(m, "end"),
                    ((List<Object>) m.get("scopes")).stream().map(String.class::cast).toList());
            s.operator = (String) m.get("operator"); s.position = (String) m.get("position");
            s.declarationId = (String) m.get("declarationId"); s.name = (String) m.get("name");
            s.javaType = (String) m.get("javaType"); s.operandType = (String) m.get("operandType");
            if (m.containsKey("jumpOpcode")) {
                s.jumpOpcode = integer(m, "jumpOpcode");
                s.jumpMeansTrue = (Boolean) m.get("jumpMeansTrue");
            }
            s.fromType = (String) m.get("fromType"); s.operand = (String) m.get("operand");
            if (m.containsKey("delta")) s.delta = integer(m, "delta");
            s.viaArith = Boolean.TRUE.equals(m.get("viaArith"));
            if (m.containsKey("dims")) s.dims = integer(m, "dims");
            s.methodId = (String) m.get("methodId");
            s.implicit = Boolean.TRUE.equals(m.get("implicit"));
            s.owner = (String) m.get("owner");
            s.instance = Boolean.TRUE.equals(m.get("instance"));
            s.constructor = Boolean.TRUE.equals(m.get("constructor"));
            s.lambda = Boolean.TRUE.equals(m.get("lambda"));
            s.callbacks = Boolean.TRUE.equals(m.get("callbacks"));
            s.varargs = Boolean.TRUE.equals(m.get("varargs"));
            if (m.containsKey("cases")) {
                List<Plan.Case> cases = new ArrayList<>();
                for (Object c : (List<Object>) m.get("cases")) {
                    Map<String, Object> cm = (Map<String, Object>) c;
                    cases.add(new Plan.Case(((List<Object>) cm.get("values")).stream().map(v -> Math.toIntExact((Long) v)).toList(),
                            (Boolean) cm.get("default"), integer(cm, "start"), integer(cm, "end")));
                }
                s.cases = List.copyOf(cases);
            }
            if (m.containsKey("caught")) s.caught = ((List<Object>) m.get("caught")).stream().map(String.class::cast).toList();
            if (m.containsKey("unknownDeclarations")) {
                s.unknownDeclarations = ((List<Object>) m.get("unknownDeclarations")).stream().map(String.class::cast).toList();
                s.mayWriteArrays = (Boolean) m.get("mayWriteArrays");
                s.mayAdvanceIterators = Boolean.TRUE.equals(m.get("mayAdvanceIterators"));
            }
            sites.put(s.id, s);
            rawSites.put(s.id, m);
        }
        Map<Integer, Cond> conds = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> rawConds = new LinkedHashMap<>();
        for (Object o : (List<Object>) root.get("conds")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Cond c = new Cond(Cond.Op.valueOf((String) m.get("op")), integer(m, "start"), integer(m, "end"));
            c.sourceOp = (String) m.get("sourceOp");
            conds.put(integer(m, "id"), c);
            rawConds.put(integer(m, "id"), m);
        }
        for (var entry : rawConds.entrySet()) {
            Cond c = conds.get(entry.getKey());
            Map<String, Object> m = entry.getValue();
            if (m.get("parent") != null) c.parent = require(conds, integer(m, "parent"));
            for (Object child : (List<Object>) m.get("children")) c.children.add(require(conds, ((Long) child).intValue()));
            if (m.containsKey("site")) c.site = require(sites, integer(m, "site"));
        }
        for (var entry : rawSites.entrySet()) {
            if (entry.getValue().containsKey("leaf")) sites.get(entry.getKey()).leaf = require(conds, integer(entry.getValue(), "leaf"));
            Map<String, Object> raw = entry.getValue();
            Site site = sites.get(entry.getKey());
            if (raw.containsKey("params")) {
                List<Site> params = new ArrayList<>();
                for (Object id : (List<Object>) raw.get("params")) params.add(require(sites, ((Long) id).intValue()));
                site.params = List.copyOf(params);
            }
            if (raw.containsKey("fields")) {
                List<Site> fields = new ArrayList<>();
                for (Object id : (List<Object>) raw.get("fields")) fields.add(require(sites, ((Long) id).intValue()));
                site.fields = List.copyOf(fields);
            }
            if (raw.containsKey("callee")) site.callee = require(sites, integer(raw, "callee"));
            if (raw.containsKey("returnSite")) site.returnSite = require(sites, integer(raw, "returnSite"));
            if (entry.getValue().containsKey("declareBefore")) {
                for (Object id : (List<Object>) entry.getValue().get("declareBefore"))
                    sites.get(entry.getKey()).declareBefore.add(require(sites, ((Long) id).intValue()));
            }
        }
        Analysis analysis = new Analysis(source, readDiagnostics(root.get("compileErrors")), readDiagnostics(root.get("unsupported")),
                readDiagnostics(root.get("uncaptured")), Map.of(), Map.of(), Map.copyOf(sites), (String) root.get("rootScope"), Boolean.TRUE.equals(root.get("interactive")),
                ((List<Object>) root.get("complexity")).stream().map(o -> readEstimate((Map<String, Object>) o)).toList());
        return new Decoded(analysis, (Boolean) root.get("traced"));
    }

    private static void number(Cond c, Map<Cond, Integer> ids) {
        if (ids.containsKey(c)) return;
        ids.put(c, ids.size() + 1);
        for (Cond child : c.children) number(child, ids);
    }

    private static List<Object> diagnostics(List<Diagnostic> list) {
        List<Object> out = new ArrayList<>();
        for (Diagnostic d : list) {
            Map<String, Object> m = TraceBuilder.ordered("phase", d.phase(), "code", d.code(), "message", d.message());
            if (d.start() != null) { m.put("start", d.start()); m.put("end", d.end()); }
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Diagnostic> readDiagnostics(Object o) {
        List<Diagnostic> out = new ArrayList<>();
        for (Object item : (List<Object>) o) {
            Map<String, Object> m = (Map<String, Object>) item;
            out.add(new Diagnostic((String) m.get("phase"), (String) m.get("code"), (String) m.get("message"),
                    m.containsKey("start") ? integer(m, "start") : null, m.containsKey("end") ? integer(m, "end") : null));
        }
        return List.copyOf(out);
    }

    private static void put(Map<String, Object> m, String key, Object value) { if (value != null) m.put(key, value); }

    private static int integer(Map<String, Object> m, String key) { return Math.toIntExact((Long) m.get(key)); }

    private static <T> T require(Map<Integer, T> map, int id) {
        T value = map.get(id);
        if (value == null) throw new IllegalArgumentException("plan reference " + id);
        return value;
    }
}
