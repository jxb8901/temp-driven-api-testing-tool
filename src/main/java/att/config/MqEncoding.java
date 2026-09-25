/* Author: Jeffrey + ChatGPT */
package att.config;

/** IBM MQ message encoding validation shared by descriptor loading and tests. */
public final class MqEncoding {
    private static final int INTEGER_MASK = 0x0000000f;
    private static final int DECIMAL_MASK = 0x000000f0;
    private static final int FLOAT_MASK = 0x00000f00;
    private static final int RESERVED_MASK = 0xfffff000;

    private MqEncoding() {}

    /**
     * IBM MQ encodings are formed by adding one legal integer, decimal, and
     * floating-point representation.  Zero means that the corresponding
     * representation is undefined; the complete native value (273/546/etc.)
     * is therefore valid as well.
     */
    public static boolean isValid(int value) {
        if (value < 0 || (value & RESERVED_MASK) != 0) return false;
        return validInteger(value & INTEGER_MASK)
                && validDecimal(value & DECIMAL_MASK)
                && validFloat(value & FLOAT_MASK);
    }

    private static boolean validInteger(int value) { return value == 0 || value == 1 || value == 2; }
    private static boolean validDecimal(int value) { return value == 0 || value == 0x10 || value == 0x20; }
    private static boolean validFloat(int value) {
        return value == 0 || value == 0x100 || value == 0x200 || value == 0x300 || value == 0x400;
    }
}
