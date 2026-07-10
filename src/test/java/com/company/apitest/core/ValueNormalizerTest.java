/* Author: Jeffrey + ChatGPT */
package com.company.apitest.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValueNormalizerTest {
    @Test void normalizesDocumentedBlankMarkersOnly() {
        assertEquals("", ValueNormalizer.normalize(" N/A "));
        assertEquals("", ValueNormalizer.normalize("NONE"));
        assertEquals("business", ValueNormalizer.normalize(" business "));
    }
}
