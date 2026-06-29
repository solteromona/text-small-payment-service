package com.taller.charges;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ChargesController {

    @Autowired private ChargesService service;
    @Autowired private ChargeStore store;

    @PostMapping("/charges")
    public ResponseEntity<Charge> createCharge(@Valid @RequestBody ChargeRequest req) {
        Charge charge = service.createCharge(req);
        return ResponseEntity.status(201).body(charge);
    }

    @GetMapping("/charges/{id}")
    public ResponseEntity<Charge> getCharge(@PathVariable String id) {
        Charge c = store.findById(id);
        return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(c);
    }

    @GetMapping("/customers/search")
    public List<Charge> searchCustomers(@RequestParam String email) {
        // Quick lookup by email for the support team
        return store.findByEmail(email);
    }
}
