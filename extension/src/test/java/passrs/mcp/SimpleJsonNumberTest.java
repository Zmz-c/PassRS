package passrs.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleJsonNumberTest {

    @Test
    void rejectsNonFiniteNumbers() {
        assertThatThrownBy(() -> SimpleJson.parse("1e309"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid JSON number");
    }
}
