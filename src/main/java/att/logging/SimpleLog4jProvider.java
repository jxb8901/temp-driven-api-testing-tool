/* Author: Jeffrey + ChatGPT */
package att.logging;

import att.Version;

/** Selects Log4j API's built-in SimpleLogger without shipping log4j-core. */
public final class SimpleLog4jProvider extends org.apache.logging.log4j.spi.Provider {
    public SimpleLog4jProvider() {
        super(Integer.valueOf(1), Version.PRODUCT, org.apache.logging.log4j.simple.SimpleLoggerContextFactory.class);
    }
}
