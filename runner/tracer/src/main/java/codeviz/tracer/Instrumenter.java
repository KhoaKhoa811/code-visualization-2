package codeviz.tracer;

import codeviz.tracer.Plan.Expect;
import codeviz.tracer.Plan.Item;
import codeviz.tracer.Plan.Site;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Matches the analyzer's plan against javac's bytecode for {@code Main.main}, one
 * relevant instruction per plan item, then replaces each matched instruction with a
 * {@code codeviz.rt.Recorder} helper that performs it once. Any mismatch means the
 * plan does not describe what will execute, so tracing is refused.
 */
final class Instrumenter {
    static final class MappingException extends Exception {
        MappingException(String message) { super(message); }
    }

    private static final String RT = "codeviz/rt/Recorder";

    private Instrumenter() { }

    static final String MAIN = "Main#main([Ljava/lang/String;)V";

    /** Whether a class (JVM internal name) has any planned method. */
    static boolean planned(String className, Map<String, List<Item>> plans) {
        return plans.keySet().stream().anyMatch(k -> k.startsWith(className + "#"));
    }

    static byte[] instrument(byte[] classFile, Map<String, List<Item>> plans, Map<Integer, Site> sites, Map<String, byte[]> program)
            throws MappingException {
        ClassNode cn = new ClassNode();
        new CrtReader(classFile).accept(cn, new Attribute[]{new Crt()}, ClassReader.SKIP_FRAMES);
        rejectRecorderReferences(cn);
        if (cn.name.equals("Main") && cn.methods.stream().noneMatch(m -> (cn.name + "#" + m.name + m.desc).equals(MAIN)))
            throw new MappingException("main not found");
        Map<String, Site> helpers = new HashMap<>();
        for (Site s : sites.values()) if (s.kind == Plan.Kind.METHOD) helpers.put(key(s), s);
        for (MethodNode method : cn.methods) {
            String key = cn.name + "#" + method.name + method.desc;
            List<Item> plan = plans.get(key);
            if (plan == null) continue; // untraced: runs as compiled
            Site helper = key.equals(MAIN) ? null : helpers.get(key);
            if (!key.equals(MAIN) && helper == null) throw new MappingException("no method site for " + key);
            Map<Site, Region> regions = regions(method, plan);
            List<Match> matches = match(method, plan, regions, helper);
            AbstractInsnNode anchor = null; // a constructor is entered after its super() call
            for (Match m : matches) {
                if (m.item().expect() == Expect.SUPER) anchor = m.insn();
                rewrite(method, m);
            }
            if (helper == null) {
                InsnList entry = new InsnList();
                entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
                entry.add(call("enter", "([Ljava/lang/String;)V"));
                method.instructions.insert(entry);
                for (AbstractInsnNode insn : method.instructions.toArray())
                    if (insn.getOpcode() == Opcodes.RETURN) method.instructions.insertBefore(insn, call("exit", "()V"));
            } else {
                if (helper.constructor && anchor == null) throw new MappingException("constructor without super()");
                enterHelper(method, helper, anchor);
            }
        }
        // The range table only served mapping; it is not written back.
        for (MethodNode m : cn.methods) if (m.attrs != null) m.attrs.removeIf(Crt.class::isInstance);
        Hierarchy hierarchy = new Hierarchy(program);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String b) { return hierarchy.common(a, b); }
        };
        cn.accept(writer);
        return writer.toByteArray();
    }

    // ---- traced helpers (Part 8d) --------------------------------------------------------

    /** Plan key of a METHOD site: declaring class (internal name), #, name and JVM descriptor (this excluded). */
    static String key(Site method) {
        StringBuilder b = new StringBuilder(internal(method.owner)).append('#').append(method.name).append('(');
        for (Site p : method.params.subList(method.instance ? 1 : 0, method.params.size())) b.append(descriptorOf(p.javaType));
        return b.append(')').append(method.javaType.equals("void") ? "V" : descriptorOf(method.javaType)).toString();
    }

    /**
     * Entry: {@code begin(method)} decides whether this invocation is traced, then one record per
     * parameter. A catch-all handler ends the invocation in the recorder when an exception leaves
     * it and rethrows the same exception; earlier (user) handlers keep precedence. A constructor is
     * entered after its super() call ({@code anchor}), once this is initialized (Part 8e).
     */
    private static void enterHelper(MethodNode method, Site site, AbstractInsnNode anchor) {
        InsnList entry = new InsnList();
        entry.add(siteConstant(site));
        entry.add(call("begin", "(I)V"));
        int slot = 0;
        for (Site p : site.params) {
            switch (Analyzer.stack(p.javaType)) {
                case "long" -> { entry.add(new VarInsnNode(Opcodes.LLOAD, slot)); entry.add(siteConstant(p)); entry.add(call("vl", "(JI)V")); slot += 2; }
                case "float" -> { entry.add(new VarInsnNode(Opcodes.FLOAD, slot)); entry.add(siteConstant(p)); entry.add(call("vf", "(FI)V")); slot++; }
                case "double" -> { entry.add(new VarInsnNode(Opcodes.DLOAD, slot)); entry.add(siteConstant(p)); entry.add(call("vd", "(DI)V")); slot += 2; }
                case "int" -> { entry.add(new VarInsnNode(Opcodes.ILOAD, slot)); entry.add(siteConstant(p)); entry.add(call("vi", "(II)V")); slot++; }
                default -> { entry.add(new VarInsnNode(Opcodes.ALOAD, slot)); entry.add(siteConstant(p)); entry.add(call("va", "(Ljava/lang/Object;I)V")); slot++; }
            }
        }
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        entry.add(start);
        if (anchor == null) method.instructions.insert(entry);
        else method.instructions.insert(anchor, entry);
        method.instructions.add(end);
        method.instructions.add(handler);
        method.instructions.add(call("unwind", "()V"));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
    }

    // ---- statement ranges (javac -Xjcov CharacterRangeTable) ------------------------

    /** Exposes label creation so the range table can mark statement boundaries. */
    private static final class CrtReader extends ClassReader {
        CrtReader(byte[] bytes) { super(bytes); }
        Label label(int offset, Label[] labels) { return readLabel(offset, labels); }
    }

    /** CharacterRangeTable: statement bytecode ranges with their source line/column ranges. */
    static final class Crt extends Attribute {
        record Entry(Label start, Label end, int charStart, int charEnd, int flags) { }
        static final int STATEMENT = 0x0001;
        final List<Entry> entries = new ArrayList<>();

        Crt() { super("CharacterRangeTable"); }

        @Override public boolean isCodeAttribute() { return true; }

        @Override protected Attribute read(ClassReader reader, int offset, int length, char[] buffer, int codeOffset, Label[] labels) {
            Crt crt = new Crt();
            if (labels == null || !(reader instanceof CrtReader r)) return crt;
            int count = reader.readUnsignedShort(offset);
            for (int i = 0, p = offset + 2; i < count; i++, p += 14) {
                int startPc = reader.readUnsignedShort(p), endPc = reader.readUnsignedShort(p + 2);
                if (endPc + 1 >= labels.length) continue;
                crt.entries.add(new Entry(r.label(startPc, labels), r.label(endPc + 1, labels),
                        reader.readInt(p + 4), reader.readInt(p + 8), reader.readUnsignedShort(p + 12)));
            }
            return crt;
        }
    }

    private record Region(LabelNode start, LabelNode end) { }

    /** Bytecode range of each uncaptured statement: the table entry with exactly its source range. */
    private static Map<Site, Region> regions(MethodNode main, List<Item> plan) throws MappingException {
        Crt crt = main.attrs == null ? null
                : main.attrs.stream().filter(Crt.class::isInstance).map(Crt.class::cast).findFirst().orElse(null);
        Map<Site, Region> out = new HashMap<>();
        for (Item item : plan) {
            if (item.expect() != Expect.REGION) continue;
            Site s = item.site();
            if (crt == null || s.crtStart < 0 || s.crtEnd < 0) throw new MappingException("no statement range table");
            List<Crt.Entry> found = crt.entries.stream()
                    .filter(e -> (e.flags() & Crt.STATEMENT) != 0 && e.charStart() == s.crtStart && e.charEnd() == s.crtEnd).toList();
            if (found.isEmpty()) throw new MappingException("no bytecode range for an uncaptured statement");
            // Several entries share a range when javac lowers a statement (a for-each's hidden setup
            // carries the loop's range): the statement is the outermost one, all starting together.
            LabelNode start = null, end = null;
            for (Crt.Entry e : found) {
                if (!(e.start().info instanceof LabelNode s0) || !(e.end().info instanceof LabelNode e0))
                    throw new MappingException("statement range labels missing");
                if (start != null && s0 != start) throw new MappingException("ambiguous statement range");
                start = s0;
                if (end == null || main.instructions.indexOf(e0) > main.instructions.indexOf(end)) end = e0;
            }
            out.put(s, new Region(start, end));
        }
        return out;
    }

    /**
     * Common superclass for frame computation. User classes come from the compiled
     * program (they are not loadable here); JDK classes from the platform loader.
     */
    private static final class Hierarchy {
        private final Map<String, String> supers = new HashMap<>();
        private final Set<String> interfaces = new HashSet<>();

        Hierarchy(Map<String, byte[]> program) {
            for (byte[] bytes : program.values()) {
                ClassReader r = new ClassReader(bytes);
                supers.put(r.getClassName(), r.getSuperName());
                if ((r.getAccess() & Opcodes.ACC_INTERFACE) != 0) interfaces.add(r.getClassName());
            }
        }

        String common(String a, String b) {
            try {
                if (interfaces.contains(a) || interfaces.contains(b)) return "java/lang/Object";
                List<String> left = ancestors(a);
                for (String candidate : ancestors(b)) if (left.contains(candidate)) return candidate;
            } catch (ClassNotFoundException | RuntimeException unknown) {
                // fall through
            }
            return "java/lang/Object";
        }

        private List<String> ancestors(String type) throws ClassNotFoundException {
            List<String> out = new ArrayList<>();
            String current = type;
            while (current != null && supers.containsKey(current)) { out.add(current); current = supers.get(current); }
            if (current == null) return out;
            Class<?> c = Class.forName(current.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
            if (c.isInterface()) { out.add("java/lang/Object"); return out; }
            for (; c != null; c = c.getSuperclass()) out.add(c.getName().replace('.', '/'));
            return out;
        }
    }

    private static void rejectRecorderReferences(ClassNode cn) throws MappingException {
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                String owner = insn instanceof MethodInsnNode mi ? mi.owner
                        : insn instanceof FieldInsnNode fi ? fi.owner
                        : insn instanceof TypeInsnNode ti ? ti.desc : null;
                if (owner != null && owner.startsWith("codeviz/"))
                    throw new MappingException("user code references the recorder package");
            }
        }
    }

    // ---- matching -------------------------------------------------------------

    private record Match(Item item, AbstractInsnNode insn, AbstractInsnNode anchor) { }

    private static List<Match> match(MethodNode main, List<Item> plan, Map<Site, Region> regions, Site helper) throws MappingException {
        List<Match> matches = new ArrayList<>();
        Map<LabelNode, Site> regionStarts = new HashMap<>();
        for (var r : regions.entrySet()) regionStarts.put(r.getValue().start(), r.getKey());
        int p = 0;
        LabelNode skipUntil = null; // end of the uncaptured statement being skipped
        Site skipped = null;
        for (AbstractInsnNode insn : main.instructions.toArray()) {
            if (skipUntil != null) {
                if (insn != skipUntil) {
                    // Part 8d: a return inside an uncaptured statement of a helper is still recorded, at that statement.
                    if (helper != null && isReturn(insn)) matches.add(new Match(Item.of(Expect.RETURN, skipped.returnSite), insn, insn));
                    continue;
                }
                skipUntil = null; // the end label may start the next region
            }
            if (insn instanceof LabelNode label && regionStarts.containsKey(label)) {
                Site site = regionStarts.get(label);
                if (p >= plan.size() || plan.get(p).site() != site)
                    throw new MappingException("uncaptured statement out of plan order");
                matches.add(new Match(plan.get(p++), label, label));
                if (regions.get(site).end() != label) { skipUntil = regions.get(site).end(); skipped = site; }
                continue;
            }
            Expect found = helper != null && isReturn(insn) ? Expect.RETURN : classify(insn);
            if (found == null) continue;
            if (found == Expect.RETURN && (p >= plan.size() || plan.get(p).expect() != Expect.RETURN)) {
                // javac's implicit return at the end of a void method has no statement.
                if (insn.getOpcode() != Opcodes.RETURN) throw new MappingException("unplanned value return");
                matches.add(new Match(Item.of(Expect.RETURN, helper.returnSite), insn, insn));
                continue;
            }
            if (p >= plan.size()) throw new MappingException("unplanned instruction " + found);
            Item item = plan.get(p++);
            if (found != family(item.expect())) throw new MappingException("expected " + item.expect() + ", found " + found);
            check(item, insn, main);
            if (item.site() != null) matches.add(new Match(item, insn, insn));
        }
        if (skipUntil != null || p != plan.size()) throw new MappingException("plan not fully matched");
        return matches;
    }

    /** The instruction family a plan item matches. */
    private static Expect family(Expect expect) {
        return switch (expect) {
            case NEWARRAY_LITERAL -> Expect.NEWARRAY;
            case LITERAL_STORE -> Expect.ASTORE;
            case CALL, SUPER, CTOR, LIB, LIBNEW, APPEND, TOSTRING, BOX, UNBOX -> Expect.INVOKE;
            default -> expect;
        };
    }

    /** Relevant instruction family, or null for purely structural instructions. */
    private static boolean isReturn(AbstractInsnNode insn) {
        return insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN;
    }

    private static Expect classify(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op < 0) return null; // labels, line numbers, frames
        return switch (op) {
            case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> Expect.STORE;
            case Opcodes.IINC -> Expect.IINC;
            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD, Opcodes.BALOAD,
                 Opcodes.CALOAD, Opcodes.SALOAD -> Expect.ALOAD;
            case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE,
                 Opcodes.CASTORE, Opcodes.SASTORE -> Expect.ASTORE;
            case Opcodes.ARRAYLENGTH -> Expect.ARRAYLENGTH;
            case Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY -> Expect.NEWARRAY;
            case Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> Expect.SWITCH;
            case Opcodes.NEW -> Expect.NEW;
            case Opcodes.CHECKCAST -> null; // javac's casts of erased generic results (Part 8f); never planned
            case Opcodes.GETFIELD -> Expect.FIELD_GET;
            case Opcodes.PUTFIELD -> Expect.FIELD_PUT;
            case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG -> Expect.NEG;
            case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> Expect.CMP;
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                 Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
                 Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.IFNULL, Opcodes.IFNONNULL -> Expect.JUMP;
            case Opcodes.GETSTATIC -> Expect.GETSTATIC;
            case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD, Opcodes.ICONST_M1, Opcodes.ICONST_0,
                 Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.LCONST_0,
                 Opcodes.LCONST_1, Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2, Opcodes.DCONST_0, Opcodes.DCONST_1,
                 Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.ACONST_NULL, Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2,
                 Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2, Opcodes.GOTO, Opcodes.RETURN, Opcodes.NOP,
                 Opcodes.POP, Opcodes.POP2 -> null; // a pop discards a traced call's ignored result (Part 8d)
            case Opcodes.LDC -> {
                Object c = ((LdcInsnNode) insn).cst;
                yield c instanceof Integer || c instanceof String || c instanceof Long || c instanceof Float
                        || c instanceof Double ? null : Expect.INVOKE;
            }
            // Everything else (invokes, fields, throw, switch...) must be planned explicitly;
            // only println invokes are, so anything else fails matching.
            default -> op >= Opcodes.IADD && op <= Opcodes.DREM || op >= Opcodes.ISHL && op <= Opcodes.LXOR ? Expect.ARITH
                    : op >= Opcodes.I2L && op <= Opcodes.I2S ? Expect.CONVERT : Expect.INVOKE;
        };
    }

    private static void check(Item item, AbstractInsnNode insn, MethodNode main) throws MappingException {
        Site site = item.site();
        if (site == null && !List.of(Expect.GETSTATIC, Expect.INVOKE, Expect.NEW, Expect.CTOR, Expect.BOX, Expect.UNBOX).contains(item.expect())) {
            // A hidden for-each step (copy, length, index) or a folded conversion: its family is enough.
            if (item.expect() == Expect.CONVERT) checkConversion(item, insn);
            return;
        }
        switch (item.expect()) {
            case ALOAD -> {
                if (insn.getOpcode() != Opcodes.IALOAD + elementOffset(Analyzer.component(site.javaType)))
                    throw new MappingException("array load type mismatch");
            }
            case ASTORE, LITERAL_STORE -> {
                if (insn.getOpcode() != Opcodes.IASTORE + elementOffset(Analyzer.component(site.javaType)))
                    throw new MappingException("array store type mismatch");
            }
            case NEWARRAY, NEWARRAY_LITERAL -> {
                String element = Analyzer.component(site.javaType);
                boolean ok = switch (insn) {
                    case MultiANewArrayInsnNode m -> site.dims >= 2 && m.dims == site.dims && m.desc.equals(descriptorOf(site.javaType));
                    case TypeInsnNode t -> site.dims <= 1 && t.desc.equals(internalName(element));
                    case IntInsnNode i -> site.dims <= 1 && i.operand == ARRAY_TYPES.indexOf(element) + Opcodes.T_BOOLEAN;
                    default -> false;
                };
                if (!ok) throw new MappingException("array creation mismatch");
            }
            case CALL -> {
                Site callee = site.callee;
                int opcode = callee.constructor ? Opcodes.INVOKESPECIAL : callee.instance ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKESTATIC;
                if (!(insn instanceof MethodInsnNode m) || insn.getOpcode() != opcode
                        || !(m.owner + "#" + m.name + m.desc).equals(item.member())) throw new MappingException("unexpected call");
            }
            case SUPER -> {
                if (!(insn instanceof MethodInsnNode m) || insn.getOpcode() != Opcodes.INVOKESPECIAL
                        || !(m.owner + "." + m.name + m.desc).equals(item.member())) throw new MappingException("expected super()");
            }
            case LIB, LIBNEW, APPEND, TOSTRING, BOX, UNBOX -> {
                int opcode = switch (item.expect()) {
                    case LIB -> switch (site.operator) { case "static" -> Opcodes.INVOKESTATIC; case "interface" -> Opcodes.INVOKEINTERFACE; default -> Opcodes.INVOKEVIRTUAL; };
                    case LIBNEW -> Opcodes.INVOKESPECIAL;
                    case BOX -> Opcodes.INVOKESTATIC;
                    default -> Opcodes.INVOKEVIRTUAL;
                };
                if (!(insn instanceof MethodInsnNode m) || insn.getOpcode() != opcode
                        || !(m.owner + "#" + m.name + m.desc).equals(item.member())) throw new MappingException("unexpected library call");
            }
            case CTOR -> {
                if (!(insn instanceof MethodInsnNode m) || insn.getOpcode() != Opcodes.INVOKESPECIAL
                        || !(m.owner + "#" + m.name + m.desc).equals(item.member())) throw new MappingException("unexpected constructor");
            }
            case NEW -> {
                if (!((TypeInsnNode) insn).desc.equals(item.member())) throw new MappingException("unexpected new");
            }
            case FIELD_GET, FIELD_PUT -> {
                FieldInsnNode f = (FieldInsnNode) insn;
                if (!f.owner.equals(internal(site.owner)) || !f.name.equals(site.name)) throw new MappingException("unexpected field " + f.name);
            }
            case SWITCH -> {
                Set<Integer> keys = new HashSet<>();
                if (insn instanceof LookupSwitchInsnNode l) keys.addAll(l.keys);
                else {
                    TableSwitchInsnNode t = (TableSwitchInsnNode) insn;
                    for (int i = 0; i < t.labels.size(); i++) if (t.labels.get(i) != t.dflt) keys.add(t.min + i);
                }
                Set<Integer> planned = new HashSet<>();
                for (Plan.Case c : site.cases) planned.addAll(c.values());
                if (!keys.equals(planned)) throw new MappingException("switch cases differ");
            }
            case GETSTATIC -> {
                FieldInsnNode f = (FieldInsnNode) insn;
                if (!f.owner.equals("java/lang/System") || !f.name.equals(item.member()))
                    throw new MappingException("unexpected static field " + f.owner + "." + f.name);
            }
            case INVOKE -> {
                if (!(insn instanceof MethodInsnNode m) || insn.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !m.owner.equals("java/io/PrintStream") || !(m.name + m.desc).equals(item.member()))
                    throw new MappingException("unexpected call");
            }
            case STORE -> {
                int var = ((VarInsnNode) insn).var;
                LocalVariableNode lv = localAfter(main, insn, var);
                // javac drops zero-length scopes (a declaration ending its block), so a
                // missing entry is acceptable; a different variable is not.
                if (lv != null && !lv.name.equals(site.name)) throw new MappingException("store to unexpected local");
            }
            case IINC -> {
                IincInsnNode i = (IincInsnNode) insn;
                LocalVariableNode lv = localAfter(main, insn, i.var);
                int delta = site.kind == Plan.Kind.UPDATE ? (site.operator.equals("++") ? 1 : -1) : site.delta;
                if (lv == null || !lv.name.equals(site.name) || i.incr != delta) throw new MappingException("unexpected iinc");
            }
            case ARITH -> {
                if (insn.getOpcode() != arithmeticOpcode(site)) throw new MappingException("arithmetic operator mismatch");
            }
            case NEG -> {
                if (insn.getOpcode() != Opcodes.INEG + typeOffset(site.javaType)) throw new MappingException("negation type mismatch");
            }
            case CONVERT -> checkConversion(item, insn);
            case CMP -> {
                int op = insn.getOpcode();
                boolean ok = switch (site.operandType) {
                    case "long" -> op == Opcodes.LCMP;
                    case "float" -> op == Opcodes.FCMPL || op == Opcodes.FCMPG;
                    default -> op == Opcodes.DCMPL || op == Opcodes.DCMPG;
                };
                if (!ok) throw new MappingException("comparison type mismatch");
            }
            case JUMP -> {
                Boolean meaning = jumpMeaning(site, insn.getOpcode());
                if (meaning == null) throw new MappingException("condition jump does not test " + site.operator);
                site.jumpOpcode = insn.getOpcode();
                site.jumpMeansTrue = meaning;
            }
            default -> { }
        }
    }

    private static void checkConversion(Item item, AbstractInsnNode insn) throws MappingException {
        if (insn.getOpcode() != Opcodes.I2L + CONVERSIONS.indexOf(item.member()))
            throw new MappingException("expected conversion " + item.member());
    }

    // ---- array types (Part 8c) -------------------------------------------------------

    /** newarray operand order from T_BOOLEAN (4) to T_LONG (11). */
    private static final List<String> ARRAY_TYPES = List.of("boolean", "char", "float", "double", "byte", "short", "int", "long");

    /** Opcode offset from IALOAD/IASTORE for an element type (javac: baload/bastore serve byte and boolean). */
    private static int elementOffset(String element) {
        return switch (element) {
            case "int" -> 0; case "long" -> 1; case "float" -> 2; case "double" -> 3;
            case "byte", "boolean" -> 5; case "char" -> 6; case "short" -> 7; default -> 4;
        };
    }

    /** Recorder helper prefix per primitive element type: iaload, zastore, ... */
    private static final Map<String, String> HELPER_PREFIX = Map.of("int", "i", "long", "l", "float", "f", "double", "d",
            "byte", "b", "boolean", "z", "char", "c", "short", "s");

    /** Stack descriptor of a primitive element value (byte, short, char and boolean travel as int). */
    private static String loadDescriptor(String element) {
        return switch (element) { case "long" -> "J"; case "float" -> "F"; case "double" -> "D"; default -> "I"; };
    }

    /** JVM descriptor of a traced Java type. */
    static String descriptorOf(String type) {
        type = Analyzer.erasure(type);
        if (type.endsWith("[]")) return "[" + descriptorOf(Analyzer.component(type));
        return switch (type) {
            case "int" -> "I"; case "long" -> "J"; case "float" -> "F"; case "double" -> "D"; case "boolean" -> "Z";
            case "byte" -> "B"; case "char" -> "C"; case "short" -> "S"; default -> "L" + internal(type) + ";";
        };
    }

    /** Internal name of a reference type, as anewarray and checkcast use it. */
    private static String internalName(String type) {
        return type.endsWith("[]") ? descriptorOf(type) : internal(type);
    }

    /** JVM internal name of a class type: java/lang/String, Node, or Main$Node for a member class of Main (Part 8e). */
    static String internal(String type) {
        type = Analyzer.erasure(type); // Part 8f: generic arguments do not reach the JVM
        if (type.equals("java.lang.String")) return "java/lang/String";
        if (type.startsWith("Main.")) return "Main$" + type.substring(5);
        return type.replace('.', '/');
    }

    /**
     * Whether a taken jump means the source leaf is true (javac never swaps operands;
     * it tests either the operator or its negation). Null if the opcode fits neither.
     */
    static Boolean jumpMeaning(Site site, int opcode) {
        if (site.operator == null) { // boolean variable leaf
            return opcode == Opcodes.IFNE ? Boolean.TRUE : opcode == Opcodes.IFEQ ? Boolean.FALSE : null;
        }
        String tested = switch (opcode) {
            case Opcodes.IF_ICMPEQ, Opcodes.IFEQ, Opcodes.IF_ACMPEQ, Opcodes.IFNULL -> "==";
            case Opcodes.IF_ICMPNE, Opcodes.IFNE, Opcodes.IF_ACMPNE, Opcodes.IFNONNULL -> "!=";
            case Opcodes.IF_ICMPLT, Opcodes.IFLT -> "<";
            case Opcodes.IF_ICMPGE, Opcodes.IFGE -> ">=";
            case Opcodes.IF_ICMPGT, Opcodes.IFGT -> ">";
            case Opcodes.IF_ICMPLE, Opcodes.IFLE -> "<=";
            default -> null;
        };
        boolean family = switch (site.operandType) {
            case "ref" -> opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE
                    || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL;
            case "long", "float", "double" -> opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE; // tests the cmp result
            default -> opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ICMPLE;
        };
        if (tested == null || !family) return null;
        if (tested.equals(site.operator)) return Boolean.TRUE;
        if (tested.equals(negate(site.operator))) return Boolean.FALSE;
        return null;
    }

    /** Conversion opcodes I2L..I2S in order. */
    static final List<String> CONVERSIONS = List.of("I2L", "I2F", "I2D", "L2I", "L2F", "L2D", "F2I", "F2L", "F2D",
            "D2I", "D2L", "D2F", "I2B", "I2C", "I2S");

    /** Helper names for IADD..DREM, INEG..DNEG and ISHL..LXOR, indexed by opcode - IADD. */
    private static final List<String> ARITHMETIC_NAMES = List.of(
            "iadd", "ladd", "fadd", "dadd", "isub", "lsub", "fsub", "dsub", "imul", "lmul", "fmul", "dmul",
            "idiv", "ldiv", "fdiv", "ddiv", "irem", "lrem", "frem", "drem", "ineg", "lneg", "fneg", "dneg",
            "ishl", "lshl", "ishr", "lshr", "iushr", "lushr", "iand", "land", "ior", "lor", "ixor", "lxor");

    /** Opcode offset of a type within an I/L/F/D opcode family. */
    private static int typeOffset(String type) {
        return switch (Analyzer.stack(type)) { case "long" -> 1; case "float" -> 2; case "double" -> 3; default -> 0; };
    }

    private static int arithmeticOpcode(Site site) {
        String op = site.kind == Plan.Kind.UPDATE ? (site.operator.equals("++") ? "+" : "-") : site.operator;
        String type = site.kind == Plan.Kind.UPDATE ? Analyzer.stack(site.javaType) : site.javaType;
        int wide = typeOffset(type);
        return switch (op) {
            case "+" -> Opcodes.IADD + wide; case "-" -> Opcodes.ISUB + wide; case "*" -> Opcodes.IMUL + wide;
            case "/" -> Opcodes.IDIV + wide; case "%" -> Opcodes.IREM + wide;
            case "&" -> Opcodes.IAND + wide; case "|" -> Opcodes.IOR + wide; case "^", "~" -> Opcodes.IXOR + wide;
            case "<<" -> Opcodes.ISHL + wide; case ">>" -> Opcodes.ISHR + wide; default -> Opcodes.IUSHR + wide;
        };
    }

    private static char descriptor(String type) {
        return switch (Analyzer.stack(type)) { case "long" -> 'J'; case "float" -> 'F'; case "double" -> 'D'; default -> 'I'; };
    }

    private static AbstractInsnNode nextInstruction(AbstractInsnNode insn) {
        AbstractInsnNode at = insn.getNext();
        while (at != null && at.getOpcode() < 0) at = at.getNext();
        return at;
    }

    private static String negate(String op) {
        return switch (op) {
            case "<" -> ">="; case ">=" -> "<"; case ">" -> "<="; case "<=" -> ">";
            case "==" -> "!="; default -> "==";
        };
    }

    /** javac starts a local's scope at the instruction after its first store. */
    private static LocalVariableNode localAfter(MethodNode m, AbstractInsnNode store, int var) {
        if (m.localVariables == null) return null;
        int at = m.instructions.indexOf(store);
        for (LocalVariableNode lv : m.localVariables) {
            if (lv.index == var && m.instructions.indexOf(lv.start) <= at + 1 && m.instructions.indexOf(lv.end) > at) return lv;
        }
        return null;
    }

    // ---- rewriting --------------------------------------------------------------

    private static void rewrite(MethodNode m, Match match) {
        Site site = match.item().site();
        InsnList list = m.instructions;
        AbstractInsnNode insn = match.insn();
        switch (match.item().expect()) {
            case STORE -> {
                int var = ((VarInsnNode) insn).var;
                InsnList after = new InsnList();
                switch (insn.getOpcode()) {
                    case Opcodes.ASTORE -> { after.add(new VarInsnNode(Opcodes.ALOAD, var)); after.add(siteConstant(site)); after.add(call("va", "(Ljava/lang/Object;I)V")); }
                    case Opcodes.LSTORE -> { after.add(new VarInsnNode(Opcodes.LLOAD, var)); after.add(siteConstant(site)); after.add(call("vl", "(JI)V")); }
                    case Opcodes.FSTORE -> { after.add(new VarInsnNode(Opcodes.FLOAD, var)); after.add(siteConstant(site)); after.add(call("vf", "(FI)V")); }
                    case Opcodes.DSTORE -> { after.add(new VarInsnNode(Opcodes.DLOAD, var)); after.add(siteConstant(site)); after.add(call("vd", "(DI)V")); }
                    default -> { after.add(new VarInsnNode(Opcodes.ILOAD, var)); after.add(siteConstant(site)); after.add(call("vi", "(II)V")); }
                }
                list.insert(insn, after);
            }
            case IINC -> {
                InsnList after = new InsnList();
                after.add(new VarInsnNode(Opcodes.ILOAD, ((IincInsnNode) insn).var));
                after.add(siteConstant(site));
                after.add(call("vi", "(II)V"));
                list.insert(insn, after);
            }
            case ALOAD -> {
                String element = Analyzer.component(site.javaType);
                if (element.endsWith("[]") || element.equals("java.lang.String")) {
                    list.insertBefore(insn, siteConstant(site));
                    MethodInsnNode load = call("aaload", "([Ljava/lang/Object;II)Ljava/lang/Object;");
                    list.set(insn, load);
                    list.insert(load, new TypeInsnNode(Opcodes.CHECKCAST, internalName(element)));
                } else {
                    replace(list, insn, site, HELPER_PREFIX.get(element) + "aload",
                            "(" + descriptorOf(site.javaType) + "II)" + loadDescriptor(element));
                }
            }
            case ASTORE -> {
                String element = Analyzer.component(site.javaType);
                boolean reference = element.endsWith("[]") || element.equals("java.lang.String");
                String array = reference ? "[Ljava/lang/Object;" : descriptorOf(site.javaType);
                replace(list, insn, site, (reference ? "a" : HELPER_PREFIX.get(element)) + "astore",
                        "(" + array + "I" + (reference ? "Ljava/lang/Object;" : loadDescriptor(element)) + "I)V");
            }
            case ARRAYLENGTH -> replace(list, insn, site, "arraylength", "(Ljava/lang/Object;I)I");
            case NEWARRAY -> {
                // One helper allocates exactly once (same exceptions); the checkcast restores the static type.
                InsnList before = new InsnList();
                String helper, desc;
                switch (insn) {
                    case MultiANewArrayInsnNode multi -> {
                        before.add(new LdcInsnNode(Type.getType(multi.desc)));
                        helper = "multi"; desc = "(" + "I".repeat(multi.dims) + "Ljava/lang/Class;I)Ljava/lang/Object;";
                    }
                    case TypeInsnNode t -> {
                        before.add(new LdcInsnNode(Type.getObjectType(t.desc)));
                        helper = "anewarray"; desc = "(ILjava/lang/Class;I)Ljava/lang/Object;";
                    }
                    default -> {
                        before.add(new LdcInsnNode(((IntInsnNode) insn).operand));
                        helper = "newarray"; desc = "(III)Ljava/lang/Object;";
                    }
                }
                before.add(siteConstant(site));
                list.insertBefore(insn, before);
                MethodInsnNode helperCall = call(helper, desc);
                list.set(insn, helperCall);
                list.insert(helperCall, new TypeInsnNode(Opcodes.CHECKCAST, descriptorOf(site.javaType)));
            }
            case NEWARRAY_LITERAL, LITERAL_STORE -> {
                // An initializer is recorded once, after its last element store (or its creation when empty).
                boolean last = match.item().expect() == Expect.NEWARRAY_LITERAL ? match.item().literalLength() == 0
                        : match.item().literalLength() == 1;
                if (!last) return;
                InsnList after = new InsnList();
                after.add(new InsnNode(Opcodes.DUP));
                after.add(siteConstant(site));
                after.add(call("literal", "(Ljava/lang/Object;I)V"));
                list.insert(insn, after);
            }
            case CALL -> { // announces a traced call site just before the invocation (Part 8d)
                InsnList before = new InsnList();
                before.add(siteConstant(site));
                before.add(call("call", "(I)V"));
                list.insertBefore(insn, before);
            }
            case RETURN -> {
                InsnList before = new InsnList();
                switch (insn.getOpcode()) {
                    case Opcodes.RETURN -> { before.add(siteConstant(site)); before.add(call("ret", "(I)V")); }
                    case Opcodes.LRETURN -> { before.add(new InsnNode(Opcodes.DUP2)); before.add(siteConstant(site)); before.add(call("retl", "(JI)V")); }
                    case Opcodes.DRETURN -> { before.add(new InsnNode(Opcodes.DUP2)); before.add(siteConstant(site)); before.add(call("retd", "(DI)V")); }
                    case Opcodes.FRETURN -> { before.add(new InsnNode(Opcodes.DUP)); before.add(siteConstant(site)); before.add(call("retf", "(FI)V")); }
                    case Opcodes.ARETURN -> { before.add(new InsnNode(Opcodes.DUP)); before.add(siteConstant(site)); before.add(call("reta", "(Ljava/lang/Object;I)V")); }
                    default -> { before.add(new InsnNode(Opcodes.DUP)); before.add(siteConstant(site)); before.add(call("reti", "(II)V")); }
                }
                list.insertBefore(insn, before);
            }
            case LIB -> {
                // Spill receiver and arguments, run the original call (a failure is recorded and rethrown),
                // then record receiver, arguments and result (Part 8f).
                MethodInsnNode call = (MethodInsnNode) insn;
                Type[] args = Type.getArgumentTypes(call.desc);
                Type result = Type.getReturnType(call.desc);
                boolean instance = call.getOpcode() != Opcodes.INVOKESTATIC;
                int base = m.maxLocals + 2; // after the field-write spare (Part 8e)
                int[] slots = new int[args.length];
                for (int i = 0, next = base + 1; i < args.length; next += args[i].getSize(), i++) slots[i] = next;
                InsnList before = new InsnList();
                for (int i = args.length - 1; i >= 0; i--) before.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), slots[i]));
                if (instance) {
                    before.add(new VarInsnNode(Opcodes.ASTORE, base));
                    // A receiver seen for the first time is recorded before the call changes it.
                    before.add(new VarInsnNode(Opcodes.ALOAD, base));
                    before.add(call("libSee", "(Ljava/lang/Object;)V"));
                    before.add(new VarInsnNode(Opcodes.ALOAD, base));
                }
                for (int i = 0; i < args.length; i++) before.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slots[i]));
                LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
                before.add(start);
                list.insertBefore(insn, before);
                InsnList after = new InsnList();
                after.add(end);
                boolean isVoid = result.getSort() == Type.VOID;
                if (!isVoid) { after.add(new InsnNode(result.getSize() == 2 ? Opcodes.DUP2 : Opcodes.DUP)); box(after, result); }
                after.add(siteConstant(site));
                after.add(instance ? new VarInsnNode(Opcodes.ALOAD, base) : new InsnNode(Opcodes.ACONST_NULL));
                after.add(new LdcInsnNode(args.length));
                after.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
                for (int i = 0; i < args.length; i++) {
                    after.add(new InsnNode(Opcodes.DUP));
                    after.add(new LdcInsnNode(i));
                    after.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slots[i]));
                    box(after, args[i]);
                    after.add(new InsnNode(Opcodes.AASTORE));
                }
                after.add(isVoid ? call("libV", "(ILjava/lang/Object;[Ljava/lang/Object;)V")
                        : call("lib", "(Ljava/lang/Object;ILjava/lang/Object;[Ljava/lang/Object;)V"));
                list.insert(insn, after);
                list.add(handler);
                list.add(new InsnNode(Opcodes.DUP));
                list.add(siteConstant(site));
                list.add(call("libFail", "(Ljava/lang/Throwable;I)V"));
                list.add(new InsnNode(Opcodes.ATHROW));
                m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
            }
            case LIBNEW -> {
                InsnList after = new InsnList();
                after.add(new InsnNode(Opcodes.DUP));
                after.add(siteConstant(site));
                after.add(call("libNew", "(Ljava/lang/Object;I)V"));
                list.insert(insn, after);
            }
            case APPEND -> { // the operand of a concatenation, recorded as it is appended
                char t = Type.getArgumentTypes(((MethodInsnNode) insn).desc)[0].getDescriptor().charAt(0);
                InsnList before = new InsnList();
                before.add(new InsnNode(t == 'J' || t == 'D' ? Opcodes.DUP2 : Opcodes.DUP));
                before.add(siteConstant(site));
                String type = t == 'L' ? "Ljava/lang/Object;" : String.valueOf(t);
                before.add(call("cat" + (t == 'L' ? 'A' : t), "(" + type + "I)V"));
                list.insertBefore(insn, before);
            }
            case TOSTRING -> {
                InsnList after = new InsnList();
                after.add(new InsnNode(Opcodes.DUP));
                after.add(siteConstant(site));
                after.add(call("catEnd", "(Ljava/lang/String;I)V"));
                list.insert(insn, after);
            }
            case FIELD_GET -> {
                // dup; null check (records the failure); dup; getfield; record the read and return the value.
                char t = fieldKind(site.javaType);
                InsnList before = new InsnList();
                before.add(new InsnNode(Opcodes.DUP));
                before.add(new LdcInsnNode("Cannot read field \"" + site.name + "\" because the object is null"));
                before.add(siteConstant(site));
                before.add(call("nn", "(Ljava/lang/Object;Ljava/lang/String;I)V"));
                before.add(new InsnNode(Opcodes.DUP));
                list.insertBefore(insn, before);
                InsnList after = new InsnList();
                after.add(siteConstant(site));
                after.add(call("rg" + t, "(Ljava/lang/Object;" + (t == 'A' ? "Ljava/lang/Object;" : String.valueOf(t)) + "I)"
                        + (t == 'A' ? "Ljava/lang/Object;" : String.valueOf(t))));
                if (t == 'A') after.add(new TypeInsnNode(Opcodes.CHECKCAST, internalName(site.javaType)));
                list.insert(insn, after);
            }
            case FIELD_PUT -> {
                // Value to a spare local; null check; putfield; record the write with the stored value.
                char t = fieldKind(site.javaType);
                int store = switch (t) { case 'J' -> Opcodes.LSTORE; case 'F' -> Opcodes.FSTORE; case 'D' -> Opcodes.DSTORE; case 'A' -> Opcodes.ASTORE; default -> Opcodes.ISTORE; };
                int load = store - (Opcodes.ISTORE - Opcodes.ILOAD);
                int spare = m.maxLocals; // beyond every local of the method; frames are recomputed
                InsnList before = new InsnList();
                before.add(new VarInsnNode(store, spare));
                before.add(new InsnNode(Opcodes.DUP));
                before.add(new LdcInsnNode("Cannot assign field \"" + site.name + "\" because the object is null"));
                before.add(siteConstant(site));
                before.add(call("nn", "(Ljava/lang/Object;Ljava/lang/String;I)V"));
                before.add(new InsnNode(Opcodes.DUP));
                before.add(new VarInsnNode(load, spare));
                list.insertBefore(insn, before);
                InsnList after = new InsnList();
                after.add(new VarInsnNode(load, spare));
                after.add(siteConstant(site));
                after.add(call("wp" + t, "(Ljava/lang/Object;" + (t == 'A' ? "Ljava/lang/Object;" : String.valueOf(t)) + "I)V"));
                list.insert(insn, after);
            }
            case SWITCH -> {
                InsnList before = new InsnList();
                before.add(siteConstant(site));
                before.add(call("sw", "(II)I"));
                list.insertBefore(insn, before);
            }
            case ARITH, NEG -> {
                int op = insn.getOpcode();
                char t = op >= Opcodes.ISHL ? ((op - Opcodes.ISHL) % 2 == 0 ? 'I' : 'J') : "IJFD".charAt((op - Opcodes.IADD) % 4);
                boolean shift = op >= Opcodes.ISHL && op <= Opcodes.LUSHR;
                String desc = match.item().expect() == Expect.NEG ? "(" + t + "I)" + t
                        : "(" + t + (shift ? 'I' : t) + "I)" + t;
                replace(list, insn, site, ARITHMETIC_NAMES.get(op - Opcodes.IADD), desc);
            }
            case CONVERT -> {
                // One helper performs the whole cast (one or two JVM conversions) and records it.
                char from = descriptor(site.fromType), to = descriptor(site.javaType);
                InsnList before = new InsnList();
                if (to == 'I') before.add(new LdcInsnNode((int) narrowCode(site.javaType)));
                before.add(siteConstant(site));
                list.insertBefore(insn, before);
                if (match.item().literalLength() == 2) list.remove(nextInstruction(insn)); // the folded i2b/i2c/i2s
                list.set(insn, call("cv" + from + to, "(" + from + (to == 'I' ? "I" : "") + "I)" + to));
            }
            case CMP -> {
                int op = insn.getOpcode();
                int jump = nextInstruction(insn).getOpcode();
                InsnList before = new InsnList();
                if (op != Opcodes.LCMP) before.add(new LdcInsnNode(op));
                before.add(new LdcInsnNode(jump));
                before.add(siteConstant(site));
                list.insertBefore(insn, before);
                list.set(insn, op == Opcodes.LCMP ? call("jlcmp", "(JJII)I")
                        : op <= Opcodes.FCMPG ? call("jfcmp", "(FFIII)I") : call("jdcmp", "(DDIII)I"));
            }
            case REGION -> {
                // After the label (and its line/frame nodes) so every entry, including jumps, is recorded once.
                AbstractInsnNode at = insn.getNext();
                while (at != null && at.getOpcode() < 0) at = at.getNext();
                InsnList marker = new InsnList();
                marker.add(siteConstant(site));
                marker.add(new InsnNode(site.mayWriteArrays ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
                marker.add(call("region", "(IZ)V"));
                if (at == null) list.add(marker); else list.insertBefore(at, marker);
            }
            case JUMP -> {
                // long/float/double: the comparison helper already recorded the decision.
                if (List.of("long", "float", "double").contains(site.operandType)) return;
                int op = insn.getOpcode();
                String name, desc;
                if (op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ICMPLE) { name = "jcmp"; desc = "(IIII)Z"; }
                else if (op >= Opcodes.IFEQ && op <= Opcodes.IFLE) { name = "jzero"; desc = "(III)Z"; }
                else if (op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) { name = "jref"; desc = "(Ljava/lang/Object;Ljava/lang/Object;II)Z"; }
                else { name = "jnull"; desc = "(Ljava/lang/Object;II)Z"; }
                InsnList before = new InsnList();
                before.add(new LdcInsnNode(op));
                before.add(siteConstant(site));
                before.add(call(name, desc));
                list.insertBefore(insn, before);
                list.set(insn, new JumpInsnNode(Opcodes.IFNE, ((JumpInsnNode) insn).label));
            }
            default -> { }
        }
    }

    /** Boxes a primitive on the stack (a reference stays as it is). */
    private static void box(InsnList list, Type type) {
        String owner = switch (type.getSort()) {
            case Type.INT -> "java/lang/Integer"; case Type.LONG -> "java/lang/Long"; case Type.DOUBLE -> "java/lang/Double";
            case Type.FLOAT -> "java/lang/Float"; case Type.CHAR -> "java/lang/Character"; case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.SHORT -> "java/lang/Short"; case Type.BYTE -> "java/lang/Byte"; default -> null;
        };
        if (owner != null) list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", "(" + type.getDescriptor() + ")L" + owner + ";", false));
    }

    /** Recorder helper suffix for a field type: I (int-sized), J, F, D or A (reference). */
    private static char fieldKind(String type) {
        return switch (Analyzer.stack(type)) {
            case "int" -> 'I'; case "long" -> 'J'; case "float" -> 'F'; case "double" -> 'D'; default -> 'A';
        };
    }

    /** Narrowing applied after an int-producing conversion: byte, short, char or none. */
    private static char narrowCode(String type) {
        return switch (type) { case "byte" -> 'B'; case "short" -> 'S'; case "char" -> 'C'; default -> 'I'; };
    }

    private static void replace(InsnList list, AbstractInsnNode insn, Site site, String name, String desc) {
        list.insertBefore(insn, siteConstant(site));
        list.set(insn, call(name, desc));
    }

    private static AbstractInsnNode siteConstant(Site site) { return new LdcInsnNode(site.id); }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, RT, name, desc, false);
    }
}
