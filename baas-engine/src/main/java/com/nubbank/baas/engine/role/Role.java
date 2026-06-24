package com.nubbank.baas.engine.role;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "roles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Role {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 100) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(nullable = false) private boolean disabled;
    @Column(name = "built_in", nullable = false) private boolean builtIn;
    @Column(name = "role_scope", nullable = false, length = 20) private String roleScope;
    @Column(name = "is_superuser", nullable = false) private boolean superuser;
    @Builder.Default
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "role_permissions",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now(); disabled = false;
        if (roleScope == null) roleScope = PartnerRoles.SCOPE_PARTNER;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
