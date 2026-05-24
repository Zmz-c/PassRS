package passrs.browser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserMessageCodecTest {

    @Test
    void roundTripsEncodedStringAndBytes() {
        assertThat(BrowserMessageCodec.decodeString(BrowserMessageCodec.encodeString("hello 中文")))
                .isEqualTo("hello 中文");
        assertThat(BrowserMessageCodec.decodeBytes(BrowserMessageCodec.encodeBytes(new byte[]{1, 2, 3})))
                .containsExactly(1, 2, 3);
    }

    @Test
    void parsesKeyValueLinesWithoutBreakingValuesContainingEquals() {
        assertThat(BrowserMessageCodec.parseKeyValueLines("A=1\nB=a=b\ninvalid").get("B"))
                .isEqualTo("a=b");
    }

    @Test
    void buildsHttpResponseAndDropsUnsafeTransferHeaders() {
        byte[] response = BrowserMessageCodec.buildHttpResponseBytes(
                200,
                "",
                List.of("Transfer-Encoding: chunked", "Content-Encoding: gzip", "X-Test: yes"),
                "body".getBytes(StandardCharsets.UTF_8)
        );

        String text = new String(response, StandardCharsets.ISO_8859_1);
        assertThat(text).startsWith("HTTP/1.1 200 OK\r\n");
        assertThat(text).contains("X-Test: yes\r\n");
        assertThat(text).contains("Content-Length: 4\r\n");
        assertThat(text).doesNotContain("Transfer-Encoding");
        assertThat(text).doesNotContain("Content-Encoding");
    }
}
