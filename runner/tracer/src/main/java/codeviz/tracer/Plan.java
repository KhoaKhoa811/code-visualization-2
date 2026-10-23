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
    enum Expect { STORE, IINC, ALOAD, ASTORE, ARRAYLENGTH, NEWARRAY, NEWARRAY_LITERAL, LITERAL_STORE, ARITH, NEG, CONVERT, CMP, JUMP,
        CALL, RETURN, NEW, SUPER, CTOR, FIELD_GET, FIELD_PUT, LIB, LIBNEW, APPEND, TOSTRING, BOX, UNBOX,
        SWITCH, GETSTATIC, INVOKE, REGION, THROW, ITER, NEXT, LAMBDA }

    enum Kind { DECLARE, ASSIGN, UPDATE, ALLOCATE, ARRAY_READ, ARRAY_LENGTH, ARRAY_WRITE, ARITHMETIC, CAST, LEAF, SWITCH, REGION,
        METHOD, PARAM, CALL, RETURN, CLASS, FIELD, FIELD_READ, FIELD_WRITE, LIBRARY, CONCAT, CONCAT_ARG,
        THROW, CATCH, FINALLY, EXCEPTION_CLASS, ITERATE, NEXT, LAMBDA }

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
        boolean mayAdvanceIterators;        // Part 12c: the statement uses an iterator variable
        int crtStart = -1, crtEnd = -1;     // javac CharacterRangeTable positions (line << 10 | column)
        int crtFirstStart = -1, crtFirstEnd = -1; // try-with-resources: its code starts with the first resource's own entry
        // Part 8b. ARITHMETIC: javaType is the operation type; CAST: fromType -> javaType.
        String fromType;
        String operand;                     // ASSIGN via iinc: the source constant (x += c)
        int delta;                          // ASSIGN via iinc: the iinc increment
        boolean viaArith;                   // UPDATE of a non-int local: arithmetic then store
        final List<Site> declareBefore = new ArrayList<>(); // declarations without initializer that precede this site
        // Part 8c. Array sites: javaType is the array type; ALLOCATE dims is the number of dimension
        // lengths (0 for an initializer). SWITCH: javaType is the selector type, cases in source order.
        int dims;
        List<Case> cases = List.of();
        // Part 8d. METHOD: methodId, javaType = result type ("void"), params, scopes = [root scope];
        // PARAM: a parameter's declaration; CALL: callee is the METHOD site; RETURN: implicit for a void
        // method's closing brace; REGION in a helper: returnSite for returns inside it.
        String methodId;
        List<Site> params = List.of();
        Site callee, returnSite;
        boolean implicit;
        // Part 8e. METHOD: owner (declaring class javaType), instance (params[0] is this), constructor.
        // CLASS: javaType, fields (FIELD sites in declaration order). FIELD/FIELD_READ/FIELD_WRITE: name is
        // the field, javaType its type, owner the declaring class.
        String owner;
        boolean instance, constructor;
        List<Site> fields = List.of();
        // Part 8h. THROW: a throw statement (javaType the thrown static type). CATCH: a catch parameter's
        // declaration; caught lists the binary names of its types. FINALLY: the store of the exception
        // in flight at the start of a finally handler. EXCEPTION_CLASS: a Throwable class of the source,
        // name its binary name and owner its superclass's binary name.
        List<String> caught = List.of();
        // Part 10d. ITERATE: a for-each over a collection (javaType) or a map view; name is the view
        // (elements, keys, values, entries), operator interface/virtual for the call it records before.
        // NEXT: one next() of that loop; callee is its ITERATE site, javaType the element's static type.
        // LAMBDA: one lambda or method reference creation; javaType its functional type, name its label,
        // callee the METHOD site it runs (a lambda body or a helper of Main), or for a JDK method reference
        // methodId and operator (static, instance, print). METHOD: lambda marks a lambda body, whose
        // synthetic name the instrumenter learns. LIBRARY: callbacks when the call may run functions.
        boolean lambda, callbacks;
        // Part 12b. LIBRARY: varargs when the call passes its last parameter as separate arguments
        // (String.format, Stream.of); they are recorded and shown one by one.
        boolean varargs;

        Site(int id, Kind kind, int start, int end, List<String> scopes) {
            this.id = id; this.kind = kind; this.start = start; this.end = end;
            this.scopes = List.copyOf(scopes);
        }
    }

    /** One switch case: its folded constants (none for default) and its label's source range. */
    record Case(List<Integer> values, boolean isDefault, int start, int end) { }

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
