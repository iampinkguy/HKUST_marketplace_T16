package com.hkust.marketplace.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ListingDescriptionServiceTest {
    private final AtomicReference<JsonObject> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private ListingDescriptionService service;
    private volatile Reply reply = new Reply(200, """
            {"choices":[{"finish_reason":"stop","message":{"content":"  Used desk lamp.  "}}]}
            """);

    @Before
    public void startDeepSeekStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::complete);
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
        service = new ListingDescriptionService(endpoint, "test-only-key");
    }

    @After
    public void stopDeepSeekStub() {
        if (server != null) server.stop(0);
    }

    @Test
    public void draftsDescriptionFromTheSellerProvidedFacts() throws Exception {
        String draft = service.generate("Desk lamp", "Electronics", "Used", ListingDescriptionService.Length.MEDIUM);

        assertEquals("Used desk lamp.", draft);
        assertEquals("Bearer test-only-key", authorization.get());
        JsonObject body = request.get();
        JsonObject facts = JsonParser.parseString(body.getAsJsonArray("messages")
                .get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
        assertEquals(JsonParser.parseString("""
                {"itemName":"Desk lamp","category":"Electronics","condition":"Used"}
                """), facts);
    }

    @Test
    public void requestsAnItemFocusedDescriptionWithOrWithoutNotes() throws Exception {
        for (String notes : new String[]{"", "- like new\n- includes the original box"}) {
            service.generate("Desk lamp", "Electronics", "Like New", notes, ListingDescriptionService.Length.MEDIUM);

            String instructions = request.get().getAsJsonArray("messages").get(0)
                    .getAsJsonObject().get("content").getAsString();
            assertTrue("The description should make the item the subject.",
                    instructions.contains("Make the item the subject"));
            assertFalse(instructions.contains("Write in the first person"));
        }
    }

    @Test
    public void polishRequestsCompletePositiveCopyEvenWhenShortIsSelected() throws Exception {
        String notes = "- 2 brightness levels\n- 1.5 m cable\n- small scratch below the switch";
        service.generate("Desk lamp", "Electronics", "Used", notes, ListingDescriptionService.Length.SHORT);

        JsonObject body = request.get();
        String instructions = body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(instructions.contains("Preserve every detail from the seller's notes"));
        assertTrue(instructions.contains("Completeness takes priority over the preferred length"));
        assertTrue(instructions.contains("Use a positive, factual tone"));
        assertFalse("The short draft token cap should not limit a polish request.", body.has("max_tokens"));
    }

    @Test
    public void reportsTheProviderCompletionAndTokenUsage() throws Exception {
        reply = new Reply(200, """
                {"id":"test-completion-123","model":"deepseek-flash","usage":{"total_tokens":47},
                 "choices":[{"finish_reason":"stop","message":{"content":"Used desk lamp."}}]}
                """);
        var logged = new AtomicReference<String>();
        var formatter = new SimpleFormatter();
        var handler = new Handler() {
            @Override public void publish(LogRecord record) { logged.set(formatter.formatMessage(record)); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger logger = Logger.getLogger(ListingDescriptionService.class.getName());
        logger.addHandler(handler);
        try {
            service.generate("Desk lamp", "Electronics", "Used", ListingDescriptionService.Length.MEDIUM);
            assertEquals("DeepSeek completion test-completion-123 (model deepseek-flash, total tokens 47)", logged.get());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    public void rejectsUnusableDrafts() {
        for (Reply unusable : new Reply[]{
                new Reply(429, "{\"error\":{\"message\":\"Rate limited\"}}"),
                new Reply(200, "{\"choices\":[]}"),
                new Reply(200, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"Used desk\"}}]}")
        }) {
            reply = unusable;
            assertThrows(IOException.class, () ->
                    service.generate("Desk lamp", "Electronics", "Used", ListingDescriptionService.Length.SHORT));
        }
    }

    private void complete(HttpExchange exchange) throws IOException {
        try (exchange) {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject());
            Reply currentReply = reply;
            byte[] body = currentReply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(currentReply.status(), body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private record Reply(int status, String body) {}
}
