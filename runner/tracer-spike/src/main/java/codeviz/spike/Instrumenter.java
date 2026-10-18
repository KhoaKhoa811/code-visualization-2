package codeviz.spike;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Part 7a spike: rewrites compiled user classes so each covered bytecode operation
 * calls {@code codeviz.rt.Recorder}. Replaced instructions are performed inside the
 * recorder helper exactly once; values are taken from the operand stack, never by
 * re-evaluating source expressions. Requires classes compiled with {@code javac -g}.
 *
 * Covered: method enter/normal exit/exceptional exit with distinct frame IDs, named
 * local stores and IINC, int[] reads/writes, reference-array writes, array allocation,
 * int comparisons (IF_ICMPxx and IFLT/IFGE/IFGT/IFLE). Not covered in the spike:
 * constructors, static initializers, object-array reads, non-int primitive arrays,
 * fields, long/float/double comparisons, string/collection operations.
 */
public final class Instrumenter {
    private static final String RT = "codeviz/rt/Recorder";

    public static void main(String[] args) throws IOException {
        instrumentDirectory(Path.of(args[0]), Path.of(args[1]));
    }

    public static void instrumentDirectory(Path in, Path out) throws IOException {
        try (Stream<Path> files = Files.walk(in)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                Path target = out.resolve(in.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.write(target, instrument(Files.readAllBytes(file)));
            }
        }
    }

    public static byte[] instrument(byte[] classFile) {
        ClassNode cn = new ClassNode();
        new ClassReader(classFile).accept(cn, ClassReader.SKIP_FRAMES);
        for (MethodNode mn : cn.methods) {
            if (mn.name.startsWith("<") || (mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            new MethodRewrite(cn.name, mn).run();
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String b) {
                // Spike shortcut: single-file programs without user class hierarchies.
                // Production must resolve user classes from the compiled class set.
                try { return super.getCommonSuperClass(a, b); }
                catch (RuntimeException unknownUserClass) { return "java/lang/Object"; }
            }
        };
        cn.accept(writer);
        return writer.toByteArray();
    }

    private static final class MethodRewrite {
        private final String owner;
        private final MethodNode mn;
        private final Map<AbstractInsnNode, Integer> index = new HashMap<>();
        private final int frameLocal;
        private int line = -1;

        MethodRewrite(String owner, MethodNode mn) {
            this.owner = owner; this.mn = mn;
            AbstractInsnNode[] original = mn.instructions.toArray();
            for (int i = 0; i < original.length; i++) index.put(original[i], i);
            frameLocal = mn.maxLocals++;
        }

        void run() {
            AbstractInsnNode[] original = mn.instructions.toArray();
            int firstLine = -1;
            for (AbstractInsnNode n : original) if (n instanceof LineNumberNode ln) { firstLine = ln.line; break; }

            for (AbstractInsnNode insn : original) {
                if (insn instanceof LineNumberNode ln) { line = ln.line; continue; }
                rewrite(insn);
            }

            // Prologue: frame ID, then parameters as named locals.
            InsnList prologue = new InsnList();
            prologue.add(new LdcInsnNode(owner.replace('/', '.') + "." + mn.name));
            prologue.add(new LdcInsnNode(firstLine));
            prologue.add(call("enter", "(Ljava/lang/String;I)I"));
            prologue.add(new VarInsnNode(Opcodes.ISTORE, frameLocal));
            LabelNode start = new LabelNode();
            prologue.add(start);
            line = firstLine;
            int slot = (mn.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
            for (Type arg : Type.getArgumentTypes(mn.desc)) {
                LocalVariableNode lv = parameter(slot);
                if (lv != null) prologue.add(recordLocal(slot, lv));
                slot += arg.getSize();
            }
            mn.instructions.insert(prologue);

            // Exceptional exit: outermost handler, after any user handlers.
            LabelNode end = new LabelNode(), handler = new LabelNode();
            mn.instructions.add(end);
            mn.instructions.add(handler);
            mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, frameLocal));
            mn.instructions.add(call("thrown", "(Ljava/lang/Throwable;I)Ljava/lang/Throwable;"));
            mn.instructions.add(new InsnNode(Opcodes.ATHROW));
            mn.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        }

        private void rewrite(AbstractInsnNode insn) {
            int op = insn.getOpcode();
            switch (op) {
                case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> {
                    int var = ((VarInsnNode) insn).var;
                    LocalVariableNode lv = localAfter(insn, var);
                    if (lv != null) mn.instructions.insert(insn, recordLocal(var, lv));
                }
                case Opcodes.IINC -> {
                    int var = ((IincInsnNode) insn).var;
                    LocalVariableNode lv = localAfter(insn, var);
                    if (lv != null) mn.instructions.insert(insn, recordLocal(var, lv));
                }
                case Opcodes.IASTORE -> replace(insn, "iastore", "([IIIII)V");
                case Opcodes.IALOAD -> replace(insn, "iaload", "([IIII)I");
                case Opcodes.AASTORE -> replace(insn, "aastore", "([Ljava/lang/Object;ILjava/lang/Object;II)V");
                case Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> {
                    InsnList after = new InsnList();
                    after.add(new InsnNode(Opcodes.DUP));
                    after.add(frameAndLine());
                    after.add(call("newArray", "(Ljava/lang/Object;II)V"));
                    mn.instructions.insert(insn, after);
                }
                case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                     Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE ->
                        branch((JumpInsnNode) insn, "icmp", "(IILjava/lang/String;II)Z");
                case Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE ->
                        branch((JumpInsnNode) insn, "izero", "(ILjava/lang/String;II)Z");
                case Opcodes.IRETURN -> {
                    InsnList before = new InsnList();
                    before.add(new LdcInsnNode(Type.getReturnType(mn.desc).getDescriptor()));
                    before.add(frameAndLine());
                    before.add(call("exitI", "(ILjava/lang/String;II)I"));
                    mn.instructions.insertBefore(insn, before);
                }
                case Opcodes.LRETURN -> beforeReturn(insn, "exitJ", "(JII)J", null);
                case Opcodes.DRETURN -> beforeReturn(insn, "exitD", "(DII)D", null);
                case Opcodes.ARETURN -> beforeReturn(insn, "exitA", "(Ljava/lang/Object;II)Ljava/lang/Object;",
                        Type.getReturnType(mn.desc).getInternalName());
                case Opcodes.RETURN -> beforeReturn(insn, "exit", "(II)V", null);
                default -> { }
            }
        }

        private void beforeReturn(AbstractInsnNode insn, String name, String desc, String cast) {
            InsnList before = new InsnList();
            before.add(frameAndLine());
            before.add(call(name, desc));
            if (cast != null && !cast.equals("java/lang/Object")) before.add(new TypeInsnNode(Opcodes.CHECKCAST, cast));
            mn.instructions.insertBefore(insn, before);
        }

        private void replace(AbstractInsnNode insn, String name, String desc) {
            mn.instructions.insertBefore(insn, frameAndLine());
            mn.instructions.set(insn, call(name, desc));
        }

        /** Same operands, same target: the helper returns the original jump decision. */
        private void branch(JumpInsnNode insn, String name, String desc) {
            InsnList before = new InsnList();
            before.add(new LdcInsnNode(opcodeName(insn.getOpcode())));
            before.add(frameAndLine());
            before.add(call(name, desc));
            mn.instructions.insertBefore(insn, before);
            mn.instructions.set(insn, new JumpInsnNode(Opcodes.IFNE, insn.label));
        }

        private InsnList recordLocal(int var, LocalVariableNode lv) {
            InsnList list = new InsnList();
            String desc = lv.desc;
            switch (desc.charAt(0)) {
                case 'Z', 'C', 'B', 'S', 'I' -> {
                    list.add(new VarInsnNode(Opcodes.ILOAD, var));
                    list.add(new LdcInsnNode(lv.name)); list.add(new LdcInsnNode(desc));
                    list.add(frameAndLine()); list.add(call("localI", "(ILjava/lang/String;Ljava/lang/String;II)V"));
                }
                case 'J' -> {
                    list.add(new VarInsnNode(Opcodes.LLOAD, var)); list.add(new LdcInsnNode(lv.name));
                    list.add(frameAndLine()); list.add(call("localJ", "(JLjava/lang/String;II)V"));
                }
                case 'F', 'D' -> {
                    if (desc.charAt(0) == 'F') { list.add(new VarInsnNode(Opcodes.FLOAD, var)); list.add(new InsnNode(Opcodes.F2D)); }
                    else list.add(new VarInsnNode(Opcodes.DLOAD, var));
                    list.add(new LdcInsnNode(lv.name)); list.add(frameAndLine());
                    list.add(call("localD", "(DLjava/lang/String;II)V"));
                }
                default -> {
                    list.add(new VarInsnNode(Opcodes.ALOAD, var)); list.add(new LdcInsnNode(lv.name));
                    list.add(frameAndLine()); list.add(call("localA", "(Ljava/lang/Object;Ljava/lang/String;II)V"));
                }
            }
            return list;
        }

        /** The variable whose scope covers the instruction after the store (javac starts scopes there). */
        private LocalVariableNode localAfter(AbstractInsnNode store, int var) {
            if (mn.localVariables == null) return null;
            int at = index.get(store);
            for (LocalVariableNode lv : mn.localVariables) {
                if (lv.index == var && index.get(lv.start) <= at + 1 && index.get(lv.end) > at) return lv;
            }
            return null; // Compiler temporary (for-each iterator, switch temp): not a user variable.
        }

        private LocalVariableNode parameter(int slot) {
            if (mn.localVariables == null) return null;
            LocalVariableNode best = null;
            for (LocalVariableNode lv : mn.localVariables)
                if (lv.index == slot && (best == null || index.get(lv.start) < index.get(best.start))) best = lv;
            return best;
        }

        private InsnList frameAndLine() {
            InsnList list = new InsnList();
            list.add(new VarInsnNode(Opcodes.ILOAD, frameLocal));
            list.add(new LdcInsnNode(line));
            return list;
        }

        private static MethodInsnNode call(String name, String desc) {
            return new MethodInsnNode(Opcodes.INVOKESTATIC, RT, name, desc, false);
        }
    }

    private static String opcodeName(int op) {
        return switch (op) {
            case Opcodes.IF_ICMPEQ -> "IF_ICMPEQ"; case Opcodes.IF_ICMPNE -> "IF_ICMPNE";
            case Opcodes.IF_ICMPLT -> "IF_ICMPLT"; case Opcodes.IF_ICMPGE -> "IF_ICMPGE";
            case Opcodes.IF_ICMPGT -> "IF_ICMPGT"; case Opcodes.IF_ICMPLE -> "IF_ICMPLE";
            case Opcodes.IFLT -> "IFLT"; case Opcodes.IFGE -> "IFGE";
            case Opcodes.IFGT -> "IFGT"; case Opcodes.IFLE -> "IFLE";
            default -> throw new IllegalArgumentException("opcode " + op);
        };
    }
}
