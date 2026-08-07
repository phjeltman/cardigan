// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import dev.cardigan.http.Get;
import dev.cardigan.http.Response;
import dev.cardigan.http.StaticBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Preloads the fixed HttpArena static corpus into Cardigan native bodies. */
public final class HttpArenaStaticController {
    private final Response resetCss;
    private final Response layoutCss;
    private final Response themeCss;
    private final Response componentsCss;
    private final Response utilitiesCss;
    private final Response analyticsJs;
    private final Response helpersJs;
    private final Response appJs;
    private final Response vendorJs;
    private final Response routerJs;
    private final Response headerHtml;
    private final Response footerHtml;
    private final Response regularWoff2;
    private final Response boldWoff2;
    private final Response logoSvg;
    private final Response iconSpriteSvg;
    private final Response heroWebp;
    private final Response thumb1Webp;
    private final Response thumb2Webp;
    private final Response manifestJson;

    HttpArenaStaticController(Path directory) throws IOException {
        resetCss = load(directory, "reset.css", "text/css");
        layoutCss = load(directory, "layout.css", "text/css");
        themeCss = load(directory, "theme.css", "text/css");
        componentsCss = load(directory, "components.css", "text/css");
        utilitiesCss = load(directory, "utilities.css", "text/css");
        analyticsJs = load(
            directory, "analytics.js", "application/javascript");
        helpersJs = load(
            directory, "helpers.js", "application/javascript");
        appJs = load(directory, "app.js", "application/javascript");
        vendorJs = load(directory, "vendor.js", "application/javascript");
        routerJs = load(directory, "router.js", "application/javascript");
        headerHtml = load(directory, "header.html", "text/html");
        footerHtml = load(directory, "footer.html", "text/html");
        regularWoff2 = load(directory, "regular.woff2", "font/woff2");
        boldWoff2 = load(directory, "bold.woff2", "font/woff2");
        logoSvg = load(directory, "logo.svg", "image/svg+xml");
        iconSpriteSvg = load(
            directory, "icon-sprite.svg", "image/svg+xml");
        heroWebp = load(directory, "hero.webp", "image/webp");
        thumb1Webp = load(directory, "thumb1.webp", "image/webp");
        thumb2Webp = load(directory, "thumb2.webp", "image/webp");
        manifestJson = load(
            directory, "manifest.json", "application/json");
    }

    @Get("/static/reset.css") public Response resetCss() { return resetCss; }
    @Get("/static/layout.css") public Response layoutCss() { return layoutCss; }
    @Get("/static/theme.css") public Response themeCss() { return themeCss; }
    @Get("/static/components.css") public Response componentsCss() { return componentsCss; }
    @Get("/static/utilities.css") public Response utilitiesCss() { return utilitiesCss; }
    @Get("/static/analytics.js") public Response analyticsJs() { return analyticsJs; }
    @Get("/static/helpers.js") public Response helpersJs() { return helpersJs; }
    @Get("/static/app.js") public Response appJs() { return appJs; }
    @Get("/static/vendor.js") public Response vendorJs() { return vendorJs; }
    @Get("/static/router.js") public Response routerJs() { return routerJs; }
    @Get("/static/header.html") public Response headerHtml() { return headerHtml; }
    @Get("/static/footer.html") public Response footerHtml() { return footerHtml; }
    @Get("/static/regular.woff2") public Response regularWoff2() { return regularWoff2; }
    @Get("/static/bold.woff2") public Response boldWoff2() { return boldWoff2; }
    @Get("/static/logo.svg") public Response logoSvg() { return logoSvg; }
    @Get("/static/icon-sprite.svg") public Response iconSpriteSvg() { return iconSpriteSvg; }
    @Get("/static/hero.webp") public Response heroWebp() { return heroWebp; }
    @Get("/static/thumb1.webp") public Response thumb1Webp() { return thumb1Webp; }
    @Get("/static/thumb2.webp") public Response thumb2Webp() { return thumb2Webp; }
    @Get("/static/manifest.json") public Response manifestJson() { return manifestJson; }

    private static Response load(
            Path directory, String filename, String contentType)
            throws IOException {
        byte[] bytes = Files.readAllBytes(directory.resolve(filename));
        return new Response(
            200, contentType, StaticBody.copyOf(bytes));
    }
}
