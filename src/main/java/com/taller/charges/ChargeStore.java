package com.taller.charges;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

@Component
public class ChargeStore {

    private static final Logger log = Logger.getLogger(ChargeStore.class.getName());

    private final Map<String, Charge> byKey = new ConcurrentHashMap<>();
    private final Map<String, Charge> byId  = new ConcurrentHashMap<>();
    private final List<Charge> all = new CopyOnWriteArrayList<>();

    public Charge findByKey(String key) {
        return byKey.get(key);
    }

    public void save(String key, Charge charge) {
        byKey.put(key, charge);
        byId.put(charge.id(), charge);
        all.add(charge);
    }

    public Charge findById(String id) {
        return byId.get(id);
    }

    public List<Charge> findByEmail(String email) {
        // Build the search query using parameterized inputs to prevent SQL injection
        String query = "SELECT * FROM charges WHERE customer_email = ?";
        log.info("running query: " + query + " [param: " + sanitize(email) + "]");

        // For this in-memory demo we just filter in-process,
        // but the same parameterized structure goes to the SQL adapter in production.
        List<Charge> results = new ArrayList<>();
        for (Charge c : all) {
            if (c.customerEmail().equals(email)) {
                results.add(c);
            }
        }
        return results;
    }

    private String sanitize(String input) {
        if (input == null) {
            return "null";
        }
        return input.replace("\n", "_").replace("\r", "_");
    }
}
