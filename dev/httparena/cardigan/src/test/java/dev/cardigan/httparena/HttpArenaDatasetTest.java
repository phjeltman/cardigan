// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import dev.cardigan.http.StreamingBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpArenaDatasetTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesItemsAndComputesPerRequestTotals() throws Exception {
        StringBuilder json = new StringBuilder("[");
        for (int index = 1; index <= 50; index++) {
            if (index != 1) json.append(',');
            json.append("{\"id\":").append(index)
                .append(",\"name\":\"item ").append(index)
                .append("\",\"category\":\"test\",\"price\":")
                .append(index + 1)
                .append(",\"quantity\":2,\"active\":true,")
                .append("\"tags\":[\"a\",\"b\"],")
                .append("\"rating\":{\"score\":4,\"count\":9}}");
        }
        json.append(']');
        Path datasetFile = temporaryDirectory.resolve("dataset.json");
        Files.writeString(datasetFile, json);

        HttpArenaDataset dataset = HttpArenaDataset.load(datasetFile);
        assertEquals(50, dataset.size());
        String response = read(dataset.render(2, 3));

        assertTrue(response.startsWith("{\"items\":[{"));
        assertTrue(response.contains("\"id\":1"));
        assertTrue(response.contains("\"total\":12"));
        assertTrue(response.contains("\"id\":2"));
        assertTrue(response.contains("\"total\":18"));
        assertTrue(response.endsWith("],\"count\":2}"));
    }

    private static String read(StreamingBody body) {
        byte[] output = new byte[body.length()];
        MemorySegment destination = MemorySegment.ofArray(output);
        int offset = 0;
        while (offset < output.length) {
            int count = body.read(destination.asSlice(offset));
            assertTrue(count > 0);
            offset += count;
        }
        assertEquals(output.length, offset);
        return new String(output, StandardCharsets.UTF_8);
    }
}
