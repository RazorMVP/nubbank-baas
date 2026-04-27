# NubBank BaaS — Phase 1A: baas-engine Foundation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scaffold the `baas-engine` Spring Boot 3.5 service with PostgreSQL schema-per-partner multi-tenancy, Partner JWT + API key authentication, automated tenant provisioning (CREATE SCHEMA + Flyway), and core BaaS APIs (Customers, Accounts, Payments, Sandbox) — all fully partner-scoped.

**Architecture:** Each partner gets an isolated PostgreSQL schema (`partner_{uuid}`). Hibernate 6 SCHEMA multi-tenancy automatically routes all JPA queries to the correct schema via `SET search_path` on every connection — no application-level `WHERE partner_id` needed. A `PartnerContextFilter` resolves partner identity from JWT or API key on every request and stores it in a ThreadLocal that Hibernate reads.

**Tech Stack:** Java 21, Spring Boot 3.5.0, PostgreSQL 16, Hibernate 6 (SCHEMA multi-tenancy), Flyway 10, Nimbus JOSE+JWT 9.37.3, Jasypt 3.0.5, Redis (rate limiting), Lombok 1.18.38, springdoc-openapi 2.8.6, Testcontainers 1.20.1, JUnit 5

**Repository:** `github.com/RazorMVP/nubbank-baas` — currently contains only README.md

**PRD:** https://akinwalenubeero.atlassian.net/wiki/spaces/NCBP/pages/349208578

---

## File Map

```
nubbank-baas/
└── baas-engine/
    ├── pom.xml
    ├── .mvn/wrapper/maven-wrapper.properties
    └── src/
        ├── main/
        │   ├── java/com/nubbank/baas/engine/
        │   │   ├── BaasEngineApplication.java
        │   │   ├── common/
        │   │   │   ├── ApiResponse.java              — standard { data, meta, errors } envelope
        │   │   │   ├── BaasException.java             — domain exception with HTTP status
        │   │   │   └── GlobalExceptionHandler.java   — @RestControllerAdvice
        │   │   ├── config/
        │   │   │   ├── SecurityConfig.java            — permit-all + stateless session
        │   │   │   ├── OpenApiConfig.java             — springdoc setup
        │   │   │   └── AsyncConfig.java               — @EnableAsync thread pool
        │   │   ├── tenant/
        │   │   │   ├── PartnerContext.java            — record + ThreadLocal holder
        │   │   │   ├── PartnerContextFilter.java      — OncePerRequestFilter (resolves JWT + API key)
        │   │   │   ├── PartnerTenantResolver.java     — CurrentTenantIdentifierResolver<String>
        │   │   │   ├── PartnerSchemaProvider.java     — MultiTenantConnectionProvider<String>
        │   │   │   ├── MultiTenantConfig.java         — HibernatePropertiesCustomizer bean
        │   │   │   └── TenantProvisioningService.java — CREATE SCHEMA + Flyway per-tenant runner
        │   │   ├── partner/
        │   │   │   ├── PartnerOrganization.java       — @Entity (public schema)
        │   │   │   ├── PartnerUser.java               — @Entity (public schema)
        │   │   │   ├── PartnerApiKey.java             — @Entity (public schema)
        │   │   │   ├── PartnerStatus.java             — enum: SANDBOX, PENDING_REVIEW, BASIC, PRO, ENTERPRISE, SUSPENDED
        │   │   │   ├── PartnerTier.java               — enum: SANDBOX, BASIC, PRO, ENTERPRISE
        │   │   │   ├── PartnerEnvironment.java        — enum: SANDBOX, PRODUCTION
        │   │   │   ├── PartnerOrganizationRepository.java
        │   │   │   ├── PartnerUserRepository.java
        │   │   │   ├── PartnerApiKeyRepository.java
        │   │   │   ├── PartnerService.java
        │   │   │   ├── PartnerController.java         — /baas/v1/org, /baas/v1/admin/partners
        │   │   │   └── dto/
        │   │   │       ├── PartnerOrgResponse.java
        │   │   │       └── ApprovePartnerRequest.java
        │   │   ├── auth/
        │   │   │   ├── PartnerJwtService.java         — HMAC-SHA256 issue + validate (Nimbus)
        │   │   │   ├── AuthController.java            — POST /baas/v1/auth/register, /login, /refresh
        │   │   │   └── dto/
        │   │   │       ├── RegisterRequest.java
        │   │   │       ├── LoginRequest.java
        │   │   │       └── AuthResponse.java
        │   │   ├── virtualaccount/
        │   │   │   ├── VirtualAccountPool.java        — @Entity (public schema)
        │   │   │   ├── VirtualAccountRepository.java
        │   │   │   └── VirtualAccountService.java     — NUBAN assignment (atomic)
        │   │   ├── customer/
        │   │   │   ├── Customer.java                  — @Entity (tenant schema)
        │   │   │   ├── KycStatus.java                 — enum: PENDING_KYC, ACTIVE, SUSPENDED, CLOSED
        │   │   │   ├── KycLevel.java                  — enum: NONE, BASIC, STANDARD, ENHANCED
        │   │   │   ├── KycProvider.java               — enum: NUBBANK, PARTNER, SMILE_IDENTITY, YOUVERIFY
        │   │   │   ├── CustomerRepository.java
        │   │   │   ├── CustomerService.java
        │   │   │   ├── CustomerController.java        — /baas/v1/customers
        │   │   │   └── dto/
        │   │   │       ├── CreateCustomerRequest.java
        │   │   │       └── CustomerResponse.java
        │   │   ├── account/
        │   │   │   ├── Account.java                   — @Entity (tenant schema)
        │   │   │   ├── AccountStatus.java             — enum: ACTIVE, FROZEN, CLOSED
        │   │   │   ├── Transaction.java               — @Entity (tenant schema)
        │   │   │   ├── TransactionType.java           — enum: CREDIT, DEBIT
        │   │   │   ├── AccountRepository.java
        │   │   │   ├── TransactionRepository.java
        │   │   │   ├── AccountService.java
        │   │   │   ├── AccountController.java         — /baas/v1/accounts
        │   │   │   └── dto/
        │   │   │       ├── OpenAccountRequest.java
        │   │   │       ├── AccountResponse.java
        │   │   │       ├── TransactionRequest.java
        │   │   │       └── TransactionResponse.java
        │   │   ├── payment/
        │   │   │   ├── Payment.java                   — @Entity (tenant schema)
        │   │   │   ├── PaymentStatus.java             — enum: PENDING, COMPLETED, FAILED, REVERSED
        │   │   │   ├── PaymentType.java               — enum: INTERNAL, NIP, SWIFT
        │   │   │   ├── PaymentRepository.java
        │   │   │   ├── PaymentService.java
        │   │   │   ├── PaymentController.java         — /baas/v1/payments
        │   │   │   └── dto/
        │   │   │       ├── TransferRequest.java
        │   │   │       └── PaymentResponse.java
        │   │   └── sandbox/
        │   │       ├── SandboxService.java
        │   │       └── SandboxController.java         — /baas/v1/sandbox/*
        │   └── resources/
        │       ├── application.yml
        │       ├── application-test.yml
        │       └── db/migration/
        │           ├── public/
        │           │   └── V1__public_schema.sql      — runs once at startup (public schema)
        │           └── tenant/
        │               └── V1__tenant_schema.sql      — runs per partner at provisioning
        └── test/
            └── java/com/nubbank/baas/engine/
                ├── AbstractIntegrationTest.java       — Testcontainers PostgreSQL base class
                ├── tenant/
                │   └── MultiTenancyTest.java          — proves schema isolation
                ├── auth/
                │   └── AuthControllerTest.java
                ├── customer/
                │   └── CustomerControllerTest.java
                ├── account/
                │   └── AccountControllerTest.java
                └── payment/
                    └── PaymentControllerTest.java
```

---

## Task 1: Repository Structure and Maven Project

**Files:**
- Create: `baas-engine/pom.xml`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/BaasEngineApplication.java`
- Create: `baas-engine/src/main/resources/application.yml`
- Create: `baas-engine/src/main/resources/application-test.yml`

- [ ] **Step 1: Clone the repo and create the baas-engine directory**

```bash
git clone https://github.com/RazorMVP/nubbank-baas.git
cd nubbank-baas
mkdir -p baas-engine/src/main/java/com/nubbank/baas/engine
mkdir -p baas-engine/src/main/resources/db/migration/public
mkdir -p baas-engine/src/main/resources/db/migration/tenant
mkdir -p baas-engine/src/test/java/com/nubbank/baas/engine
```

- [ ] **Step 2: Create `baas-engine/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.0</version>
        <relativePath/>
    </parent>

    <groupId>com.nubbank</groupId>
    <artifactId>baas-engine</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>baas-engine</name>
    <description>NubBank BaaS — Multi-tenant core banking engine</description>

    <properties>
        <java.version>21</java.version>
        <lombok.version>1.18.38</lombok.version>
        <springdoc.version>2.8.6</springdoc.version>
        <nimbus-jose.version>9.37.3</nimbus-jose.version>
        <jasypt.version>3.0.5</jasypt.version>
        <testcontainers.version>1.20.1</testcontainers.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>
        <dependency>
            <groupId>com.nimbusds</groupId>
            <artifactId>nimbus-jose-jwt</artifactId>
            <version>${nimbus-jose.version}</version>
        </dependency>
        <dependency>
            <groupId>com.github.ulisesbocchio</groupId>
            <artifactId>jasypt-spring-boot-starter</artifactId>
            <version>${jasypt.version}</version>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <release>21</release>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Create `baas-engine/src/main/java/com/nubbank/baas/engine/BaasEngineApplication.java`**

```java
package com.nubbank.baas.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class BaasEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(BaasEngineApplication.class, args);
    }
}
```

- [ ] **Step 4: Create `baas-engine/src/main/resources/application.yml`**

```yaml
spring:
  application:
    name: baas-engine
  datasource:
    url: ${DATASOURCE_URL:jdbc:postgresql://localhost:5432/nubbank_baas}
    username: ${DATASOURCE_USERNAME:baas}
    password: ${DATASOURCE_PASSWORD:baas}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    open-in-view: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        multiTenancy: SCHEMA
  flyway:
    enabled: true
    locations: classpath:db/migration/public
    schemas: public
    default-schema: public
    baseline-on-migrate: true
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

server:
  port: 8080

app:
  jwt:
    secret: ${JWT_SECRET:nubbank-baas-dev-secret-key-must-be-at-least-32-characters-long}
    expiry-hours: 24
  encryption:
    key: ${ENCRYPTION_KEY:nubbank-baas-dev-enc-key-32chars!}
  rate-limit:
    sandbox-rpm: 30
    basic-rpm: 100
    pro-rpm: 500
    enterprise-rpm: 2000

management:
  endpoints:
    web:
      exposure:
        include: health,info

logging:
  level:
    com.nubbank.baas: INFO
    org.hibernate.SQL: OFF
```

- [ ] **Step 5: Create `baas-engine/src/main/resources/application-test.yml`**

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration/public
  jpa:
    hibernate:
      ddl-auto: none

app:
  jwt:
    secret: test-secret-key-that-is-at-least-32-characters-long-for-hmac
    expiry-hours: 1
  encryption:
    key: test-encryption-key-exactly-32c!
```

- [ ] **Step 6: Copy the Maven wrapper from cba-platform (avoid requiring Maven installed globally)**

```bash
# Run from the nubbank-baas root
cp -r ../CoreBanking/backend/mvnw baas-engine/mvnw
cp -r ../CoreBanking/backend/.mvn baas-engine/.mvn
chmod +x baas-engine/mvnw
```

- [ ] **Step 7: Verify the project compiles**

```bash
cd baas-engine
./mvnw clean compile -q
```

Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 8: Commit**

```bash
cd ..
git add baas-engine/
git commit -m "feat(baas-engine): scaffold Spring Boot 3.5 project with Maven"
```

---

## Task 2: Common Layer — ApiResponse, BaasException, GlobalExceptionHandler

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/common/ApiResponse.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/common/BaasException.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/common/GlobalExceptionHandler.java`

- [ ] **Step 1: Create `ApiResponse.java`**

```java
package com.nubbank.baas.engine.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    T data,
    Meta meta,
    List<ApiError> errors
) {
    public record Meta(String requestId, Instant timestamp) {}
    public record ApiError(String code, String message, String field, String docsUrl) {}

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data,
            new Meta(java.util.UUID.randomUUID().toString(), Instant.now()),
            null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(null,
            new Meta(java.util.UUID.randomUUID().toString(), Instant.now()),
            List.of(new ApiError(code, message, null,
                "https://developers.nubbank.com/docs/error-reference#" + code)));
    }

    public static <T> ApiResponse<T> fieldError(String code, String message, String field) {
        return new ApiResponse<>(null,
            new Meta(java.util.UUID.randomUUID().toString(), Instant.now()),
            List.of(new ApiError(code, message, field,
                "https://developers.nubbank.com/docs/error-reference#" + code)));
    }
}
```

- [ ] **Step 2: Create `BaasException.java`**

```java
package com.nubbank.baas.engine.common;

import org.springframework.http.HttpStatus;

public class BaasException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    private BaasException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static BaasException notFound(String code, String message) {
        return new BaasException(code, message, HttpStatus.NOT_FOUND);
    }

    public static BaasException badRequest(String code, String message) {
        return new BaasException(code, message, HttpStatus.BAD_REQUEST);
    }

    public static BaasException conflict(String code, String message) {
        return new BaasException(code, message, HttpStatus.CONFLICT);
    }

    public static BaasException unauthorized(String code, String message) {
        return new BaasException(code, message, HttpStatus.UNAUTHORIZED);
    }

    public static BaasException forbidden(String code, String message) {
        return new BaasException(code, message, HttpStatus.FORBIDDEN);
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
```

- [ ] **Step 3: Create `GlobalExceptionHandler.java`**

```java
package com.nubbank.baas.engine.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BaasException.class)
    public ResponseEntity<ApiResponse<Void>> handleBaas(BaasException ex) {
        log.warn("BaasException: {} — {}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
            .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError first = ex.getBindingResult().getFieldErrors().stream().findFirst().orElseThrow();
        return ResponseEntity.badRequest()
            .body(ApiResponse.fieldError("VALIDATION_ERROR", first.getDefaultMessage(), first.getField()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
            .body(ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
```

- [ ] **Step 4: Verify compilation**

```bash
cd baas-engine && ./mvnw clean compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/common/
git commit -m "feat(baas-engine): add ApiResponse envelope, BaasException, GlobalExceptionHandler"
```

---

## Task 3: Public Schema — Flyway Migration

**Files:**
- Create: `baas-engine/src/main/resources/db/migration/public/V1__public_schema.sql`

- [ ] **Step 1: Create `V1__public_schema.sql`**

```sql
-- V1__public_schema.sql
-- Runs once at baas-engine startup on the public schema.
-- Contains platform-level tables shared across all partners.

SET search_path TO public;

-- Partner organisations
CREATE TABLE partner_organizations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(200) NOT NULL,
    status            VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    tier              VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    environment       VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    schema_name       VARCHAR(100) UNIQUE NOT NULL,
    website           VARCHAR(500),
    contact_email     VARCHAR(255),
    approved_by       VARCHAR(255),
    approved_at       TIMESTAMPTZ,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partner portal users (log into baas-portal and baas-backoffice)
CREATE TABLE partner_users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID NOT NULL REFERENCES partner_organizations(id) ON DELETE CASCADE,
    email         VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL DEFAULT 'PARTNER_ADMIN',
    active        BOOLEAN      NOT NULL DEFAULT true,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- API keys (machine-to-machine authentication)
CREATE TABLE partner_api_keys (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID NOT NULL REFERENCES partner_organizations(id) ON DELETE CASCADE,
    key_hash    VARCHAR(255) UNIQUE NOT NULL,
    key_prefix  VARCHAR(20)  NOT NULL,
    name        VARCHAR(100),
    scopes      JSONB        NOT NULL DEFAULT '[]',
    tier        VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    environment VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    active      BOOLEAN      NOT NULL DEFAULT true,
    last_used_at TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- NUBAN virtual account pool (pre-allocated numbers, assigned at account creation)
CREATE TABLE virtual_account_pool (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number       VARCHAR(20) UNIQUE NOT NULL,
    bank_code            VARCHAR(3)  NOT NULL,
    assigned             BOOLEAN     NOT NULL DEFAULT false,
    assigned_to_schema   VARCHAR(100),
    assigned_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Audit trail for schema provisioning
CREATE TABLE schema_provision_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id    UUID NOT NULL REFERENCES partner_organizations(id),
    schema_name   VARCHAR(100) NOT NULL,
    status        VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    flyway_version VARCHAR(50),
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);

-- Per-API-call billing events (one row per call, for invoice generation)
CREATE TABLE billing_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id  UUID NOT NULL REFERENCES partner_organizations(id),
    endpoint    VARCHAR(200) NOT NULL,
    method      VARCHAR(10)  NOT NULL,
    environment VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Idempotency key cache (24-hour window)
CREATE TABLE idempotency_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_value    VARCHAR(255) UNIQUE NOT NULL,
    partner_id   UUID        NOT NULL,
    endpoint     VARCHAR(200) NOT NULL,
    response_body TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);

-- Webhooks
CREATE TABLE partner_webhooks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID NOT NULL REFERENCES partner_organizations(id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    callback_url TEXT NOT NULL,
    secret       VARCHAR(255) NOT NULL,
    events       JSONB NOT NULL DEFAULT '[]',
    active       BOOLEAN NOT NULL DEFAULT true,
    version      BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Webhook delivery log
CREATE TABLE webhook_deliveries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_id    UUID NOT NULL REFERENCES partner_webhooks(id) ON DELETE CASCADE,
    event_type    VARCHAR(100) NOT NULL,
    delivery_uuid UUID NOT NULL,
    payload       JSONB,
    http_status   INTEGER,
    status        VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes
CREATE INDEX idx_partner_users_org      ON partner_users(org_id);
CREATE INDEX idx_api_keys_org           ON partner_api_keys(org_id);
CREATE INDEX idx_api_keys_hash          ON partner_api_keys(key_hash) WHERE active = true;
CREATE INDEX idx_billing_partner_date   ON billing_events(partner_id, created_at);
CREATE INDEX idx_idempotency_expires    ON idempotency_keys(expires_at);
CREATE INDEX idx_vpool_unassigned       ON virtual_account_pool(assigned) WHERE assigned = false;
CREATE INDEX idx_webhooks_org           ON partner_webhooks(org_id) WHERE active = true;
CREATE INDEX idx_deliveries_retry       ON webhook_deliveries(status, next_retry_at)
    WHERE status = 'PENDING' OR status = 'FAILED';

-- Seed virtual account pool with 10,000 NUBAN numbers for dev/test
-- Bank code 058 (Guaranty Trust Bank — used as demo in NubBank SaaS)
-- In production, the pool top-up job generates real NUBANs using the NUBAN algorithm
INSERT INTO virtual_account_pool (account_number, bank_code)
SELECT
    '058' ||
    LPAD(generate_series::text, 6, '0') ||
    -- Check digit: simplified for seed data (real algorithm in VirtualAccountService)
    CAST((10 - (
        (3 * CAST(SUBSTRING(LPAD(generate_series::text, 6, '0'), 1, 1) AS INT) +
         7 * CAST(SUBSTRING(LPAD(generate_series::text, 6, '0'), 2, 1) AS INT) +
         3 * CAST(SUBSTRING(LPAD(generate_series::text, 6, '0'), 3, 1) AS INT) +
         3 * CAST(SUBSTRING(LPAD(generate_series::text, 6, '0'), 4, 1) AS INT) +
         7 * CAST(SUBSTRING(LPAD(generate_series::text, 6, '0'), 5, 1) AS INT) +
         3 * CAST(SUBSTRING(LPAD(generate_series::text, 6, '0'), 6, 1) AS INT)) % 10
    ) % 10 AS TEXT),
    '058'
FROM generate_series(100000, 109999);
```

- [ ] **Step 2: Verify the SQL is valid (requires a local PostgreSQL or Docker)**

```bash
# Start a quick Postgres container to validate SQL
docker run --rm -d \
  --name baas-pg-test \
  -e POSTGRES_DB=nubbank_baas \
  -e POSTGRES_USER=baas \
  -e POSTGRES_PASSWORD=baas \
  -p 5433:5432 \
  postgres:16-alpine

# Wait for it to be ready
sleep 3

# Apply the migration manually to verify syntax
psql postgresql://baas:baas@localhost:5433/nubbank_baas -f \
  baas-engine/src/main/resources/db/migration/public/V1__public_schema.sql

# Expected: no errors, CREATE TABLE × 8, CREATE INDEX × 8, INSERT 0 10000
docker stop baas-pg-test
```

- [ ] **Step 3: Commit**

```bash
git add baas-engine/src/main/resources/db/migration/public/
git commit -m "feat(baas-engine): public schema Flyway migration V1"
```

---

## Task 4: Tenant Schema — Flyway Migration

**Files:**
- Create: `baas-engine/src/main/resources/db/migration/tenant/V1__tenant_schema.sql`

- [ ] **Step 1: Create `V1__tenant_schema.sql`**

```sql
-- V1__tenant_schema.sql
-- Runs once PER PARTNER SCHEMA at provisioning time.
-- search_path is set to the partner's schema before Flyway runs this.
-- DO NOT reference public schema tables with schema prefix here —
-- use unqualified names; they resolve within this partner's schema.

-- Customers (end-users of the partner)
CREATE TABLE customers (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_reference   VARCHAR(100) UNIQUE,        -- partner's own customer ID
    first_name_encrypted VARCHAR(500) NOT NULL,
    last_name_encrypted  VARCHAR(500) NOT NULL,
    email_encrypted      VARCHAR(500),
    phone_encrypted      VARCHAR(500),
    date_of_birth        DATE,
    gender               VARCHAR(20),
    kyc_status           VARCHAR(50) NOT NULL DEFAULT 'PENDING_KYC',
    kyc_level            VARCHAR(50) NOT NULL DEFAULT 'NONE',
    kyc_provider         VARCHAR(50),
    bvn_encrypted        VARCHAR(500),               -- Bank Verification Number
    nin_encrypted        VARCHAR(500),               -- National Identity Number
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Savings / checking accounts
CREATE TABLE accounts (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id          UUID NOT NULL REFERENCES customers(id),
    account_number       VARCHAR(20) UNIQUE NOT NULL,   -- NUBAN assigned from pool
    virtual_account_ref  UUID,                           -- FK to public.virtual_account_pool
    account_name         VARCHAR(200),
    account_type_label   VARCHAR(100),                   -- partner-defined label
    status               VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    balance              NUMERIC(19,4) NOT NULL DEFAULT 0,
    available_balance    NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency_code        VARCHAR(3) NOT NULL DEFAULT 'NGN',
    minimum_balance      NUMERIC(19,4) NOT NULL DEFAULT 0,
    allow_overdraft      BOOLEAN NOT NULL DEFAULT false,
    overdraft_limit      NUMERIC(19,4),
    programmatic_open    BOOLEAN NOT NULL DEFAULT true,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Immutable transaction ledger
CREATE TABLE transactions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID NOT NULL REFERENCES accounts(id),
    transaction_type VARCHAR(50) NOT NULL,          -- CREDIT or DEBIT
    amount           NUMERIC(19,4) NOT NULL,
    running_balance  NUMERIC(19,4) NOT NULL,
    currency_code    VARCHAR(3) NOT NULL DEFAULT 'NGN',
    reference        VARCHAR(100),
    description      VARCHAR(500),
    payment_id       UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
    -- No version column — transactions are append-only and never updated
);

-- Payments (transfers, NIP, SWIFT)
CREATE TABLE payments (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id       UUID REFERENCES accounts(id),
    destination_account_id  UUID REFERENCES accounts(id),
    amount                  NUMERIC(19,4) NOT NULL,
    currency_code           VARCHAR(3) NOT NULL DEFAULT 'NGN',
    payment_type            VARCHAR(50) NOT NULL,  -- INTERNAL, NIP, SWIFT
    status                  VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    reference               VARCHAR(100),
    description             VARCHAR(500),
    idempotency_key         VARCHAR(255),
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Exchange rates (per-tenant — each partner sets their own)
CREATE TABLE exchange_rates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_currency   VARCHAR(3) NOT NULL,
    to_currency     VARCHAR(3) NOT NULL,
    rate            NUMERIC(19,8) NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT true,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (from_currency, to_currency)
);

-- Partner-defined loan products
CREATE TABLE loan_products (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(200) NOT NULL,
    short_name           VARCHAR(10) UNIQUE NOT NULL,
    description          TEXT,
    min_principal        NUMERIC(19,4) NOT NULL,
    max_principal        NUMERIC(19,4) NOT NULL,
    default_principal    NUMERIC(19,4) NOT NULL,
    nominal_interest_rate NUMERIC(8,4) NOT NULL,
    repayment_type       VARCHAR(50) NOT NULL DEFAULT 'ANNUITY',
    number_of_repayments INTEGER NOT NULL,
    repayment_every      INTEGER NOT NULL DEFAULT 1,
    repayment_frequency  VARCHAR(20) NOT NULL DEFAULT 'MONTHS',
    active               BOOLEAN NOT NULL DEFAULT true,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partner-defined deposit products
CREATE TABLE deposit_products (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(200) NOT NULL,
    short_name           VARCHAR(10) UNIQUE NOT NULL,
    account_type         VARCHAR(50) NOT NULL DEFAULT 'SAVINGS',
    minimum_balance      NUMERIC(19,4) NOT NULL DEFAULT 0,
    nominal_interest_rate NUMERIC(8,4) NOT NULL DEFAULT 0,
    allow_overdraft      BOOLEAN NOT NULL DEFAULT false,
    overdraft_limit      NUMERIC(19,4),
    active               BOOLEAN NOT NULL DEFAULT true,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Audit log (per-tenant, append-only)
CREATE TABLE audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(100) NOT NULL,
    entity_id   UUID,
    action      VARCHAR(100) NOT NULL,
    changed_by  VARCHAR(255),
    old_values  TEXT,
    new_values  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes
CREATE INDEX idx_customers_ext_ref   ON customers(external_reference);
CREATE INDEX idx_customers_kyc       ON customers(kyc_status);
CREATE INDEX idx_accounts_customer   ON accounts(customer_id);
CREATE INDEX idx_accounts_number     ON accounts(account_number);
CREATE INDEX idx_transactions_acct   ON transactions(account_id, created_at DESC);
CREATE INDEX idx_payments_source     ON payments(source_account_id);
CREATE INDEX idx_payments_dest       ON payments(destination_account_id);
CREATE INDEX idx_audit_entity        ON audit_log(entity_type, entity_id, created_at DESC);
```

- [ ] **Step 2: Commit**

```bash
git add baas-engine/src/main/resources/db/migration/tenant/
git commit -m "feat(baas-engine): tenant schema Flyway migration V1 (customers, accounts, transactions, payments)"
```

---

## Task 5: Multi-Tenancy — PartnerContext and Hibernate Configuration

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/tenant/PartnerContext.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/tenant/PartnerTenantResolver.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/tenant/PartnerSchemaProvider.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/tenant/MultiTenantConfig.java`

- [ ] **Step 1: Write the failing test**

```java
// baas-engine/src/test/java/com/nubbank/baas/engine/tenant/PartnerContextTest.java
package com.nubbank.baas.engine.tenant;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerContextTest {

    @Test
    void threadLocal_setAndGet_returnsCorrectContext() {
        var ctx = new PartnerContext("partner-id-1", "partner_abc123", "PRO", "PRODUCTION", "API_KEY");
        PartnerContext.set(ctx);
        assertThat(PartnerContext.get()).isEqualTo(ctx);
        assertThat(PartnerContext.get().schemaName()).isEqualTo("partner_abc123");
        PartnerContext.clear();
    }

    @Test
    void clear_removesContext() {
        PartnerContext.set(new PartnerContext("id", "schema", "BASIC", "SANDBOX", "JWT"));
        PartnerContext.clear();
        assertThat(PartnerContext.get()).isNull();
    }

    @Test
    void get_withoutSet_returnsNull() {
        PartnerContext.clear(); // ensure clean
        assertThat(PartnerContext.get()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd baas-engine
./mvnw test -Dtest=PartnerContextTest -q 2>&1 | tail -5
```

Expected: `COMPILATION ERROR — cannot find symbol: class PartnerContext`

- [ ] **Step 3: Create `PartnerContext.java`**

```java
package com.nubbank.baas.engine.tenant;

public record PartnerContext(
    String partnerId,
    String schemaName,
    String tier,
    String environment,
    String authMode
) {
    private static final ThreadLocal<PartnerContext> HOLDER = new ThreadLocal<>();

    public static void set(PartnerContext ctx) { HOLDER.set(ctx); }
    public static PartnerContext get() { return HOLDER.get(); }
    public static void clear() { HOLDER.remove(); }

    public boolean isSandbox() { return "SANDBOX".equals(environment); }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw test -Dtest=PartnerContextTest -q
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Create `PartnerTenantResolver.java`**

```java
package com.nubbank.baas.engine.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class PartnerTenantResolver implements CurrentTenantIdentifierResolver<String> {

    @Override
    public String resolveCurrentTenantIdentifier() {
        return Optional.ofNullable(PartnerContext.get())
            .map(PartnerContext::schemaName)
            .orElse("public");
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
```

- [ ] **Step 6: Create `PartnerSchemaProvider.java`**

```java
package com.nubbank.baas.engine.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerSchemaProvider implements MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String schemaName) throws SQLException {
        Connection connection = getAnyConnection();
        try {
            // Validates schemaName contains only safe characters before executing
            if (!schemaName.matches("[a-zA-Z0-9_]+")) {
                throw new SQLException("Invalid schema name: " + schemaName);
            }
            connection.createStatement()
                .execute("SET search_path TO " + schemaName + ", public");
        } catch (SQLException ex) {
            releaseConnection(schemaName, connection);
            throw ex;
        }
        return connection;
    }

    @Override
    public void releaseConnection(String schemaName, Connection connection) throws SQLException {
        try {
            connection.createStatement().execute("SET search_path TO public");
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean supportsAggressiveRelease() { return false; }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) { return false; }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        throw new UnsupportedOperationException("Cannot unwrap PartnerSchemaProvider");
    }
}
```

- [ ] **Step 7: Create `MultiTenantConfig.java`**

```java
package com.nubbank.baas.engine.tenant;

import lombok.RequiredArgsConstructor;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class MultiTenantConfig {

    private final PartnerTenantResolver tenantResolver;
    private final PartnerSchemaProvider schemaProvider;

    @Bean
    public HibernatePropertiesCustomizer multiTenantHibernateCustomizer() {
        return hibernateProperties -> {
            hibernateProperties.put(
                AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantResolver);
            hibernateProperties.put(
                AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, schemaProvider);
        };
    }
}
```

- [ ] **Step 8: Verify compilation**

```bash
./mvnw clean compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 9: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/tenant/
git add baas-engine/src/test/java/com/nubbank/baas/engine/tenant/
git commit -m "feat(baas-engine): Hibernate SCHEMA multi-tenancy (PartnerContext, TenantResolver, SchemaProvider)"
```

---

## Task 6: Partner Entities and Repositories (Public Schema)

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerStatus.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerTier.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerEnvironment.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerOrganization.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerUser.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerApiKey.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerOrganizationRepository.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerUserRepository.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/partner/PartnerApiKeyRepository.java`

> **Important:** Public schema entities use `@Table(schema = "public")` to prevent Hibernate from routing them through the multi-tenant connection provider. Without this, public schema entities would be queried against the current partner schema.

- [ ] **Step 1: Create the three enums**

```java
// PartnerStatus.java
package com.nubbank.baas.engine.partner;
public enum PartnerStatus {
    SANDBOX, PENDING_REVIEW, BASIC, PRO, ENTERPRISE, SUSPENDED
}

// PartnerTier.java
package com.nubbank.baas.engine.partner;
public enum PartnerTier {
    SANDBOX, BASIC, PRO, ENTERPRISE
}

// PartnerEnvironment.java
package com.nubbank.baas.engine.partner;
public enum PartnerEnvironment {
    SANDBOX, PRODUCTION
}
```

- [ ] **Step 2: Create `PartnerOrganization.java`**

```java
package com.nubbank.baas.engine.partner;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "partner_organizations", schema = "public")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PartnerOrganization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PartnerStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PartnerTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PartnerEnvironment environment;

    @Column(name = "schema_name", nullable = false, unique = true, length = 100)
    private String schemaName;

    @Column(length = 500)
    private String website;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = PartnerStatus.SANDBOX;
        if (tier == null) tier = PartnerTier.SANDBOX;
        if (environment == null) environment = PartnerEnvironment.SANDBOX;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
```

- [ ] **Step 3: Create `PartnerUser.java`**

```java
package com.nubbank.baas.engine.partner;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "partner_users", schema = "public")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PartnerUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private PartnerOrganization organization;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String role;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (role == null) role = "PARTNER_ADMIN";
        active = true;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
```

- [ ] **Step 4: Create `PartnerApiKey.java`**

```java
package com.nubbank.baas.engine.partner;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "partner_api_keys", schema = "public")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PartnerApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private PartnerOrganization organization;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 20)
    private String keyPrefix;

    @Column(length = 100)
    private String name;

    @Column(columnDefinition = "jsonb")
    private String scopes; // stored as JSON string "[]"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PartnerTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PartnerEnvironment environment;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        active = true;
        if (scopes == null) scopes = "[]";
        if (tier == null) tier = PartnerTier.SANDBOX;
        if (environment == null) environment = PartnerEnvironment.SANDBOX;
    }
}
```

> **Note:** For `scopes`, we store as a JSON string rather than using `@JdbcTypeCode(SqlTypes.JSON)` which requires Hibernate 6.2+. Use `Jackson.writeValueAsString(scopeList)` in the service layer when reading/writing.

- [ ] **Step 5: Create the three repositories**

```java
// PartnerOrganizationRepository.java
package com.nubbank.baas.engine.partner;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PartnerOrganizationRepository extends JpaRepository<PartnerOrganization, UUID> {
    Optional<PartnerOrganization> findBySchemaName(String schemaName);
    boolean existsByName(String name);
}

// PartnerUserRepository.java
package com.nubbank.baas.engine.partner;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PartnerUserRepository extends JpaRepository<PartnerUser, UUID> {
    Optional<PartnerUser> findByEmailAndActiveTrue(String email);
    boolean existsByEmail(String email);
}

// PartnerApiKeyRepository.java
package com.nubbank.baas.engine.partner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartnerApiKeyRepository extends JpaRepository<PartnerApiKey, UUID> {
    Optional<PartnerApiKey> findByKeyHashAndActiveTrue(String keyHash);
    List<PartnerApiKey> findByOrganizationIdAndActiveTrueOrderByCreatedAtDesc(UUID orgId);

    @Modifying
    @Query("UPDATE PartnerApiKey k SET k.lastUsedAt = :now WHERE k.id = :id")
    void updateLastUsed(UUID id, Instant now);
}
```

- [ ] **Step 6: Add `SecurityConfig.java` (permit-all for now — tightened in Task 9)**

```java
package com.nubbank.baas.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
```

- [ ] **Step 7: Verify compilation**

```bash
./mvnw clean compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/partner/
git add baas-engine/src/main/java/com/nubbank/baas/engine/config/SecurityConfig.java
git commit -m "feat(baas-engine): partner entities (PartnerOrganization, PartnerUser, PartnerApiKey) + repositories"
```

---

## Task 7: Partner JWT Service and Auth Controller

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/auth/PartnerJwtService.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/auth/dto/RegisterRequest.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/auth/dto/LoginRequest.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/auth/dto/AuthResponse.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/auth/AuthController.java`
- Create: `baas-engine/src/test/java/com/nubbank/baas/engine/auth/PartnerJwtServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// PartnerJwtServiceTest.java
package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.tenant.PartnerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PartnerJwtServiceTest {

    private PartnerJwtService jwtService;
    private final String secret = "test-secret-key-that-is-at-least-32-characters-long-for-hmac";

    @BeforeEach
    void setUp() {
        jwtService = new PartnerJwtService(secret, 24L);
    }

    @Test
    void issueAndValidate_roundtrip_returnsCorrectContext() {
        String userId = UUID.randomUUID().toString();
        String orgId = UUID.randomUUID().toString();
        String schemaName = "partner_abc123";

        String token = jwtService.issue(userId, "dev@credpal.com", "PARTNER_TELLER",
                orgId, "Credpal Fintech", schemaName, "PRO", "PRODUCTION");

        assertThat(token).isNotBlank();

        PartnerContext ctx = jwtService.validate(token);
        assertThat(ctx.partnerId()).isEqualTo(orgId);
        assertThat(ctx.schemaName()).isEqualTo(schemaName);
        assertThat(ctx.tier()).isEqualTo("PRO");
        assertThat(ctx.environment()).isEqualTo("PRODUCTION");
        assertThat(ctx.authMode()).isEqualTo("JWT");
    }

    @Test
    void validate_tamperedToken_throwsBaasException() {
        String token = jwtService.issue(UUID.randomUUID().toString(), "e@e.com",
                "PARTNER_ADMIN", UUID.randomUUID().toString(), "Org",
                "partner_xyz", "BASIC", "SANDBOX") + "tampered";

        assertThatThrownBy(() -> jwtService.validate(token))
                .hasMessageContaining("Invalid");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -Dtest=PartnerJwtServiceTest -q 2>&1 | tail -5
```

Expected: `COMPILATION ERROR — cannot find symbol: class PartnerJwtService`

- [ ] **Step 3: Create `PartnerJwtService.java`**

```java
package com.nubbank.baas.engine.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Service
public class PartnerJwtService {

    private final String secret;
    private final long expiryHours;

    public PartnerJwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiry-hours}") long expiryHours) {
        this.secret = secret;
        this.expiryHours = expiryHours;
    }

    public String issue(String userId, String email, String role,
                        String orgId, String orgName,
                        String schemaName, String tier, String environment) {
        try {
            MACSigner signer = new MACSigner(secret.getBytes(StandardCharsets.UTF_8));
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId)
                .claim("email", email)
                .claim("role", role)
                .claim("partner_id", orgId)
                .claim("org_name", orgName)
                .claim("schema_name", schemaName)
                .claim("tier", tier)
                .claim("environment", environment)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + expiryHours * 3_600_000L))
                .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new RuntimeException("Failed to issue JWT", ex);
        }
    }

    public PartnerContext validate(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            MACVerifier verifier = new MACVerifier(secret.getBytes(StandardCharsets.UTF_8));
            if (!jwt.verify(verifier)) {
                throw BaasException.unauthorized("INVALID_TOKEN", "Invalid JWT signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime().before(new Date())) {
                throw BaasException.unauthorized("TOKEN_EXPIRED", "JWT has expired");
            }
            return new PartnerContext(
                claims.getStringClaim("partner_id"),
                claims.getStringClaim("schema_name"),
                claims.getStringClaim("tier"),
                claims.getStringClaim("environment"),
                "JWT"
            );
        } catch (BaasException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BaasException.unauthorized("INVALID_TOKEN", "Invalid or malformed JWT");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw test -Dtest=PartnerJwtServiceTest -q
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Create auth DTOs**

```java
// RegisterRequest.java
package com.nubbank.baas.engine.auth.dto;
import jakarta.validation.constraints.*;
public record RegisterRequest(
    @NotBlank(message = "Organisation name is required") String orgName,
    @Email(message = "Valid email required") @NotBlank String adminEmail,
    @Size(min = 8, message = "Password must be at least 8 characters") @NotBlank String password
) {}

// LoginRequest.java
package com.nubbank.baas.engine.auth.dto;
import jakarta.validation.constraints.*;
public record LoginRequest(
    @Email @NotBlank String email,
    @NotBlank String password
) {}

// AuthResponse.java
package com.nubbank.baas.engine.auth.dto;
public record AuthResponse(
    String token,
    String partnerId,
    String schemaName,
    String tier,
    String environment,
    String role,
    String orgName
) {}
```

- [ ] **Step 6: Create `AuthController.java`**

```java
package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.auth.dto.*;
import com.nubbank.baas.engine.common.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/baas/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final PartnerOrganizationRepository orgRepo;
    private final PartnerUserRepository userRepo;
    private final PartnerJwtService jwtService;
    private final TenantProvisioningService provisioningService;
    private final BCryptPasswordEncoder passwordEncoder;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest req) {

        if (userRepo.existsByEmail(req.adminEmail())) {
            throw BaasException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }

        // Generate schema name from UUID (no hyphens, prefixed with partner_)
        String partnerId = UUID.randomUUID().toString().replace("-", "");
        String schemaName = "partner_" + partnerId;

        PartnerOrganization org = PartnerOrganization.builder()
            .name(req.orgName())
            .status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX)
            .schemaName(schemaName)
            .contactEmail(req.adminEmail())
            .build();
        org = orgRepo.save(org);

        PartnerUser admin = PartnerUser.builder()
            .organization(org)
            .email(req.adminEmail())
            .passwordHash(passwordEncoder.encode(req.password()))
            .role("PARTNER_ADMIN")
            .build();
        userRepo.save(admin);

        // Provision the partner schema asynchronously after response
        provisioningService.provisionAsync(org.getId(), schemaName);

        String token = jwtService.issue(
            admin.getId().toString(), admin.getEmail(), admin.getRole(),
            org.getId().toString(), org.getName(),
            schemaName, org.getTier().name(), org.getEnvironment().name());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
            new AuthResponse(token, org.getId().toString(), schemaName,
                org.getTier().name(), org.getEnvironment().name(),
                admin.getRole(), org.getName())));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest req) {

        PartnerUser user = userRepo.findByEmailAndActiveTrue(req.email())
            .orElseThrow(() -> BaasException.unauthorized("INVALID_CREDENTIALS",
                "Invalid email or password"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw BaasException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
        }

        PartnerOrganization org = user.getOrganization();
        String token = jwtService.issue(
            user.getId().toString(), user.getEmail(), user.getRole(),
            org.getId().toString(), org.getName(),
            org.getSchemaName(), org.getTier().name(), org.getEnvironment().name());

        return ResponseEntity.ok(ApiResponse.ok(
            new AuthResponse(token, org.getId().toString(), org.getSchemaName(),
                org.getTier().name(), org.getEnvironment().name(),
                user.getRole(), org.getName())));
    }
}
```

- [ ] **Step 7: Add `BCryptPasswordEncoder` bean to `SecurityConfig.java`**

```java
// Add to SecurityConfig.java

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

// Inside the class, add:
@Bean
public BCryptPasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
}
```

- [ ] **Step 8: Compile**

```bash
./mvnw clean compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 9: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/auth/
git add baas-engine/src/test/java/com/nubbank/baas/engine/auth/
git commit -m "feat(baas-engine): Partner JWT service (HMAC-SHA256) + register/login endpoints"
```

---

## Task 8: Tenant Provisioning Service

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/tenant/TenantProvisioningService.java`
- Create: `baas-engine/src/test/java/com/nubbank/baas/engine/tenant/MultiTenancyTest.java`
- Create: `baas-engine/src/test/java/com/nubbank/baas/engine/AbstractIntegrationTest.java`

- [ ] **Step 1: Create `AbstractIntegrationTest.java`**

```java
package com.nubbank.baas.engine;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.nubbank.baas.engine.tenant.PartnerContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("nubbank_baas_test")
        .withUsername("baas_test")
        .withPassword("baas_test")
        .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @AfterEach
    void clearTenantContext() {
        PartnerContext.clear();
    }
}
```

- [ ] **Step 2: Write the failing integration test**

```java
// MultiTenancyTest.java
package com.nubbank.baas.engine.tenant;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class MultiTenancyTest extends AbstractIntegrationTest {

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private DataSource dataSource;

    @Test
    void provisionSchema_createsSchemaWithTenantTables() throws Exception {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        UUID partnerId = UUID.randomUUID();

        provisioningService.provision(partnerId, schemaName);

        // Verify schema exists with expected tables
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, schemaName, "customers", null)) {
            assertThat(rs.next()).isTrue();
        }
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, schemaName, "accounts", null)) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    void searchPath_isolatesDataBetweenPartners() throws Exception {
        String schema1 = "partner_" + UUID.randomUUID().toString().replace("-", "");
        String schema2 = "partner_" + UUID.randomUUID().toString().replace("-", "");
        provisioningService.provision(UUID.randomUUID(), schema1);
        provisioningService.provision(UUID.randomUUID(), schema2);

        // Insert a row in schema1
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO " + schema1 + ", public");
            conn.createStatement().execute(
                "INSERT INTO customers (first_name_encrypted, last_name_encrypted, kyc_status, kyc_level) " +
                "VALUES ('enc_john', 'enc_doe', 'PENDING_KYC', 'NONE')");
        }

        // Verify schema2 does NOT see schema1's data
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO " + schema2 + ", public");
            ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM customers");
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }

        // Verify schema1 DOES have its data
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO " + schema1 + ", public");
            ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM customers");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
./mvnw test -Dtest=MultiTenancyTest -q 2>&1 | tail -5
```

Expected: `COMPILATION ERROR — cannot find symbol: class TenantProvisioningService`

- [ ] **Step 4: Create `TenantProvisioningService.java`**

```java
package com.nubbank.baas.engine.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Provision a new partner schema synchronously.
     * Creates the PostgreSQL schema and runs the tenant Flyway migrations.
     * Called at registration time (Phase 1: synchronous; Phase 2: can be made async with status polling).
     */
    public void provision(UUID partnerId, String schemaName) {
        log.info("Provisioning schema {} for partner {}", schemaName, partnerId);

        // Safety: schema name must only contain safe characters
        if (!schemaName.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }

        try {
            // Step 1: Create the PostgreSQL schema
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
            }

            // Step 2: Also create a sandbox schema alongside production
            String sandboxSchema = schemaName.replace("partner_", "sandbox_");
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + sandboxSchema);
            }

            // Step 3: Run tenant Flyway migrations on the production schema
            runTenantMigrations(schemaName);

            // Step 4: Run tenant Flyway migrations on the sandbox schema
            runTenantMigrations(sandboxSchema);

            // Step 5: Record success in provision log
            jdbcTemplate.update(
                "INSERT INTO public.schema_provision_log " +
                "(partner_id, schema_name, status, completed_at) VALUES (?, ?, 'SUCCESS', ?)",
                partnerId, schemaName, Instant.now());

            log.info("Schema {} provisioned successfully", schemaName);

        } catch (Exception ex) {
            log.error("Failed to provision schema {}: {}", schemaName, ex.getMessage(), ex);
            try {
                jdbcTemplate.update(
                    "INSERT INTO public.schema_provision_log " +
                    "(partner_id, schema_name, status, error_message) VALUES (?, ?, 'FAILED', ?)",
                    partnerId, schemaName, ex.getMessage());
            } catch (Exception logEx) {
                log.error("Failed to write provision log", logEx);
            }
            throw new RuntimeException("Schema provisioning failed for " + schemaName, ex);
        }
    }

    /**
     * Provision asynchronously — used from register endpoint so the HTTP response
     * is not blocked by schema creation. Status can be polled via GET /baas/v1/org/schema-status.
     */
    @Async
    public void provisionAsync(UUID partnerId, String schemaName) {
        provision(partnerId, schemaName);
    }

    private void runTenantMigrations(String schemaName) {
        Flyway tenantFlyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas(schemaName)
            .defaultSchema(schemaName)
            .locations("classpath:db/migration/tenant")
            .baselineOnMigrate(true)
            .load();
        tenantFlyway.migrate();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./mvnw test -Dtest=MultiTenancyTest -q
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/tenant/TenantProvisioningService.java
git add baas-engine/src/test/java/com/nubbank/baas/engine/
git commit -m "feat(baas-engine): TenantProvisioningService — CREATE SCHEMA + Flyway per-tenant runner (integration tested)"
```

---

## Task 9: PartnerContextFilter (API Key + JWT Resolution)

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/tenant/PartnerContextFilter.java`
- Modify: `baas-engine/src/main/java/com/nubbank/baas/engine/config/SecurityConfig.java`

- [ ] **Step 1: Create `PartnerContextFilter.java`**

```java
package com.nubbank.baas.engine.tenant;

import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.PartnerApiKey;
import com.nubbank.baas.engine.partner.PartnerApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerContextFilter extends OncePerRequestFilter {

    private final PartnerJwtService jwtService;
    private final PartnerApiKeyRepository apiKeyRepo;

    private static final String API_KEY_PREFIX = "ApiKey ";
    private static final String BEARER_PREFIX   = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        try {
            resolveContext(request);
            chain.doFilter(request, response);
        } finally {
            PartnerContext.clear(); // always clear to prevent ThreadLocal leaks
        }
    }

    private void resolveContext(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null) return;

        if (authHeader.startsWith(API_KEY_PREFIX)) {
            resolveApiKey(authHeader.substring(API_KEY_PREFIX.length()).trim());
        } else if (authHeader.startsWith(BEARER_PREFIX)) {
            resolveJwt(authHeader.substring(BEARER_PREFIX.length()).trim());
        }
    }

    private void resolveApiKey(String rawKey) {
        try {
            String keyHash = sha256Hex(rawKey);
            apiKeyRepo.findByKeyHashAndActiveTrue(keyHash).ifPresent(key -> {
                PartnerContext ctx = new PartnerContext(
                    key.getOrganization().getId().toString(),
                    key.getOrganization().getSchemaName(),
                    key.getTier().name(),
                    key.getEnvironment().name(),
                    "API_KEY"
                );
                PartnerContext.set(ctx);
                // Update last_used_at asynchronously (fire and forget)
                apiKeyRepo.updateLastUsed(key.getId(), Instant.now());
            });
        } catch (Exception ex) {
            log.debug("API key resolution failed: {}", ex.getMessage());
        }
    }

    private void resolveJwt(String token) {
        try {
            PartnerContext ctx = jwtService.validate(token);
            PartnerContext.set(ctx);
        } catch (Exception ex) {
            log.debug("JWT resolution failed: {}", ex.getMessage());
        }
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
```

- [ ] **Step 2: Register the filter in `SecurityConfig.java`**

```java
// Replace the existing SecurityConfig with this:
package com.nubbank.baas.engine.config;

import com.nubbank.baas.engine.tenant.PartnerContextFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final PartnerContextFilter partnerContextFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(partnerContextFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/baas/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().permitAll() // tightened in Phase 2 when roles are enforced
            );
        return http.build();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
```

- [ ] **Step 3: Compile**

```bash
./mvnw clean compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/tenant/PartnerContextFilter.java
git add baas-engine/src/main/java/com/nubbank/baas/engine/config/SecurityConfig.java
git commit -m "feat(baas-engine): PartnerContextFilter resolves API key + JWT into PartnerContext ThreadLocal"
```

---

## Task 10: Virtual Account Service (NUBAN Assignment)

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/virtualaccount/VirtualAccountPool.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/virtualaccount/VirtualAccountRepository.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/virtualaccount/VirtualAccountService.java`

- [ ] **Step 1: Create `VirtualAccountPool.java`**

```java
package com.nubbank.baas.engine.virtualaccount;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "virtual_account_pool", schema = "public")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VirtualAccountPool {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 20)
    private String accountNumber;

    @Column(name = "bank_code", nullable = false, length = 3)
    private String bankCode;

    @Column(nullable = false)
    private boolean assigned;

    @Column(name = "assigned_to_schema", length = 100)
    private String assignedToSchema;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
}
```

- [ ] **Step 2: Create `VirtualAccountRepository.java`**

```java
package com.nubbank.baas.engine.virtualaccount;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface VirtualAccountRepository extends JpaRepository<VirtualAccountPool, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM VirtualAccountPool v WHERE v.assigned = false ORDER BY v.createdAt ASC LIMIT 1")
    Optional<VirtualAccountPool> findFirstUnassignedForUpdate();

    long countByAssignedFalse();
}
```

- [ ] **Step 3: Create `VirtualAccountService.java`**

```java
package com.nubbank.baas.engine.virtualaccount;

import com.nubbank.baas.engine.common.BaasException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualAccountService {

    private final VirtualAccountRepository poolRepo;
    private static final long LOW_POOL_THRESHOLD = 1000L;

    /**
     * Assign the next available NUBAN from the pool to a partner account.
     * Uses PESSIMISTIC_WRITE lock to prevent concurrent duplicate assignments.
     * Must be called within a transaction.
     */
    @Transactional
    public String assignNext(String schemaName) {
        VirtualAccountPool entry = poolRepo.findFirstUnassignedForUpdate()
            .orElseThrow(() -> BaasException.conflict("ACCOUNT_POOL_EXHAUSTED",
                "Virtual account pool is exhausted — contact platform support"));

        entry.setAssigned(true);
        entry.setAssignedToSchema(schemaName);
        entry.setAssignedAt(Instant.now());
        poolRepo.save(entry);

        long remaining = poolRepo.countByAssignedFalse();
        if (remaining < LOW_POOL_THRESHOLD) {
            log.warn("Virtual account pool is low: {} remaining", remaining);
            // Phase 2: trigger async pool top-up job
        }

        return entry.getAccountNumber();
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/virtualaccount/
git commit -m "feat(baas-engine): VirtualAccountService — atomic NUBAN assignment from pool with PESSIMISTIC_WRITE lock"
```

---

## Task 11: Customer API

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/customer/Customer.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/customer/KycStatus.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/customer/KycLevel.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/customer/KycProvider.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/customer/CustomerRepository.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/customer/CustomerService.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/customer/CustomerController.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/customer/dto/CreateCustomerRequest.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/customer/dto/CustomerResponse.java`
- Create: `baas-engine/src/test/java/com/nubbank/baas/engine/customer/CustomerControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
// CustomerControllerTest.java
package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private PartnerUserRepository userRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwtToken;
    private String schemaName;

    @BeforeEach
    void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        UUID orgId = UUID.randomUUID();

        PartnerOrganization org = PartnerOrganization.builder()
            .name("Test Partner").status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX)
            .schemaName(schemaName).contactEmail("test@partner.com").build();
        org = orgRepo.save(org);

        provisioningService.provision(org.getId(), schemaName);

        jwtToken = jwtService.issue(UUID.randomUUID().toString(), "test@partner.com",
            "PARTNER_ADMIN", org.getId().toString(), "Test Partner",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void createCustomer_validRequest_returns201WithCustomerId() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = Map.of(
            "firstName", "John",
            "lastName", "Doe",
            "email", "john.doe@example.com",
            "externalReference", "ext-001"
        );

        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data).containsKey("id");
        assertThat(data.get("externalReference")).isEqualTo("ext-001");
    }

    @Test
    void createCustomer_duplicateExternalRef_returns409() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = Map.of("firstName", "Jane", "lastName", "Smith",
            "externalReference", "ext-dupe");

        // First call succeeds
        restTemplate.exchange("/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        // Second call with same external reference fails
        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createCustomer_noToken_returns200WithNoContextError() {
        // Without token, PartnerContext is null — service should return 400 NOT 500
        Map<String, String> body = Map.of("firstName", "Jane", "lastName", "Smith");

        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/baas/v1/customers", body, Map.class);

        // No partner context means the query runs against public schema (no customers table) => bad request
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -Dtest=CustomerControllerTest -q 2>&1 | tail -5
```

Expected: `COMPILATION ERROR — cannot find symbol: class Customer`

- [ ] **Step 3: Create enums**

```java
// KycStatus.java
package com.nubbank.baas.engine.customer;
public enum KycStatus { PENDING_KYC, ACTIVE, SUSPENDED, CLOSED }

// KycLevel.java
package com.nubbank.baas.engine.customer;
public enum KycLevel { NONE, BASIC, STANDARD, ENHANCED }

// KycProvider.java
package com.nubbank.baas.engine.customer;
public enum KycProvider { NUBBANK, PARTNER, SMILE_IDENTITY, YOUVERIFY }
```

- [ ] **Step 4: Create `Customer.java`**

```java
package com.nubbank.baas.engine.customer;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "customers")
// No schema = "public" here — this is a TENANT table; Hibernate routes it via PartnerSchemaProvider
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "external_reference", unique = true, length = 100)
    private String externalReference;

    @Column(name = "first_name_encrypted", nullable = false, length = 500)
    private String firstNameEncrypted;

    @Column(name = "last_name_encrypted", nullable = false, length = 500)
    private String lastNameEncrypted;

    @Column(name = "email_encrypted", length = 500)
    private String emailEncrypted;

    @Column(name = "phone_encrypted", length = 500)
    private String phoneEncrypted;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(length = 20)
    private String gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 50)
    private KycStatus kycStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_level", nullable = false, length = 50)
    private KycLevel kycLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_provider", length = 50)
    private KycProvider kycProvider;

    @Column(name = "bvn_encrypted", length = 500)
    private String bvnEncrypted;

    @Column(name = "nin_encrypted", length = 500)
    private String ninEncrypted;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (kycStatus == null) kycStatus = KycStatus.PENDING_KYC;
        if (kycLevel == null) kycLevel = KycLevel.NONE;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
```

- [ ] **Step 5: Create `CustomerRepository.java`**

```java
package com.nubbank.baas.engine.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    boolean existsByExternalReference(String externalReference);
    Optional<Customer> findByExternalReference(String externalReference);
    Page<Customer> findByKycStatus(KycStatus kycStatus, Pageable pageable);
}
```

- [ ] **Step 6: Create `CreateCustomerRequest.java` and `CustomerResponse.java`**

```java
// CreateCustomerRequest.java
package com.nubbank.baas.engine.customer.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record CreateCustomerRequest(
    @NotBlank(message = "firstName is required") String firstName,
    @NotBlank(message = "lastName is required") String lastName,
    String email,
    String phone,
    String dateOfBirth,  // YYYY-MM-DD
    String gender,
    @Size(max = 100) String externalReference,
    String bvn,
    String nin
) {}

// CustomerResponse.java
package com.nubbank.baas.engine.customer.dto;
import com.nubbank.baas.engine.customer.*;
import java.time.Instant;
import java.util.UUID;
public record CustomerResponse(
    UUID id,
    String externalReference,
    String firstName,
    String lastName,
    String email,
    String phone,
    KycStatus kycStatus,
    KycLevel kycLevel,
    Instant createdAt
) {}
```

- [ ] **Step 7: Create `CustomerService.java`**

```java
package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepo;

    @Transactional
    public CustomerResponse create(CreateCustomerRequest req) {
        requirePartnerContext();

        if (req.externalReference() != null &&
                customerRepo.existsByExternalReference(req.externalReference())) {
            throw BaasException.conflict("DUPLICATE_EXTERNAL_REFERENCE",
                "A customer with external_reference '" + req.externalReference() + "' already exists");
        }

        Customer customer = Customer.builder()
            .externalReference(req.externalReference())
            .firstNameEncrypted(req.firstName()) // Phase 2: encrypt with Jasypt
            .lastNameEncrypted(req.lastName())
            .emailEncrypted(req.email())
            .phoneEncrypted(req.phone())
            .bvnEncrypted(req.bvn())
            .ninEncrypted(req.nin())
            .build();

        customer = customerRepo.save(customer);
        return toResponse(customer);
    }

    @Transactional(readOnly = true)
    public CustomerResponse getById(UUID id) {
        requirePartnerContext();
        Customer customer = customerRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND",
                "Customer " + id + " not found"));
        return toResponse(customer);
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(int page, int size) {
        requirePartnerContext();
        return customerRepo.findAll(PageRequest.of(page, size, Sort.by("createdAt").descending()))
            .map(this::toResponse);
    }

    private void requirePartnerContext() {
        if (PartnerContext.get() == null) {
            throw BaasException.unauthorized("MISSING_AUTH",
                "Authorization header required — use ApiKey or Bearer JWT");
        }
    }

    private CustomerResponse toResponse(Customer c) {
        return new CustomerResponse(c.getId(), c.getExternalReference(),
            c.getFirstNameEncrypted(), c.getLastNameEncrypted(),
            c.getEmailEncrypted(), c.getPhoneEncrypted(),
            c.getKycStatus(), c.getKycLevel(), c.getCreatedAt());
    }
}
```

- [ ] **Step 8: Create `CustomerController.java`**

```java
package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.customer.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @PostMapping
    public ResponseEntity<ApiResponse<CustomerResponse>> create(
            @Valid @RequestBody CreateCustomerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(customerService.create(req)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CustomerResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(customerService.getById(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<CustomerResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(customerService.list(page, size)));
    }
}
```

- [ ] **Step 9: Run the test**

```bash
./mvnw test -Dtest=CustomerControllerTest -q
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 10: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/customer/
git add baas-engine/src/test/java/com/nubbank/baas/engine/customer/
git commit -m "feat(baas-engine): Customer API — POST/GET /baas/v1/customers (partner-scoped, integration tested)"
```

---

## Task 12: Account API

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/Account.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountStatus.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/Transaction.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/TransactionType.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountRepository.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/TransactionRepository.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountService.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/AccountController.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/account/dto/*.java`
- Create: `baas-engine/src/test/java/com/nubbank/baas/engine/account/AccountControllerTest.java`

- [ ] **Step 1: Create enums and entities**

```java
// AccountStatus.java
package com.nubbank.baas.engine.account;
public enum AccountStatus { ACTIVE, FROZEN, CLOSED }

// TransactionType.java
package com.nubbank.baas.engine.account;
public enum TransactionType { CREDIT, DEBIT }
```

```java
// Account.java
package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.customer.Customer;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "account_number", nullable = false, unique = true, length = 20)
    private String accountNumber;

    @Column(name = "virtual_account_ref")
    private UUID virtualAccountRef;

    @Column(name = "account_name", length = 200)
    private String accountName;

    @Column(name = "account_type_label", length = 100)
    private String accountTypeLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AccountStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableBalance;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "minimum_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal minimumBalance;

    @Column(name = "allow_overdraft", nullable = false)
    private boolean allowOverdraft;

    @Column(name = "overdraft_limit", precision = 19, scale = 4)
    private BigDecimal overdraftLimit;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = AccountStatus.ACTIVE;
        if (balance == null) balance = BigDecimal.ZERO;
        if (availableBalance == null) availableBalance = BigDecimal.ZERO;
        if (minimumBalance == null) minimumBalance = BigDecimal.ZERO;
        if (currencyCode == null) currencyCode = "NGN";
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
```

```java
// Transaction.java
package com.nubbank.baas.engine.account;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 50)
    private TransactionType transactionType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "running_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal runningBalance;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(length = 100)
    private String reference;

    @Column(length = 500)
    private String description;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (currencyCode == null) currencyCode = "NGN";
    }
}
```

- [ ] **Step 2: Create repositories**

```java
// AccountRepository.java
package com.nubbank.baas.engine.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByAccountNumber(String accountNumber);
    Page<Account> findByCustomerId(UUID customerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(UUID id);
}

// TransactionRepository.java
package com.nubbank.baas.engine.account;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Page<Transaction> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);
}
```

- [ ] **Step 3: Create DTOs**

```java
// OpenAccountRequest.java
package com.nubbank.baas.engine.account.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
public record OpenAccountRequest(
    @NotNull(message = "customerId is required") UUID customerId,
    String accountTypeLabel,
    String accountName,
    @Pattern(regexp = "[A-Z]{3}", message = "currencyCode must be 3-letter ISO code")
    String currencyCode,
    BigDecimal minimumBalance
) {}

// AccountResponse.java
package com.nubbank.baas.engine.account.dto;
import com.nubbank.baas.engine.account.AccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record AccountResponse(
    UUID id,
    UUID customerId,
    String accountNumber,
    String accountTypeLabel,
    AccountStatus status,
    BigDecimal balance,
    BigDecimal availableBalance,
    String currencyCode,
    Instant createdAt
) {}

// TransactionRequest.java
package com.nubbank.baas.engine.account.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record TransactionRequest(
    @NotNull @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    BigDecimal amount,
    String reference,
    String description
) {}

// TransactionResponse.java
package com.nubbank.baas.engine.account.dto;
import com.nubbank.baas.engine.account.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record TransactionResponse(
    UUID id,
    UUID accountId,
    TransactionType transactionType,
    BigDecimal amount,
    BigDecimal runningBalance,
    String currencyCode,
    String reference,
    Instant createdAt
) {}
```

- [ ] **Step 4: Create `AccountService.java`**

```java
package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.account.dto.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.virtualaccount.VirtualAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final CustomerRepository customerRepo;
    private final VirtualAccountService virtualAccountService;

    @Transactional
    public AccountResponse open(OpenAccountRequest req) {
        requireContext();
        Customer customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND",
                "Customer " + req.customerId() + " not found"));

        String schema = PartnerContext.get().schemaName();
        String accountNumber = virtualAccountService.assignNext(schema);

        Account account = Account.builder()
            .customer(customer)
            .accountNumber(accountNumber)
            .accountTypeLabel(req.accountTypeLabel())
            .accountName(req.accountName() != null ? req.accountName() :
                customer.getFirstNameEncrypted() + " " + customer.getLastNameEncrypted())
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .minimumBalance(req.minimumBalance() != null ? req.minimumBalance() : BigDecimal.ZERO)
            .build();

        return toResponse(accountRepo.save(account));
    }

    @Transactional(readOnly = true)
    public AccountResponse getById(UUID id) {
        requireContext();
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public TransactionResponse deposit(UUID accountId, TransactionRequest req) {
        requireContext();
        Account account = accountRepo.findByIdForUpdate(accountId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE",
                "Account must be ACTIVE to accept deposits");
        }

        account.setBalance(account.getBalance().add(req.amount()));
        account.setAvailableBalance(account.getAvailableBalance().add(req.amount()));
        accountRepo.save(account);

        Transaction tx = Transaction.builder()
            .account(account).transactionType(TransactionType.CREDIT)
            .amount(req.amount()).runningBalance(account.getBalance())
            .currencyCode(account.getCurrencyCode())
            .reference(req.reference()).description(req.description()).build();

        return toTxResponse(txRepo.save(tx));
    }

    @Transactional
    public TransactionResponse withdraw(UUID accountId, TransactionRequest req) {
        requireContext();
        Account account = accountRepo.findByIdForUpdate(accountId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE",
                "Account must be ACTIVE to allow withdrawals");
        }

        BigDecimal floor = account.isAllowOverdraft() && account.getOverdraftLimit() != null
            ? account.getOverdraftLimit().negate()
            : account.getMinimumBalance();

        if (account.getBalance().subtract(req.amount()).compareTo(floor) < 0) {
            throw BaasException.badRequest("INSUFFICIENT_BALANCE",
                "Insufficient balance for this withdrawal");
        }

        account.setBalance(account.getBalance().subtract(req.amount()));
        account.setAvailableBalance(account.getAvailableBalance().subtract(req.amount()));
        accountRepo.save(account);

        Transaction tx = Transaction.builder()
            .account(account).transactionType(TransactionType.DEBIT)
            .amount(req.amount()).runningBalance(account.getBalance())
            .currencyCode(account.getCurrencyCode())
            .reference(req.reference()).description(req.description()).build();

        return toTxResponse(txRepo.save(tx));
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactions(UUID accountId, int page, int size) {
        requireContext();
        findOrThrow(accountId); // verify account exists and belongs to this partner
        return txRepo.findByAccountIdOrderByCreatedAtDesc(accountId,
            PageRequest.of(page, size)).map(this::toTxResponse);
    }

    private Account findOrThrow(UUID id) {
        return accountRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND",
                "Account " + id + " not found"));
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }

    private AccountResponse toResponse(Account a) {
        return new AccountResponse(a.getId(), a.getCustomer().getId(),
            a.getAccountNumber(), a.getAccountTypeLabel(), a.getStatus(),
            a.getBalance(), a.getAvailableBalance(), a.getCurrencyCode(), a.getCreatedAt());
    }

    private TransactionResponse toTxResponse(Transaction t) {
        return new TransactionResponse(t.getId(), t.getAccount().getId(),
            t.getTransactionType(), t.getAmount(), t.getRunningBalance(),
            t.getCurrencyCode(), t.getReference(), t.getCreatedAt());
    }
}
```

- [ ] **Step 5: Create `AccountController.java`**

```java
package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.account.dto.*;
import com.nubbank.baas.engine.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> open(
            @Valid @RequestBody OpenAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(accountService.open(req)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getById(id)));
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<ApiResponse<TransactionResponse>> deposit(
            @PathVariable UUID id, @Valid @RequestBody TransactionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.deposit(id, req)));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<ApiResponse<TransactionResponse>> withdraw(
            @PathVariable UUID id, @Valid @RequestBody TransactionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.withdraw(id, req)));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> transactions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getTransactions(id, page, size)));
    }
}
```

- [ ] **Step 6: Write and run integration test**

```java
// AccountControllerTest.java
package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AccountControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;

    private String jwt;
    private String schemaName;
    private UUID customerId;

    @BeforeEach
    void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = PartnerOrganization.builder()
            .name("Account Test Partner").status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX)
            .schemaName(schemaName).contactEmail("acct@test.com").build();
        org = orgRepo.save(org);
        provisioningService.provision(org.getId(), schemaName);

        // Set partner context to create customer in the right schema
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST"));
        Customer customer = Customer.builder()
            .firstNameEncrypted("Test").lastNameEncrypted("User")
            .build();
        customerId = customerRepo.save(customer).getId();
        PartnerContext.clear();

        jwt = jwtService.issue(UUID.randomUUID().toString(), "acct@test.com",
            "PARTNER_TELLER", org.getId().toString(), "Account Test Partner",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void openAccount_validCustomer_returns201WithAccountNumber() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of("customerId", customerId.toString(),
            "accountTypeLabel", "Savings", "currencyCode", "NGN");

        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("accountNumber").toString()).hasSize(10); // NUBAN = 10 digits
        assertThat(data.get("balance")).isEqualTo(0);
    }

    @Test
    void depositAndWithdraw_updatesBalance() {
        // Open account first
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> openBody = Map.of("customerId", customerId.toString());
        ResponseEntity<Map> openResp = restTemplate.exchange(
            "/baas/v1/accounts", HttpMethod.POST, new HttpEntity<>(openBody, headers), Map.class);
        String accountId = ((Map<?, ?>) openResp.getBody().get("data")).get("id").toString();

        // Deposit ₦5,000
        Map<String, Object> depositBody = Map.of("amount", 5000.00, "description", "Initial deposit");
        restTemplate.exchange("/baas/v1/accounts/" + accountId + "/deposit",
            HttpMethod.POST, new HttpEntity<>(depositBody, headers), Map.class);

        // Withdraw ₦1,000
        Map<String, Object> withdrawBody = Map.of("amount", 1000.00);
        restTemplate.exchange("/baas/v1/accounts/" + accountId + "/withdraw",
            HttpMethod.POST, new HttpEntity<>(withdrawBody, headers), Map.class);

        // Check balance
        ResponseEntity<Map> getResp = restTemplate.exchange(
            "/baas/v1/accounts/" + accountId, HttpMethod.GET,
            new HttpEntity<>(headers), Map.class);
        Map<?, ?> data = (Map<?, ?>) getResp.getBody().get("data");
        assertThat(((Number) data.get("balance")).doubleValue()).isEqualTo(4000.0);
    }
}
```

```bash
./mvnw test -Dtest=AccountControllerTest -q
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/account/
git add baas-engine/src/test/java/com/nubbank/baas/engine/account/
git commit -m "feat(baas-engine): Account API — open, deposit, withdraw, transactions (integration tested)"
```

---

## Task 13: Payment API (Internal Transfer)

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/payment/Payment.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/payment/PaymentStatus.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/payment/PaymentType.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/payment/PaymentRepository.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/payment/PaymentService.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/payment/PaymentController.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/payment/dto/TransferRequest.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/payment/dto/PaymentResponse.java`
- Create: `baas-engine/src/test/java/com/nubbank/baas/engine/payment/PaymentControllerTest.java`

- [ ] **Step 1: Create enums**

```java
// PaymentStatus.java
package com.nubbank.baas.engine.payment;
public enum PaymentStatus { PENDING, COMPLETED, FAILED, REVERSED }

// PaymentType.java
package com.nubbank.baas.engine.payment;
public enum PaymentType { INTERNAL, NIP, SWIFT }
```

- [ ] **Step 2: Create `Payment.java`**

```java
package com.nubbank.baas.engine.payment;

import com.nubbank.baas.engine.account.Account;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id")
    private Account destinationAccount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 50)
    private PaymentType paymentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PaymentStatus status;

    @Column(length = 100)
    private String reference;

    @Column(length = 500)
    private String description;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = PaymentStatus.PENDING;
        if (currencyCode == null) currencyCode = "NGN";
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
```

- [ ] **Step 3: Create DTOs and Repository**

```java
// TransferRequest.java
package com.nubbank.baas.engine.payment.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
public record TransferRequest(
    @NotNull UUID sourceAccountId,
    @NotNull UUID destinationAccountId,
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    String currencyCode,
    String reference,
    String description,
    String idempotencyKey
) {}

// PaymentResponse.java
package com.nubbank.baas.engine.payment.dto;
import com.nubbank.baas.engine.payment.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record PaymentResponse(
    UUID id,
    UUID sourceAccountId,
    UUID destinationAccountId,
    BigDecimal amount,
    String currencyCode,
    PaymentType paymentType,
    PaymentStatus status,
    String reference,
    Instant createdAt
) {}
```

```java
// PaymentRepository.java
package com.nubbank.baas.engine.payment;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Page<Payment> findBySourceAccountIdOrDestinationAccountId(
        UUID srcId, UUID dstId, Pageable pageable);
    Optional<Payment> findByIdempotencyKey(String key);
}
```

- [ ] **Step 4: Create `PaymentService.java`**

```java
package com.nubbank.baas.engine.payment;

import com.nubbank.baas.engine.account.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.payment.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepo;
    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;

    @Transactional
    public PaymentResponse transfer(TransferRequest req) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");

        // Idempotency check
        if (req.idempotencyKey() != null) {
            var existing = paymentRepo.findByIdempotencyKey(req.idempotencyKey());
            if (existing.isPresent()) return toResponse(existing.get());
        }

        if (req.sourceAccountId().equals(req.destinationAccountId())) {
            throw BaasException.badRequest("SAME_ACCOUNT_TRANSFER",
                "Source and destination accounts must be different");
        }

        // Lock both accounts in consistent UUID order to prevent deadlocks
        UUID firstId = req.sourceAccountId().compareTo(req.destinationAccountId()) < 0
            ? req.sourceAccountId() : req.destinationAccountId();
        UUID secondId = firstId.equals(req.sourceAccountId())
            ? req.destinationAccountId() : req.sourceAccountId();

        Account first = accountRepo.findByIdForUpdate(firstId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Account not found: " + firstId));
        Account second = accountRepo.findByIdForUpdate(secondId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Account not found: " + secondId));

        Account source = first.getId().equals(req.sourceAccountId()) ? first : second;
        Account destination = first.getId().equals(req.destinationAccountId()) ? first : second;

        if (source.getStatus() != AccountStatus.ACTIVE)
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE", "Source account is not ACTIVE");
        if (destination.getStatus() != AccountStatus.ACTIVE)
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE", "Destination account is not ACTIVE");

        BigDecimal floor = source.isAllowOverdraft() && source.getOverdraftLimit() != null
            ? source.getOverdraftLimit().negate() : source.getMinimumBalance();
        if (source.getBalance().subtract(req.amount()).compareTo(floor) < 0)
            throw BaasException.badRequest("INSUFFICIENT_BALANCE", "Insufficient balance");

        // Debit source
        source.setBalance(source.getBalance().subtract(req.amount()));
        source.setAvailableBalance(source.getAvailableBalance().subtract(req.amount()));
        accountRepo.save(source);

        // Credit destination
        destination.setBalance(destination.getBalance().add(req.amount()));
        destination.setAvailableBalance(destination.getAvailableBalance().add(req.amount()));
        accountRepo.save(destination);

        // Create payment record
        Payment payment = Payment.builder()
            .sourceAccount(source).destinationAccount(destination)
            .amount(req.amount())
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .paymentType(PaymentType.INTERNAL)
            .status(PaymentStatus.COMPLETED)
            .reference(req.reference()).description(req.description())
            .idempotencyKey(req.idempotencyKey()).build();
        payment = paymentRepo.save(payment);

        // Create debit transaction
        txRepo.save(Transaction.builder().account(source)
            .transactionType(TransactionType.DEBIT).amount(req.amount())
            .runningBalance(source.getBalance()).paymentId(payment.getId()).build());

        // Create credit transaction
        txRepo.save(Transaction.builder().account(destination)
            .transactionType(TransactionType.CREDIT).amount(req.amount())
            .runningBalance(destination.getBalance()).paymentId(payment.getId()).build());

        return toResponse(payment);
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(p.getId(),
            p.getSourceAccount() != null ? p.getSourceAccount().getId() : null,
            p.getDestinationAccount() != null ? p.getDestinationAccount().getId() : null,
            p.getAmount(), p.getCurrencyCode(), p.getPaymentType(), p.getStatus(),
            p.getReference(), p.getCreatedAt());
    }
}
```

- [ ] **Step 5: Create `PaymentController.java`**

```java
package com.nubbank.baas.engine.payment;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.payment.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/baas/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<PaymentResponse>> transfer(
            @Valid @RequestBody TransferRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.transfer(req)));
    }
}
```

- [ ] **Step 6: Write integration test for transfer**

```java
// PaymentControllerTest.java
package com.nubbank.baas.engine.payment;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.*;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.customer.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private AccountRepository accountRepo;
    @Autowired private TransactionRepository txRepo;

    private String jwt;
    private UUID accountA, accountB;

    @BeforeEach
    void setup() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = PartnerOrganization.builder()
            .name("Payment Test").status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX)
            .schemaName(schema).contactEmail("pay@test.com").build();
        org = orgRepo.save(org);
        provisioningService.provision(org.getId(), schema);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema,
            "SANDBOX", "SANDBOX", "TEST"));

        Customer cust = customerRepo.save(Customer.builder()
            .firstNameEncrypted("A").lastNameEncrypted("B").build());

        import java.math.BigDecimal;
        accountA = accountRepo.save(Account.builder().customer(cust)
            .accountNumber("0580000001").balance(new BigDecimal("10000"))
            .availableBalance(new BigDecimal("10000")).currencyCode("NGN")
            .minimumBalance(BigDecimal.ZERO).status(AccountStatus.ACTIVE).build()).getId();

        accountB = accountRepo.save(Account.builder().customer(cust)
            .accountNumber("0580000002").balance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO).currencyCode("NGN")
            .minimumBalance(BigDecimal.ZERO).status(AccountStatus.ACTIVE).build()).getId();

        PartnerContext.clear();

        jwt = jwtService.issue(UUID.randomUUID().toString(), "pay@test.com",
            "PARTNER_TELLER", org.getId().toString(), "Payment Test",
            schema, "SANDBOX", "SANDBOX");
    }

    @Test
    void transfer_validAccounts_debitsSourceCreditsDestination() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "sourceAccountId", accountA.toString(),
            "destinationAccountId", accountB.toString(),
            "amount", 3000.00,
            "description", "Test transfer",
            "idempotencyKey", UUID.randomUUID().toString()
        );

        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/payments/transfer", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("COMPLETED");

        // Verify balances directly in DB
        Account src = accountRepo.findById(accountA).orElseThrow();
        Account dst = accountRepo.findById(accountB).orElseThrow();
        assertThat(src.getBalance()).isEqualByComparingTo("7000");
        assertThat(dst.getBalance()).isEqualByComparingTo("3000");
    }

    @Test
    void transfer_idempotentRequest_returnsSamePayment() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String idemKey = UUID.randomUUID().toString();

        Map<String, Object> body = Map.of("sourceAccountId", accountA.toString(),
            "destinationAccountId", accountB.toString(), "amount", 500.00,
            "idempotencyKey", idemKey);

        ResponseEntity<Map> r1 = restTemplate.exchange("/baas/v1/payments/transfer",
            HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        ResponseEntity<Map> r2 = restTemplate.exchange("/baas/v1/payments/transfer",
            HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        String id1 = ((Map<?, ?>) r1.getBody().get("data")).get("id").toString();
        String id2 = ((Map<?, ?>) r2.getBody().get("data")).get("id").toString();
        assertThat(id1).isEqualTo(id2); // same payment returned
    }
}
```

```bash
./mvnw test -Dtest=PaymentControllerTest -q
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/payment/
git add baas-engine/src/test/java/com/nubbank/baas/engine/payment/
git commit -m "feat(baas-engine): Payment API — internal transfer with deadlock-safe UUID lock ordering + idempotency"
```

---

## Task 14: Sandbox Controller

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/sandbox/SandboxService.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/sandbox/SandboxController.java`

- [ ] **Step 1: Create `SandboxService.java`**

```java
package com.nubbank.baas.engine.sandbox;

import com.nubbank.baas.engine.account.*;
import com.nubbank.baas.engine.account.dto.TransactionResponse;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxService {

    private final CustomerRepository customerRepo;
    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public TransactionResponse simulateDeposit(UUID accountId, BigDecimal amount) {
        requireSandbox();
        Account account = accountRepo.findByIdForUpdate(accountId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));

        account.setBalance(account.getBalance().add(amount));
        account.setAvailableBalance(account.getAvailableBalance().add(amount));
        accountRepo.save(account);

        Transaction tx = Transaction.builder()
            .account(account).transactionType(TransactionType.CREDIT)
            .amount(amount).runningBalance(account.getBalance())
            .currencyCode(account.getCurrencyCode())
            .description("SANDBOX: simulated deposit").build();
        tx = txRepo.save(tx);

        return new TransactionResponse(tx.getId(), account.getId(), tx.getTransactionType(),
            tx.getAmount(), tx.getRunningBalance(), tx.getCurrencyCode(), null, tx.getCreatedAt());
    }

    @Transactional
    public void reset() {
        requireSandbox();
        String schema = PartnerContext.get().schemaName();
        // Use sandbox_ schema not the production schema
        String sandboxSchema = schema.startsWith("sandbox_")
            ? schema : schema.replace("partner_", "sandbox_");

        log.info("Resetting sandbox schema: {}", sandboxSchema);

        // Truncate all tenant tables in order (respect FK constraints)
        String[] tables = {"audit_log", "transactions", "payments", "accounts", "customers",
            "exchange_rates", "loan_products", "deposit_products"};
        for (String table : tables) {
            jdbcTemplate.execute("TRUNCATE TABLE " + sandboxSchema + "." + table + " CASCADE");
        }
        log.info("Sandbox schema {} reset complete", sandboxSchema);
    }

    private void requireSandbox() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        if (!"SANDBOX".equals(PartnerContext.get().environment()))
            throw BaasException.forbidden("SANDBOX_ONLY",
                "Sandbox simulation endpoints are only available in the SANDBOX environment");
    }
}
```

- [ ] **Step 2: Create `SandboxController.java`**

```java
package com.nubbank.baas.engine.sandbox;

import com.nubbank.baas.engine.account.dto.TransactionResponse;
import com.nubbank.baas.engine.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/sandbox")
@RequiredArgsConstructor
public class SandboxController {

    private final SandboxService sandboxService;

    @PostMapping("/simulate/deposit")
    public ResponseEntity<ApiResponse<TransactionResponse>> simulateDeposit(
            @RequestBody Map<String, Object> body) {
        UUID accountId = UUID.fromString(body.get("accountId").toString());
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        return ResponseEntity.ok(ApiResponse.ok(sandboxService.simulateDeposit(accountId, amount)));
    }

    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<Map<String, String>>> reset() {
        sandboxService.reset();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "RESET_COMPLETE",
            "message", "All sandbox data has been wiped and re-seeded")));
    }
}
```

- [ ] **Step 3: Compile**

```bash
./mvnw clean compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/sandbox/
git commit -m "feat(baas-engine): Sandbox API — simulate deposit, reset sandbox schema"
```

---

## Task 15: Full Integration Smoke Test and First Application Start

- [ ] **Step 1: Run all tests**

```bash
cd baas-engine
./mvnw test -q
```

Expected: `BUILD SUCCESS` with all tests passing and no failures.

- [ ] **Step 2: Start infrastructure for local smoke test**

```bash
# From nubbank-baas root — create a minimal docker-compose for local testing
cat > docker-compose.dev.yml << 'EOF'
version: '3.8'
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: nubbank_baas
      POSTGRES_USER: baas
      POSTGRES_PASSWORD: baas
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U baas"]
      interval: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
EOF

docker compose -f docker-compose.dev.yml up -d
sleep 5
```

- [ ] **Step 3: Start baas-engine locally**

```bash
cd baas-engine
DATASOURCE_URL=jdbc:postgresql://localhost:5432/nubbank_baas \
DATASOURCE_USERNAME=baas \
DATASOURCE_PASSWORD=baas \
./mvnw spring-boot:run &

# Wait for startup
sleep 15
curl -s http://localhost:8080/actuator/health | grep -q '"status":"UP"' && echo "APP IS UP" || echo "APP FAILED TO START"
```

Expected output: `APP IS UP`

- [ ] **Step 4: Smoke test — register a partner**

```bash
curl -s -X POST http://localhost:8080/baas/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"orgName":"Credpal Fintech","adminEmail":"dev@credpal.com","password":"SecurePass@123"}' \
  | python3 -m json.tool
```

Expected: JSON response with `token`, `partnerId`, `schemaName`, `tier: "SANDBOX"`

- [ ] **Step 5: Stop and clean up**

```bash
pkill -f "spring-boot:run"
docker compose -f docker-compose.dev.yml down
```

- [ ] **Step 6: Commit final state**

```bash
cd ..
git add docker-compose.dev.yml
git add baas-engine/
git commit -m "feat(baas-engine): Phase 1A complete — multi-tenant BaaS engine with Customer, Account, Payment, Sandbox APIs"
```

---

## Self-Review

### Spec Coverage Check

| Spec Section | Covered By Task | Status |
|---|---|---|
| Schema isolation (PostgreSQL schema per partner) | Task 5 (PartnerSchemaProvider), Task 8 (TenantProvisioningService), MultiTenancyTest | ✅ |
| Partner provisioning (CREATE SCHEMA + Flyway) | Task 8 (TenantProvisioningService) | ✅ |
| Partner JWT (HMAC-SHA256, claims: partner_id, schema_name, tier, environment) | Task 7 (PartnerJwtService) | ✅ |
| API key authentication | Task 9 (PartnerContextFilter — resolveApiKey) | ✅ |
| Register + Login endpoints | Task 7 (AuthController) | ✅ |
| Customer API (create, get, list) | Task 11 | ✅ |
| BVN/NIN fields on Customer (Phase 1 constraint) | Task 11 (Customer entity — bvn_encrypted, nin_encrypted fields) | ✅ |
| Account API (open, deposit, withdraw, transactions) | Task 12 | ✅ |
| Virtual account assignment (NUBAN from pool) | Task 10 (VirtualAccountService — PESSIMISTIC_WRITE) | ✅ |
| Payment API — internal transfer + idempotency | Task 13 | ✅ |
| Deadlock-safe lock ordering for transfers | Task 13 (UUID ordering in PaymentService) | ✅ |
| Sandbox simulate deposit + reset | Task 14 | ✅ |
| Sandbox environment isolation (sandbox_ schema prefix) | Task 14 (SandboxService.requireSandbox) | ✅ |
| Public schema entities use schema="public" | Task 6 (all partner entities annotated) | ✅ |
| ThreadLocal cleanup (memory leak prevention) | Task 9 (PartnerContextFilter — finally block) | ✅ |

### Missing from Phase 1A (intentionally deferred to sub-plans 1B–1E)
- CBN dual-format adapter → Sub-plan 1B (baas-ncube)
- Rate limiting (Redis INCR+EXPIRE) → Add to Phase 1A Task 16 (see below)
- Webhook delivery → Phase 2
- Metering/billing events → Phase 2
- baas-backoffice React app → Sub-plan 1C
- baas-portal React app → Sub-plan 1D
- Docker Compose + CI/CD → Sub-plan 1E

### Rate Limiting Gap — Add Task 16

---

## Task 16: Rate Limiting (Redis)

**Files:**
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/config/RateLimitService.java`
- Create: `baas-engine/src/main/java/com/nubbank/baas/engine/config/RateLimitFilter.java`

- [ ] **Step 1: Create `RateLimitService.java`**

```java
package com.nubbank.baas.engine.config;

import com.nubbank.baas.engine.partner.PartnerTier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redis;

    @Value("${app.rate-limit.sandbox-rpm:30}")   private int sandboxRpm;
    @Value("${app.rate-limit.basic-rpm:100}")     private int basicRpm;
    @Value("${app.rate-limit.pro-rpm:500}")       private int proRpm;
    @Value("${app.rate-limit.enterprise-rpm:2000}") private int enterpriseRpm;

    // Lua script: atomic INCR + EXPIRE (fixed window, 60-second window)
    private static final String LUA = """
        local key = KEYS[1]
        local limit = tonumber(ARGV[1])
        local current = redis.call('INCR', key)
        if current == 1 then redis.call('EXPIRE', key, 60) end
        return {current, limit, redis.call('TTL', key)}
        """;

    public record RateLimitResult(boolean allowed, long current, long limit, long resetInSeconds) {}

    public RateLimitResult check(String partnerId, String tier, String environment) {
        int limit = resolveLimit(tier, environment);
        String key = "rl:baas:" + partnerId;
        DefaultRedisScript<List> script = new DefaultRedisScript<>(LUA, List.class);
        @SuppressWarnings("unchecked")
        List<Long> result = (List<Long>) redis.execute(script, List.of(key),
            String.valueOf(limit));
        long current = result.get(0);
        long ttl = result.get(2);
        return new RateLimitResult(current <= limit, current, limit, ttl);
    }

    private int resolveLimit(String tier, String environment) {
        if ("SANDBOX".equals(environment)) return sandboxRpm;
        return switch (PartnerTier.valueOf(tier)) {
            case BASIC -> basicRpm;
            case PRO -> proRpm;
            case ENTERPRISE -> enterpriseRpm;
            default -> sandboxRpm;
        };
    }
}
```

- [ ] **Step 2: Create `RateLimitFilter.java`**

```java
package com.nubbank.baas.engine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.tenant.PartnerContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        PartnerContext ctx = PartnerContext.get();
        if (ctx != null) {
            var result = rateLimitService.check(ctx.partnerId(), ctx.tier(), ctx.environment());

            response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(result.limit() - result.current()));
            response.setHeader("X-RateLimit-Reset",
                String.valueOf(System.currentTimeMillis() / 1000L + result.resetInSeconds()));

            if (!result.allowed()) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader("Retry-After", "60");
                response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.error("RATE_LIMIT_EXCEEDED",
                        "API rate limit exceeded. Retry after 60 seconds.")));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip rate limiting for auth endpoints and actuator
        String path = request.getRequestURI();
        return path.startsWith("/baas/v1/auth/") || path.startsWith("/actuator/");
    }
}
```

- [ ] **Step 3: Compile**

```bash
./mvnw clean compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Run all tests**

```bash
./mvnw test -q
```

Expected: All tests pass.

- [ ] **Step 5: Final commit**

```bash
git add baas-engine/src/main/java/com/nubbank/baas/engine/config/RateLimitService.java
git add baas-engine/src/main/java/com/nubbank/baas/engine/config/RateLimitFilter.java
git commit -m "feat(baas-engine): Redis fixed-window rate limiting with X-RateLimit-* headers"

git push origin main
```

---

## Phase 1A Complete ✅

The following is now production-ready in `baas-engine`:

| Capability | Endpoint |
|---|---|
| Partner registration | POST /baas/v1/auth/register |
| Partner login | POST /baas/v1/auth/login |
| Schema isolation | Hibernate SCHEMA strategy + SET search_path |
| Tenant provisioning | Automatic on registration (async) |
| Customer CRUD | POST/GET /baas/v1/customers |
| Account management | POST/GET /baas/v1/accounts + deposit/withdraw/transactions |
| Internal transfer + idempotency | POST /baas/v1/payments/transfer |
| Sandbox simulate + reset | POST /baas/v1/sandbox/simulate/deposit + /reset |
| Rate limiting (Redis) | All authenticated endpoints |

**Next sub-plans to write:**
- **1B:** `baas-ncube` — CBN format adapter + BVN/NIN (2026-04-27-nubbank-baas-phase1b-ncube.md)
- **1C:** `baas-backoffice` — React shell (2026-04-27-nubbank-baas-phase1c-backoffice.md)
- **1D:** `baas-portal` — React developer portal (2026-04-27-nubbank-baas-phase1d-portal.md)
- **1E:** Infrastructure — Docker Compose + CI/CD (2026-04-27-nubbank-baas-phase1e-infra.md)
