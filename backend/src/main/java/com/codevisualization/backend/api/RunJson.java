package com.codevisualization.backend.api;

import com.codevisualization.backend.runner.RunSupervisor;
import com.codevisualization.backend.runner.TracedRunLifecycle;
import java.nio.charset.StandardCharsets;

/** Writes run-v1 snapshots, the output view and fixed errors; never echoes request text. */
final class RunJson {
    private RunJson() { }

    static byte[] snapshot(RunSupervisor.Snapshot s) {
        var b = new StringBuilder(s.trace() == null ? 512 : s.trace().length() + 512);
        b.append("{\"schemaVersion\":\"1.1.0\",\"runId\":");
        quote(b, s.runId());
        b.append(",\"sourceSha256\":");
        quote(b, s.sourceSha256());
        b.append(",\"input\":{\"mode\":\"").append(s.inputMode()).append("\",\"sha256\":"); // Part 10a
        if (s.inputSha256() == null) b.append("null"); else quote(b, s.inputSha256());
        b.append("},\"revision\":").append(s.revision())
                .append(",\"phase\":\"").append(s.phase().wire()).append('"')
                .append(",\"terminationConfirmed\":").append(s.terminationConfirmed())
                .append(",\"cleanupVerified\":").append(s.cleanupVerified())
                .append(",\"slotHeld\":").append(s.slotHeld())
                .append(",\"trace\":").append(s.trace() == null ? "null" : s.trace()) // trusted trace-v1 JSON
                .append(",\"infrastructureFailures\":[");
        for (int i = 0; i < s.infrastructureFailures().size(); i++) {
            if (i > 0) b.append(',');
            quote(b, s.infrastructureFailures().get(i));
        }
        return b.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Console bytes decoded as UTF-8; malformed sequences become U+FFFD. */
    static byte[] output(String runId, TracedRunLifecycle.Result result) {
        var b = new StringBuilder("{\"runId\":");
        quote(b, runId);
        b.append(",\"stdout\":");
        quote(b, new String(result.stdout(), StandardCharsets.UTF_8));
        b.append(",\"stderr\":");
        quote(b, new String(result.stderr(), StandardCharsets.UTF_8));
        return b.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] error(String code) {
        return ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
