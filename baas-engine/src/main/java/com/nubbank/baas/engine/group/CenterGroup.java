package com.nubbank.baas.engine.group;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "center_groups",
    uniqueConstraints = @UniqueConstraint(columnNames = {"center_id", "group_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CenterGroup {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "center_id", nullable = false)
    @JsonIgnore private Center center;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "group_id", nullable = false)
    @JsonIgnore private Group group;
    @Column(name = "center_id", insertable = false, updatable = false) private UUID centerId;
    @Column(name = "group_id", insertable = false, updatable = false) private UUID groupId;
}
