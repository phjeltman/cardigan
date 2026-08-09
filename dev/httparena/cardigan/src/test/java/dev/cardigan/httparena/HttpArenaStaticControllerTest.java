// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.HttpRequestParser;
import dev.cardigan.http.Response;
import dev.cardigan.http.StaticBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpArenaStaticControllerTest {
    private static final List<String> FILES = List.of(
        "reset.css", "layout.css", "theme.css", "components.css",
        "utilities.css", "analytics.js", "helpers.js", "app.js",
        "vendor.js", "router.js", "header.html", "footer.html",
        "regular.woff2", "bold.woff2", "logo.svg", "icon-sprite.svg",
        "hero.webp", "thumb1.webp", "thumb2.webp", "manifest.json"
    );

    @TempDir
    Path directory;

    private HttpArenaStaticController controller;

    @BeforeEach
    void createCorpus() throws Exception {
        for (String filename : FILES) {
            Files.writeString(directory.resolve(filename), filename);
        }
        Files.writeString(directory.resolve("reset.css.br"), "brotli-reset");
        controller = new HttpArenaStaticController(directory);
    }

    @Test
    void selectsPrecompressedBrotliWithoutMaterializingTheHeader() {
        Response response = controller.resetCss(
            request("gzip;q=0.8, Br ; q=1"));

        assertEquals("brotli-reset", body(response));
        assertEquals("br", header(response, "content-encoding"));
        assertEquals("Accept-Encoding", header(response, "vary"));
    }

    @Test
    void explicitBrotliRejectionOverridesWildcard() {
        Response response = controller.resetCss(
            request("*;q=1, br;q=0"));

        assertEquals("reset.css", body(response));
        assertNull(header(response, "content-encoding"));
        assertEquals("Accept-Encoding", header(response, "vary"));
    }

    @Test
    void wildcardSelectsBrotliWhenNoExplicitPreferenceExists() {
        Response response = controller.resetCss(
            request("gzip;q=0.8, *;q=0.5"));

        assertEquals("brotli-reset", body(response));
        assertEquals("br", header(response, "content-encoding"));
    }

    @Test
    void missingAcceptEncodingSelectsIdentity() {
        Response response = controller.resetCss(request(null));

        assertEquals("reset.css", body(response));
        assertNull(header(response, "content-encoding"));
        assertEquals("Accept-Encoding", header(response, "vary"));
    }

    @Test
    void assetsWithoutSidecarsRemainIdentityResponses() {
        Response response = controller.heroWebp(request("br"));

        assertEquals("hero.webp", body(response));
        assertNull(header(response, "content-encoding"));
        assertNull(header(response, "vary"));
    }

    @Test
    void malformedOrOutOfRangeQualityRejectsBrotli() {
        assertEquals(
            "reset.css", body(controller.resetCss(request("br;q=1.1"))));
        assertEquals(
            "reset.css", body(controller.resetCss(request("br;q=0.1234"))));
        assertEquals(
            "reset.css", body(controller.resetCss(request("br;q=wat"))));
    }

    private static HttpRequest request(String acceptEncoding) {
        String wire = "GET /static/reset.css HTTP/1.1\r\nHost: localhost\r\n"
            + (acceptEncoding == null
                ? "" : "Accept-Encoding: " + acceptEncoding + "\r\n")
            + "\r\n";
        byte[] bytes = wire.getBytes(StandardCharsets.US_ASCII);
        MemorySegment segment = Arena.global().allocate(bytes.length);
        MemorySegment.copy(
            bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
        HttpRequest request = new HttpRequest();
        assertTrue(HttpRequestParser.parse(segment, bytes.length, request));
        return request;
    }

    private static String body(Response response) {
        StaticBody body = (StaticBody) response.body();
        return new String(
            body.segment().toArray(ValueLayout.JAVA_BYTE),
            StandardCharsets.UTF_8);
    }

    private static String header(Response response, String name) {
        for (int index = 0; index < response.headers().size(); index++) {
            if (response.headers().name(index).equals(name)) {
                return response.headers().value(index);
            }
        }
        return null;
    }
}
