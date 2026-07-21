/*
 * Author: Jeffrey + ChatGPT
 */

package att.template;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies placeholder replacement used by XML and command templates.
 */
class TemplateRendererTest {
    @Test
    void replacesKnownPlaceholdersAndLeavesUnknownAsEmpty() {
        Map<String, Object> context = new HashMap<String, Object>();
        context.put("Case ID", "TC001");
        context.put("Amount", 100);

        assertEquals("case=TC001 amount=100 missing= id=TC001",
                TemplateRenderer.render("case=${Case ID} amount=${Amount} missing=${missing} id=#{upper(${Case ID})}", context));
    }
}
