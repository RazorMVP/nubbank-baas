# Phase 1C — Track-FEP (`baas-fep`, D7) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
> This track runs in the `~/nb-fep` worktree on branch `feature/phase1c-fep`, branched from `main` at or after
> `c6cb1b8` (Foundation merged). Read spec §6.7 and the contracts doc §2 before starting. Track-FEP consumes
> Track-Card's two internal endpoints **by contract** — it is built against a mocked Card client and wired to
> the live service in Stage 5, so it can run **in parallel** with Track-Card.

**Goal:** Build `baas-fep`, a stateless ISO 8583-1987 front-end processor: a Netty TCP server (port 8583), a
jPOS `GenericPackager`, an MTI router (`0100/0200/0400/0800`), **BIN-based tenant routing** (extract DE2 PAN →
BIN → resolve owning partner via Card's `GET /internal/v1/bins/{bin}` → set routing context), and an
authorization flow that forwards routed requests to Card's `POST /internal/v1/authorize` and maps the decision
to DE39 in the `0110` response.

**Architecture:** **No database** — FEP holds no tenant data; it routes and forwards. Spring Boot hosts the
config, the actuator health endpoint (HTTP 8082), the outbound HMAC clients, and the Netty server lifecycle.
The TCP server frames messages with a 2-byte big-endian length prefix (jPOS standard). BIN→partner resolutions
are cached in **Caffeine** (TTL 5 min). An unknown/unrouteable BIN yields ISO 8583 RC `91` with **no PAN echo**.

**Tech Stack:** Java 21, Spring Boot 3.5.3, Netty 4.1.x (raw TCP server), jPOS 2.1.x (`GenericPackager`,
`ISOMsg`), Caffeine 3.x, Spring `RestTemplate` with the ported HMAC `SigningInterceptor`, Lombok 1.18.38,
Testcontainers **not needed** (no DB), `MockRestServiceServer`/WireMock for the Card client in tests.

**Dependency / parallelism note:** FEP depends on Card's `GET /internal/v1/bins/{bin}` →
`{partnerId, schemaName}` and `POST /internal/v1/authorize` → `{decision, responseCode, message}` (contract
§2 + Track-Card Task 6). FEP is built against a `CardClient` interface with a mock in tests; the live base URL
(`app.card.base-url`) is wired in Stage 5. No Card source is read or imported.

---

## File structure

```
baas-fep/
├── pom.xml                          ← Spring Boot parent 3.5.3; deps: web, actuator, security, validation,
│                                       netty-all 4.1.x, jpos 2.1.10, caffeine 3.1.8, lombok; NO data-jpa/flyway/redis
├── mvnw, mvnw.cmd, .mvn/            ← copy from baas-engine
├── Dockerfile                       ← mirror baas-engine/Dockerfile; EXPOSE 8082 8583; health on 8082
├── src/main/java/com/nubbank/baas/fep/
│   ├── FepApplication.java          ← @SpringBootApplication
│   ├── common/
│   │   ├── ApiResponse.java         ← PORTED verbatim (repackaged) — used by any HTTP/actuator JSON
│   │   └── BaasException.java       ← PORTED verbatim
│   ├── config/
│   │   ├── SecurityConfig.java      ← minimal: permit /actuator/health/**, deny rest (no partner API here)
│   │   ├── FepProperties.java       ← @ConfigurationProperties(prefix="fep") — tcpPort, card base-url, hmac secret
│   │   └── CardClientConfig.java    ← RestTemplate bean w/ ported SigningInterceptor (HMAC)
│   ├── iso/
│   │   ├── IsoField.java            ← DE index constants (MTI=0 … DE128)
│   │   ├── IsoMessageFactory.java   ← loads GenericPackager from classpath XML; pack()/unpack() helpers
│   │   └── (resources) iso8583-1987-fields.xml
│   ├── server/
│   │   ├── FepTcpServer.java        ← @PostConstruct/@PreDestroy Netty lifecycle; binds fep.tcp.port
│   │   ├── FepServerInitializer.java← ChannelInitializer: LengthFieldBasedFrameDecoder(65535,0,2,0,2)+Prepender(2)
│   │   └── FepMessageHandler.java    ← @ChannelHandler.Sharable; decode→route→encode; RC=96 on unhandled error
│   ├── router/
│   │   ├── MessageRouter.java       ← switch on MTI → handler; unknown MTI → RC 30
│   │   ├── AuthorizationHandler.java← 0100→0110: BIN-route → Card authorize → DE39
│   │   ├── FinancialHandler.java    ← 0200→0210 (same flow, withdrawal processing code)
│   │   ├── ReversalHandler.java     ← 0400→0410 (stub: echo approved)
│   │   └── NetworkHandler.java      ← 0800→0810 sign-on/echo (DE70)
│   ├── routing/
│   │   ├── BinResolver.java         ← extract DE2 → BIN(6/8) → CardClient.lookupBin → Caffeine cache
│   │   ├── PartnerRoute.java        ← record(partnerId, schemaName)
│   │   └── CardClient.java          ← interface: lookupBin(bin)->Optional<PartnerRoute>; authorize(req)->Decision
│   └── client/
│       └── HttpCardClient.java      ← @Component CardClient impl over the HMAC RestTemplate
├── src/main/resources/
│   ├── application.yml              ← server.port 8082; fep.tcp-port 8583; fep.card.base-url; fep.hmac-secret
│   └── iso8583-1987-fields.xml
└── src/test/java/com/nubbank/baas/fep/
    ├── iso/IsoMessageFactoryTest.java
    ├── server/FepTcpServerLoopbackTest.java
    ├── router/{AuthorizationHandlerTest,NetworkHandlerTest}.java
    ├── routing/BinResolverTest.java
    └── support/{Iso8583TestClient.java, StubCardClient.java}
```

**Repackage rule:** PORTED files (`ApiResponse`, `BaasException`, the HMAC `SigningInterceptor`) are copied
byte-for-byte from `~/nubbank-baas/baas-engine/...` and repackaged `com.nubbank.baas.engine` →
`com.nubbank.baas.fep`. Deltas are shown where needed.

---

## Task 1: Service scaffold + ported common + HMAC Card client config

**Files:** `baas-fep/pom.xml`, wrapper, `Dockerfile`, `FepApplication.java`, `common/ApiResponse.java`,
`common/BaasException.java`, `config/SecurityConfig.java`, `config/FepProperties.java`,
`config/CardClientConfig.java`, `application.yml`; Test: `FepContextTest.java`

- [ ] **Step 1: Scaffold module.** Copy `baas-engine/pom.xml` → `baas-fep/pom.xml`; artifactId `baas-fep`.
  **Remove** `spring-boot-starter-data-jpa`, `flyway-core`, `flyway-database-postgresql`, `postgresql`,
  `spring-boot-starter-data-redis`, `testcontainers*`, `jasypt*`. **Add**:

```xml
<dependency><groupId>io.netty</groupId><artifactId>netty-all</artifactId><version>4.1.115.Final</version></dependency>
<dependency><groupId>org.jpos</groupId><artifactId>jpos</artifactId><version>2.1.10</version></dependency>
<dependency><groupId>com.github.ben-manes.caffeine</groupId><artifactId>caffeine</artifactId><version>3.1.8</version></dependency>
```
  Keep `spring-boot-starter-web`, `-security`, `-validation`, `-actuator`, `-test`, `spring-security-test`,
  `nimbus-jose-jwt`, `lombok`. Copy `mvnw`, `.mvn/`. Copy `Dockerfile`; change `8080`→`8082` on EXPOSE/health
  and **add** `EXPOSE 8583`; header `baas-engine`→`baas-fep`.
  > **jPOS note:** jPOS 2.1.x is on Maven Central. If the build can't resolve it, add the jPOS repo
  > `<repository><id>jpos</id><url>https://jpos.org/maven</url></repository>` — but Central should suffice for
  > 2.1.10. Verify with `./mvnw -B dependency:resolve` before proceeding.

- [ ] **Step 2: Application class.**

```java
package com.nubbank.baas.fep;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class FepApplication {
    public static void main(String[] args) { SpringApplication.run(FepApplication.class, args); }
}
```

- [ ] **Step 3: PORT common.** `common/ApiResponse.java`, `common/BaasException.java` (verbatim, repackaged).

- [ ] **Step 4: `FepProperties` + `application.yml`.**

```java
@ConfigurationProperties(prefix = "fep")
public record FepProperties(int tcpPort, Card card, String hmacSecret) {
    public record Card(String baseUrl) {}
}
```
```yaml
server:
  port: 8082
spring:
  application:
    name: baas-fep
fep:
  tcp-port: ${FEP_TCP_PORT:8583}
  card:
    base-url: ${CARD_BASE_URL:http://baas-card:8081}
  hmac-secret: ${INTERNAL_SERVICE_SECRET}
management:
  endpoints: { web: { exposure: { include: health,info } } }
  endpoint:  { health: { probes: { enabled: true } } }
logging:
  level: { com.nubbank.baas: INFO }
```
  Enable with `@EnableConfigurationProperties(FepProperties.class)` on a `@Configuration`. Add
  `application-test.yml` with `fep.hmac-secret: test-shared-secret-min-32-chars-long-okay` and
  `fep.card.base-url: http://localhost:0`.

- [ ] **Step 5: Minimal `SecurityConfig`.** One chain: `securityMatcher("/actuator/**")`, permit
  `/actuator/health/**`, `anyRequest().denyAll()`, CSRF off, stateless. FEP exposes no partner HTTP API; the
  TCP server is the surface and is not behind Spring Security.

- [ ] **Step 6: `CardClientConfig`.** A `RestTemplate` bean (`@Qualifier("cardRestTemplate")`) built with the
  **ported** `SigningInterceptor` (copy from `baas-engine/.../config/InternalServiceClient.java`: the inner
  `SigningInterceptor` computes `HmacSHA256(secret, METHOD|PATH|epoch|sha256hex(body))`, sets
  `Authorization: Internal <hex>` + `X-Internal-Timestamp`). Connect/read timeouts 3s/5s. Secret from
  `FepProperties.hmacSecret()`.

- [ ] **Step 7: Smoke test + commit.**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FepContextTest {
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.boot.test.web.client.TestRestTemplate rest;
    @Test void healthReady() {
        var r = rest.getForEntity("/actuator/health/readiness", String.class);
        org.assertj.core.api.Assertions.assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
```
  > The Netty server binds `fep.tcp-port` on context start. In tests set `fep.tcp-port: 0` (ephemeral) to avoid
  > a fixed-port clash, and expose the bound port for the loopback test (Task 3). Add `fep.tcp-port: 0` to
  > `application-test.yml`.
  `./mvnw -B test` → green. Commit `feat(fep): scaffold baas-fep — Netty/jPOS deps, HMAC card client, health`.

---

## Task 2: ISO 8583 packager + message model

**Files:** `iso/IsoField.java`, `iso/IsoMessageFactory.java`, `resources/iso8583-1987-fields.xml`;
Test: `iso/IsoMessageFactoryTest.java`

- [ ] **Step 1: Failing pack/unpack test.** Build an `ISOMsg` MTI `0100` with DE2=`5060001234567890`,
  DE3=`000000`, DE4=`000000005000`, DE11=`000001`, DE41=`TERM0001`, DE49=`566`; `pack()` to bytes; `unpack()`
  back; assert round-trip equality of those fields and `getMTI()=="0100"`. Run → FAIL (no factory).

- [ ] **Step 2: `IsoField` constants.**

```java
public final class IsoField {
    private IsoField(){}
    public static final int MTI=0, PAN=2, PROC_CODE=3, AMOUNT=4, TRANSMISSION_DTS=7, STAN=11,
        LOCAL_TIME=12, LOCAL_DATE=13, EXPIRY=14, POS_ENTRY=22, RRN=37, AUTH_CODE=38, RESPONSE_CODE=39,
        TERMINAL_ID=41, MERCHANT_ID=42, CURRENCY=49, NETWORK_MGMT_CODE=70;
}
```

- [ ] **Step 3: `iso8583-1987-fields.xml`.** A jPOS `GenericPackager` config (DTD
  `genericpackager.dtd`) covering the fields above. Minimal working subset:

```xml
<?xml version="1.0"?>
<!DOCTYPE isopackager SYSTEM "genericpackager.dtd">
<isopackager>
  <isofield id="0"  length="4"  name="MTI"                     class="org.jpos.iso.IFA_NUMERIC"/>
  <isofield id="1"  length="16" name="BITMAP"                  class="org.jpos.iso.IFA_BITMAP"/>
  <isofield id="2"  length="19" name="PAN"                     class="org.jpos.iso.IFA_LLNUM"/>
  <isofield id="3"  length="6"  name="PROCESSING CODE"         class="org.jpos.iso.IFA_NUMERIC"/>
  <isofield id="4"  length="12" name="AMOUNT"                  class="org.jpos.iso.IFA_NUMERIC"/>
  <isofield id="7"  length="10" name="TRANSMISSION DATE/TIME"  class="org.jpos.iso.IFA_NUMERIC"/>
  <isofield id="11" length="6"  name="STAN"                    class="org.jpos.iso.IFA_NUMERIC"/>
  <isofield id="12" length="6"  name="LOCAL TIME"              class="org.jpos.iso.IFA_NUMERIC"/>
  <isofield id="13" length="4"  name="LOCAL DATE"              class="org.jpos.iso.IFA_NUMERIC"/>
  <isofield id="14" length="4"  name="EXPIRY"                  class="org.jpos.iso.IFA_NUMERIC"/>
  <isofield id="22" length="3"  name="POS ENTRY MODE"          class="org.jpos.iso.IFA_NUMERIC"/>
  <isofield id="37" length="12" name="RRN"                     class="org.jpos.iso.IF_CHAR"/>
  <isofield id="38" length="6"  name="AUTH CODE"               class="org.jpos.iso.IF_CHAR"/>
  <isofield id="39" length="2"  name="RESPONSE CODE"           class="org.jpos.iso.IF_CHAR"/>
  <isofield id="41" length="8"  name="TERMINAL ID"             class="org.jpos.iso.IF_CHAR"/>
  <isofield id="42" length="15" name="MERCHANT ID"             class="org.jpos.iso.IF_CHAR"/>
  <isofield id="49" length="3"  name="CURRENCY"                class="org.jpos.iso.IFA_NUMERIC"/>
  <isofield id="70" length="3"  name="NETWORK MGMT CODE"       class="org.jpos.iso.IFA_NUMERIC"/>
</isopackager>
```
  > The `genericpackager.dtd` ships inside the jPOS jar; jPOS resolves it from the classpath automatically.

- [ ] **Step 4: `IsoMessageFactory`.**

```java
@Component
public class IsoMessageFactory {
    private final GenericPackager packager;
    public IsoMessageFactory() {
        try (var in = getClass().getResourceAsStream("/iso8583-1987-fields.xml")) {
            this.packager = new GenericPackager(in);
        } catch (Exception e) { throw new IllegalStateException("Cannot load ISO 8583 packager", e); }
    }
    public ISOMsg create(String mti) {
        ISOMsg m = new ISOMsg(); m.setPackager(packager);
        try { m.setMTI(mti); } catch (ISOException e) { throw new IllegalStateException(e); }
        return m;
    }
    public byte[] pack(ISOMsg m) {
        try { m.setPackager(packager); return m.pack(); }
        catch (ISOException e) { throw new IllegalStateException("pack failed", e); }
    }
    public ISOMsg unpack(byte[] raw) {
        ISOMsg m = new ISOMsg(); m.setPackager(packager);
        try { m.unpack(raw); return m; }
        catch (ISOException e) { throw new IllegalArgumentException("unpack failed", e); }
    }
}
```

- [ ] **Step 5: Run + commit** `feat(fep): jPOS GenericPackager + ISO 8583-1987 field model`.

---

## Task 3: Netty TCP server (2-byte length framing)

**Files:** `server/FepTcpServer.java`, `server/FepServerInitializer.java`, `server/FepMessageHandler.java`;
`support/Iso8583TestClient.java`; Test: `server/FepTcpServerLoopbackTest.java`

- [ ] **Step 1: Failing loopback test.** Start context (`fep.tcp-port: 0`); read the bound port from
  `FepTcpServer.getBoundPort()`; open a socket, send a length-prefixed `0800` echo (DE70=`301`), read the
  length-prefixed reply, unpack → assert MTI `0810`, DE39 `00`. Run → FAIL.

- [ ] **Step 2: Initializer (framing).**

```java
@Component @RequiredArgsConstructor
public class FepServerInitializer extends ChannelInitializer<SocketChannel> {
    private final FepMessageHandler handler;
    @Override protected void initChannel(SocketChannel ch) {
        ch.pipeline()
          .addLast(new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2))  // strip 2-byte len header
          .addLast(new LengthFieldPrepender(2))                          // prepend 2-byte len on write
          .addLast(handler);
    }
}
```

- [ ] **Step 3: Server lifecycle.**

```java
@Component @RequiredArgsConstructor @Slf4j
public class FepTcpServer {
    private final FepProperties props;
    private final FepServerInitializer initializer;
    private EventLoopGroup boss, worker;
    private Channel channel;

    @PostConstruct public void start() {
        boss = new NioEventLoopGroup(1); worker = new NioEventLoopGroup();
        var b = new ServerBootstrap().group(boss, worker)
            .channel(NioServerSocketChannel.class)
            .childHandler(initializer);
        channel = b.bind(props.tcpPort()).syncUninterruptibly().channel();
        log.info("FEP TCP server bound on {}", getBoundPort());
    }
    public int getBoundPort() { return ((InetSocketAddress) channel.localAddress()).getPort(); }
    @PreDestroy public void stop() {
        if (channel != null) channel.close();
        if (boss != null) boss.shutdownGracefully();
        if (worker != null) worker.shutdownGracefully();
    }
}
```

- [ ] **Step 4: Handler (decode→route→encode).**

```java
@Component @ChannelHandler.Sharable @RequiredArgsConstructor @Slf4j
public class FepMessageHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private final IsoMessageFactory iso;
    private final MessageRouter router;
    @Override protected void channelRead0(ChannelHandlerContext ctx, ByteBuf in) {
        byte[] raw = new byte[in.readableBytes()]; in.readBytes(raw);
        ISOMsg response;
        try {
            ISOMsg request = iso.unpack(raw);
            response = router.route(request);
        } catch (Exception e) {
            log.warn("FEP processing error: {}", e.getMessage());   // never log PAN
            response = router.systemError();                        // MTI derived, DE39=96
        }
        ctx.writeAndFlush(Unpooled.wrappedBuffer(iso.pack(response)));
    }
}
```

- [ ] **Step 5: `Iso8583TestClient` helper** (open socket, write `len(2)+bytes`, read `len(2)+bytes`). Run →
  the loopback test now drives Task 4's router; if router isn't built yet, stub `MessageRouter.route` to return
  an `0810`/`00` for `0800`. Commit `feat(fep): Netty TCP server with 2-byte length framing`.

---

## Task 4: MTI router

**Files:** `router/MessageRouter.java`, `router/NetworkHandler.java` (others stubbed then filled in T5–T6);
Test: `router/NetworkHandlerTest.java`

- [ ] **Step 1: Failing test.** `MessageRouter.route(0800 with DE70=001)` → `0810`, DE39 `00`, DE70 `001`;
  `route(0810?)`/unknown MTI `0999` → response MTI `0810`-style error or RC `30` (format error). Assert an
  unknown MTI returns DE39 `30`. Run → FAIL.

- [ ] **Step 2: Router.**

```java
@Component @RequiredArgsConstructor
public class MessageRouter {
    private final AuthorizationHandler authHandler;
    private final FinancialHandler financialHandler;
    private final ReversalHandler reversalHandler;
    private final NetworkHandler networkHandler;
    private final IsoMessageFactory iso;

    public ISOMsg route(ISOMsg req) {
        String mti = mti(req);
        return switch (mti) {
            case "0100" -> authHandler.handle(req);
            case "0200" -> financialHandler.handle(req);
            case "0400" -> reversalHandler.handle(req);
            case "0800" -> networkHandler.handle(req);
            default     -> error(req, "30");   // format error / unsupported MTI
        };
    }
    public ISOMsg systemError() { ISOMsg m = iso.create("0810"); set(m, IsoField.RESPONSE_CODE, "96"); return m; }
    private ISOMsg error(ISOMsg req, String rc) {
        ISOMsg m = iso.create(responseMti(mti(req)));
        set(m, IsoField.RESPONSE_CODE, rc); return m;
    }
    static String mti(ISOMsg m){ try { return m.getMTI(); } catch (ISOException e){ return "0000"; } }
    static String responseMti(String reqMti){ // 0100->0110, 0200->0210, 0400->0410, 0800->0810
        return reqMti.substring(0,2) + "1" + reqMti.substring(3);
    }
    static void set(ISOMsg m,int f,String v){ try { m.set(f,v);} catch(ISOException e){ throw new IllegalStateException(e);} }
}
```

- [ ] **Step 3: `NetworkHandler`.** `0800` → `0810`: echo DE70, DE39 `00`, DE11 (STAN) and DE7 echoed. Run →
  green. Commit `feat(fep): MTI router + network-management (0800/0810)`.

---

## Task 5: BIN-based tenant routing + Caffeine cache + Card BIN client

**Files:** `routing/BinResolver.java`, `routing/PartnerRoute.java`, `routing/CardClient.java`,
`client/HttpCardClient.java`, `support/StubCardClient.java`; Test: `routing/BinResolverTest.java`

- [ ] **Step 1: Failing test.** With a `StubCardClient` returning `PartnerRoute(uuid,"partner_x")` for BIN
  `506000` and empty for others: `BinResolver.resolve("5060001234567890")` → present route; a second call hits
  the **cache** (assert the stub's call-count stays 1); `resolve("9999990000000000")` → empty. Run → FAIL.

- [ ] **Step 2: `CardClient` interface + `PartnerRoute`.**

```java
public record PartnerRoute(java.util.UUID partnerId, String schemaName) {}

public interface CardClient {
    java.util.Optional<PartnerRoute> lookupBin(String bin);
    AuthorizationDecision authorize(AuthorizationDecision.Request req);   // defined in Task 6
}
```

- [ ] **Step 3: `BinResolver` with Caffeine.**

```java
@Component @RequiredArgsConstructor
public class BinResolver {
    private final CardClient cardClient;
    private final Cache<String, Optional<PartnerRoute>> cache = Caffeine.newBuilder()
        .maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build();

    public Optional<PartnerRoute> resolve(String pan) {
        String bin = bin(pan);
        return cache.get(bin, cardClient::lookupBin);
    }
    static String bin(String pan) {                 // 6–8 digit BIN, zero-padded to 8 to match Card's lookup
        String digits = pan.replaceAll("\\D", "");
        String head = digits.length() >= 8 ? digits.substring(0,8) : digits;
        return String.format("%-8s", head).replace(' ', '0');
    }
}
```
  > **Normalization parity:** `bin(...)` MUST match Card's `BinService.normalize(...)` (8-char zero-pad) so the
  > FEP-sent BIN range-matches Card's stored `bin_start/bin_end`. This is a cross-track invariant — if either
  > side changes the normalization, both break. Documented in the contracts doc note (Task 9).

- [ ] **Step 4: `HttpCardClient`.** `lookupBin` GETs `{card.base-url}/internal/v1/bins/{bin}` via the HMAC
  `cardRestTemplate`; `200` → map `data` to `PartnerRoute`; `404`/`RestClientException` → `Optional.empty()`
  (fail-closed — unknown BIN is treated as unrouteable, never throws into the Netty thread).

- [ ] **Step 5: Run + commit** `feat(fep): BIN→tenant routing via Card lookup + Caffeine 5-min cache`.

---

## Task 6: Authorization flow (0100→0110, 0200→0210) + unknown-BIN RC 91

**Files:** `router/AuthorizationHandler.java`, `router/FinancialHandler.java`, `routing/AuthorizationDecision.java`,
extend `HttpCardClient`/`StubCardClient`; Test: `router/AuthorizationHandlerTest.java`

- [ ] **Step 1: Failing tests.** With a stub Card client:
  - Known BIN + Card returns `APPROVE/00` → handler builds `0110`, DE39 `00`, echoes DE11/DE7, sets DE38 auth
    code.
  - Known BIN + Card returns `DECLINE/61` → `0110`, DE39 `61`.
  - **Unknown BIN** → `0110`, DE39 `91`, and **DE2 (PAN) is absent from the response** (no PAN echo). Assert
    `response.hasField(2) == false`.
  Run → FAIL.

- [ ] **Step 2: Decision DTO.**

```java
public record AuthorizationDecision(String decision, String responseCode, String message) {
    public record Request(String partnerId, String schemaName, String pan, long amountMinor, String currency) {}
}
```
  > `Request` carries `schemaName` so Card can set its tenant context (Card Task 6). FEP sends the PAN so Card
  > can resolve the card; **FEP never logs the PAN** and never echoes it on an unrouteable response.

- [ ] **Step 3: `AuthorizationHandler`.**

```java
@Component @RequiredArgsConstructor
public class AuthorizationHandler {
    private final BinResolver binResolver;
    private final CardClient cardClient;
    private final IsoMessageFactory iso;

    public ISOMsg handle(ISOMsg req) {
        String pan = field(req, IsoField.PAN);
        var route = binResolver.resolve(pan);
        if (route.isEmpty()) return unrouteable(req);            // RC 91, NO PAN echo
        long amount = Long.parseLong(field(req, IsoField.AMOUNT));
        var decision = cardClient.authorize(new AuthorizationDecision.Request(
            route.get().partnerId().toString(), route.get().schemaName(),
            pan, amount, field(req, IsoField.CURRENCY)));
        ISOMsg resp = iso.create(MessageRouter.responseMti(MessageRouter.mti(req)));
        echo(req, resp, IsoField.STAN, IsoField.TRANSMISSION_DTS, IsoField.PROC_CODE, IsoField.AMOUNT,
             IsoField.CURRENCY, IsoField.TERMINAL_ID);
        MessageRouter.set(resp, IsoField.RESPONSE_CODE, decision.responseCode());
        if ("00".equals(decision.responseCode()))
            MessageRouter.set(resp, IsoField.AUTH_CODE, authCode());
        return resp;
    }
    private ISOMsg unrouteable(ISOMsg req) {
        ISOMsg resp = iso.create(MessageRouter.responseMti(MessageRouter.mti(req)));
        echo(req, resp, IsoField.STAN, IsoField.TRANSMISSION_DTS);   // deliberately NOT DE2
        MessageRouter.set(resp, IsoField.RESPONSE_CODE, "91");       // issuer unavailable / unrouteable
        return resp;
    }
    // field(), echo(), authCode() helpers — echo copies present source fields; authCode = 6 random digits.
}
```

- [ ] **Step 4: `FinancialHandler`.** Same flow as `AuthorizationHandler` for `0200→0210` (processing code
  `01xxxx` = withdrawal; pass through identically in the stub). `ReversalHandler` (`0400→0410`): stub — echo
  STAN/DE90 and approve (`00`); real reversal logic is Phase 2.

- [ ] **Step 5: Run + commit** `feat(fep): authorization flow → Card decision → DE39; unknown-BIN RC91 no-PAN-echo`.

---

## Task 7: Infrastructure — compose + CI

**Files:** `infrastructure/docker-compose.yml` (add block), `.github/workflows/baas-fep-ci.yml`

- [ ] **Step 1: Compose block.** Add `baas-fep`: build `../baas-fep`, `image: baas-fep:local`,
  env `CARD_BASE_URL: http://baas-card:8081`, `INTERNAL_SERVICE_SECRET`, `FEP_TCP_PORT: 8583`,
  `SPRING_PROFILES_ACTIVE`; `ports: ["${BAAS_FEP_HTTP_PORT:-8083}:8082", "${BAAS_FEP_TCP_PORT:-8583}:8583"]`;
  `depends_on: baas-card (service_started)`; healthcheck on `http://127.0.0.1:8082/actuator/health/readiness`.
  > Host HTTP port mapped to 8083 to avoid clashing with engine 8080 / card 8081 / ncube 8082 on the host;
  > container still listens on 8082. TCP 8583 mapped straight through.

- [ ] **Step 2: CI workflow.** Copy `baas-engine-ci.yml` → `baas-fep-ci.yml`; change name, `paths`
  (`baas-fep/**`), `working-directory: baas-fep`, image `ghcr.io/${lower_owner}/baas-fep`, build context
  `baas-fep`. Keep pinned action SHAs.

- [ ] **Step 3: Commit** `chore(fep): docker-compose block + GHCR CI workflow`.

---

## Task 8: Session Completion Gate — docs, deferred registry, contract note

**Files:** `baas-log.md`, `CLAUDE.md`, `docs/deferred-items.md`, `docs/api-reference.html` (note FEP is a TCP
service — document the MTIs and routing, not REST), `docs/contracts/phase1c-interfaces.md`

- [ ] **Step 1: Run full suite** `cd baas-fep && ./mvnw -B test` → `Tests run: N, Failures: 0`.
- [ ] **Step 2: Deferred registry.** The card-scheme deferrals (`DEF-1C-01..07`) already cover EMV/HSM/scheme/
  settlement. Append FEP-specific:
  - `DEF-1C-24 | FEP authorization-log persistence (auth audit trail) | Stateless spine in 1C | Phase 2 | Track-FEP`
  - `DEF-1C-25 | Reversal (0400) real processing — match + reverse original | Stub approves in 1C | Phase 2 | Track-FEP`
  - `DEF-1C-26 | Card-BIN-change cache invalidation push (vs 5-min TTL only) | TTL acceptable in 1C | Phase 2 | Track-FEP`
- [ ] **Step 3: `baas-log.md`** — top entry: Session N, summary + SHA, files table, Key Decisions (stateless
  FEP/no DB; 2-byte framing; BIN normalization parity invariant with Card; unknown-BIN RC91 no-PAN-echo;
  PAN-never-logged), Build Verification, **Confirmed Platform Versions** for `baas-fep` (Spring Boot 3.5.3,
  Java 21, jPOS 2.1.10, Netty 4.1.115, last commit SHA).
- [ ] **Step 4: `CLAUDE.md`** — Confirmed Platform Versions: add `baas-fep` row (+ jPOS/Netty/Caffeine);
  Module Catalogue: mark FEP ✅ with the MTI inventory; gotchas (BIN normalization must match Card;
  `@ChannelHandler.Sharable` required; never log/echo PAN on unrouteable).
- [ ] **Step 5: Contract note** — in `docs/contracts/phase1c-interfaces.md` §2, confirm FEP consumes
  `GET /internal/v1/bins/{bin}` and `POST /internal/v1/authorize`, and **record the BIN normalization rule**
  (6–8 digits, zero-padded to 8) as a shared invariant both tracks must hold.
- [ ] **Step 6: Commit** `docs(baas-log+claude): Track-FEP complete — baas-fep ISO 8583 spine`. Then finish via
  `superpowers:finishing-a-development-branch` → **Option 2 (push + PR into `main`)**.

---

## Self-review checklist (run before opening the PR)

- [ ] Spec §6.7 coverage: Netty TCP ✓, jPOS packager ✓, MTI router (0100/0200/0400/0800) ✓, BIN→tenant
  routing ✓, Caffeine 5-min ✓, unknown-BIN RC 91 no-PAN-echo ✓, decision→DE39→0110 ✓. EMV/HSM/scheme/
  settlement correctly absent (deferred).
- [ ] **PAN safety:** PAN never logged (grep handlers/log lines); PAN never echoed on the unrouteable (RC 91)
  path — `BinResolverTest`/`AuthorizationHandlerTest` assert `!response.hasField(2)`.
- [ ] No DB: no `spring-boot-starter-data-jpa`/flyway/postgres in `pom.xml`; no `application.yml` datasource.
- [ ] BIN normalization in `BinResolver.bin(...)` matches Card's `BinService.normalize(...)` exactly.
- [ ] `FepMessageHandler` is `@ChannelHandler.Sharable`; `PartnerContext` is **never** set in FEP (FEP holds no
  tenant ThreadLocal — it passes `schemaName` to Card in the request body).
- [ ] `./mvnw -B test` green; new service has Dockerfile + compose + CI.
- [ ] CardClient is mocked in all tests (no live Card dependency); `app.card.base-url` wired for Stage 5.
