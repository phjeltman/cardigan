// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
        String response;
        try (Arena arena = Arena.ofConfined()) {
            var body = dataset.render(2, 3);
            MemorySegment output = arena.allocate(body.length());
            body.write(output);
            response = new String(
                output.toArray(ValueLayout.JAVA_BYTE),
                StandardCharsets.UTF_8);
        }

        assertTrue(response.startsWith("{\"items\":[{"));
        assertTrue(response.contains("\"id\":1"));
        assertTrue(response.contains("\"total\":12"));
        assertTrue(response.contains("\"id\":2"));
        assertTrue(response.contains("\"total\":18"));
        assertTrue(response.endsWith("],\"count\":2}"));
    }

    @Test
    void compactsFormattingWithoutChangingStringContents() throws Exception {
        StringBuilder json = new StringBuilder("[\n");
        for (int index = 1; index <= 50; index++) {
            if (index != 1) json.append(",\n");
            json.append("  {\n")
                .append("    \"id\" : ").append(index).append(",\n")
                .append("    \"name\" : \"item ").append(index)
                .append(" with \\\"quotes\\\"\",\n")
                .append("    \"price\" : 3,\n")
                .append("    \"quantity\" : 4\n")
                .append("  }");
        }
        json.append("\n]");
        Path datasetFile = temporaryDirectory.resolve("pretty-dataset.json");
        Files.writeString(datasetFile, json);

        HttpArenaDataset dataset = HttpArenaDataset.load(datasetFile);
        String response;
        try (Arena arena = Arena.ofConfined()) {
            var body = dataset.render(1, 2);
            MemorySegment output = arena.allocate(body.length());
            body.write(output);
            response = new String(
                output.toArray(ValueLayout.JAVA_BYTE),
                StandardCharsets.UTF_8);
        }

        assertEquals(
            "{\"items\":[{\"id\":1,\"name\":"
                + "\"item 1 with \\\"quotes\\\"\",\"price\":3,"
                + "\"quantity\":4,\"total\":24}],\"count\":1}",
            response);
    }

}
