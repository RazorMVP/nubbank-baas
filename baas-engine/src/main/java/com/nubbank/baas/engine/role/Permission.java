package com.nubbank.baas.engine.role;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "permissions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Permission {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 100) private String grouping;
    @Column(unique = true, nullable = false, length = 200) private String code;
    @Column(name = "entity_name", length = 100) private String entityName;
    @Column(name = "action_name", length = 100) private String actionName;
    @Column(name = "can_maker_checker", nullable = false) private boolean canMakerChecker;
}
