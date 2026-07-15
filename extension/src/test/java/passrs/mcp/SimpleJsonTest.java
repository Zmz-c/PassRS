package passrs.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleJsonTest {

    @Test
    void parsesObjectsArraysAndEscapes() {
        Object parsed = SimpleJson.parse("""
                {"method":"tools/call","params":{"items":["a\\nb",true,null,12]}}
                """);

        Map<String, Object> object = SimpleJson.asObject(parsed);
        Map<String, Object> params = SimpleJson.asObject(object.get("params"));
        List<Object> items = SimpleJson.asList(params.get("items"));

        assertThat(object.get("method")).isEqualTo("tools/call");
        assertThat(items).containsExactly("a\nb", true, null, 12L);
    }

    @Test
    void stringifiesJsonSafely() {
        String json = SimpleJson.stringify(SimpleJson.object("text", "line\r\n\"quoted\"", "ok", true));

        assertThat(json).isEqualTo("{\"text\":\"line\\r\\n\\\"quoted\\\"\",\"ok\":true}");
    }
}
