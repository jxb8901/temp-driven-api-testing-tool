/* Author: Jeffrey + ChatGPT */
package att.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Simple CASE data helpers retained for isolated rendering tests. */
public final class Contexts {
    private Contexts() {}

    public static Map<String, Object> requestContext(TestCase testCase) {
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("CASE", new LinkedHashMap<String, Object>(testCase.caseData()));
        return context;
    }

    public static Map<String, Object> expectedContext(TestCase testCase) { return requestContext(testCase); }
}
