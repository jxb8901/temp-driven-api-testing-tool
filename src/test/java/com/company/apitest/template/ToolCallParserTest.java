/* Author: Jeffrey + ChatGPT */
package com.company.apitest.template;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ToolCallParserTest {
    @Test void parsesQuotedCommasNestedContextAndTypedLiterals() {
        ToolCallParser parser = new ToolCallParser();
        ToolCallParser.ParsedCall call = parser.parse("#{send(message='hello, world', count=12.5, enabled=true, ref=${CASE.id})}");
        assertEquals("send", call.name());
        assertEquals(4, call.arguments().size());
        assertEquals("'hello, world'", call.arguments().get(0).expression());
        assertEquals(new BigDecimal("12.5"), parser.literal("12.5"));
        assertEquals(Boolean.TRUE, parser.literal("true"));
    }

    @Test void rejectsUnbalancedCalls() {
        assertThrows(IllegalArgumentException.class, () -> new ToolCallParser().parse("#{send(message='open)}"));
    }
}
