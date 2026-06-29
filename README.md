# Charges API (Java / Spring Boot)

Small payments service. Exposes `POST /charges` (idempotent), `GET /charges/{id}`, and `GET /customers/search?email=`.

## Run

### Local execution
```bash
mvn spring-boot:run
```

The API listens on `http://localhost:8080`.

### Running in Docker

#### Build and run with Docker Compose (Recommended)
```bash
docker compose up --build
```

#### Run with Docker CLI
1. Build the image:
   ```bash
   docker build -t charges-api .
   ```
2. Run the container:
   ```bash
   docker run -p 8080:8080 -e STRIPE_API_KEY=your_key charges-api
   ```

## Quick smoke test

```bash
curl -X POST http://localhost:8080/charges \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"k1","amount":12.50,"currency":"USD","customerEmail":"a@b.com","cardToken":"tok_visa"}'
```

## Security & Concurrency Fixes (Resolved)

The following issues reported by customers and the security review team have been resolved:
1. **Idempotency Locks**: Concurrency checks on `idempotencyKey` are thread-safe and protected from parallel race conditions.
2. **Data-Store Thread Safety**: Internal storage lists and maps converted to Concurrent collections.
3. **SQL Injection**: Parameterized queries logging format resolved raw SQL injection risks.
4. **Log Compliance**: Sensitive Stripe card tokens masked in logging outputs, and logs sanitized from CRLF injection.
5. **Request Payload Validations**: Integrated automatic validation constraints on the request body properties.

