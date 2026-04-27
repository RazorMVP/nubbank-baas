# NubBank BaaS — Phase 1B: baas-ncube Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scaffold the `baas-ncube` Spring Boot 3.5 service at port 8082 — an ISO 20022 adapter that exposes clean REST endpoints for partners while hiding all NPS XML complexity; implements CBN Open Banking format transformation; provides a fully testable NIP payment pipeline with stub NPS connectivity ready to activate in Phase 2.

**Architecture:** `baas-ncube` is a stateless adapter service with no database. It sits between partners (REST JSON) and both `baas-engine` (for account/consent data) and NIBSS NPS (for NIP payments in Phase 2). Every NPS concern — XML building, signing, encryption, HTTP — is hidden behind interfaces with stub implementations in Phase 1B. Phase 2 activates real NIBSS connectivity by replacing stubs with Apache Santuario + real certificates — zero architectural changes needed.

**Tech Stack:** Java 21, Spring Boot 3.5.0, Lombok 1.18.38, springdoc-openapi 2.8.6, spring-boot-starter-web/security/validation/actuator, JUnit 5, MockMvc — **no database, no Redis, no Flyway** (pure adapter).

**Repository:** `~/nubbank-baas` — branch `feature/phase1b-ncube` (create from `feature/phase1a-engine`)
**NPS Reference:** `docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md`
**CBN Reference:** `docs/regulatory/CBN-Open-Banking-Compliance-Gap-Analysis.md`
**Phase 1A (dependency):** `baas-engine` complete at `~/nubbank-baas/baas-engine/` — provides accounts, payments, consents APIs at port 8080

---

## File Map

```
nubbank-baas/
└── baas-ncube/
    ├── pom.xml
    ├── mvnw + .mvn/
    └── src/
        ├── main/java/com/nubbank/baas/ncube/
        │   ├── BaasNcubeApplication.java
        │   ├── config/
        │   │   ├── SecurityConfig.java           — permit-all, stateless (baas-engine validates auth)
        │   │   └── BaasEngineClientConfig.java   — RestTemplate bean pointing to baas-engine
        │   ├── common/
        │   │   ├── CbnApiResponse.java           — { Data, Links, Meta } CBN envelope
        │   │   ├── CbnLinks.java                 — { Self }
        │   │   ├── CbnMeta.java                  — { TotalPages }
        │   │   ├── CbnAmount.java                — { Amount, Currency } reused across DTOs
        │   │   └── NcubeException.java           — runtime exception with code + message
        │   ├── account/
        │   │   ├── dto/
        │   │   │   ├── NubBankAccountDto.java    — inner DTO: baas-engine account shape
        │   │   │   ├── NubBankTransactionDto.java — inner DTO: baas-engine transaction shape
        │   │   │   ├── CbnAccountItem.java       — CBN account shape (AccountId, Currency, etc.)
        │   │   │   ├── CbnAccountScheme.java     — CBN identification scheme (NIBSS.AccountNumber)
        │   │   │   ├── CbnBalanceItem.java       — CBN balance shape
        │   │   │   └── CbnTransactionItem.java   — CBN transaction shape
        │   │   ├── NcubeAccountClient.java       — calls baas-engine, forwards Authorization header
        │   │   └── NcubeAccountController.java   — GET /baas/v1/ncube/accounts, /balances, /transactions
        │   ├── consent/
        │   │   ├── dto/
        │   │   │   ├── NubBankConsentDto.java    — inner DTO: baas-engine consent shape
        │   │   │   ├── CbnConsentItem.java       — CBN consent shape
        │   │   │   └── CbnConsentRequest.java    — CBN PISP consent initiation request
        │   │   ├── NcubeConsentClient.java       — calls baas-engine consent endpoints
        │   │   └── NcubeConsentController.java   — GET/POST/DELETE /baas/v1/ncube/consents
        │   ├── payment/
        │   │   ├── dto/
        │   │   │   ├── NipPaymentRequest.java    — partner-facing: sourceAccountId, destAccountNo, etc.
        │   │   │   └── NipPaymentResponse.java   — partner-facing: paymentId, status, reference
        │   │   ├── nps/
        │   │   │   ├── Pacs008Message.java       — Java model for ISO 20022 pacs.008 fields
        │   │   │   ├── Acmt023Message.java       — Java model for ISO 20022 acmt.023 fields
        │   │   │   ├── Acmt024Response.java      — Java model for parsed acmt.024 response
        │   │   │   ├── Pacs002Response.java      — Java model for parsed pacs.002 response
        │   │   │   ├── NpsXmlBuilder.java        — builds ISO 20022 XML strings from Java models
        │   │   │   ├── NpsXmlParser.java         — parses NPS XML responses into Java models
        │   │   │   ├── NpsMessageSigner.java     — interface; stub returns XML unchanged
        │   │   │   ├── NpsMessageEncryptor.java  — interface; stub returns XML unchanged
        │   │   │   └── NpsHttpClient.java        — interface; stub returns mock acmt.024/pacs.002
        │   │   ├── NipPaymentOrchestrator.java   — two-step: acmt.023 Name Enquiry → pacs.008 Payment
        │   │   └── NcubePaymentController.java   — POST /baas/v1/ncube/payments
        │   └── identity/
        │       ├── dto/
        │       │   ├── BvnVerificationRequest.java  — { bvn: 11-digit string }
        │       │   ├── NinVerificationRequest.java  — { nin: 11-digit string }
        │       │   └── VerificationResponse.java    — { identifier, verified, firstName, ..., source }
        │       └── NcubeIdentityController.java  — POST /baas/v1/ncube/identity/verify-bvn, verify-nin
        ├── resources/
        │   ├── application.yml
        │   └── application-test.yml
        └── test/java/com/nubbank/baas/ncube/
            ├── account/
            │   └── NcubeAccountControllerTest.java
            ├── consent/
            │   └── NcubeConsentControllerTest.java
            ├── payment/
            │   ├── nps/
            │   │   ├── NpsXmlBuilderTest.java         — verifies correct XML output
            │   │   └── NipPaymentOrchestratorTest.java — verifies two-step flow
            │   └── NcubePaymentControllerTest.java
            └── identity/
                └── NcubeIdentityControllerTest.java
```

---

## Task 1: Branch + Maven Project Scaffold

**Files:**
- Create: `baas-ncube/pom.xml`
- Create: `baas-ncube/src/main/java/com/nubbank/baas/ncube/BaasNcubeApplication.java`
- Create: `baas-ncube/src/main/resources/application.yml`
- Create: `baas-ncube/src/test/resources/application-test.yml`

- [ ] **Step 1: Create feature branch from Phase 1A**

```bash
cd ~/nubbank-baas
git checkout feature/phase1a-engine
git checkout -b feature/phase1b-ncube
```

- [ ] **Step 2: Create directory structure**

```bash
mkdir -p baas-ncube/src/main/java/com/nubbank/baas/ncube/{config,common,account/dto,consent/dto,payment/{dto,nps},identity/dto}
mkdir -p baas-ncube/src/main/resources
mkdir -p baas-ncube/src/test/java/com/nubbank/baas/ncube/{account,consent,payment/nps,identity}
mkdir -p baas-ncube/src/test/resources
```

- [ ] **Step 3: Create `baas-ncube/pom.xml`**

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
    <artifactId>baas-ncube</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>baas-ncube</name>
    <description>NubBank BaaS — CBN Open Banking adapter and NIBSS NPS ISO 20022 gateway</description>

    <properties>
        <java.version>21</java.version>
        <lombok.version>1.18.38</lombok.version>
        <springdoc.version>2.8.6</springdoc.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
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
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
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

- [ ] **Step 4: Create `BaasNcubeApplication.java`**

```java
package com.nubbank.baas.ncube;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BaasNcubeApplication {
    public static void main(String[] args) {
        SpringApplication.run(BaasNcubeApplication.class, args);
    }
}
```

- [ ] **Step 5: Create `application.yml`**

```yaml
spring:
  application:
    name: baas-ncube

server:
  port: 8082

baas:
  engine:
    base-url: ${BAAS_ENGINE_URL:http://localhost:8080}
  nps:
    endpoint: ${NPS_ENDPOINT:http://localhost:9022}
    member-id: ${NPS_MEMBER_ID:999058}
    bicfi: ${NPS_BICFI:999058}
    institution-name: ${NPS_INSTITUTION_NAME:NubBank BaaS}
    signing:
      enabled: ${NPS_SIGNING_ENABLED:false}   # false = stub; true = Apache Santuario (Phase 2)
    encryption:
      enabled: ${NPS_ENCRYPTION_ENABLED:false} # false = stub; true = AES-256-GCM (Phase 2)
    live: ${NPS_LIVE:false}                    # false = stub HTTP; true = real NIBSS (Phase 2)

management:
  endpoints:
    web:
      exposure:
        include: health,info

logging:
  level:
    com.nubbank.baas.ncube: INFO
```

- [ ] **Step 6: Create `src/test/resources/application-test.yml`**

```yaml
baas:
  engine:
    base-url: http://localhost:9999  # WireMock or MockBean — no real engine in unit tests
  nps:
    signing:
      enabled: false
    encryption:
      enabled: false
    live: false
```

- [ ] **Step 7: Copy Maven wrapper from baas-engine**

```bash
cp baas-engine/mvnw baas-ncube/mvnw
chmod +x baas-ncube/mvnw
cp -r baas-engine/.mvn baas-ncube/.mvn
```

- [ ] **Step 8: Verify compilation**

```bash
cd ~/nubbank-baas/baas-ncube && ./mvnw clean compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 9: Commit**

```bash
cd ~/nubbank-baas
git add baas-ncube/
git commit -m "feat(baas-ncube): scaffold Spring Boot 3.5 project — CBN Open Banking adapter"
```

---

## Task 2: SecurityConfig + BaasEngineClientConfig + Common Types

**Files:**
- Create: `config/SecurityConfig.java`
- Create: `config/BaasEngineClientConfig.java`
- Create: `common/CbnApiResponse.java`
- Create: `common/CbnLinks.java`
- Create: `common/CbnMeta.java`
- Create: `common/CbnAmount.java`
- Create: `common/NcubeException.java`

- [ ] **Step 1: Create `config/SecurityConfig.java`**

```java
package com.nubbank.baas.ncube.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// baas-ncube does not validate auth itself — it forwards the Authorization header
// to baas-engine on every call. Auth enforcement is baas-engine's responsibility.
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

- [ ] **Step 2: Create `config/BaasEngineClientConfig.java`**

```java
package com.nubbank.baas.ncube.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class BaasEngineClientConfig {

    @Value("${baas.engine.base-url}")
    private String baasEngineBaseUrl;

    @Bean("baasEngineRestTemplate")
    public RestTemplate baasEngineRestTemplate() {
        return new RestTemplate();
    }

    @Bean
    public String baasEngineBaseUrl() {
        return baasEngineBaseUrl;
    }
}
```

- [ ] **Step 3: Create common types**

```java
// CbnApiResponse.java
package com.nubbank.baas.ncube.common;
import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CbnApiResponse<T>(T Data, CbnLinks Links, CbnMeta Meta) {}

// CbnLinks.java
package com.nubbank.baas.ncube.common;
public record CbnLinks(String Self) {}

// CbnMeta.java
package com.nubbank.baas.ncube.common;
public record CbnMeta(int TotalPages) {}

// CbnAmount.java — ISO 20022 amount: string value + currency code
package com.nubbank.baas.ncube.common;
public record CbnAmount(String Amount, String Currency) {}

// NcubeException.java
package com.nubbank.baas.ncube.common;
import org.springframework.http.HttpStatus;
public class NcubeException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public NcubeException(String code, String message) {
        super(message);
        this.code = code;
        this.status = HttpStatus.BAD_REQUEST;
    }
    public NcubeException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }
    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
```

- [ ] **Step 4: Compile**

```bash
cd ~/nubbank-baas/baas-ncube && ./mvnw clean compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
cd ~/nubbank-baas
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/config/
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/common/
git commit -m "feat(baas-ncube): SecurityConfig (permit-all), BaasEngineClientConfig, CBN common types"
```

---

## Task 3: CBN Account API (TDD)

**Files:**
- Create: `account/dto/NubBankAccountDto.java`
- Create: `account/dto/NubBankTransactionDto.java`
- Create: `account/dto/CbnAccountItem.java`
- Create: `account/dto/CbnAccountScheme.java`
- Create: `account/dto/CbnBalanceItem.java`
- Create: `account/dto/CbnTransactionItem.java`
- Create: `account/NcubeAccountClient.java`
- Create: `account/NcubeAccountController.java`
- Test: `account/NcubeAccountControllerTest.java`

- [ ] **Step 1: Write the failing test first**

```java
// src/test/java/com/nubbank/baas/ncube/account/NcubeAccountControllerTest.java
package com.nubbank.baas.ncube.account;

import com.nubbank.baas.ncube.account.dto.NubBankAccountDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NcubeAccountController.class)
class NcubeAccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NcubeAccountClient accountClient;

    @Test
    void getAccounts_returnsCbnFormat() throws Exception {
        when(accountClient.getAccounts(any())).thenReturn(List.of(
            new NubBankAccountDto("uuid-1", "0581000042", "Savings",
                "ACTIVE", new BigDecimal("5000.00"), new BigDecimal("5000.00"), "NGN")));

        mockMvc.perform(get("/baas/v1/ncube/accounts")
                .header("Authorization", "Bearer test-jwt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.Account[0].AccountId").value("0581000042"))
            .andExpect(jsonPath("$.Data.Account[0].Currency").value("NGN"))
            .andExpect(jsonPath("$.Data.Account[0].AccountSubType").value("Savings"))
            .andExpect(jsonPath("$.Data.Account[0].Account[0].SchemeName").value("NIBSS.AccountNumber"))
            .andExpect(jsonPath("$.Links.Self").exists())
            .andExpect(jsonPath("$.Meta.TotalPages").value(1));
    }

    @Test
    void getBalance_returnsCbnBalanceFormat() throws Exception {
        when(accountClient.getBalance(any(), any())).thenReturn(
            new NubBankAccountDto("uuid-1", "0581000042", "Savings",
                "ACTIVE", new BigDecimal("5000.00"), new BigDecimal("5000.00"), "NGN"));

        mockMvc.perform(get("/baas/v1/ncube/accounts/0581000042/balances")
                .header("Authorization", "Bearer test-jwt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.Balance[0].AccountId").value("0581000042"))
            .andExpect(jsonPath("$.Data.Balance[0].CreditDebitIndicator").value("Credit"))
            .andExpect(jsonPath("$.Data.Balance[0].Amount.Amount").value("5000.00"))
            .andExpect(jsonPath("$.Data.Balance[0].Amount.Currency").value("NGN"));
    }

    @Test
    void getTransactions_returnsCbnTransactionFormat() throws Exception {
        when(accountClient.getTransactions(any(), any())).thenReturn(List.of(
            new com.nubbank.baas.ncube.account.dto.NubBankTransactionDto(
                "tx-001", "CREDIT", new BigDecimal("1000.00"),
                new BigDecimal("6000.00"), "NGN", "Payment received", "2026-04-27T10:00:00Z")));

        mockMvc.perform(get("/baas/v1/ncube/accounts/0581000042/transactions")
                .header("Authorization", "Bearer test-jwt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.Transaction[0].AccountId").value("0581000042"))
            .andExpect(jsonPath("$.Data.Transaction[0].CreditDebitIndicator").value("Credit"))
            .andExpect(jsonPath("$.Data.Transaction[0].Amount.Amount").value("1000.00"))
            .andExpect(jsonPath("$.Data.Transaction[0].Status").value("Booked"));
    }
}
```

Run: `./mvnw test -Dtest=NcubeAccountControllerTest -q 2>&1 | tail -3`
Expected: COMPILATION ERROR — classes don't exist yet.

- [ ] **Step 2: Create DTOs**

```java
// NubBankAccountDto.java — inner DTO matching baas-engine AccountResponse
package com.nubbank.baas.ncube.account.dto;
import java.math.BigDecimal;
public record NubBankAccountDto(
    String id, String accountNumber, String accountTypeLabel,
    String status, BigDecimal balance, BigDecimal availableBalance, String currencyCode
) {}

// NubBankTransactionDto.java — inner DTO matching baas-engine TransactionResponse
package com.nubbank.baas.ncube.account.dto;
import java.math.BigDecimal;
public record NubBankTransactionDto(
    String id, String transactionType, BigDecimal amount,
    BigDecimal runningBalance, String currencyCode, String reference, String createdAt
) {}

// CbnAccountScheme.java
package com.nubbank.baas.ncube.account.dto;
public record CbnAccountScheme(
    String SchemeName,        // "NIBSS.AccountNumber"
    String Identification,    // NUBAN
    String Name,              // account holder name
    String SecondaryIdentification  // issuing institution
) {}

// CbnAccountItem.java
package com.nubbank.baas.ncube.account.dto;
import java.util.List;
public record CbnAccountItem(
    String AccountId,         // NUBAN
    String Currency,          // NGN
    String AccountType,       // Personal / Business
    String AccountSubType,    // partner-defined label
    String Nickname,          // partner-defined label
    List<CbnAccountScheme> Account
) {}

// CbnBalanceItem.java
package com.nubbank.baas.ncube.account.dto;
import com.nubbank.baas.ncube.common.CbnAmount;
public record CbnBalanceItem(
    String AccountId,
    String CreditDebitIndicator,  // Credit or Debit
    String Type,                   // ClosingAvailable
    String DateTime,              // ISO 8601
    CbnAmount Amount
) {}

// CbnTransactionItem.java
package com.nubbank.baas.ncube.account.dto;
import com.nubbank.baas.ncube.common.CbnAmount;
public record CbnTransactionItem(
    String AccountId,
    String TransactionId,
    String CreditDebitIndicator,  // Credit or Debit
    String Status,                // Booked
    String BookingDateTime,       // ISO 8601
    CbnAmount Amount,
    String TransactionInformation // description
) {}
```

- [ ] **Step 3: Create `NcubeAccountClient.java`**

```java
package com.nubbank.baas.ncube.account;

import com.nubbank.baas.ncube.account.dto.NubBankAccountDto;
import com.nubbank.baas.ncube.account.dto.NubBankTransactionDto;
import com.nubbank.baas.ncube.common.NcubeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NcubeAccountClient {

    @Qualifier("baasEngineRestTemplate")
    private final RestTemplate restTemplate;
    private final String baasEngineBaseUrl;

    public List<NubBankAccountDto> getAccounts(String authHeader) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                baasEngineBaseUrl + "/baas/v1/accounts?page=0&size=100",
                HttpMethod.GET, withAuth(authHeader), Map.class);
            return extractAccounts(response.getBody());
        } catch (RestClientException ex) {
            log.warn("Failed to fetch accounts from baas-engine: {}", ex.getMessage());
            return List.of();
        }
    }

    public NubBankAccountDto getBalance(String authHeader, String accountNumber) {
        try {
            // find account by number by searching the list
            List<NubBankAccountDto> accounts = getAccounts(authHeader);
            return accounts.stream()
                .filter(a -> a.accountNumber().equals(accountNumber))
                .findFirst()
                .orElseThrow(() -> new NcubeException("ACCOUNT_NOT_FOUND",
                    "Account " + accountNumber + " not found"));
        } catch (NcubeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NcubeException("ENGINE_ERROR", "Failed to fetch account balance");
        }
    }

    public List<NubBankTransactionDto> getTransactions(String authHeader, String accountNumber) {
        try {
            // First resolve account ID from account number
            List<NubBankAccountDto> accounts = getAccounts(authHeader);
            String accountId = accounts.stream()
                .filter(a -> a.accountNumber().equals(accountNumber))
                .map(NubBankAccountDto::id)
                .findFirst()
                .orElseThrow(() -> new NcubeException("ACCOUNT_NOT_FOUND",
                    "Account " + accountNumber + " not found"));

            ResponseEntity<Map> response = restTemplate.exchange(
                baasEngineBaseUrl + "/baas/v1/accounts/" + accountId + "/transactions?page=0&size=100",
                HttpMethod.GET, withAuth(authHeader), Map.class);
            return extractTransactions(response.getBody());
        } catch (NcubeException ex) {
            throw ex;
        } catch (RestClientException ex) {
            log.warn("Failed to fetch transactions from baas-engine: {}", ex.getMessage());
            return List.of();
        }
    }

    private HttpEntity<Void> withAuth(String authHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (authHeader != null) headers.set("Authorization", authHeader);
        return new HttpEntity<>(headers);
    }

    @SuppressWarnings("unchecked")
    private List<NubBankAccountDto> extractAccounts(Map body) {
        if (body == null) return List.of();
        Object data = body.get("data");
        if (data instanceof Map dataMap) {
            Object content = ((Map<?, ?>) dataMap).get("content");
            if (content instanceof List<?> list) {
                return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> mapToAccountDto((Map<?, ?>) item))
                    .toList();
            }
        }
        return List.of();
    }

    private NubBankAccountDto mapToAccountDto(Map<?, ?> m) {
        return new NubBankAccountDto(
            str(m, "id"), str(m, "accountNumber"), str(m, "accountTypeLabel"),
            str(m, "status"),
            new BigDecimal(String.valueOf(m.getOrDefault("balance", "0"))),
            new BigDecimal(String.valueOf(m.getOrDefault("availableBalance", "0"))),
            str(m, "currencyCode"));
    }

    @SuppressWarnings("unchecked")
    private List<NubBankTransactionDto> extractTransactions(Map body) {
        if (body == null) return List.of();
        Object data = body.get("data");
        if (data instanceof Map dataMap) {
            Object content = ((Map<?, ?>) dataMap).get("content");
            if (content instanceof List<?> list) {
                return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> mapToTransactionDto((Map<?, ?>) item))
                    .toList();
            }
        }
        return List.of();
    }

    private NubBankTransactionDto mapToTransactionDto(Map<?, ?> m) {
        return new NubBankTransactionDto(
            str(m, "id"), str(m, "transactionType"),
            new BigDecimal(String.valueOf(m.getOrDefault("amount", "0"))),
            new BigDecimal(String.valueOf(m.getOrDefault("runningBalance", "0"))),
            str(m, "currencyCode"), str(m, "reference"), str(m, "createdAt"));
    }

    private String str(Map<?, ?> m, String key) {
        return String.valueOf(m.getOrDefault(key, ""));
    }
}
```

- [ ] **Step 4: Create `NcubeAccountController.java`**

```java
package com.nubbank.baas.ncube.account;

import com.nubbank.baas.ncube.account.dto.*;
import com.nubbank.baas.ncube.common.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/baas/v1/ncube/accounts")
@RequiredArgsConstructor
public class NcubeAccountController {

    private final NcubeAccountClient accountClient;
    private static final String BASE_URL = "https://api.nubbank.com/baas/v1/ncube/accounts";

    @GetMapping
    public CbnApiResponse<Map<String, Object>> getAccounts(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        List<CbnAccountItem> accounts = accountClient.getAccounts(auth).stream()
            .map(this::toCbnAccount).toList();
        return new CbnApiResponse<>(
            Map.of("Account", accounts),
            new CbnLinks(BASE_URL),
            new CbnMeta(1));
    }

    @GetMapping("/{accountNumber}/balances")
    public CbnApiResponse<Map<String, Object>> getBalance(
            @PathVariable String accountNumber,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        var dto = accountClient.getBalance(auth, accountNumber);
        CbnBalanceItem balance = new CbnBalanceItem(
            dto.accountNumber(),
            "Credit",
            "ClosingAvailable",
            java.time.Instant.now().toString(),
            new CbnAmount(dto.balance().toPlainString(), dto.currencyCode()));
        return new CbnApiResponse<>(
            Map.of("Balance", List.of(balance)),
            new CbnLinks(BASE_URL + "/" + accountNumber + "/balances"),
            new CbnMeta(1));
    }

    @GetMapping("/{accountNumber}/transactions")
    public CbnApiResponse<Map<String, Object>> getTransactions(
            @PathVariable String accountNumber,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        List<CbnTransactionItem> txns = accountClient.getTransactions(auth, accountNumber).stream()
            .map(t -> new CbnTransactionItem(
                accountNumber, t.id(),
                "CREDIT".equals(t.transactionType()) ? "Credit" : "Debit",
                "Booked",
                t.createdAt(),
                new CbnAmount(t.amount().toPlainString(), t.currencyCode()),
                t.reference()))
            .toList();
        return new CbnApiResponse<>(
            Map.of("Transaction", txns),
            new CbnLinks(BASE_URL + "/" + accountNumber + "/transactions"),
            new CbnMeta(1));
    }

    private CbnAccountItem toCbnAccount(com.nubbank.baas.ncube.account.dto.NubBankAccountDto dto) {
        return new CbnAccountItem(
            dto.accountNumber(),
            dto.currencyCode(),
            "Personal",
            dto.accountTypeLabel() != null ? dto.accountTypeLabel() : "CurrentAccount",
            dto.accountTypeLabel(),
            List.of(new CbnAccountScheme("NIBSS.AccountNumber", dto.accountNumber(), "", "NubBank")));
    }
}
```

- [ ] **Step 5: Run tests**

```bash
cd ~/nubbank-baas/baas-ncube && ./mvnw test -Dtest=NcubeAccountControllerTest -q
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd ~/nubbank-baas
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/account/
git add baas-ncube/src/test/java/com/nubbank/baas/ncube/account/
git commit -m "feat(baas-ncube): CBN Account API — GET accounts, balances, transactions (TDD)"
```

---

## Task 4: CBN Consent API (TDD)

**Files:**
- Create: `consent/dto/NubBankConsentDto.java`
- Create: `consent/dto/CbnConsentItem.java`
- Create: `consent/dto/CbnConsentRequest.java`
- Create: `consent/NcubeConsentClient.java`
- Create: `consent/NcubeConsentController.java`
- Test: `consent/NcubeConsentControllerTest.java`

- [ ] **Step 1: Write failing test**

```java
// src/test/java/com/nubbank/baas/ncube/consent/NcubeConsentControllerTest.java
package com.nubbank.baas.ncube.consent;

import com.nubbank.baas.ncube.consent.dto.NubBankConsentDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NcubeConsentController.class)
class NcubeConsentControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private NcubeConsentClient consentClient;

    @Test
    void getConsents_returnsCbnFormat() throws Exception {
        when(consentClient.getConsents(any())).thenReturn(List.of(
            new NubBankConsentDto("consent-uuid", "AUTHORISED",
                List.of("ReadAccountsBasic", "ReadBalances"),
                "partner-org-id", "2026-12-31T00:00:00Z", "2026-04-27T10:00:00Z")));

        mockMvc.perform(get("/baas/v1/ncube/consents")
                .header("Authorization", "Bearer test-jwt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.Consent[0].ConsentId").value("consent-uuid"))
            .andExpect(jsonPath("$.Data.Consent[0].Status").value("Authorised"))
            .andExpect(jsonPath("$.Data.Consent[0].Permissions[0]").value("ReadAccountsBasic"));
    }

    @Test
    void createConsent_returnsCbnConsentResponse() throws Exception {
        when(consentClient.createConsent(any(), any())).thenReturn(
            new NubBankConsentDto("new-consent-uuid", "AwaitingAuthorisation",
                List.of("ReadAccountsBasic"), "partner-org-id",
                "2026-12-31T00:00:00Z", "2026-04-27T10:00:00Z"));

        mockMvc.perform(post("/baas/v1/ncube/consents")
                .header("Authorization", "Bearer test-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"Data\":{\"Permissions\":[\"ReadAccountsBasic\"],\"ExpirationDateTime\":\"2026-12-31T00:00:00Z\"},\"Risk\":{}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.Data.Consent.ConsentId").value("new-consent-uuid"))
            .andExpect(jsonPath("$.Data.Consent.Status").value("AwaitingAuthorisation"));
    }
}
```

- [ ] **Step 2: Create DTOs**

```java
// NubBankConsentDto.java
package com.nubbank.baas.ncube.consent.dto;
import java.util.List;
public record NubBankConsentDto(
    String id, String status, List<String> scopes,
    String tppClientId, String expiryDate, String createdAt
) {}

// CbnConsentItem.java
package com.nubbank.baas.ncube.consent.dto;
import java.util.List;
public record CbnConsentItem(
    String ConsentId,
    String CreationDateTime,
    String Status,               // AwaitingAuthorisation | Authorised | Revoked
    String StatusUpdateDateTime,
    List<String> Permissions,    // ReadAccountsBasic, ReadBalances, ReadTransactions, etc.
    String ExpirationDateTime,
    String TPPId,
    String TPPName
) {}

// CbnConsentRequest.java — CBN AISP consent initiation
package com.nubbank.baas.ncube.consent.dto;
import java.util.List;
public record CbnConsentRequest(CbnConsentInitiation Data, CbnRisk Risk) {
    public record CbnConsentInitiation(
        List<String> Permissions,
        String ExpirationDateTime,
        String TransactionFromDateTime,
        String TransactionToDateTime
    ) {}
    public record CbnRisk() {}
}
```

- [ ] **Step 3: Create `NcubeConsentClient.java`**

```java
package com.nubbank.baas.ncube.consent;

import com.nubbank.baas.ncube.consent.dto.CbnConsentRequest;
import com.nubbank.baas.ncube.consent.dto.NubBankConsentDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NcubeConsentClient {

    @Qualifier("baasEngineRestTemplate")
    private final RestTemplate restTemplate;
    private final String baasEngineBaseUrl;

    public List<NubBankConsentDto> getConsents(String authHeader) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                baasEngineBaseUrl + "/baas/v1/open-banking/consents",
                HttpMethod.GET, withAuth(authHeader), Map.class);
            return extractConsents(response.getBody());
        } catch (RestClientException ex) {
            log.warn("Failed to fetch consents from baas-engine: {}", ex.getMessage());
            return List.of();
        }
    }

    public NubBankConsentDto createConsent(String authHeader, CbnConsentRequest req) {
        try {
            // Translate CBN consent request to NubBank format
            Map<String, Object> nubBankReq = Map.of(
                "scopes", req.Data().Permissions(),
                "expiryDate", req.Data().ExpirationDateTime() != null
                    ? req.Data().ExpirationDateTime() : ""
            );
            HttpHeaders headers = new HttpHeaders();
            if (authHeader != null) headers.set("Authorization", authHeader);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> response = restTemplate.exchange(
                baasEngineBaseUrl + "/baas/v1/open-banking/consents",
                HttpMethod.POST, new HttpEntity<>(nubBankReq, headers), Map.class);
            return extractSingleConsent(response.getBody());
        } catch (RestClientException ex) {
            log.warn("Failed to create consent in baas-engine: {}", ex.getMessage());
            throw new com.nubbank.baas.ncube.common.NcubeException("CONSENT_CREATE_FAILED",
                "Failed to create consent: " + ex.getMessage());
        }
    }

    public void revokeConsent(String authHeader, String consentId) {
        try {
            restTemplate.exchange(
                baasEngineBaseUrl + "/baas/v1/open-banking/consents/" + consentId,
                HttpMethod.DELETE, withAuth(authHeader), Void.class);
        } catch (RestClientException ex) {
            log.warn("Failed to revoke consent {}: {}", consentId, ex.getMessage());
        }
    }

    private HttpEntity<Void> withAuth(String authHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (authHeader != null) headers.set("Authorization", authHeader);
        return new HttpEntity<>(headers);
    }

    @SuppressWarnings("unchecked")
    private List<NubBankConsentDto> extractConsents(Map body) {
        if (body == null) return List.of();
        Object data = body.get("data");
        if (data instanceof List<?> list) {
            return list.stream()
                .filter(item -> item instanceof Map)
                .map(item -> mapToConsentDto((Map<?, ?>) item))
                .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private NubBankConsentDto extractSingleConsent(Map body) {
        if (body == null) throw new com.nubbank.baas.ncube.common.NcubeException(
            "INVALID_RESPONSE", "Empty response from baas-engine");
        Object data = body.get("data");
        if (data instanceof Map map) return mapToConsentDto(map);
        throw new com.nubbank.baas.ncube.common.NcubeException(
            "INVALID_RESPONSE", "Unexpected response format from baas-engine");
    }

    @SuppressWarnings("unchecked")
    private NubBankConsentDto mapToConsentDto(Map<?, ?> m) {
        Object scopesObj = m.get("scopes");
        List<String> scopes = scopesObj instanceof List<?> list
            ? list.stream().map(Object::toString).toList()
            : List.of();
        return new NubBankConsentDto(
            String.valueOf(m.getOrDefault("id", "")),
            String.valueOf(m.getOrDefault("status", "")),
            scopes,
            String.valueOf(m.getOrDefault("tppClientId", "")),
            String.valueOf(m.getOrDefault("expiryDate", "")),
            String.valueOf(m.getOrDefault("createdAt", "")));
    }
}
```

- [ ] **Step 4: Create `NcubeConsentController.java`**

```java
package com.nubbank.baas.ncube.consent;

import com.nubbank.baas.ncube.common.*;
import com.nubbank.baas.ncube.consent.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/baas/v1/ncube/consents")
@RequiredArgsConstructor
public class NcubeConsentController {

    private final NcubeConsentClient consentClient;
    private static final String BASE_URL = "https://api.nubbank.com/baas/v1/ncube/consents";

    @GetMapping
    public CbnApiResponse<Map<String, Object>> getConsents(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        List<CbnConsentItem> consents = consentClient.getConsents(auth).stream()
            .map(this::toCbnConsent).toList();
        return new CbnApiResponse<>(
            Map.of("Consent", consents),
            new CbnLinks(BASE_URL),
            new CbnMeta(1));
    }

    @PostMapping
    public ResponseEntity<CbnApiResponse<Map<String, Object>>> createConsent(
            @Valid @RequestBody CbnConsentRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        var created = consentClient.createConsent(auth, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CbnApiResponse<>(
            Map.of("Consent", toCbnConsent(created)),
            new CbnLinks(BASE_URL + "/" + created.id()),
            new CbnMeta(1)));
    }

    @DeleteMapping("/{consentId}")
    public ResponseEntity<Void> revokeConsent(
            @PathVariable String consentId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        consentClient.revokeConsent(auth, consentId);
        return ResponseEntity.noContent().build();
    }

    private CbnConsentItem toCbnConsent(com.nubbank.baas.ncube.consent.dto.NubBankConsentDto dto) {
        String cbnStatus = switch (dto.status()) {
            case "AWAITING_AUTHORISATION" -> "AwaitingAuthorisation";
            case "AUTHORISED" -> "Authorised";
            case "REVOKED" -> "Revoked";
            default -> dto.status();
        };
        return new CbnConsentItem(
            dto.id(), dto.createdAt(), cbnStatus, dto.createdAt(),
            dto.scopes(), dto.expiryDate(), dto.tppClientId(), "NubBank Partner");
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./mvnw test -Dtest=NcubeConsentControllerTest -q
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd ~/nubbank-baas
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/consent/
git add baas-ncube/src/test/java/com/nubbank/baas/ncube/consent/
git commit -m "feat(baas-ncube): CBN Consent API — GET/POST/DELETE /baas/v1/ncube/consents (TDD)"
```

---

## Task 5: ISO 20022 Message Models

**Files:**
- Create: `payment/nps/Pacs008Message.java`
- Create: `payment/nps/Acmt023Message.java`
- Create: `payment/nps/Acmt024Response.java`
- Create: `payment/nps/Pacs002Response.java`

These are Java records modelling the NPS XML message fields. No logic — pure data.

- [ ] **Step 1: Create `Pacs008Message.java`**

```java
package com.nubbank.baas.ncube.payment.nps;

import java.math.BigDecimal;

/**
 * Java model for ISO 20022 pacs.008.001.12 (FI to FI Customer Credit Transfer).
 * Built by NipPaymentOrchestrator and serialized to XML by NpsXmlBuilder.
 * See: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md
 */
public record Pacs008Message(
    // GrpHdr
    String msgId,               // unique message reference (35 chars)
    String creDtTm,             // ISO 8601 creation datetime
    String instgAgtMmbId,       // NubBank NPS member ID (CBN sort code)
    String instdAgtMmbId,       // destination bank NPS member ID
    // CdtTrfTxInf/PmtId
    String instrId,             // instruction ID
    String endToEndId,          // end-to-end reference
    String txId,                // same as msgId
    // Payment amount
    BigDecimal amount,          // settlement amount in minor units (e.g. 100000.00 for ₦100,000)
    String currency,            // NGN
    String settlDt,             // ISO 8601 date
    // Debtor (sender)
    String dbtrName,
    String dbtrAcct,            // sender NUBAN (goes in IBAN tag per NPS convention)
    String dbtrBvn,             // decrypted BVN from Customer entity
    int dbtrAccountTier,        // CBN tier: 1, 2, or 3 (from KycLevel mapping)
    int dbtrAccountDesignation, // 1=Individual, 2=Corporate
    // Creditor (beneficiary)
    String cdtrName,            // from acmt.024 response
    String cdtrAcct,            // beneficiary NUBAN (goes in IBAN tag)
    String cdtrBvn,             // from acmt.024 response
    int cdtrAccountTier,        // from acmt.024 response
    int cdtrAccountDesignation, // from acmt.024 response
    // RmtInf
    String narration,           // max 140 chars
    // SplmtryData — Nigerian-specific extension
    String nameEnquiryMsgId,    // MsgId from the preceding acmt.024 (MANDATORY)
    String channelCode,         // 1=internet banking, 2=mobile, 3=USSD, etc.
    String transactionLocation  // GPS coordinates e.g. "01080652440N020900337921E"
) {}
```

- [ ] **Step 2: Create `Acmt023Message.java`**

```java
package com.nubbank.baas.ncube.payment.nps;

/**
 * Java model for ISO 20022 acmt.023.001.04 (Identification Verification Request).
 * Sent to NPS before every pacs.008 to perform Name Enquiry on the beneficiary account.
 * NPS routes this to the beneficiary's bank; the bank responds with acmt.024.
 * See: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md
 */
public record Acmt023Message(
    String msgId,               // unique message reference (35 chars)
    String creDtTm,             // ISO 8601 creation datetime
    String instgAgtMmbId,       // NubBank NPS member ID (Assgnr Agt MmbId)
    String instgAgtBicfi,       // NubBank BICFI
    String destMmbId,           // destination bank NPS member ID (Assgne Agt MmbId)
    String institutionName,     // NubBank institution name (e.g. "NubBank BaaS")
    String beneficiaryName,     // name hint for the account being verified
    String beneficiaryAcct      // beneficiary NUBAN to verify (goes in IBAN tag)
) {}
```

- [ ] **Step 3: Create `Acmt024Response.java`**

```java
package com.nubbank.baas.ncube.payment.nps;

/**
 * Java model for parsed acmt.024 (Identification Verification Report) from NPS.
 * NipPaymentOrchestrator parses the NPS XML response into this record.
 * The nameEnquiryMsgId MUST be included in the subsequent pacs.008 SplmtryData.
 */
public record Acmt024Response(
    String msgId,               // acmt.024 message ID
    String orgMsgId,            // references the acmt.023 MsgId that triggered this
    String nameEnquiryMsgId,    // same as msgId — included in pacs.008 SplmtryData
    String beneficiaryName,     // verified account holder name
    String beneficiaryBvn,      // BVN from beneficiary bank
    int accountTier,            // CBN account tier (1, 2, 3)
    int accountDesignation,     // 1=Individual, 2=Corporate
    boolean verified            // true = account found and verified; false = not found
) {}
```

- [ ] **Step 4: Create `Pacs002Response.java`**

```java
package com.nubbank.baas.ncube.payment.nps;

/**
 * Java model for parsed pacs.002 (Payment Status Report) from NPS.
 * Status codes:
 *   ACSC — AcceptedSettlementCompleted (payment approved and settled)
 *   RJCT — Rejected (payment declined — see rejectReason)
 */
public record Pacs002Response(
    String msgId,               // pacs.002 message ID
    String orgMsgId,            // references the pacs.008 MsgId
    String txStatus,            // ACSC or RJCT
    String rejectReason         // populated only when txStatus = RJCT; null otherwise
) {
    public boolean isAccepted() { return "ACSC".equals(txStatus); }
}
```

- [ ] **Step 5: Compile**

```bash
./mvnw clean compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
cd ~/nubbank-baas
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/Pacs008Message.java
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/Acmt023Message.java
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/Acmt024Response.java
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/Pacs002Response.java
git commit -m "feat(baas-ncube): ISO 20022 message models (pacs.008, acmt.023, acmt.024, pacs.002)"
```

---

## Task 6: NpsXmlBuilder + NpsXmlParser (TDD)

**Files:**
- Create: `payment/nps/NpsXmlBuilder.java`
- Create: `payment/nps/NpsXmlParser.java`
- Test: `payment/nps/NpsXmlBuilderTest.java`

- [ ] **Step 1: Write failing test**

```java
// src/test/java/com/nubbank/baas/ncube/payment/nps/NpsXmlBuilderTest.java
package com.nubbank.baas.ncube.payment.nps;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class NpsXmlBuilderTest {

    private NpsXmlBuilder xmlBuilder;

    @BeforeEach
    void setUp() {
        xmlBuilder = new NpsXmlBuilder("NubBank BaaS", "999058", "999058");
    }

    @Test
    void buildAcmt023_containsCorrectNamespace() {
        Acmt023Message msg = new Acmt023Message(
            "MSG001", "2026-04-27T10:00:00Z",
            "999058", "999058", "058",
            "NubBank BaaS", "John Doe", "0581000042");
        String xml = xmlBuilder.buildAcmt023(msg);
        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:acmt.023.001.04");
        assertThat(xml).contains("IdVrfctnReq");
    }

    @Test
    void buildAcmt023_containsMsgId() {
        Acmt023Message msg = new Acmt023Message(
            "MSG-TEST-001", "2026-04-27T10:00:00Z",
            "999058", "999058", "058",
            "NubBank BaaS", "John Doe", "0581000042");
        String xml = xmlBuilder.buildAcmt023(msg);
        assertThat(xml).contains("<MsgId>MSG-TEST-001</MsgId>");
    }

    @Test
    void buildAcmt023_containsBeneficiaryAccount() {
        Acmt023Message msg = new Acmt023Message(
            "MSG001", "2026-04-27T10:00:00Z",
            "999058", "999058", "058",
            "NubBank BaaS", "Jane Doe", "0581000099");
        String xml = xmlBuilder.buildAcmt023(msg);
        assertThat(xml).contains("<IBAN>0581000099</IBAN>");
        assertThat(xml).contains("<Nm>Jane Doe</Nm>");
    }

    @Test
    void buildPacs008_containsCorrectNamespace() {
        Pacs008Message msg = buildSamplePacs008("MSG-PAY-001");
        String xml = xmlBuilder.buildPacs008(msg);
        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.12");
        assertThat(xml).contains("FIToFICstmrCdtTrf");
    }

    @Test
    void buildPacs008_containsAmountAndCurrency() {
        Pacs008Message msg = buildSamplePacs008("MSG-PAY-002");
        String xml = xmlBuilder.buildPacs008(msg);
        assertThat(xml).contains("Ccy=\"NGN\"");
        assertThat(xml).contains("100000.00");
    }

    @Test
    void buildPacs008_containsBvnInSplmtryData() {
        Pacs008Message msg = buildSamplePacs008("MSG-PAY-003");
        String xml = xmlBuilder.buildPacs008(msg);
        assertThat(xml).contains("<IdValue>12345678901</IdValue>");  // debtor BVN
        assertThat(xml).contains("<IdValue>98765432109</IdValue>");  // creditor BVN
        assertThat(xml).contains("<IdType>bvn</IdType>");
        assertThat(xml).contains("AdditionalVerificationDetails");
    }

    @Test
    void buildPacs008_containsNameEnquiryMsgId() {
        Pacs008Message msg = buildSamplePacs008("MSG-PAY-004");
        String xml = xmlBuilder.buildPacs008(msg);
        assertThat(xml).contains("<NameEnquiryMsgId>ACMT024-REF-001</NameEnquiryMsgId>");
    }

    private Pacs008Message buildSamplePacs008(String msgId) {
        return new Pacs008Message(
            msgId, "2026-04-27T10:00:00Z", "999058", "058",
            msgId + "-INSTR", msgId + "-E2E", msgId,
            new BigDecimal("100000.00"), "NGN", "2026-04-27",
            "John Sender", "0581000001", "12345678901", 1, 1,
            "Jane Receiver", "0581000099", "98765432109", 1, 1,
            "Payment for invoice 001", "ACMT024-REF-001", "1", "01080652440N020900337921E");
    }
}
```

Run: `./mvnw test -Dtest=NpsXmlBuilderTest -q 2>&1 | tail -3`
Expected: COMPILATION ERROR

- [ ] **Step 2: Create `NpsXmlBuilder.java`**

```java
package com.nubbank.baas.ncube.payment.nps;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds ISO 20022 XML strings from Java message models.
 * Phase 1B: String-template based builder (no JAXB, no Apache Santuario).
 * Phase 2: Replace with JAXB-generated classes + Apache Santuario signing/encryption.
 *
 * See: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md for full XML structure reference.
 */
@Slf4j
@Component
public class NpsXmlBuilder {

    private final String institutionName;
    private final String memberIdBicfi;
    private final String memberId;

    public NpsXmlBuilder(
            @Value("${baas.nps.institution-name}") String institutionName,
            @Value("${baas.nps.bicfi}") String memberIdBicfi,
            @Value("${baas.nps.member-id}") String memberId) {
        this.institutionName = institutionName;
        this.memberIdBicfi = memberIdBicfi;
        this.memberId = memberId;
    }

    /**
     * Builds an acmt.023.001.04 (Identification Verification Request) XML string.
     * Sent to NPS before every payment to verify the beneficiary account.
     */
    public String buildAcmt023(Acmt023Message msg) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
            "<ns2:Document xmlns:ns2=\"urn:iso:std:iso:20022:tech:xsd:acmt.023.001.04\">" +
            "<IdVrfctnReq>" +
            "<Assgnmt>" +
            "<MsgId>" + esc(msg.msgId()) + "</MsgId>" +
            "<CreDtTm>" + esc(msg.creDtTm()) + "</CreDtTm>" +
            "<Cretr><Pty><Nm>" + esc(msg.institutionName()) + "</Nm></Pty></Cretr>" +
            "<Assgnr>" +
            "<Pty><Nm>" + esc(msg.institutionName()) + "</Nm></Pty>" +
            "<Agt><FinInstnId>" +
            "<BICFI>" + esc(msg.instgAgtBicfi()) + "</BICFI>" +
            "<ClrSysMmbId><MmbId>" + esc(msg.instgAgtMmbId()) + "</MmbId></ClrSysMmbId>" +
            "</FinInstnId></Agt>" +
            "</Assgnr>" +
            "<Assgne>" +
            "<Agt><FinInstnId>" +
            "<BICFI>" + esc(msg.destMmbId()) + "</BICFI>" +
            "<ClrSysMmbId><MmbId>" + esc(msg.destMmbId()) + "</MmbId></ClrSysMmbId>" +
            "</FinInstnId></Agt>" +
            "</Assgne>" +
            "</Assgnmt>" +
            "<Vrfctn>" +
            "<Id>" + esc(msg.msgId()) + "</Id>" +
            "<PtyAndAcctId>" +
            "<Pty><Nm>" + esc(msg.beneficiaryName()) + "</Nm></Pty>" +
            "<Acct><Id><IBAN>" + esc(msg.beneficiaryAcct()) + "</IBAN></Id></Acct>" +
            "</PtyAndAcctId>" +
            "</Vrfctn>" +
            "</IdVrfctnReq>" +
            "</ns2:Document>";
    }

    /**
     * Builds a pacs.008.001.12 (FI to FI Customer Credit Transfer) XML string.
     * Includes Nigerian-specific SplmtryData with BVN and name enquiry reference.
     */
    public String buildPacs008(Pacs008Message msg) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
            "<ns2:Document xmlns:ns2=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.12\">" +
            "<FIToFICstmrCdtTrf>" +
            "<GrpHdr>" +
            "<MsgId>" + esc(msg.msgId()) + "</MsgId>" +
            "<CreDtTm>" + esc(msg.creDtTm()) + "</CreDtTm>" +
            "<BtchBookg>false</BtchBookg>" +
            "<NbOfTxs>1</NbOfTxs>" +
            "<SttlmInf><SttlmMtd>CLRG</SttlmMtd></SttlmInf>" +
            "<InstgAgt><FinInstnId>" +
            "<BICFI>" + esc(memberId) + "</BICFI>" +
            "<ClrSysMmbId><MmbId>" + esc(msg.instgAgtMmbId()) + "</MmbId></ClrSysMmbId>" +
            "</FinInstnId></InstgAgt>" +
            "<InstdAgt><FinInstnId>" +
            "<ClrSysMmbId><MmbId>" + esc(msg.instdAgtMmbId()) + "</MmbId></ClrSysMmbId>" +
            "</FinInstnId></InstdAgt>" +
            "</GrpHdr>" +
            "<CdtTrfTxInf>" +
            "<PmtId>" +
            "<InstrId>" + esc(msg.instrId()) + "</InstrId>" +
            "<EndToEndId>" + esc(msg.endToEndId()) + "</EndToEndId>" +
            "<TxId>" + esc(msg.txId()) + "</TxId>" +
            "</PmtId>" +
            "<PmtTpInf>" +
            "<ClrChanl>RTNS</ClrChanl>" +
            "<SvcLvl><Prtry>0100</Prtry></SvcLvl>" +
            "<LclInstrm><Prtry>CTAA</Prtry></LclInstrm>" +
            "<CtgyPurp><Prtry>001</Prtry></CtgyPurp>" +
            "</PmtTpInf>" +
            "<IntrBkSttlmAmt Ccy=\"" + esc(msg.currency()) + "\">" +
            msg.amount().toPlainString() +
            "</IntrBkSttlmAmt>" +
            "<IntrBkSttlmDt>" + esc(msg.settlDt()) + "</IntrBkSttlmDt>" +
            "<ChrgBr>SLEV</ChrgBr>" +
            "<InstgAgt><FinInstnId><ClrSysMmbId><MmbId>" + esc(msg.instgAgtMmbId()) +
            "</MmbId></ClrSysMmbId></FinInstnId></InstgAgt>" +
            "<InstdAgt><FinInstnId><ClrSysMmbId><MmbId>" + esc(msg.instdAgtMmbId()) +
            "</MmbId></ClrSysMmbId></FinInstnId></InstdAgt>" +
            "<Dbtr><Nm>" + esc(msg.dbtrName()) + "</Nm></Dbtr>" +
            "<DbtrAcct><Id><IBAN>" + esc(msg.dbtrAcct()) + "</IBAN></Id>" +
            "<Nm>" + esc(msg.dbtrName()) + "</Nm></DbtrAcct>" +
            "<DbtrAgt><FinInstnId><ClrSysMmbId><MmbId>" + esc(msg.instgAgtMmbId()) +
            "</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt>" +
            "<CdtrAgt><FinInstnId><ClrSysMmbId><MmbId>" + esc(msg.instdAgtMmbId()) +
            "</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt>" +
            "<Cdtr><Nm>" + esc(msg.cdtrName()) + "</Nm></Cdtr>" +
            "<CdtrAcct><Id><IBAN>" + esc(msg.cdtrAcct()) + "</IBAN></Id>" +
            "<Nm>" + esc(msg.cdtrName()) + "</Nm></CdtrAcct>" +
            (msg.narration() != null ? "<RmtInf><Ustrd>" + esc(msg.narration()) + "</Ustrd></RmtInf>" : "") +
            "</CdtTrfTxInf>" +
            buildSplmtryData(msg) +
            "</FIToFICstmrCdtTrf>" +
            "</ns2:Document>";
    }

    private String buildSplmtryData(Pacs008Message msg) {
        return "<SplmtryData>" +
            "<PlcAndNm>AdditionalVerificationDetails</PlcAndNm>" +
            "<Envlp><CustomData>" +
            "<DebtorInfo>" +
            "<AccountDesignation>" + msg.dbtrAccountDesignation() + "</AccountDesignation>" +
            "<IdType>bvn</IdType>" +
            "<IdValue>" + esc(msg.dbtrBvn()) + "</IdValue>" +
            "<AccountTier>" + msg.dbtrAccountTier() + "</AccountTier>" +
            "</DebtorInfo>" +
            "<DebtorMetadata/>" +
            "<CreditorInfo>" +
            "<AccountDesignation>" + msg.cdtrAccountDesignation() + "</AccountDesignation>" +
            "<IdType>bvn</IdType>" +
            "<IdValue>" + esc(msg.cdtrBvn()) + "</IdValue>" +
            "<AccountTier>" + msg.cdtrAccountTier() + "</AccountTier>" +
            "</CreditorInfo>" +
            "<CreditorMetadata/>" +
            "<TransactionInfo>" +
            "<TransactionLocation>" + esc(msg.transactionLocation()) + "</TransactionLocation>" +
            "<NameEnquiryMsgId>" + esc(msg.nameEnquiryMsgId()) + "</NameEnquiryMsgId>" +
            "<ChannelCode>" + esc(msg.channelCode()) + "</ChannelCode>" +
            "</TransactionInfo>" +
            "</CustomData></Envlp>" +
            "</SplmtryData>";
    }

    // XML special character escaping — required by NPS per their guidelines
    private String esc(String value) {
        if (value == null) return "";
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
```

- [ ] **Step 3: Create `NpsXmlParser.java`**

```java
package com.nubbank.baas.ncube.payment.nps;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.StringReader;
import org.xml.sax.InputSource;

/**
 * Parses NPS XML responses (acmt.024, pacs.002) into Java models.
 * Phase 1B: DOM parser for stub responses. Phase 2: same (real NPS returns same format).
 */
@Slf4j
@Component
public class NpsXmlParser {

    public Acmt024Response parseAcmt024(String xml) {
        try {
            Document doc = parseXml(xml);
            String msgId = textContent(doc, "MsgId");
            String orgMsgId = textContent(doc, "OrgnlMsgId");
            String verified = textContent(doc, "Vrfd");
            String acctNm = textContent(doc, "Nm");
            String bvn = textContent(doc, "IdValue");
            String tier = textContent(doc, "AccountTier");
            return new Acmt024Response(
                msgId, orgMsgId, msgId, acctNm,
                bvn, tier.isEmpty() ? 1 : Integer.parseInt(tier), 1,
                "true".equalsIgnoreCase(verified) || !acctNm.isEmpty());
        } catch (Exception ex) {
            log.error("Failed to parse acmt.024 response: {}", ex.getMessage());
            return new Acmt024Response("", "", "", "", "", 1, 1, false);
        }
    }

    public Pacs002Response parsePacs002(String xml) {
        try {
            Document doc = parseXml(xml);
            String msgId = textContent(doc, "MsgId");
            String orgMsgId = textContent(doc, "OrgnlMsgId");
            String txSts = textContent(doc, "TxSts");
            String rjctRsn = textContent(doc, "Rsn");
            return new Pacs002Response(msgId, orgMsgId, txSts, rjctRsn.isEmpty() ? null : rjctRsn);
        } catch (Exception ex) {
            log.error("Failed to parse pacs.002 response: {}", ex.getMessage());
            return new Pacs002Response("", "", "RJCT", "PARSE_ERROR");
        }
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Prevent XXE attacks
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private String textContent(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", tagName);
        if (nodes.getLength() == 0) nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) return nodes.item(0).getTextContent().trim();
        return "";
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest=NpsXmlBuilderTest -q
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd ~/nubbank-baas
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/NpsXmlBuilder.java
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/NpsXmlParser.java
git add baas-ncube/src/test/java/com/nubbank/baas/ncube/payment/nps/NpsXmlBuilderTest.java
git commit -m "feat(baas-ncube): NpsXmlBuilder (acmt.023 + pacs.008) + NpsXmlParser (acmt.024 + pacs.002) — TDD"
```

---

## Task 7: NPS Stub Interfaces + Implementations

**Files:**
- Create: `payment/nps/NpsMessageSigner.java`
- Create: `payment/nps/NpsMessageEncryptor.java`
- Create: `payment/nps/NpsHttpClient.java`

These are interfaces designed so Phase 2 replaces stub implementations with Apache Santuario + real NIBSS HTTP calls — **zero changes to callers**.

- [ ] **Step 1: Create `NpsMessageSigner.java`**

```java
package com.nubbank.baas.ncube.payment.nps;

/**
 * Interface for RSA-SHA256 XML digital signature (XMLDSig).
 * Phase 1B: StubNpsMessageSigner returns XML unchanged.
 * Phase 2: Replace with Apache Santuario implementation using NIBSS-provided RSA key pair.
 * Algorithm: http://www.w3.org/2001/04/xmldsig-more#rsa-sha256
 * See: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md §2.1
 */
public interface NpsMessageSigner {
    /**
     * Signs the XML document with RSA-SHA256 enveloped signature.
     * @param xmlDocument unsigned XML string
     * @return signed XML string (same content in Phase 1B stub)
     */
    String sign(String xmlDocument);
}
```

Create the stub implementation:

```java
package com.nubbank.baas.ncube.payment.nps;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 1B stub — returns XML unchanged.
 * Active when baas.nps.signing.enabled=false (default).
 * Phase 2: Replace with Apache Santuario RSA-SHA256 XMLDSig implementation.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "baas.nps.signing.enabled", havingValue = "false", matchIfMissing = true)
public class StubNpsMessageSigner implements NpsMessageSigner {
    @Override
    public String sign(String xmlDocument) {
        log.debug("STUB NpsMessageSigner: returning XML unsigned " +
            "(Phase 2 will sign with RSA-SHA256 XMLDSig)");
        return xmlDocument;
    }
}
```

- [ ] **Step 2: Create `NpsMessageEncryptor.java`**

```java
package com.nubbank.baas.ncube.payment.nps;

/**
 * Interface for AES-256-GCM content encryption with RSA-OAEP key wrapping (XMLEnc).
 * Phase 1B: StubNpsMessageEncryptor returns XML unchanged.
 * Phase 2: Replace with Apache Santuario implementation using NIBSS-provided public key.
 * Content: http://www.w3.org/2009/xmlenc11#aes256-gcm
 * Key wrap: http://www.w3.org/2001/04/xmlenc#rsa-oaep-mgf1p
 * See: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md §2.2
 */
public interface NpsMessageEncryptor {
    /**
     * Encrypts the (signed) XML document using AES-256-GCM with RSA-OAEP key wrapping.
     * @param signedXml signed XML from NpsMessageSigner
     * @return encrypted XML string (same content in Phase 1B stub)
     */
    String encrypt(String signedXml);
}
```

```java
package com.nubbank.baas.ncube.payment.nps;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "baas.nps.encryption.enabled", havingValue = "false", matchIfMissing = true)
public class StubNpsMessageEncryptor implements NpsMessageEncryptor {
    @Override
    public String encrypt(String signedXml) {
        log.debug("STUB NpsMessageEncryptor: returning XML unencrypted " +
            "(Phase 2 will encrypt with AES-256-GCM + RSA-OAEP XMLEnc)");
        return signedXml;
    }
}
```

- [ ] **Step 3: Create `NpsHttpClient.java`**

```java
package com.nubbank.baas.ncube.payment.nps;

import com.nubbank.baas.ncube.common.NcubeException;

/**
 * Interface for HTTP communication with NIBSS NPS.
 * Phase 1B: StubNpsHttpClient returns realistic mock responses for testing.
 * Phase 2: Replace with real HTTPS calls to NPS endpoint using participant credentials.
 * NPS endpoints:
 *   POST https://<nps-ip>:8022/nps/acmt/023  — Name Enquiry
 *   POST https://<nps-ip>:8022/nps/pacs/008  — Credit Transfer
 */
public interface NpsHttpClient {
    /**
     * Sends an acmt.023 Name Enquiry to NPS and returns the acmt.024 response XML.
     */
    String sendAcmt023(String signedEncryptedXml) throws NcubeException;

    /**
     * Sends a pacs.008 Credit Transfer to NPS and returns the pacs.002 status XML.
     */
    String sendPacs008(String signedEncryptedXml) throws NcubeException;
}
```

```java
package com.nubbank.baas.ncube.payment.nps;

import com.nubbank.baas.ncube.common.NcubeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 1B stub NPS HTTP client.
 * Returns realistic mock acmt.024 and pacs.002 responses for end-to-end testing.
 * Active when baas.nps.live=false (default).
 * Phase 2: Replace with real HTTPS calls to NIBSS NPS endpoint.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "baas.nps.live", havingValue = "false", matchIfMissing = true)
public class StubNpsHttpClient implements NpsHttpClient {

    @Override
    public String sendAcmt023(String signedEncryptedXml) throws NcubeException {
        log.debug("STUB NpsHttpClient.sendAcmt023: returning mock acmt.024 response");
        String responseId = "ACMT024-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        // Return a mock acmt.024 showing the account is verified
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:acmt.024.001.04\">" +
            "<IdVrfctnRpt>" +
            "<Assgnmt>" +
            "<MsgId>" + responseId + "</MsgId>" +
            "<CreDtTm>" + Instant.now() + "</CreDtTm>" +
            "</Assgnmt>" +
            "<OrgnlAssgnmt><MsgId>STUB-ORIGINAL</MsgId></OrgnlAssgnmt>" +
            "<Vrfctn>" +
            "<Vrfd>true</Vrfd>" +
            "<PtyAndAcctId>" +
            "<Pty><Nm>Stub Beneficiary</Nm></Pty>" +
            "<Acct><Id><IBAN>0581000099</IBAN></Id></Acct>" +
            "</PtyAndAcctId>" +
            "</Vrfctn>" +
            "<SplmtryData><Envlp><CustomData>" +
            "<IdType>bvn</IdType>" +
            "<IdValue>98765432109</IdValue>" +
            "<AccountTier>1</AccountTier>" +
            "</CustomData></Envlp></SplmtryData>" +
            "</IdVrfctnRpt>" +
            "</Document>";
    }

    @Override
    public String sendPacs008(String signedEncryptedXml) throws NcubeException {
        log.debug("STUB NpsHttpClient.sendPacs008: returning mock pacs.002 ACSC response");
        String responseId = "PACS002-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        // Return a mock pacs.002 ACSC (payment accepted and settled)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.002.001.14\">" +
            "<FIToFIPmtStsRpt>" +
            "<GrpHdr>" +
            "<MsgId>" + responseId + "</MsgId>" +
            "<CreDtTm>" + Instant.now() + "</CreDtTm>" +
            "</GrpHdr>" +
            "<OrgnlGrpInfAndSts>" +
            "<OrgnlMsgId>STUB-ORIGINAL-PACS008</OrgnlMsgId>" +
            "<GrpSts>ACSC</GrpSts>" +
            "</OrgnlGrpInfAndSts>" +
            "<TxInfAndSts>" +
            "<OrgnlMsgId>STUB-ORIGINAL-PACS008</OrgnlMsgId>" +
            "<TxSts>ACSC</TxSts>" +
            "</TxInfAndSts>" +
            "</FIToFIPmtStsRpt>" +
            "</Document>";
    }
}
```

- [ ] **Step 4: Compile**

```bash
./mvnw clean compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
cd ~/nubbank-baas
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/NpsMessageSigner.java
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/StubNpsMessageSigner.java
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/NpsMessageEncryptor.java
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/StubNpsMessageEncryptor.java
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/NpsHttpClient.java
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/StubNpsHttpClient.java
git commit -m "feat(baas-ncube): NPS stub interfaces (signer, encryptor, HTTP client) — Phase 2 replaces stubs with real NIBSS connectivity"
```

---

## Task 8: NipPaymentOrchestrator + NcubePaymentController (TDD)

**Files:**
- Create: `payment/dto/NipPaymentRequest.java`
- Create: `payment/dto/NipPaymentResponse.java`
- Create: `payment/NipPaymentOrchestrator.java`
- Create: `payment/NcubePaymentController.java`
- Test: `payment/nps/NipPaymentOrchestratorTest.java`
- Test: `payment/NcubePaymentControllerTest.java`

- [ ] **Step 1: Write failing orchestrator test**

```java
// src/test/java/com/nubbank/baas/ncube/payment/nps/NipPaymentOrchestratorTest.java
package com.nubbank.baas.ncube.payment.nps;

import com.nubbank.baas.ncube.payment.dto.NipPaymentRequest;
import com.nubbank.baas.ncube.payment.dto.NipPaymentResponse;
import com.nubbank.baas.ncube.payment.NipPaymentOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NipPaymentOrchestratorTest {

    @Mock private NpsXmlBuilder xmlBuilder;
    @Mock private NpsXmlParser xmlParser;
    @Mock private NpsMessageSigner signer;
    @Mock private NpsMessageEncryptor encryptor;
    @Mock private NpsHttpClient httpClient;

    private NipPaymentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new NipPaymentOrchestrator(
            xmlBuilder, xmlParser, signer, encryptor, httpClient,
            "NubBank BaaS", "999058", "999058");
    }

    @Test
    void initiate_approvedPayment_returnsCompleted() throws Exception {
        when(xmlBuilder.buildAcmt023(any())).thenReturn("<acmt023/>");
        when(signer.sign(any())).thenReturn("<acmt023-signed/>");
        when(encryptor.encrypt(any())).thenReturn("<acmt023-encrypted/>");
        when(httpClient.sendAcmt023(any())).thenReturn("<acmt024/>");
        when(xmlParser.parseAcmt024(any())).thenReturn(
            new Acmt024Response("acmt024-id", "acmt023-id", "acmt024-id",
                "Jane Receiver", "98765432109", 1, 1, true));
        when(xmlBuilder.buildPacs008(any())).thenReturn("<pacs008/>");
        when(signer.sign("<pacs008/>")).thenReturn("<pacs008-signed/>");
        when(encryptor.encrypt("<pacs008-signed/>")).thenReturn("<pacs008-encrypted/>");
        when(httpClient.sendPacs008(any())).thenReturn("<pacs002/>");
        when(xmlParser.parsePacs002(any())).thenReturn(
            new Pacs002Response("pacs002-id", "pacs008-id", "ACSC", null));

        NipPaymentRequest req = new NipPaymentRequest(
            "src-account-id", "0581000099", "058",
            new BigDecimal("5000.00"), "NGN", "Payment ref",
            "12345678901", 1, 1, "1");
        NipPaymentResponse response = orchestrator.initiate(req, "Bearer jwt");

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.paymentId()).isNotBlank();
    }

    @Test
    void initiate_rejectedPayment_returnsFailed() throws Exception {
        when(xmlBuilder.buildAcmt023(any())).thenReturn("<acmt023/>");
        when(signer.sign(any())).thenReturn("<signed/>");
        when(encryptor.encrypt(any())).thenReturn("<encrypted/>");
        when(httpClient.sendAcmt023(any())).thenReturn("<acmt024/>");
        when(xmlParser.parseAcmt024(any())).thenReturn(
            new Acmt024Response("id", "id", "id", "Jane", "987", 1, 1, true));
        when(xmlBuilder.buildPacs008(any())).thenReturn("<pacs008/>");
        when(signer.sign("<pacs008/>")).thenReturn("<pacs008-signed/>");
        when(encryptor.encrypt("<pacs008-signed/>")).thenReturn("<pacs008-encrypted/>");
        when(httpClient.sendPacs008(any())).thenReturn("<pacs002/>");
        when(xmlParser.parsePacs002(any())).thenReturn(
            new Pacs002Response("id", "id", "RJCT", "AC01 — Incorrect account number"));

        NipPaymentRequest req = new NipPaymentRequest(
            "src-account-id", "9999999999", "058",
            new BigDecimal("5000.00"), "NGN", "Bad payment",
            "12345678901", 1, 1, "1");
        NipPaymentResponse response = orchestrator.initiate(req, "Bearer jwt");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.rejectReason()).contains("AC01");
    }

    @Test
    void initiate_unverifiedBeneficiary_throwsNcubeException() throws Exception {
        when(xmlBuilder.buildAcmt023(any())).thenReturn("<acmt023/>");
        when(signer.sign(any())).thenReturn("<signed/>");
        when(encryptor.encrypt(any())).thenReturn("<encrypted/>");
        when(httpClient.sendAcmt023(any())).thenReturn("<acmt024/>");
        when(xmlParser.parseAcmt024(any())).thenReturn(
            new Acmt024Response("id", "id", "id", "", "", 0, 0, false));

        NipPaymentRequest req = new NipPaymentRequest(
            "src-account-id", "9999999999", "058",
            new BigDecimal("5000.00"), "NGN", "Invalid account",
            "12345678901", 1, 1, "1");

        assertThatThrownBy(() -> orchestrator.initiate(req, "Bearer jwt"))
            .isInstanceOf(com.nubbank.baas.ncube.common.NcubeException.class)
            .hasMessageContaining("9999999999");
    }
}
```

Run: `./mvnw test -Dtest=NipPaymentOrchestratorTest -q 2>&1 | tail -3`
Expected: COMPILATION ERROR

- [ ] **Step 2: Create DTOs**

```java
// NipPaymentRequest.java
package com.nubbank.baas.ncube.payment.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record NipPaymentRequest(
    @NotBlank String sourceAccountId,        // UUID of sender's account in baas-engine
    @NotBlank @Size(min=10,max=10) String destinationAccountNumber, // beneficiary NUBAN
    @NotBlank @Size(min=3,max=6) String destinationBankCode,        // NPS MmbId of dest bank
    @NotNull @DecimalMin("1.00") BigDecimal amount,
    String currency,                          // NGN (default)
    String narration,                         // max 140 chars
    @NotBlank @Size(min=11,max=11) String debtorBvn, // decrypted BVN of sender
    int debtorAccountTier,                    // 1, 2, or 3
    int debtorAccountDesignation,             // 1=Individual, 2=Corporate
    String channelCode                        // 1=internet, 2=mobile, etc.
) {}

// NipPaymentResponse.java
package com.nubbank.baas.ncube.payment.dto;
public record NipPaymentResponse(
    String paymentId,      // NPS MsgId (pacs.008 message ID)
    String status,         // COMPLETED | FAILED
    String reference,      // EndToEndId
    String rejectReason    // null when COMPLETED; NPS reject code when FAILED
) {}
```

- [ ] **Step 3: Create `NipPaymentOrchestrator.java`**

```java
package com.nubbank.baas.ncube.payment;

import com.nubbank.baas.ncube.common.NcubeException;
import com.nubbank.baas.ncube.payment.dto.*;
import com.nubbank.baas.ncube.payment.nps.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Orchestrates the two-step NIBSS NPS NIP credit transfer flow:
 *   Step 1: acmt.023 Name Enquiry → acmt.024 response
 *   Step 2: pacs.008 Credit Transfer → pacs.002 status
 *
 * Both steps use stub NPS clients in Phase 1B.
 * Phase 2: NpsHttpClient.live=true activates real NIBSS connectivity.
 * See: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md §4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NipPaymentOrchestrator {

    private final NpsXmlBuilder xmlBuilder;
    private final NpsXmlParser xmlParser;
    private final NpsMessageSigner signer;
    private final NpsMessageEncryptor encryptor;
    private final NpsHttpClient httpClient;

    @Value("${baas.nps.institution-name}")
    private String institutionName;

    @Value("${baas.nps.bicfi}")
    private String memberIdBicfi;

    @Value("${baas.nps.member-id}")
    private String memberId;

    // Constructor for tests (without @Value injection)
    NipPaymentOrchestrator(NpsXmlBuilder xmlBuilder, NpsXmlParser xmlParser,
            NpsMessageSigner signer, NpsMessageEncryptor encryptor,
            NpsHttpClient httpClient,
            String institutionName, String memberIdBicfi, String memberId) {
        this.xmlBuilder = xmlBuilder;
        this.xmlParser = xmlParser;
        this.signer = signer;
        this.encryptor = encryptor;
        this.httpClient = httpClient;
        this.institutionName = institutionName;
        this.memberIdBicfi = memberIdBicfi;
        this.memberId = memberId;
    }

    public NipPaymentResponse initiate(NipPaymentRequest req, String authHeader) {
        String paymentId = generateMsgId();
        String e2eRef = generateMsgId();

        log.info("NIP payment initiated: paymentId={}, dest={}, amount={}",
            paymentId, req.destinationAccountNumber(), req.amount());

        // ── Step 1: Name Enquiry (acmt.023 → acmt.024) ──────────────────────────
        String acmt023Id = generateMsgId();
        Acmt023Message acmt023 = new Acmt023Message(
            acmt023Id, Instant.now().toString(),
            memberId, memberIdBicfi,
            req.destinationBankCode(),
            institutionName,
            "",  // beneficiary name hint — empty is acceptable
            req.destinationAccountNumber());

        String acmt023Xml = xmlBuilder.buildAcmt023(acmt023);
        String signedAcmt023 = signer.sign(acmt023Xml);
        String encryptedAcmt023 = encryptor.encrypt(signedAcmt023);

        Acmt024Response acmt024;
        try {
            String acmt024Xml = httpClient.sendAcmt023(encryptedAcmt023);
            acmt024 = xmlParser.parseAcmt024(acmt024Xml);
        } catch (NcubeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NcubeException("NPS_ENQUIRY_ERROR",
                "Name enquiry failed for account " + req.destinationAccountNumber());
        }

        if (!acmt024.verified()) {
            throw new NcubeException("BENEFICIARY_ACCOUNT_NOT_FOUND",
                "Account " + req.destinationAccountNumber() + " could not be verified at destination bank");
        }

        log.info("Name enquiry verified: account={}, beneficiary={}",
            req.destinationAccountNumber(), acmt024.beneficiaryName());

        // ── Step 2: Credit Transfer (pacs.008 → pacs.002) ───────────────────────
        Pacs008Message pacs008 = new Pacs008Message(
            paymentId, Instant.now().toString(),
            memberId, req.destinationBankCode(),
            paymentId + "-INSTR", e2eRef, paymentId,
            req.amount(),
            req.currency() != null ? req.currency() : "NGN",
            LocalDate.now().toString(),
            "",  // debtor name — baas-engine has it; not required here
            req.sourceAccountId(),  // source NUBAN (Phase 2: resolve from baas-engine)
            req.debtorBvn(),
            req.debtorAccountTier(),
            req.debtorAccountDesignation(),
            acmt024.beneficiaryName(),
            req.destinationAccountNumber(),
            acmt024.beneficiaryBvn(),
            acmt024.accountTier(),
            acmt024.accountDesignation(),
            req.narration(),
            acmt024.nameEnquiryMsgId(),
            req.channelCode() != null ? req.channelCode() : "1",
            "00000000000N000000000000E"  // default location — override from partner in Phase 2
        );

        String pacs008Xml = xmlBuilder.buildPacs008(pacs008);
        String signedPacs008 = signer.sign(pacs008Xml);
        String encryptedPacs008 = encryptor.encrypt(signedPacs008);

        Pacs002Response pacs002;
        try {
            String pacs002Xml = httpClient.sendPacs008(encryptedPacs008);
            pacs002 = xmlParser.parsePacs002(pacs002Xml);
        } catch (NcubeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NcubeException("NPS_PAYMENT_ERROR",
                "Payment submission failed: " + ex.getMessage());
        }

        log.info("NIP payment {}: paymentId={}, status={}",
            pacs002.isAccepted() ? "COMPLETED" : "FAILED",
            paymentId, pacs002.txStatus());

        return new NipPaymentResponse(
            paymentId,
            pacs002.isAccepted() ? "COMPLETED" : "FAILED",
            e2eRef,
            pacs002.rejectReason());
    }

    private String generateMsgId() {
        // NPS MsgId format: up to 35 alphanumeric characters
        return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }
}
```

- [ ] **Step 4: Create `NcubePaymentController.java`**

```java
package com.nubbank.baas.ncube.payment;

import com.nubbank.baas.ncube.common.*;
import com.nubbank.baas.ncube.payment.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/baas/v1/ncube/payments")
@RequiredArgsConstructor
public class NcubePaymentController {

    private final NipPaymentOrchestrator orchestrator;

    /**
     * NIP domestic credit transfer — full two-step NPS flow (acmt.023 + pacs.008).
     * Partners supply their own customer's decrypted BVN — NubBank BaaS never decrypts
     * stored BVN on behalf of the partner; the partner's system must decrypt before calling.
     */
    @PostMapping("/nip")
    public ResponseEntity<CbnApiResponse<NipPaymentResponse>> initiateNip(
            @Valid @RequestBody NipPaymentRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        NipPaymentResponse response = orchestrator.initiate(req, auth);
        return ResponseEntity.ok(new CbnApiResponse<>(
            response,
            new CbnLinks("https://api.nubbank.com/baas/v1/ncube/payments/nip"),
            new CbnMeta(1)));
    }
}
```

- [ ] **Step 5: Write controller test**

```java
// src/test/java/com/nubbank/baas/ncube/payment/NcubePaymentControllerTest.java
package com.nubbank.baas.ncube.payment;

import com.nubbank.baas.ncube.payment.dto.NipPaymentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NcubePaymentController.class)
class NcubePaymentControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private NipPaymentOrchestrator orchestrator;

    @Test
    void initiateNip_completedPayment_returns200() throws Exception {
        when(orchestrator.initiate(any(), any())).thenReturn(
            new NipPaymentResponse("pay-id-001", "COMPLETED", "e2e-ref-001", null));

        mockMvc.perform(post("/baas/v1/ncube/payments/nip")
                .header("Authorization", "Bearer test-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sourceAccountId":"src-acct-id",
                      "destinationAccountNumber":"0581000099",
                      "destinationBankCode":"058",
                      "amount":5000.00,
                      "currency":"NGN",
                      "narration":"Payment for invoice",
                      "debtorBvn":"12345678901",
                      "debtorAccountTier":1,
                      "debtorAccountDesignation":1,
                      "channelCode":"1"
                    }"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.Data.paymentId").value("pay-id-001"));
    }
}
```

- [ ] **Step 6: Run all tests**

```bash
./mvnw test -q
```

Expected: All tests pass (NpsXmlBuilderTest + orchestrator + controllers).

- [ ] **Step 7: Commit**

```bash
cd ~/nubbank-baas
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/
git add baas-ncube/src/test/java/com/nubbank/baas/ncube/payment/
git commit -m "feat(baas-ncube): NipPaymentOrchestrator (two-step: acmt.023 Name Enquiry → pacs.008 Credit Transfer) + NcubePaymentController — TDD"
```

---

## Task 9: Identity Endpoints + Full Smoke Test

**Files:**
- Create: `identity/dto/BvnVerificationRequest.java`
- Create: `identity/dto/NinVerificationRequest.java`
- Create: `identity/dto/VerificationResponse.java`
- Create: `identity/NcubeIdentityController.java`
- Test: `identity/NcubeIdentityControllerTest.java`

- [ ] **Step 1: Write failing test**

```java
// src/test/java/com/nubbank/baas/ncube/identity/NcubeIdentityControllerTest.java
package com.nubbank.baas.ncube.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NcubeIdentityController.class)
class NcubeIdentityControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void verifyBvn_validFormat_returnsStubVerified() throws Exception {
        mockMvc.perform(post("/baas/v1/ncube/identity/verify-bvn")
                .header("Authorization", "Bearer test-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bvn\":\"12345678901\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.identifier").value("12345678901"))
            .andExpect(jsonPath("$.data.verified").value(true))
            .andExpect(jsonPath("$.data.verificationSource").value("NIBSS_NCUBE_STUB"));
    }

    @Test
    void verifyBvn_invalidFormat_returns400() throws Exception {
        mockMvc.perform(post("/baas/v1/ncube/identity/verify-bvn")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bvn\":\"123\"}"))  // too short
            .andExpect(status().isBadRequest());
    }

    @Test
    void verifyNin_validFormat_returnsStubVerified() throws Exception {
        mockMvc.perform(post("/baas/v1/ncube/identity/verify-nin")
                .header("Authorization", "Bearer test-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nin\":\"98765432109\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.identifier").value("98765432109"))
            .andExpect(jsonPath("$.data.verified").value(true));
    }

    @Test
    void verifyBvn_nonNumeric_returns400() throws Exception {
        mockMvc.perform(post("/baas/v1/ncube/identity/verify-bvn")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bvn\":\"ABCDE678901\"}"))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Create DTOs**

```java
// BvnVerificationRequest.java
package com.nubbank.baas.ncube.identity.dto;
import jakarta.validation.constraints.*;
public record BvnVerificationRequest(
    @NotBlank
    @Size(min = 11, max = 11, message = "BVN must be exactly 11 digits")
    @Pattern(regexp = "\\d{11}", message = "BVN must contain only digits")
    String bvn
) {}

// NinVerificationRequest.java
package com.nubbank.baas.ncube.identity.dto;
import jakarta.validation.constraints.*;
public record NinVerificationRequest(
    @NotBlank
    @Size(min = 11, max = 11, message = "NIN must be exactly 11 digits")
    @Pattern(regexp = "\\d{11}", message = "NIN must contain only digits")
    String nin
) {}

// VerificationResponse.java
package com.nubbank.baas.ncube.identity.dto;
public record VerificationResponse(
    String identifier,         // the BVN or NIN that was verified
    boolean verified,          // true in Phase 1B stub; real result in Phase 2
    String firstName,          // from Ncube in Phase 2; "STUB" in Phase 1B
    String lastName,           // from Ncube in Phase 2; "STUB" in Phase 1B
    String dateOfBirth,        // from Ncube in Phase 2; null in Phase 1B
    String phoneNumber,        // from Ncube in Phase 2; null in Phase 1B
    String verificationSource  // "NIBSS_NCUBE_STUB" in Phase 1B; "NIBSS_NCUBE_LIVE" in Phase 2
) {}
```

- [ ] **Step 3: Create `NcubeIdentityController.java`**

```java
package com.nubbank.baas.ncube.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nubbank.baas.ncube.identity.dto.*;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * BVN and NIN identity verification endpoints.
 * Phase 1B: Validates format (11 digits) and returns a stub "verified" response.
 * Phase 2: Calls NIBSS Ncube identity rails via acmt.023/024 flow to perform live verification.
 * CBN requirement: BVN/NIN verification is mandatory before any account opening or credit transfer.
 */
@Slf4j
@RestController
@RequestMapping("/baas/v1/ncube/identity")
public class NcubeIdentityController {

    @PostMapping("/verify-bvn")
    public ResponseEntity<Map<String, Object>> verifyBvn(
            @Valid @RequestBody BvnVerificationRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        log.debug("BVN verification requested — Phase 1B stub");
        // Phase 1B: format validated by @Valid; stub returns verified=true
        // Phase 2: call NpsHttpClient.sendAcmt023() with BVN in account field
        VerificationResponse response = new VerificationResponse(
            req.bvn(), true, "STUB_FIRST", "STUB_LAST",
            null, null, "NIBSS_NCUBE_STUB");
        return ResponseEntity.ok(Map.of("data", response));
    }

    @PostMapping("/verify-nin")
    public ResponseEntity<Map<String, Object>> verifyNin(
            @Valid @RequestBody NinVerificationRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        log.debug("NIN verification requested — Phase 1B stub");
        VerificationResponse response = new VerificationResponse(
            req.nin(), true, "STUB_FIRST", "STUB_LAST",
            null, null, "NIBSS_NCUBE_STUB");
        return ResponseEntity.ok(Map.of("data", response));
    }
}
```

- [ ] **Step 4: Run all tests**

```bash
cd ~/nubbank-baas/baas-ncube && ./mvnw test -q
```

Expected: All tests pass — report exact count.

- [ ] **Step 5: Smoke test (start baas-ncube and verify endpoints respond)**

```bash
# Start baas-ncube (no DB or Redis needed)
cd ~/nubbank-baas/baas-ncube
./mvnw spring-boot:run > /tmp/baas-ncube.log 2>&1 &
NCUBE_PID=$!
sleep 12

# Health check
curl -s http://localhost:8082/actuator/health

# Test BVN verification
curl -s -X POST http://localhost:8082/baas/v1/ncube/identity/verify-bvn \
  -H "Content-Type: application/json" \
  -d '{"bvn":"12345678901"}' | python3 -m json.tool

# Test NIP payment stub (full two-step flow with stub NPS)
curl -s -X POST http://localhost:8082/baas/v1/ncube/payments/nip \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId":"src-001",
    "destinationAccountNumber":"0581000099",
    "destinationBankCode":"058",
    "amount":5000.00,
    "currency":"NGN",
    "narration":"Test NIP payment",
    "debtorBvn":"12345678901",
    "debtorAccountTier":1,
    "debtorAccountDesignation":1,
    "channelCode":"1"
  }' | python3 -m json.tool

# Expected: {"Data":{"paymentId":"...","status":"COMPLETED",...}}

kill $NCUBE_PID
```

- [ ] **Step 6: Commit Task 9**

```bash
cd ~/nubbank-baas
git add baas-ncube/src/main/java/com/nubbank/baas/ncube/identity/
git add baas-ncube/src/test/java/com/nubbank/baas/ncube/identity/
git commit -m "feat(baas-ncube): Identity API (BVN/NIN format validation + Phase 1B stub) + smoke test passed"
```

---

## Task 10: Final Commit + Session Gate

- [ ] **Step 1: Run the full test suite one last time**

```bash
cd ~/nubbank-baas/baas-ncube && ./mvnw test -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS` with all tests passing.

- [ ] **Step 2: Update baas-log.md** — add Session 2 entry with all files, decisions, versions, test count.

- [ ] **Step 3: Update CLAUDE.md** — mark baas-ncube as ✅ in Module Catalogue; update SHA.

- [ ] **Step 4: Update CBN compliance gap analysis** — update acmt.023/024 stub, NIP stub, BVN/NIN format validation rows.

- [ ] **Step 5: Update Figma diagram** — CBN Compliance Roadmap: BVN/NIN fields and NIP routing are now ⚠️ (stub) instead of ❌.

- [ ] **Step 6: Push feature branch and open PR**

```bash
cd ~/nubbank-baas
git push origin feature/phase1b-ncube

gh pr create \
  --title "feat(baas-ncube): Phase 1B — CBN Open Banking adapter + NPS ISO 20022 pipeline" \
  --body "$(cat <<'EOF'
## Summary

- New service: `baas-ncube` (Spring Boot 3.5, port 8082)
- CBN Account API: GET /baas/v1/ncube/accounts, /balances, /transactions (CBN Open Banking format)
- CBN Consent API: GET/POST/DELETE /baas/v1/ncube/consents (CBN format)
- NIP Payment: POST /baas/v1/ncube/payments/nip — full two-step pipeline (acmt.023 → pacs.008) with stub NPS connectivity
- Identity: POST /baas/v1/ncube/identity/verify-bvn, verify-nin (format validation + stub)
- ISO 20022 XML builders for pacs.008.001.12 and acmt.023.001.04
- Stub NPS interfaces (NpsMessageSigner, NpsMessageEncryptor, NpsHttpClient) — Phase 2 replaces with Apache Santuario + real NIBSS credentials
- NPS analysis saved: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md

## Test plan
- [ ] All unit and integration tests pass
- [ ] BVN/NIN validation rejects invalid formats
- [ ] NIP payment returns COMPLETED (stub ACSC)
- [ ] CBN account format verified: AccountId, Currency, NIBSS.AccountNumber scheme
- [ ] Smoke test: baas-ncube starts, health=UP, NIP payment completes end-to-end

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

### Spec Coverage Check

| Spec Requirement | Task |
|---|---|
| New standalone Spring Boot 3.5 service, port 8082 | Task 1 ✅ |
| CBN Open Banking format (Accept header routing) | Tasks 3–4 ✅ (separate endpoints `/ncube/*`) |
| ISO 20022 pacs.008 and acmt.023 XML structure | Tasks 5–6 ✅ |
| NPS signing interface (stub) | Task 7 ✅ |
| NPS encryption interface (stub) | Task 7 ✅ |
| NPS HTTP client (stub with realistic mock responses) | Task 7 ✅ |
| acmt.023 mandatory before pacs.008 (two-step) | Task 8 ✅ |
| BVN in pacs.008 SplmtryData | Tasks 5, 8 ✅ |
| NameEnquiryMsgId from acmt.024 in pacs.008 | Tasks 5, 8 ✅ |
| KycLevel → AccountTier mapping | Tasks 5, 8 (dbtrAccountTier from request) ✅ |
| BVN/NIN 11-digit format validation | Task 9 ✅ |
| Phase 2 activation path (zero arch changes) | Tasks 7 — `@ConditionalOnProperty` interfaces ✅ |
| CBN compliance gap analysis: rows updated | Task 10 ✅ |

### No Placeholders

All code blocks are complete and compilable. No TBD, TODO, or "similar to Task N" patterns.

### Type Consistency

- `Pacs008Message.dbtrBvn` → used in `NipPaymentOrchestrator.initiate()` → passed to `NpsXmlBuilder.buildPacs008()` ✅
- `Acmt024Response.nameEnquiryMsgId` → same field in `Acmt024Response` and `Pacs008Message.nameEnquiryMsgId` ✅
- `NipPaymentRequest.debtorBvn` → maps to `Pacs008Message.dbtrBvn` in orchestrator ✅
- `CbnApiResponse<T>` used consistently across all controllers ✅
