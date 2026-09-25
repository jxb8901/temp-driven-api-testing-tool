/* Author: Jeffrey + ChatGPT */
package att.exec;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves IBM MQ CCSIDs to Java charset names without relying on unpadded aliases. */
public final class MqCcsid {
    private static final Map<Integer, String[]> ALIASES = aliases();

    private MqCcsid() {}

    public static Charset charset(int ccsid) {
        if (ccsid <= 0) throw unsupported(ccsid);
        String[] candidates = ALIASES.get(Integer.valueOf(ccsid));
        if (candidates == null) {
            String padded = String.format(java.util.Locale.ROOT, "%03d", Integer.valueOf(ccsid));
            candidates = new String[]{"IBM" + padded, "Cp" + padded};
        }
        for (String candidate : candidates) {
            try { return Charset.forName(candidate); }
            catch (Exception ignored) { /* Try the next Java alias. */ }
        }
        throw unsupported(ccsid);
    }

    private static IllegalArgumentException unsupported(int ccsid) {
        return new IllegalArgumentException("Unsupported IBM MQ CCSID: " + ccsid);
    }

    private static Map<Integer, String[]> aliases() {
        Map<Integer, String[]> result = new LinkedHashMap<Integer, String[]>();
        put(result, 37, "IBM037", "Cp037");
        put(result, 273, "IBM273", "Cp273");
        put(result, 277, "IBM277", "Cp277");
        put(result, 278, "IBM278", "Cp278");
        put(result, 280, "IBM280", "Cp280");
        put(result, 284, "IBM284", "Cp284");
        put(result, 285, "IBM285", "Cp285");
        put(result, 290, "IBM290", "Cp290");
        put(result, 297, "IBM297", "Cp297");
        put(result, 420, "IBM420", "Cp420");
        put(result, 424, "IBM424", "Cp424");
        put(result, 437, "IBM437", "Cp437");
        put(result, 500, "IBM500", "Cp500");
        put(result, 819, StandardCharsets.ISO_8859_1.name());
        put(result, 850, "IBM850", "Cp850");
        put(result, 852, "IBM852", "Cp852");
        put(result, 855, "IBM855", "Cp855");
        put(result, 857, "IBM857", "Cp857");
        put(result, 858, "IBM858", "Cp858");
        put(result, 860, "IBM860", "Cp860");
        put(result, 861, "IBM861", "Cp861");
        put(result, 862, "IBM862", "Cp862");
        put(result, 863, "IBM863", "Cp863");
        put(result, 864, "IBM864", "Cp864");
        put(result, 865, "IBM865", "Cp865");
        put(result, 866, "IBM866", "Cp866");
        put(result, 869, "IBM869", "Cp869");
        put(result, 870, "IBM870", "Cp870");
        put(result, 871, "IBM871", "Cp871");
        put(result, 874, "IBM874", "Cp874");
        put(result, 875, "IBM875", "Cp875");
        put(result, 880, "IBM880", "Cp880");
        put(result, 918, "IBM918", "Cp918");
        put(result, 921, "IBM921", "Cp921");
        put(result, 922, "IBM922", "Cp922");
        put(result, 923, "IBM923", "Cp923");
        put(result, 1025, "IBM1025", "Cp1025");
        put(result, 1026, "IBM1026", "Cp1026");
        put(result, 1047, "IBM1047", "Cp1047");
        put(result, 1122, "IBM1122", "Cp1122");
        put(result, 1123, "IBM1123", "Cp1123");
        put(result, 1124, "IBM1124", "Cp1124");
        put(result, 1125, "IBM1125", "Cp1125");
        put(result, 1130, "IBM1130", "Cp1130");
        put(result, 1132, "IBM1132", "Cp1132");
        put(result, 1133, "IBM1133", "Cp1133");
        put(result, 1137, "IBM1137", "Cp1137");
        for (int ccsid = 1140; ccsid <= 1149; ccsid++) put(result, ccsid, "IBM" + ccsid, "Cp" + ccsid);
        put(result, 1200, StandardCharsets.UTF_16.name());
        put(result, 1201, StandardCharsets.UTF_16BE.name());
        put(result, 1208, StandardCharsets.UTF_8.name());
        for (int ccsid = 1250; ccsid <= 1258; ccsid++) put(result, ccsid, "IBM" + ccsid, "Cp" + ccsid);
        put(result, 13488, StandardCharsets.UTF_16BE.name());
        put(result, 1350, "IBM1350", "Cp1350");
        put(result, 1364, "IBM1364", "Cp1364");
        put(result, 1370, "IBM1370", "Cp1370");
        put(result, 1371, "IBM1371", "Cp1371");
        put(result, 1381, "IBM1381", "Cp1381");
        put(result, 1383, "IBM1383", "Cp1383");
        put(result, 1386, "IBM1386", "Cp1386");
        put(result, 1388, "IBM1388", "Cp1388");
        put(result, 1390, "IBM1390", "Cp1390");
        put(result, 1399, "IBM1399", "Cp1399");
        return Collections.unmodifiableMap(result);
    }

    private static void put(Map<Integer, String[]> target, int ccsid, String... names) {
        target.put(Integer.valueOf(ccsid), names);
    }
}
