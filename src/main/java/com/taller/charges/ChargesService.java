package com.taller.charges;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class ChargesService {

    private static final Logger log = Logger.getLogger(ChargesService.class.getName());

    @Value("${stripe.api.key}")
    private String stripeApiKey;

    @Autowired private ChargeStore store;
    @Autowired private PaymentProcessor processor;
    @Autowired private AuditLog audit;

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public String getStripeApiKey() {
        return stripeApiKey;
    }

    @Transactional
    public Charge createCharge(ChargeRequest req) {
        String key = req.idempotencyKey();
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                // Idempotency check
                Charge existing = store.findByKey(key);
                if (existing != null) {
                    return existing;
                }

                // Process the charge
                Charge charge = processor.charge(req);

                // Persist
                persist(key, charge);

                // Audit — fire and forget so we don't slow down the response
                audit.logCharge(charge, req.customerEmail(), req.cardToken());

                return charge;
            } finally {
                locks.remove(key, lock);
            }
        }
    }

    public void persist(String key, Charge charge) {
        store.save(key, charge);
    }
}

@Service
class PaymentProcessor {
    public Charge charge(ChargeRequest req) {
        // Simulate latency talking to the processor
        try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        String id = "ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new Charge(id, req.amount(), req.currency(), req.customerEmail(), "succeeded", Instant.now().toString());
    }
}

@Service
class AuditLog {
    private static final Logger log = Logger.getLogger(AuditLog.class.getName());

    public void logCharge(Charge charge, String customerEmail, String cardToken) {
        log.info(String.format(
                "audit charge=%s amount=%s %s email=%s card=%s at=%s",
                charge.id(), charge.amount(), charge.currency(),
                sanitize(customerEmail), sanitize(maskToken(cardToken)), charge.createdAt()
        ));
    }

    String maskToken(String token) {
        if (token == null) {
            return "null";
        }
        if (token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    String sanitize(String input) {
        if (input == null) {
            return "null";
        }
        return input.replace("\n", "_").replace("\r", "_");
    }
}
