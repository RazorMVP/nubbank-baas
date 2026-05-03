package com.nubbank.baas.engine.rate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "tax_group_mappings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TaxGroupMapping {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "tax_group_id", nullable = false)
    @JsonIgnore private TaxGroup taxGroup;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "tax_component_id", nullable = false)
    @JsonIgnore private TaxComponent taxComponent;
    @Column(name = "tax_component_id", insertable = false, updatable = false) private UUID taxComponentId;
    @Column(name = "start_date", nullable = false) private LocalDate startDate;
}
