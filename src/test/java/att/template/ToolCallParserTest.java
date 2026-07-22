/* Author: Jeffrey + ChatGPT */
package att.template;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ToolCallParserTest {
    @Test void parsesQuotedCommasNestedContextAndTypedLiterals() {
        ToolCallParser parser = new ToolCallParser();
        ToolCallParser.ParsedCall call = parser.parse("#{send(message='hello, world', count=12.5, enabled=true, ref=${CASE.caseId})}");
        assertEquals("send", call.name());
        assertEquals(4, call.arguments().size());
        assertEquals("'hello, world'", call.arguments().get(0).expression());
        assertEquals(new BigDecimal("12.5"), parser.literal("12.5"));
        assertEquals(Boolean.TRUE, parser.literal("true"));
    }

    @Test void rejectsUnbalancedCalls() {
        assertThrows(IllegalArgumentException.class, () -> new ToolCallParser().parse("#{send(message='open)}"));
    }

    @Test void keepsInlineDbParameterListAsOneNamedArgument() {
        ToolCallParser parser = new ToolCallParser();
        ToolCallParser.ParsedCall call = parser.parse(
                "#{db.orders.query(sql='select ?', params=[${CASE.id}, 'OPEN', 12.5])}");
        assertEquals(2, call.arguments().size());
        assertEquals("params", call.arguments().get(1).key());
        assertFalse(call.arguments().get(1).positional());
        assertEquals(3, parser.listItems(call.arguments().get(1).expression()).size());
    }
}
