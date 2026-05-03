package com.nubbank.baas.engine.role;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "user_roles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(UserRoleId.class)
public class UserRole {
    @Id @Column(name = "user_id", nullable = false) private UUID userId;
    @Id
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "role_id", nullable = false)
    @JsonIgnore private Role role;
    @Column(name = "role_id", insertable = false, updatable = false) private UUID roleId;
}
