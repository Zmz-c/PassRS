package passrs.mcp;

import passrs.browser.BrowserRequest;
import passrs.browser.BrowserResponse;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class McpProtocolHandler {
    static final String PROTOCOL_VERSION = "2025-06-18";
    static final String TOOL_BROWSER_REQUEST = "passrs_browser_request";
    static final String TOOL_CLOSE_BROWSER = "passrs_close_browser";

    private static final int ERROR_PARSE = -32700;
    private static final int ERROR_INVALID_REQUEST = -32600;
    private static final int ERROR_METHOD_NOT_FOUND = -32601;
    private static final int ERROR_INVALID_PARAMS = -32602;
    private static final int DEFAULT_MAX_BODY_TEXT_BYTES = 65536;
    private static final int MAX_BODY_TEXT_BYTES = 1048576;

    private final Backend backend;
    private final Consumer<String> statusConsumer;

    McpProtocolHandler(Backend backend, Consumer<String> statusConsumer) {
        this.backend = backend;
        this.statusConsumer = statusConsumer == null ? ignored -> {
        } : statusConsumer;
    }

    McpResponse handle(String body) {
        Object parsed;
        try {
            parsed = SimpleJson.parse(body);
        } catch (Exception e) {
            return McpResponse.json(200, error(null, ERROR_PARSE, "Parse error"));
        }

        if (parsed instanceof List<?> batch) {
            if (batch.isEmpty()) {
                return McpResponse.json(200, error(null, ERROR_INVALID_REQUEST, "Invalid request"));
            }
            List<Object> responses = new ArrayList<>();
            for (Object item : batch) {
                Object response = handleSingle(item);
                if (response != null) {
                    responses.add(response);
                }
            }
            if (responses.isEmpty()) {
                return McpResponse.empty(202);
            }
            return McpResponse.json(200, responses);
        }

        Object response = handleSingle(parsed);
        if (response == null) {
            return McpResponse.empty(202);
        }
        return McpResponse.json(200, response);
    }

    private Object handleSingle(Object requestValue) {
        if (!(requestValue instanceof Map<?, ?>)) {
            return error(null, ERROR_INVALID_REQUEST, "Invalid request");
        }
        Map<String, Object> request = SimpleJson.asObject(requestValue);
        Object id = request.get("id");
        if (!"2.0".equals(request.get("jsonrpc")) || !(request.get("method") instanceof String method)) {
            return error(id, ERROR_INVALID_REQUEST, "Invalid request");
        }

        boolean notification = !request.containsKey("id");
        try {
            Object result = switch (method) {
                case "initialize" -> initializeResult();
                case "notifications/initialized", "notifications/cancelled" -> null;
                case "ping" -> SimpleJson.object();
                case "tools/list" -> toolsListResult();
                case "tools/call" -> callTool(SimpleJson.asObject(request.get("params")));
                default -> throw new McpException(ERROR_METHOD_NOT_FOUND, "Method not found: " + method);
            };
            if (notification) {
                return null;
            }
            return success(id, result == null ? SimpleJson.object() : result);
        } catch (McpException e) {
            return error(id, e.code(), e.getMessage());
        } catch (Exception e) {
            return error(id, ERROR_INVALID_PARAMS, safeMessage(e));
        }
    }

    private Map<String, Object> initializeResult() {
        return SimpleJson.object(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", SimpleJson.object(
                        "tools", SimpleJson.object("listChanged", false)
                ),
                "serverInfo", SimpleJson.object(
                        "name", "PassRS",
                        "version", "1.0.5"
                )
        );
    }

    private Map<String, Object> toolsListResult() {
        return SimpleJson.object("tools", List.of(
                SimpleJson.object(
                        "name", TOOL_BROWSER_REQUEST,
                        "description", "Execute an HTTP request in the PassRS browser context and return the final browser response.",
                        "inputSchema", browserRequestInputSchema()
                ),
                SimpleJson.object(
                        "name", TOOL_CLOSE_BROWSER,
                        "description", "Close the PassRS browser session and cancel any active browser bridge process.",
                        "inputSchema", SimpleJson.object(
                                "type", "object",
                                "properties", SimpleJson.object(),
                                "additionalProperties", false
                        )
                )
        ));
    }

    private Map<String, Object> browserRequestInputSchema() {
        return SimpleJson.object(
                "type", "object",
                "properties", SimpleJson.object(
                        "method", SimpleJson.object(
                                "type", "string",
                                "description", "HTTP method. Defaults to GET."
                        ),
                        "url", SimpleJson.object(
                                "type", "string",
                                "description", "Absolute http:// or https:// target URL."
                        ),
                        "headers", SimpleJson.object(
                                "description", "Headers as an object or an array of 'Name: value' strings.",
                                "oneOf", List.of(
                                        SimpleJson.object(
                                                "type", "object",
                                                "additionalProperties", SimpleJson.object("type", "string")
                                        ),
                                        SimpleJson.object(
                                                "type", "array",
                                                "items", SimpleJson.object("type", "string")
                                        )
                                )
                        ),
                        "headerLines", SimpleJson.object(
                                "type", "array",
                                "items", SimpleJson.object("type", "string"),
                                "description", "Additional raw header lines."
                        ),
                        "body", SimpleJson.object(
                                "type", "string",
                                "description", "UTF-8 request body. Ignored when bodyBase64 is set."
                        ),
                        "bodyBase64", SimpleJson.object(
                                "type", "string",
                                "description", "Base64-encoded raw request body."
                        ),
                        "maxBodyTextBytes", SimpleJson.object(
                                "type", "integer",
                                "description", "Maximum response body bytes decoded as UTF-8 text. Defaults to 65536."
                        ),
                        "includeBodyBase64", SimpleJson.object(
                                "type", "boolean",
                                "description", "Whether to include the full response body as Base64. Defaults to false."
                        )
                ),
                "required", List.of("url"),
                "additionalProperties", false
        );
    }

    private Map<String, Object> callTool(Map<String, Object> params) {
        String name = SimpleJson.string(params, "name");
        Map<String, Object> arguments = SimpleJson.asObject(params.get("arguments"));
        return switch (name) {
            case TOOL_BROWSER_REQUEST -> callBrowserRequest(arguments);
            case TOOL_CLOSE_BROWSER -> callCloseBrowser();
            default -> throw new McpException(ERROR_INVALID_PARAMS, "Unknown tool: " + name);
        };
    }

    private Map<String, Object> callBrowserRequest(Map<String, Object> arguments) {
        try {
            BrowserRequest request = buildBrowserRequest(arguments);
            statusConsumer.accept("MCP running " + request.method() + " " + request.url());
            BrowserResponse response = backend.execute(request);
            statusConsumer.accept("MCP completed " + request.method() + " " + request.url() + " -> " + response.status());
            return toolTextResult(responsePayload(response, arguments), false);
        } catch (Exception e) {
            statusConsumer.accept("MCP error: " + safeMessage(e));
            return toolTextResult(SimpleJson.object("error", safeMessage(e)), true);
        }
    }

    private BrowserRequest buildBrowserRequest(Map<String, Object> arguments) {
        String url = SimpleJson.string(arguments, "url").trim();
        if (url.isEmpty()) {
            throw new McpException(ERROR_INVALID_PARAMS, "url is required");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new McpException(ERROR_INVALID_PARAMS, "url must start with http:// or https://");
        }
        String method = SimpleJson.string(arguments, "method").trim();
        if (method.isEmpty()) {
            method = "GET";
        }
        return BrowserRequest.of(method, url, parseHeaders(arguments), parseBody(arguments));
    }

    private List<String> parseHeaders(Map<String, Object> arguments) {
        List<String> headers = new ArrayList<>();
        Object headersValue = arguments.get("headers");
        if (headersValue instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = sanitizeHeaderName(String.valueOf(entry.getKey()));
                String value = sanitizeHeaderValue(String.valueOf(entry.getValue()));
                if (!name.isBlank()) {
                    headers.add(name + ": " + value);
                }
            }
        } else if (headersValue instanceof List<?> list) {
            for (Object item : list) {
                String line = sanitizeHeaderLine(String.valueOf(item));
                if (!line.isBlank()) {
                    headers.add(line);
                }
            }
        }

        for (Object item : SimpleJson.asList(arguments.get("headerLines"))) {
            String line = sanitizeHeaderLine(String.valueOf(item));
            if (!line.isBlank()) {
                headers.add(line);
            }
        }
        return headers;
    }

    private byte[] parseBody(Map<String, Object> arguments) {
        String bodyBase64 = SimpleJson.string(arguments, "bodyBase64");
        if (!bodyBase64.isBlank()) {
            try {
                return Base64.getDecoder().decode(bodyBase64);
            } catch (IllegalArgumentException e) {
                throw new McpException(ERROR_INVALID_PARAMS, "bodyBase64 is not valid Base64");
            }
        }
        String body = SimpleJson.string(arguments, "body");
        return body.isEmpty() ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> responsePayload(BrowserResponse response, Map<String, Object> arguments) {
        byte[] body = response.body();
        int maxBodyTextBytes = Math.max(0, Math.min(
                SimpleJson.integer(arguments, "maxBodyTextBytes", DEFAULT_MAX_BODY_TEXT_BYTES),
                MAX_BODY_TEXT_BYTES
        ));
        boolean includeBodyBase64 = SimpleJson.bool(arguments, "includeBodyBase64", false);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", response.status());
        payload.put("reason", response.reason());
        payload.put("finalUrl", response.finalUrl());
        payload.put("title", response.title());
        payload.put("headers", response.headers());
        payload.put("bodyLength", body.length);
        addBodyText(payload, body, maxBodyTextBytes);
        if (includeBodyBase64) {
            payload.put("bodyBase64", Base64.getEncoder().encodeToString(body));
        }
        return payload;
    }

    private void addBodyText(Map<String, Object> payload, byte[] body, int maxBodyTextBytes) {
        int textLength = Math.min(body.length, maxBodyTextBytes);
        if (textLength == 0) {
            payload.put("bodyTextTruncated", body.length > 0);
            payload.put("bodyText", "");
            return;
        }
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer input = ByteBuffer.wrap(body, 0, textLength);
            CharBuffer output = CharBuffer.allocate(textLength);
            CoderResult result = decoder.decode(input, output, textLength == body.length);
            if (result.isError()) {
                result.throwException();
            }
            output.flip();
            payload.put("bodyTextTruncated", body.length > input.position());
            payload.put("bodyText", output.toString());
        } catch (CharacterCodingException e) {
            payload.put("bodyTextTruncated", body.length > textLength);
            payload.put("bodyText", "");
            payload.put("bodyTextEncoding", "binary");
        }
    }

    private Map<String, Object> callCloseBrowser() {
        backend.closeBrowser();
        statusConsumer.accept("MCP closed browser");
        return toolTextResult(SimpleJson.object("closed", true), false);
    }

    private Map<String, Object> toolTextResult(Object payload, boolean isError) {
        return SimpleJson.object(
                "content", List.of(SimpleJson.object(
                        "type", "text",
                        "text", SimpleJson.stringify(payload)
                )),
                "isError", isError
        );
    }

    private Map<String, Object> success(Object id, Object result) {
        return SimpleJson.object(
                "jsonrpc", "2.0",
                "id", id,
                "result", result
        );
    }

    private Map<String, Object> error(Object id, int code, String message) {
        return SimpleJson.object(
                "jsonrpc", "2.0",
                "id", id,
                "error", SimpleJson.object(
                        "code", code,
                        "message", message == null || message.isBlank() ? "JSON-RPC error" : message
                )
        );
    }

    private String sanitizeHeaderName(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "").replace("\n", "").trim();
    }

    private String sanitizeHeaderValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "").replace("\n", "").trim();
    }

    private String sanitizeHeaderLine(String value) {
        String line = sanitizeHeaderValue(value);
        int index = line.indexOf(':');
        if (index <= 0) {
            return "";
        }
        return sanitizeHeaderName(line.substring(0, index)) + ": " + sanitizeHeaderValue(line.substring(index + 1));
    }

    private String safeMessage(Exception exception) {
        return exception == null || exception.getMessage() == null ? "unknown error" : exception.getMessage();
    }

    interface Backend {
        BrowserResponse execute(BrowserRequest request);

        void closeBrowser();
    }

    record McpResponse(int status, String body) {
        static McpResponse json(int status, Object value) {
            return new McpResponse(status, SimpleJson.stringify(value));
        }

        static McpResponse empty(int status) {
            return new McpResponse(status, "");
        }
    }

    private static final class McpException extends RuntimeException {
        private final int code;

        private McpException(int code, String message) {
            super(message);
            this.code = code;
        }

        private int code() {
            return code;
        }
    }
}
