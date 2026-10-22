package codeviz.tracer;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Trusted mirrors of library objects (Part 8f). The builder replays every recorded library
 * call on a mirror made of the same JDK classes, so it checks each recorded result and derives
 * the new contents itself instead of trusting the user JVM. Mirror elements are boxed values,
 * {@link Str} for strings (text semantics, identity remembered), {@link Ref} for traced objects
 * and arrays (identity), and other mirrors for nested collections.
 */
final class Library {
    private Library() { }

    /** A string inside a mirror: equal, hashed and ordered by its text, like String. */
    record Str(String text, String id) implements Comparable<Str>, CharSequence {
        @Override public boolean equals(Object o) { return o instanceof Str s && s.text.equals(text); }
        @Override public int hashCode() { return text.hashCode(); }
        @Override public int compareTo(Str o) { return text.compareTo(o.text); }
        @Override public String toString() { return text; }
        // Part 10d: Collectors.joining appends its elements as character sequences.
        @Override public int length() { return text.length(); }
        @Override public char charAt(int index) { return text.charAt(index); }
        @Override public CharSequence subSequence(int start, int end) { return text.subSequence(start, end); }
    }

    /** A traced object or array inside a mirror: equal only to itself (classes without equals). */
    record Ref(String id) { }

    static final Set<String> CLASSES = Set.of("java.util.ArrayList", "java.util.LinkedList", "java.util.ArrayDeque",
            "java.util.Stack", "java.util.HashMap", "java.util.TreeMap", "java.util.LinkedHashMap", "java.util.HashSet",
            "java.util.TreeSet", "java.util.LinkedHashSet", "java.util.PriorityQueue", "java.lang.StringBuilder", "java.util.Scanner",
            "java.io.BufferedReader");

    /** Empty mirror of a covered library class. */
    static Object create(String className) throws ReflectiveOperationException {
        if (!CLASSES.contains(className) || className.equals("java.util.Scanner") || className.equals("java.io.BufferedReader")) throw new IllegalArgumentException("library class " + className);
        return Class.forName(className).getConstructor().newInstance();
    }

    /** Sorted classes that may be built with {@code Collections.reverseOrder()} (Part 10d). */
    static final Set<String> REVERSIBLE = Set.of("java.util.PriorityQueue", "java.util.TreeMap", "java.util.TreeSet");

    /** Empty mirror ordered by the JDK's reverse natural order, the only comparator a mirror may hold (Part 10d). */
    static Object createReversed(String className) throws ReflectiveOperationException {
        if (!REVERSIBLE.contains(className)) throw new IllegalArgumentException("reversed library class " + className);
        return Class.forName(className).getConstructor(java.util.Comparator.class).newInstance(java.util.Collections.reverseOrder());
    }

    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final Map<String, Class<?>> PRIMITIVES = Map.of("int", int.class, "long", long.class, "double", double.class,
            "float", float.class, "char", char.class, "boolean", boolean.class, "short", short.class, "byte", byte.class);

    /** The JDK method a plan's method id names: {@code java.util.List.add(java.lang.Object)}. */
    static Method method(String methodId) throws ReflectiveOperationException {
        Method cached = METHODS.get(methodId);
        if (cached != null) return cached;
        int open = methodId.indexOf('('), dot = methodId.lastIndexOf('.', open);
        String owner = methodId.substring(0, dot), name = methodId.substring(dot + 1, open);
        String params = methodId.substring(open + 1, methodId.length() - 1);
        Class<?>[] types = params.isEmpty() ? new Class<?>[0] : java.util.Arrays.stream(params.split(","))
                .map(p -> { try { return type(p); } catch (ClassNotFoundException e) { throw new IllegalArgumentException(p); } })
                .toArray(Class<?>[]::new);
        // Part 10d: a nested owner (java.util.Map.Entry) has a binary name with '$'.
        Class<?> ownerClass = owner.equals("java.util.Map.Entry") ? java.util.Map.Entry.class : Class.forName(owner);
        Method method = ownerClass.getMethod(name, types);
        METHODS.put(methodId, method);
        return method;
    }

    /** A parameter type name: a primitive, a class, or (Part 10d) an array of either. */
    private static Class<?> type(String name) throws ClassNotFoundException {
        if (name.endsWith("[]")) return type(name.substring(0, name.length() - 2)).arrayType();
        return PRIMITIVES.containsKey(name) ? PRIMITIVES.get(name) : Class.forName(name);
    }
}
