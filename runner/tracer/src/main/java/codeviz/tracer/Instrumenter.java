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

    static byte[] instrument(byte[] classFile, List<Item> plan, Map<Integer, Site> sites, Map<String, byte[]> program)
            throws MappingException {
        ClassNode cn = new ClassNode();
        new CrtReader(classFile).accept(cn, new Attribute[]{new Crt()}, ClassReader.SKIP_FRAMES);
        rejectRecorderReferences(cn);
        MethodNode main = cn.methods.stream()
                .filter(m -> m.name.equals("main") && m.desc.equals("([Ljava/lang/String;)V")).findFirst()
                .orElseThrow(() -> new MappingException("main not found"));
        Map<Site, Region> regions = regions(main, plan);
        List<Match> matches = match(main, plan, regions);
        for (Match m : matches) rewrite(main, m);
        InsnList entry = new InsnList();
        entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.add(call("enter", "([Ljava/lang/String;)V"));
        main.instructions.insert(entry);
        for (AbstractInsnNode insn : main.instructions.toArray())
            if (insn.getOpcode() == Opcodes.RETURN) main.instructions.insertBefore(insn, call("exit", "()V"));
        // The range table only served mapping; it is not written back.
        for (MethodNode m : cn.methods) if (m.attrs != null) m.attrs.removeIf(Crt.class::isInstance);
        Hierarchy hierarchy = new Hierarchy(program);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String b) { return hierarchy.common(a, b); }
        };
        cn.accept(writer);
        return writer.toByteArray();
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
            Crt.Entry first = found.get(0);
            for (Crt.Entry e : found)
                if (e.start() != first.start() || e.end() != first.end()) throw new MappingException("ambiguous statement range");
            if (!(first.start().info instanceof LabelNode start) || !(first.end().info instanceof LabelNode end))
                throw new MappingException("statement range labels missing");
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

    private static List<Match> match(MethodNode main, List<Item> plan, Map<Site, Region> regions) throws MappingException {
        List<Match> matches = new ArrayList<>();
        Map<LabelNode, Site> regionStarts = new HashMap<>();
        for (var r : regions.entrySet()) regionStarts.put(r.getValue().start(), r.getKey());
        int p = 0;
        AbstractInsnNode literalStart = null;
        int literalRemaining = 0;
        Item literal = null;
        LabelNode skipUntil = null; // end of the uncaptured statement being skipped
        for (AbstractInsnNode insn : main.instructions.toArray()) {
            if (skipUntil != null) {
                if (insn != skipUntil) continue;
                skipUntil = null; // the end label may start the next region
            }
            if (insn instanceof LabelNode label && regionStarts.containsKey(label)) {
                Site site = regionStarts.get(label);
                if (literalRemaining > 0 || p >= plan.size() || plan.get(p).site() != site)
                    throw new MappingException("uncaptured statement out of plan order");
                matches.add(new Match(plan.get(p++), label, label));
                if (regions.get(site).end() != label) skipUntil = regions.get(site).end();
                continue;
            }
            Expect found = classify(insn);
            if (found == null) continue;
            if (literalRemaining > 0) {
                if (found != Expect.IASTORE) throw new MappingException("array literal interrupted at " + found);
                if (--literalRemaining == 0) matches.add(new Match(literal, literalStart, insn));
                continue;
            }
            if (p >= plan.size()) throw new MappingException("unplanned instruction " + found);
            Item item = plan.get(p++);
            if (item.expect() == Expect.NEWARRAY_LITERAL) {
                if (found != Expect.NEWARRAY) throw new MappingException("expected array literal, found " + found);
                literal = item; literalStart = insn; literalRemaining = item.literalLength();
                if (literalRemaining == 0) matches.add(new Match(item, insn, insn));
                continue;
            }
            if (found != item.expect()) throw new MappingException("expected " + item.expect() + ", found " + found);
            check(item, insn, main);
            if (item.site() != null) matches.add(new Match(item, insn, insn));
        }
        if (skipUntil != null || literalRemaining > 0 || p != plan.size()) throw new MappingException("plan not fully matched");
        return matches;
    }

    /** Relevant instruction family, or null for purely structural instructions. */
    private static Expect classify(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op < 0) return null; // labels, line numbers, frames
        return switch (op) {
            case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> Expect.STORE;
            case Opcodes.IINC -> Expect.IINC;
            case Opcodes.IALOAD -> Expect.IALOAD;
            case Opcodes.IASTORE -> Expect.IASTORE;
            case Opcodes.ARRAYLENGTH -> Expect.ARRAYLENGTH;
            case Opcodes.NEWARRAY -> ((IntInsnNode) insn).operand == Opcodes.T_INT ? Expect.NEWARRAY : Expect.INVOKE;
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
                 Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2, Opcodes.GOTO, Opcodes.RETURN, Opcodes.NOP -> null;
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
        switch (item.expect()) {
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
            case CONVERT -> {
                if (insn.getOpcode() != Opcodes.I2L + CONVERSIONS.indexOf(item.member()))
                    throw new MappingException("expected conversion " + item.member());
            }
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
            case IALOAD -> replace(list, insn, site, "iaload", "([III)I");
            case IASTORE -> replace(list, insn, site, "iastore", "([IIII)V");
            case ARRAYLENGTH -> replace(list, insn, site, "arraylength", "([II)I");
            case NEWARRAY -> replace(list, insn, site, "newIntArray", "(II)[I");
            case NEWARRAY_LITERAL -> {
                InsnList after = new InsnList();
                after.add(new InsnNode(Opcodes.DUP));
                after.add(siteConstant(site));
                after.add(call("literal", "([II)V"));
                list.insert(match.anchor(), after);
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
