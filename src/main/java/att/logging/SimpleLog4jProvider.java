/* Author: Jeffrey + ChatGPT */
package att.logging;

/** Selects Log4j API's built-in SimpleLogger without shipping log4j-core. */
public final class SimpleLog4jProvider extends org.apache.logging.log4j.spi.Provider {
    public SimpleLog4jProvider() {
        super(Integer.valueOf(1), "2.6.0", org.apache.logging.log4j.simple.SimpleLoggerContextFactory.class);
    }
}
