package att.core;

import java.io.PrintStream;
import java.util.function.Consumer;

/** Flushes each already-redacted Case-log append to an identifiable console stream. */
public final class CaseLogConsoleMirror implements Consumer<String> {
    private final String caseId;
    private final PrintStream output;

    public CaseLogConsoleMirror(String caseId, PrintStream output) {
        if (output == null) throw new IllegalArgumentException("Case-log console mirror requires an output stream");
        this.caseId = safeIdentity(caseId);
        this.output = output;
    }

    @Override public void accept(String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (output) {
            output.print("[CASE-LOG case=" + caseId + "] ");
            output.print(text);
            if (!text.endsWith("\n")) output.println();
            output.flush();
        }
    }

    private static String safeIdentity(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        return value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
}
