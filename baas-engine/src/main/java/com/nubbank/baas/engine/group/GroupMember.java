package com.nubbank.baas.engine.group;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nubbank.baas.engine.customer.Customer;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "group_members",
    uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "customer_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GroupMember {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "group_id", nullable = false)
    @JsonIgnore private Group group;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id", nullable = false)
    @JsonIgnore private Customer customer;
    @Column(name = "group_id", insertable = false, updatable = false) private UUID groupId;
    @Column(name = "customer_id", insertable = false, updatable = false) private UUID customerId;
    @Column(name = "joined_at", updatable = false) private Instant joinedAt;
    @PrePersist void onCreate() { joinedAt = Instant.now(); }
}
