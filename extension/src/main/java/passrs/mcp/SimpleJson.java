package passrs.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SimpleJson {

    private SimpleJson() {
    }

    static Object parse(String json) {
        Parser parser = new Parser(json == null ? "" : json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isEnd()) {
            throw new IllegalArgumentException("unexpected trailing JSON data");
        }
        return value;
    }

    static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        writeJson(builder, value);
        return builder.toString();
    }

    static Map<String, Object> object(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("object requires key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof String text ? text : "";
    }

    static boolean bool(Map<String, Object> object, String key, boolean defaultValue) {
        Object value = object.get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    static int integer(Map<String, Object> object, String key, int defaultValue) {
        Object value = object.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private static void writeJson(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof String text) {
            writeString(builder, text);
            return;
        }
        if (value instanceof Number number) {
            builder.append(number);
            return;
        }
        if (value instanceof Boolean bool) {
            builder.append(bool);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                writeJson(builder, entry.getValue());
            }
            builder.append('}');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeJson(builder, item);
            }
            builder.append(']');
            return;
        }
        writeString(builder, String.valueOf(value));
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Object parseValue() {
            skipWhitespace();
            if (isEnd()) {
                throw new IllegalArgumentException("empty JSON input");
            }
            char ch = peek();
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (ch == '-' || Character.isDigit(ch)) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("unexpected JSON token at " + index);
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consumeIf('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                if (consumeIf('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (consumeIf(']')) {
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (consumeIf(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (!isEnd()) {
                char ch = input.charAt(index++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch != '\\') {
                    builder.append(ch);
                    continue;
                }
                if (isEnd()) {
                    throw new IllegalArgumentException("unterminated JSON escape");
                }
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> builder.append(escaped);
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> builder.append(parseUnicodeEscape());
                    default -> throw new IllegalArgumentException("invalid JSON escape at " + (index - 1));
                }
            }
            throw new IllegalArgumentException("unterminated JSON string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > input.length()) {
                throw new IllegalArgumentException("short JSON unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char ch = input.charAt(index++);
                int digit = Character.digit(ch, 16);
                if (digit < 0) {
                    throw new IllegalArgumentException("invalid JSON unicode escape");
                }
                value = (value << 4) + digit;
            }
            return (char) value;
        }

        private Object parseNumber() {
            int start = index;
            consumeIf('-');
            consumeDigits();
            boolean floatingPoint = false;
            if (consumeIf('.')) {
                floatingPoint = true;
                consumeDigits();
            }
            if (consumeIf('e') || consumeIf('E')) {
                floatingPoint = true;
                consumeIf('+');
                consumeIf('-');
                consumeDigits();
            }
            String number = input.substring(start, index);
            try {
                if (floatingPoint) {
                    double value = Double.parseDouble(number);
                    if (!Double.isFinite(value)) {
                        throw new NumberFormatException("non-finite JSON number");
                    }
                    return value;
                }
                return Long.parseLong(number);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid JSON number", e);
            }
        }

        private void consumeDigits() {
            int start = index;
            while (!isEnd() && Character.isDigit(peek())) {
                index++;
            }
            if (start == index) {
                throw new IllegalArgumentException("expected JSON digit at " + index);
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) {
                throw new IllegalArgumentException("invalid JSON literal at " + index);
            }
            index += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (!isEnd() && Character.isWhitespace(peek())) {
                index++;
            }
        }

        private boolean consumeIf(char expected) {
            if (!isEnd() && peek() == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (isEnd() || input.charAt(index) != expected) {
                throw new IllegalArgumentException("expected '" + expected + "' at " + index);
            }
            index++;
        }

        private char peek() {
            return input.charAt(index);
        }

        private boolean isEnd() {
            return index >= input.length();
        }
    }
}
