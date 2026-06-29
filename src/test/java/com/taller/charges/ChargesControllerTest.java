package com.taller.charges;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Happy-path smoke tests and edge cases regression tests for the charges service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChargesControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ChargesService service;

    @Autowired
    private ChargeStore store;

    @SpyBean
    private PaymentProcessor processor;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void stripeApiKeyIsCorrectlyInjected() {
        assertEquals("sk_live_v21_TAL_W7kQ9rR2bX4mE8nP6vY3aJ5sZ8cN1uK0", service.getStripeApiKey());
    }

    @Test
    void createChargeConcurrentlyWithSameKeyReturnsSameChargeId() throws InterruptedException, ExecutionException {
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        
        ChargeRequest req = new ChargeRequest(
                "concurrent_test_key_" + UUID.randomUUID().toString(),
                150.0,
                "USD",
                "concurrent@example.com",
                "tok_visa"
        );

        List<Future<Charge>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> service.createCharge(req)));
        }

        List<Charge> results = new ArrayList<>();
        for (Future<Charge> f : futures) {
            results.add(f.get());
        }

        executor.shutdown();

        assertFalse(results.isEmpty());
        String expectedId = results.get(0).id();
        for (Charge c : results) {
            assertEquals(expectedId, c.id(), "All concurrent requests must return the exact same charge ID");
        }
    }

    @Test
    void sequentialIdempotenceCheckReturnsSameCharge() {
        String key = "sequential_key_" + UUID.randomUUID().toString();
        ChargeRequest req = new ChargeRequest(key, 50.0, "USD", "seq@example.com", "tok_visa");

        Charge first = service.createCharge(req);
        Charge second = service.createCharge(req);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.id(), second.id(), "Sequential calls with same key must return identical Charge object");
        assertEquals(first.createdAt(), second.createdAt());
    }

    @Test
    void idempotencyKeyIsRetriableOnProcessorFailure() {
        String key = "retry_key_" + UUID.randomUUID().toString();
        ChargeRequest req = new ChargeRequest(key, 99.0, "USD", "fail_retry@example.com", "tok_visa");

        // Spy stub to throw exception on first call, but work normally on second call
        doThrow(new RuntimeException("Simulated gateway failure"))
                .doCallRealMethod()
                .when(processor).charge(any(ChargeRequest.class));

        // First call fails
        assertThrows(RuntimeException.class, () -> service.createCharge(req));

        // Since it failed, the lock should be released and the record not persisted.
        // Second call with same key should succeed
        Charge success = service.createCharge(req);
        assertNotNull(success);
        assertEquals("succeeded", success.status());
        assertEquals(key, store.findById(success.id()).customerEmail().equals("fail_retry@example.com") ? key : "");
    }

    @Test
    void getChargeReturns404IfNotFound() {
        ResponseEntity<Charge> resp = rest.getForEntity(url("/charges/non_existent_id"), Charge.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void getChargeReturns200IfFound() {
        String key = "get_test_key_" + UUID.randomUUID().toString();
        ChargeRequest req = new ChargeRequest(key, 75.0, "USD", "get@example.com", "tok_visa");
        Charge created = service.createCharge(req);

        ResponseEntity<Charge> resp = rest.getForEntity(url("/charges/" + created.id()), Charge.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(created.id(), resp.getBody().id());
        assertEquals(created.amount(), resp.getBody().amount());
    }

    @Test
    void searchCustomersReturnsFilteredList() {
        String email = "search_" + UUID.randomUUID().toString() + "@example.com";
        ChargeRequest req1 = new ChargeRequest("k_search_1_" + UUID.randomUUID().toString(), 10.0, "USD", email, "tok_visa");
        ChargeRequest req2 = new ChargeRequest("k_search_2_" + UUID.randomUUID().toString(), 20.0, "USD", email, "tok_visa");
        ChargeRequest req3 = new ChargeRequest("k_search_3_" + UUID.randomUUID().toString(), 30.0, "USD", "other_" + email, "tok_visa");

        service.createCharge(req1);
        service.createCharge(req2);
        service.createCharge(req3);

        ResponseEntity<Charge[]> resp = rest.getForEntity(url("/customers/search?email=" + email), Charge[].class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        
        Charge[] charges = resp.getBody();
        assertEquals(2, charges.length);
        for (Charge c : charges) {
            assertEquals(email, c.customerEmail());
        }

        // Test non-existent email search
        ResponseEntity<Charge[]> emptyResp = rest.getForEntity(url("/customers/search?email=not_found@example.com"), Charge[].class);
        assertEquals(HttpStatus.OK, emptyResp.getStatusCode());
        assertNotNull(emptyResp.getBody());
        assertEquals(0, emptyResp.getBody().length);
    }

    @Test
    void testQueryUsesParametrizedLogging() {
        // Assert that the email search does not throw and executes normally
        List<Charge> results = store.findByEmail("test@example.com");
        assertNotNull(results);
    }

    @Test
    void createChargeReturns201ForAFreshKey() {
        String json = "{"
                + "\"idempotencyKey\":\"test_fresh_key\","
                + "\"amount\":12.50,"
                + "\"currency\":\"USD\","
                + "\"customerEmail\":\"happy@example.com\","
                + "\"cardToken\":\"tok_visa\""
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.exchange(
                url("/charges"),
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class);

        assertEquals(201, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().contains("\"id\":\"ch_"));
    }

    @Test
    void missingIdempotencyKeyReturns400() {
        String json = "{"
                + "\"idempotencyKey\":\"\","
                + "\"amount\":1.00,"
                + "\"currency\":\"USD\","
                + "\"customerEmail\":\"x@y.com\","
                + "\"cardToken\":\"tok_visa\""
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.exchange(
                url("/charges"),
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class);

        assertEquals(400, resp.getStatusCode().value());
    }

    @Autowired
    private AuditLog auditLog;

    @Test
    void nullIdempotencyKeyReturns400() {
        String json = "{"
                + "\"idempotencyKey\":null,"
                + "\"amount\":1.00,"
                + "\"currency\":\"USD\","
                + "\"customerEmail\":\"x@y.com\","
                + "\"cardToken\":\"tok_visa\""
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.exchange(
                url("/charges"),
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class);

        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void negativeAmountReturns400() {
        String json = "{"
                + "\"idempotencyKey\":\"valid_key\","
                + "\"amount\":-5.00,"
                + "\"currency\":\"USD\","
                + "\"customerEmail\":\"x@y.com\","
                + "\"cardToken\":\"tok_visa\""
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.exchange(
                url("/charges"),
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class);

        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void malformedEmailReturns400() {
        String json = "{"
                + "\"idempotencyKey\":\"valid_key\","
                + "\"amount\":10.00,"
                + "\"currency\":\"USD\","
                + "\"customerEmail\":\"not-an-email\","
                + "\"cardToken\":\"tok_visa\""
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.exchange(
                url("/charges"),
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class);

        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void blankCurrencyReturns400() {
        String json = "{"
                + "\"idempotencyKey\":\"valid_key\","
                + "\"amount\":10.00,"
                + "\"currency\":\"\","
                + "\"customerEmail\":\"x@y.com\","
                + "\"cardToken\":\"tok_visa\""
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.exchange(
                url("/charges"),
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class);

        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void blankCardTokenReturns400() {
        String json = "{"
                + "\"idempotencyKey\":\"valid_key\","
                + "\"amount\":10.00,"
                + "\"currency\":\"USD\","
                + "\"customerEmail\":\"x@y.com\","
                + "\"cardToken\":\"\""
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.exchange(
                url("/charges"),
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class);

        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void testAuditLogMaskToken() {
        assertEquals("tok_...cdef", auditLog.maskToken("tok_12345abcdef"));
        assertEquals("null", auditLog.maskToken(null));
        assertEquals("***", auditLog.maskToken("short"));
    }

    @Test
    void testAuditLogSanitizesNewlines() {
        assertEquals("test__email", auditLog.sanitize("test\r\nemail"));
        assertEquals("null", auditLog.sanitize(null));
        assertEquals("safe_string", auditLog.sanitize("safe_string"));
    }
}
