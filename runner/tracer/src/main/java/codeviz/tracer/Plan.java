package codeviz.tracer;

import java.util.ArrayList;
import java.util.List;

/**
 * Static facts produced by {@link Analyzer} and trusted by {@link TraceBuilder}.
 * The user JVM only reports a site number plus values; everything else about an
 * operation (range, operator, declaration, scope) comes from here.
 */
final class Plan {
    private Plan() { }

    /** Relevant bytecode instruction families, in javac code-layout order. */
    enum Expect { STORE, IINC, IALOAD, IASTORE, ARRAYLENGTH, NEWARRAY, NEWARRAY_LITERAL, ARITH, INEG, JUMP, GETSTATIC, INVOKE, REGION }

    enum Kind { DECLARE, ASSIGN, UPDATE, ALLOCATE, ARRAY_READ, ARRAY_LENGTH, ARRAY_WRITE, ARITHMETIC, LEAF, REGION }

    /** One instrumented operation site. Fields not relevant to a kind stay null. */
    static final class Site {
        final int id;
        final Kind kind;
        final int start, end;
        final List<String> scopes;          // static scope chain, outer to inner
        String operator;                    // + - * / % ; < <= ... ; ++ --
        String position;                    // prefix / postfix
        String declarationId, name, javaType;
        Cond leaf;                          // condition leaf node
        String operandType;                 // int / boolean / ref, for leaves
        int jumpOpcode = -1;                // set when matched
        boolean jumpMeansTrue;              // set when matched
        // REGION (Part 8a): a statement that runs without capture, and what it may change.
        List<String> unknownDeclarations = List.of(); // covered locals the statement may assign
        boolean mayWriteArrays;
        int crtStart = -1, crtEnd = -1;     // javac CharacterRangeTable positions (line << 10 | column)

        Site(int id, Kind kind, int start, int end, List<String> scopes) {
            this.id = id; this.kind = kind; this.start = start; this.end = end;
            this.scopes = List.copyOf(scopes);
        }
    }

    /** Condition tree evaluated with javac jumps; operator results derive from leaves. */
    static final class Cond {
        enum Op { AND, OR, NOT, LEAF }
        final Op op;
        final int start, end;
        final List<Cond> children = new ArrayList<>();
        Cond parent;
        Site site;                          // LEAF only
        String sourceOp;                    // comparison operator, null for a boolean variable

        Cond(Op op, int start, int end) { this.op = op; this.start = start; this.end = end; }

        Cond root() { return parent == null ? this : parent.root(); }

        Cond leftmostLeaf() { return op == Op.LEAF ? this : children.get(0).leftmostLeaf(); }
    }

    /** One expected relevant instruction. */
    record Item(Expect expect, Site site, int literalLength, String member) {
        static Item of(Expect expect, Site site) { return new Item(expect, site, 0, null); }
    }

    record Diagnostic(String phase, String code, String message, Integer start, Integer end) { }
}
