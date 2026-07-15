package passrs.browser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BrowserMessageCodec {

    private BrowserMessageCodec() {
    }

    static String encodeString(String value) {
        return java.util.Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String encodeBytes(byte[] value) {
        return java.util.Base64.getEncoder().encodeToString(value == null ? new byte[0] : value);
    }

    static String decodeString(String value) {
        if (isEmpty(value)) {
            return "";
        }
        return new String(java.util.Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
    }

    static byte[] decodeBytes(String value) {
        if (isEmpty(value)) {
            return new byte[0];
        }
        return java.util.Base64.getDecoder().decode(value);
    }

    static Map<String, String> parseKeyValueLines(String output) {
        Map<String, String> result = new LinkedHashMap<>();
        if (output == null || output.isBlank()) {
            return result;
        }
        for (String line : output.split("\\R")) {
            int index = line.indexOf('=');
            if (index <= 0) {
                continue;
            }
            result.put(line.substring(0, index), line.substring(index + 1));
        }
        return result;
    }

    static byte[] buildHttpResponseBytes(int status, String reason, List<String> headers, byte[] body) {
        byte[] bodyBytes = body == null ? new byte[0] : body;
        String safeReason = reason == null || reason.isBlank() ? "OK" : reason;
        StringBuilder headerBuilder = new StringBuilder()
                .append("HTTP/1.1 ")
                .append(status)
                .append(' ')
                .append(safeReason)
                .append("\r\n");

        if (headers != null) {
            for (String headerLine : headers) {
                int index = headerLine == null ? -1 : headerLine.indexOf(':');
                if (index <= 0) {
                    continue;
                }
                String name = headerLine.substring(0, index).trim();
                if ("transfer-encoding".equalsIgnoreCase(name)
                        || "content-encoding".equalsIgnoreCase(name)
                        || "content-length".equalsIgnoreCase(name)) {
                    continue;
                }
                headerBuilder.append(name).append(": ").append(headerLine.substring(index + 1).trim()).append("\r\n");
            }
        }
        headerBuilder.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        headerBuilder.append("\r\n");

        byte[] headerBytes = headerBuilder.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] responseBytes = new byte[headerBytes.length + bodyBytes.length];
        System.arraycopy(headerBytes, 0, responseBytes, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, responseBytes, headerBytes.length, bodyBytes.length);
        return responseBytes;
    }

    static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
