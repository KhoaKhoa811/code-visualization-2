package codeviz.tracer;

import codeviz.tracer.Plan.Expect;
import codeviz.tracer.Plan.Item;
import codeviz.tracer.Plan.Site;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
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

    static byte[] instrument(byte[] classFile, List<Item> plan, Map<Integer, Site> sites) throws MappingException {
        ClassNode cn = new ClassNode();
        new ClassReader(classFile).accept(cn, ClassReader.SKIP_FRAMES);
        rejectRecorderReferences(cn);
        MethodNode main = cn.methods.stream()
                .filter(m -> m.name.equals("main") && m.desc.equals("([Ljava/lang/String;)V")).findFirst()
                .orElseThrow(() -> new MappingException("main not found"));
        List<Match> matches = match(main, plan);
        for (Match m : matches) rewrite(main, m);
        InsnList entry = new InsnList();
        entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.add(call("enter", "([Ljava/lang/String;)V"));
        main.instructions.insert(entry);
        for (AbstractInsnNode insn : main.instructions.toArray())
            if (insn.getOpcode() == Opcodes.RETURN) main.instructions.insertBefore(insn, call("exit", "()V"));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String b) {
                // Only JDK types (String, int[]) meet in main's frames in this increment.
                try { return super.getCommonSuperClass(a, b); }
                catch (RuntimeException unknown) { return "java/lang/Object"; }
            }
        };
        cn.accept(writer);
        return writer.toByteArray();
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

    private static List<Match> match(MethodNode main, List<Item> plan) throws MappingException {
        List<Match> matches = new ArrayList<>();
        int p = 0;
        AbstractInsnNode literalStart = null;
        int literalRemaining = 0;
        Item literal = null;
        for (AbstractInsnNode insn : main.instructions.toArray()) {
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
        if (literalRemaining > 0 || p != plan.size()) throw new MappingException("plan not fully matched");
        return matches;
    }

    /** Relevant instruction family, or null for purely structural instructions. */
    private static Expect classify(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op < 0) return null; // labels, line numbers, frames
        return switch (op) {
            case Opcodes.ISTORE, Opcodes.ASTORE -> Expect.STORE;
            case Opcodes.IINC -> Expect.IINC;
            case Opcodes.IALOAD -> Expect.IALOAD;
            case Opcodes.IASTORE -> Expect.IASTORE;
            case Opcodes.ARRAYLENGTH -> Expect.ARRAYLENGTH;
            case Opcodes.NEWARRAY -> ((IntInsnNode) insn).operand == Opcodes.T_INT ? Expect.NEWARRAY : Expect.INVOKE;
            case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM -> Expect.ARITH;
            case Opcodes.INEG -> Expect.INEG;
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                 Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
                 Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.IFNULL, Opcodes.IFNONNULL -> Expect.JUMP;
            case Opcodes.GETSTATIC -> Expect.GETSTATIC;
            case Opcodes.ILOAD, Opcodes.ALOAD, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                 Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.ACONST_NULL,
                 Opcodes.DUP, Opcodes.GOTO, Opcodes.RETURN, Opcodes.NOP -> null;
            case Opcodes.LDC -> {
                Object c = ((LdcInsnNode) insn).cst;
                yield c instanceof Integer || c instanceof String ? null : Expect.INVOKE;
            }
            // Everything else (invokes, fields, other types, throw, switch...) must be planned
            // explicitly; only println invokes are, so anything else fails matching.
            default -> Expect.INVOKE;
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
                int delta = site.operator.equals("++") ? 1 : -1;
                if (lv == null || !lv.name.equals(site.name) || i.incr != delta) throw new MappingException("unexpected iinc");
            }
            case ARITH -> {
                int expected = switch (site.operator) {
                    case "+" -> Opcodes.IADD; case "-" -> Opcodes.ISUB; case "*" -> Opcodes.IMUL;
                    case "/" -> Opcodes.IDIV; default -> Opcodes.IREM;
                };
                if (insn.getOpcode() != expected) throw new MappingException("arithmetic operator mismatch");
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
            default -> opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ICMPLE;
        };
        if (tested == null || !family) return null;
        if (tested.equals(site.operator)) return Boolean.TRUE;
        if (tested.equals(negate(site.operator))) return Boolean.FALSE;
        return null;
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
                boolean ref = insn.getOpcode() == Opcodes.ASTORE;
                InsnList after = new InsnList();
                after.add(new VarInsnNode(ref ? Opcodes.ALOAD : Opcodes.ILOAD, var));
                after.add(siteConstant(site));
                after.add(call(ref ? "va" : "vi", ref ? "(Ljava/lang/Object;I)V" : "(II)V"));
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
            case ARITH -> {
                String name = switch (insn.getOpcode()) {
                    case Opcodes.IADD -> "iadd"; case Opcodes.ISUB -> "isub"; case Opcodes.IMUL -> "imul";
                    case Opcodes.IDIV -> "idiv"; default -> "irem";
                };
                replace(list, insn, site, name, "(III)I");
            }
            case INEG -> replace(list, insn, site, "ineg", "(II)I");
            case JUMP -> {
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

    private static void replace(InsnList list, AbstractInsnNode insn, Site site, String name, String desc) {
        list.insertBefore(insn, siteConstant(site));
        list.set(insn, call(name, desc));
    }

    private static AbstractInsnNode siteConstant(Site site) { return new LdcInsnNode(site.id); }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, RT, name, desc, false);
    }
}
