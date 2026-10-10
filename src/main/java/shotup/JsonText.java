package shotup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small JSON object reader and string quoter used by the burp-mcp command
 * responses. The body only carries folder paths, so values are strings.
 */
final class JsonText {
    private final String text;
    private int index;

    private JsonText(String text) {
        this.text = text;
    }

    static Map<String, String> object(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        if (body.length() > 65536) {
            throw new IllegalArgumentException("The request body is too large.");
        }
        JsonText parser = new JsonText(body);
        parser.skipWs();
        if (parser.end() || parser.text.charAt(parser.index) != '{') {
            throw new IllegalArgumentException("The request body must be empty or a JSON object.");
        }
        Map<String, String> fields = parser.parseObject();
        parser.skipWs();
        if (!parser.end()) {
            throw new IllegalArgumentException("Unexpected characters after the JSON object.");
        }
        return fields;
    }

    static String quote(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private Map<String, String> parseObject() {
        expect('{');
        Map<String, String> fields = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            index++;
            return fields;
        }
        while (true) {
            skipWs();
            String key = parseString("Expected a string for a JSON object key.");
            skipWs();
            expect(':');
            skipWs();
            fields.put(key, parseString("JSON object values must be strings."));
            skipWs();
            char next = peek();
            if (next == ',') {
                index++;
                continue;
            }
            if (next == '}') {
                index++;
                return fields;
            }
            throw new IllegalArgumentException("Expected ',' or '}' in the JSON object.");
        }
    }

    private String parseString(String expected) {
        if (end() || text.charAt(index) != '"') {
            throw new IllegalArgumentException(expected);
        }
        index++;
        StringBuilder out = new StringBuilder();
        while (!end()) {
            char c = text.charAt(index++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (end()) {
                    throw new IllegalArgumentException("The JSON string is incomplete.");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(parseHex());
                    default -> throw new IllegalArgumentException("Invalid escape in the JSON string.");
                }
                continue;
            }
            if (c < 0x20) {
                throw new IllegalArgumentException("The JSON string contains a raw control character.");
            }
            out.append(c);
        }
        throw new IllegalArgumentException("The JSON string is incomplete.");
    }

    private char parseHex() {
        if (index + 4 > text.length()) {
            throw new IllegalArgumentException("Invalid escape in the JSON string.");
        }
        int code = 0;
        for (int n = 0; n < 4; n++) {
            int digit = Character.digit(text.charAt(index++), 16);
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid escape in the JSON string.");
            }
            code = (code << 4) + digit;
        }
        return (char) code;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw new IllegalArgumentException("The JSON object is incomplete.");
        }
        index++;
    }

    private char peek() {
        if (end()) {
            throw new IllegalArgumentException("The JSON object is incomplete.");
        }
        return text.charAt(index);
    }

    private void skipWs() {
        while (!end()) {
            char c = text.charAt(index);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return;
            }
            index++;
        }
    }

    private boolean end() {
        return index >= text.length();
    }
}
