package passrs.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalMcpServerTest {

    @Test
    void allowsNonBrowserClientsAndLoopbackOrigins() {
        assertThat(LocalMcpServer.isAllowedOrigin(null)).isTrue();
        assertThat(LocalMcpServer.isAllowedOrigin("http://127.0.0.1:12345")).isTrue();
        assertThat(LocalMcpServer.isAllowedOrigin("https://localhost:8443/")).isTrue();
        assertThat(LocalMcpServer.isAllowedOrigin("http://[::1]:9000")).isTrue();
    }

    @Test
    void rejectsRemoteOrMalformedOrigins() {
        assertThat(LocalMcpServer.isAllowedOrigin("https://attacker.example")).isFalse();
        assertThat(LocalMcpServer.isAllowedOrigin("null")).isFalse();
        assertThat(LocalMcpServer.isAllowedOrigin("http://127.0.0.1:12345/not-an-origin")).isFalse();
        assertThat(LocalMcpServer.isAllowedOrigin("http://user@127.0.0.1:12345")).isFalse();
    }

    @Test
    void acceptsOnlyJsonRequestContentTypes() {
        assertThat(LocalMcpServer.isJsonContentType("application/json")).isTrue();
        assertThat(LocalMcpServer.isJsonContentType("Application/JSON; charset=UTF-8")).isTrue();
        assertThat(LocalMcpServer.isJsonContentType("text/plain")).isFalse();
        assertThat(LocalMcpServer.isJsonContentType(null)).isFalse();
    }
}
