/*
 * Author: Jeffrey + ChatGPT
 */
package com.company.apitest.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Shared escaping and stable Unicode-safe HTML identifiers. */
final class HtmlSupport {
    private HtmlSupport() {}
    static String escape(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    static String id(String value) {
        String readable = value == null ? "item" : value.replaceAll("[^\\p{L}\\p{N}._-]", "-");
        return readable + "-" + hash(value == null ? "" : value).substring(0, 10);
    }
    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b & 0xff)); return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
