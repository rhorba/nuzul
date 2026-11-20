package ma.nuzul.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** properties() is never null, even after Java serialization drops the transient map. */
class CodedExceptionTest {

    @Test
    void propertiesAreKept() {
        var e = new ConflictException("C", "m", Map.of("nights", List.of("2026-12-01")));

        assertThat(e.properties()).containsEntry("nights", List.of("2026-12-01"));
    }

    @Test
    void propertiesAreEmptyNotNullAfterDeserialization() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(new ConflictException("C", "m", Map.of("k", "v")));
        }
        CodedException copy;
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (CodedException) in.readObject();
        }

        assertThat(copy.code()).isEqualTo("C");
        assertThat(copy.properties()).isNotNull().isEmpty();
    }
}
