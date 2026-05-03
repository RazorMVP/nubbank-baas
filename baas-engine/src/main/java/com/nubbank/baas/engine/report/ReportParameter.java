package com.nubbank.baas.engine.report;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "report_parameters")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReportParameter {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "report_id", nullable = false)
    @JsonIgnore private Report report;
    @Column(name = "param_name", nullable = false, length = 100) private String paramName;
    @Column(name = "param_type", nullable = false, length = 50) private String paramType;
    @Column(nullable = false) private boolean required;
    @Column(name = "default_value", length = 500) private String defaultValue;
}
