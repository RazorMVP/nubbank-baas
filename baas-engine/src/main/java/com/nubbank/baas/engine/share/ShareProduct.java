package com.nubbank.baas.engine.share;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "share_products")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShareProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "short_name", unique = true, nullable = false, length = 10)
    private String shortName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "total_shares", nullable = false)
    private Long totalShares;

    @Column(name = "shares_issued", nullable = false)
    private Long sharesIssued;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "minimum_shares", nullable = false)
    private Integer minimumShares;

    @Column(name = "maximum_shares")
    private Integer maximumShares;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

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
        if (sharesIssued == null) sharesIssued = 0L;
        if (currencyCode == null) currencyCode = "NGN";
        if (minimumShares == null) minimumShares = 1;
        active = true;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
