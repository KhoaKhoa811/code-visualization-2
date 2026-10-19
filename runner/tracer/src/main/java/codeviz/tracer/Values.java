package codeviz.tracer;

import java.util.Map;

/**
 * Raw record tokens for primitive values (Part 8b) and their trace-v1 typed form.
 * Integral values are decimal; float and double are raw IEEE-754 bits in hex, so
 * nothing is lost to decimal formatting. The builder recomputes every recorded
 * result with Java semantics here; a disagreement is an inconsistency.
 */
final class Values {
    private Values() { }

    static Map<String, Object> typed(String type, String token) {
        return switch (type) {
            case "int" -> TraceBuilder.ordered("kind", "int", "value", Integer.toString(Integer.parseInt(token)));
            case "byte", "short" -> {
                int v = Integer.parseInt(token);
                if (type.equals("byte") ? v != (byte) v : v != (short) v) throw new IllegalArgumentException(type + " range");
                yield TraceBuilder.ordered("kind", type, "value", Integer.toString(v));
            }
            case "char" -> {
                int v = Integer.parseInt(token);
                if (v != (char) v) throw new IllegalArgumentException("char range");
                yield TraceBuilder.ordered("kind", "char", "value", v);
            }
            case "long" -> TraceBuilder.ordered("kind", "long", "value", Long.toString(Long.parseLong(token)));
            case "float" -> TraceBuilder.ordered("kind", "float", "bits", bits(token, 8));
            case "double" -> TraceBuilder.ordered("kind", "double", "bits", bits(token, 16));
            case "boolean" -> {
                if (!token.equals("0") && !token.equals("1")) throw new IllegalArgumentException("boolean value");
                yield TraceBuilder.ordered("kind", "boolean", "value", token.equals("1"));
            }
            default -> throw new IllegalArgumentException("type " + type);
        };
    }

    private static String bits(String token, int width) {
        if (token.length() != width || !token.matches("[0-9a-f]+")) throw new IllegalArgumentException("bits");
        return token;
    }

    static float f(String token) { return Float.intBitsToFloat(Integer.parseUnsignedInt(bits(token, 8), 16)); }
    static double d(String token) { return Double.longBitsToDouble(Long.parseUnsignedLong(bits(token, 16), 16)); }
    static String f(float v) { return pad(Integer.toHexString(Float.floatToRawIntBits(v)), 8); }
    static String d(double v) { return pad(Long.toHexString(Double.doubleToRawLongBits(v)), 16); }
    private static String pad(String s, int width) { return "0".repeat(width - s.length()) + s; }

    /** Whether two tokens of a type denote the same recorded value (any NaN matches any NaN). */
    static boolean same(String type, String a, String b) {
        return switch (type) {
            case "float" -> a.equals(b) || Float.isNaN(f(a)) && Float.isNaN(f(b));
            case "double" -> a.equals(b) || Double.isNaN(d(a)) && Double.isNaN(d(b));
            case "long" -> Long.parseLong(a) == Long.parseLong(b);
            default -> Integer.parseInt(a) == Integer.parseInt(b);
        };
    }

    /** Java result of a binary operation in {@code type} (shift distances are int); null if it throws. */
    static String compute(String op, String type, String l, String r) {
        switch (type) {
            case "int", "boolean" -> {
                int x = Integer.parseInt(l), y = Integer.parseInt(r);
                if ((op.equals("/") || op.equals("%")) && y == 0) return null;
                return Integer.toString(switch (op) {
                    case "+" -> x + y; case "-" -> x - y; case "*" -> x * y; case "/" -> x / y; case "%" -> x % y;
                    case "&" -> x & y; case "|" -> x | y; case "^", "~" -> x ^ y;
                    case "<<" -> x << y; case ">>" -> x >> y; default -> x >>> y;
                });
            }
            case "long" -> {
                long x = Long.parseLong(l), y = Long.parseLong(r);
                if ((op.equals("/") || op.equals("%")) && y == 0) return null;
                int s = (int) y;
                return Long.toString(switch (op) {
                    case "+" -> x + y; case "-" -> x - y; case "*" -> x * y; case "/" -> x / y; case "%" -> x % y;
                    case "&" -> x & y; case "|" -> x | y; case "^", "~" -> x ^ y;
                    case "<<" -> x << s; case ">>" -> x >> s; default -> x >>> s;
                });
            }
            case "float" -> {
                float x = f(l), y = f(r);
                return f(switch (op) { case "+" -> x + y; case "-" -> x - y; case "*" -> x * y; case "/" -> x / y; default -> x % y; });
            }
            default -> {
                double x = d(l), y = d(r);
                return d(switch (op) { case "+" -> x + y; case "-" -> x - y; case "*" -> x * y; case "/" -> x / y; default -> x % y; });
            }
        }
    }

    static String negate(String type, String v) {
        return switch (type) {
            case "long" -> Long.toString(-Long.parseLong(v));
            case "float" -> f(-f(v));
            case "double" -> d(-d(v));
            default -> Integer.toString(-Integer.parseInt(v));
        };
    }

    /** Java primitive conversion of a token from one type to another. */
    static String cast(String from, String token, String to) {
        String stack = Analyzer.stack(from);
        long asLong; int asInt; float asFloat; double asDouble;
        switch (stack) {
            case "long" -> { long v = Long.parseLong(token); asLong = v; asInt = (int) v; asFloat = v; asDouble = v; }
            case "float" -> { float v = f(token); asLong = (long) v; asInt = (int) v; asFloat = v; asDouble = v; }
            case "double" -> { double v = d(token); asLong = (long) v; asInt = (int) v; asFloat = (float) v; asDouble = v; }
            default -> { int v = Integer.parseInt(token); asLong = v; asInt = v; asFloat = v; asDouble = v; }
        }
        return switch (to) {
            case "byte" -> Integer.toString((byte) asInt);
            case "short" -> Integer.toString((short) asInt);
            case "char" -> Integer.toString((char) asInt);
            case "int" -> Integer.toString(asInt);
            case "long" -> Long.toString(asLong);
            case "float" -> f(asFloat);
            default -> d(asDouble);
        };
    }
}
