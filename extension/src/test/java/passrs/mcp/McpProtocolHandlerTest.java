package passrs.mcp;

import org.junit.jupiter.api.Test;
import passrs.browser.BrowserRequest;
import passrs.browser.BrowserResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class McpProtocolHandlerTest {

    @Test
    void handlesInitializeAndToolsList() {
        McpProtocolHandler handler = handlerReturning(new BrowserResponse(200, "OK", List.of(), new byte[0], "", ""));

        Map<String, Object> initialize = response(handler.handle("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
                """));
        Map<String, Object> initializeResult = SimpleJson.asObject(initialize.get("result"));

        assertThat(initializeResult.get("protocolVersion")).isEqualTo("2025-06-18");
        assertThat(SimpleJson.asObject(initializeResult.get("capabilities"))).containsKey("tools");

        Map<String, Object> toolsList = response(handler.handle("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """));
        List<Object> tools = SimpleJson.asList(SimpleJson.asObject(toolsList.get("result")).get("tools"));

        assertThat(tools)
                .extracting(tool -> SimpleJson.asObject(tool).get("name"))
                .contains(McpProtocolHandler.TOOL_BROWSER_REQUEST, McpProtocolHandler.TOOL_CLOSE_BROWSER);
    }

    @Test
    void callsBrowserRequestToolAndReturnsResponsePayload() {
        AtomicReference<BrowserRequest> captured = new AtomicReference<>();
        McpProtocolHandler handler = new McpProtocolHandler(new McpProtocolHandler.Backend() {
            @Override
            public BrowserResponse execute(BrowserRequest request) {
                captured.set(request);
                return new BrowserResponse(
                        200,
                        "OK",
                        List.of("Content-Type: text/plain"),
                        "hello".getBytes(StandardCharsets.UTF_8),
                        "https://example.test/final",
                        "Final"
                );
            }

            @Override
            public void closeBrowser() {
            }
        }, ignored -> {
        });

        Map<String, Object> rpc = response(handler.handle("""
                {"jsonrpc":"2.0","id":"call-1","method":"tools/call","params":{"name":"passrs_browser_request","arguments":{"method":"POST","url":"https://example.test/path","headers":{"X-Test":"yes"},"body":"payload"}}}
                """));
        Map<String, Object> result = SimpleJson.asObject(rpc.get("result"));
        String text = String.valueOf(SimpleJson.asObject(SimpleJson.asList(result.get("content")).getFirst()).get("text"));
        Map<String, Object> payload = SimpleJson.asObject(SimpleJson.parse(text));

        assertThat(captured.get().method()).isEqualTo("POST");
        assertThat(captured.get().url()).isEqualTo("https://example.test/path");
        assertThat(captured.get().headers()).containsExactly("X-Test: yes");
        assertThat(new String(captured.get().body(), StandardCharsets.UTF_8)).isEqualTo("payload");
        assertThat(result.get("isError")).isEqualTo(false);
        assertThat(payload.get("status")).isEqualTo(200L);
        assertThat(payload.get("bodyText")).isEqualTo("hello");
        assertThat(payload.get("finalUrl")).isEqualTo("https://example.test/final");
    }

    @Test
    void closeBrowserToolInvokesBackend() {
        AtomicBoolean closed = new AtomicBoolean(false);
        McpProtocolHandler handler = new McpProtocolHandler(new McpProtocolHandler.Backend() {
            @Override
            public BrowserResponse execute(BrowserRequest request) {
                throw new AssertionError("execute should not be called");
            }

            @Override
            public void closeBrowser() {
                closed.set(true);
            }
        }, ignored -> {
        });

        Map<String, Object> rpc = response(handler.handle("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"passrs_close_browser","arguments":{}}}
                """));

        assertThat(closed).isTrue();
        assertThat(SimpleJson.asObject(rpc.get("result")).get("isError")).isEqualTo(false);
    }

    @Test
    void truncatesUtf8TextOnlyAtCodePointBoundaries() {
        McpProtocolHandler handler = handlerReturning(new BrowserResponse(
                200,
                "OK",
                List.of("Content-Type: text/plain; charset=UTF-8"),
                "你好".getBytes(StandardCharsets.UTF_8),
                "https://example.test/",
                ""
        ));

        Map<String, Object> rpc = response(handler.handle("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"passrs_browser_request","arguments":{"url":"https://example.test/","maxBodyTextBytes":4}}}
                """));
        Map<String, Object> result = SimpleJson.asObject(rpc.get("result"));
        String text = String.valueOf(SimpleJson.asObject(SimpleJson.asList(result.get("content")).getFirst()).get("text"));
        Map<String, Object> payload = SimpleJson.asObject(SimpleJson.parse(text));

        assertThat(payload.get("bodyText")).isEqualTo("你");
        assertThat(payload.get("bodyTextTruncated")).isEqualTo(true);
        assertThat(payload).doesNotContainKey("bodyTextEncoding");
    }

    private McpProtocolHandler handlerReturning(BrowserResponse response) {
        return new McpProtocolHandler(new McpProtocolHandler.Backend() {
            @Override
            public BrowserResponse execute(BrowserRequest request) {
                return response;
            }

            @Override
            public void closeBrowser() {
            }
        }, ignored -> {
        });
    }

    private Map<String, Object> response(McpProtocolHandler.McpResponse response) {
        assertThat(response.status()).isEqualTo(200);
        return SimpleJson.asObject(SimpleJson.parse(response.body()));
    }
}
