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
    static final int VERSION = 3; // 2: uncaptured regions (Part 8a); 3: primitive types (Part 8b)

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
            if (!s.declareBefore.isEmpty()) m.put("declareBefore", s.declareBefore.stream().map(d -> (Object) d.id).toList());
            if (s.kind == Kind.REGION) {
                m.put("unknownDeclarations", new ArrayList<Object>(s.unknownDeclarations));
                m.put("mayWriteArrays", s.mayWriteArrays);
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
                "compileErrors", diagnostics(a.compileErrors()),
                "unsupported", diagnostics(a.unsupported()),
                "uncaptured", diagnostics(a.uncaptured()),
                "sites", sites,
                "conds", conds));
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
            if (m.containsKey("unknownDeclarations")) {
                s.unknownDeclarations = ((List<Object>) m.get("unknownDeclarations")).stream().map(String.class::cast).toList();
                s.mayWriteArrays = (Boolean) m.get("mayWriteArrays");
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
            if (entry.getValue().containsKey("declareBefore")) {
                for (Object id : (List<Object>) entry.getValue().get("declareBefore"))
                    sites.get(entry.getKey()).declareBefore.add(require(sites, ((Long) id).intValue()));
            }
        }
        Analysis analysis = new Analysis(source, readDiagnostics(root.get("compileErrors")), readDiagnostics(root.get("unsupported")),
                readDiagnostics(root.get("uncaptured")), Map.of(), List.of(), Map.copyOf(sites), (String) root.get("rootScope"));
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
