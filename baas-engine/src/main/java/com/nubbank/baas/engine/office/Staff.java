package com.nubbank.baas.engine.office;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "staff")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Staff {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "office_id") @JsonIgnore private Office office;
    @Column(name = "office_id", insertable = false, updatable = false) private UUID officeId;
    @Column(name = "first_name", nullable = false, length = 200) private String firstName;
    @Column(name = "last_name", nullable = false, length = 200) private String lastName;
    @Column(name = "display_name", length = 400) private String displayName;
    @JsonProperty("isLoanOfficer")
    @Column(name = "is_loan_officer", nullable = false) private boolean loanOfficer;
    @Column(name = "external_id", length = 100) private String externalId;
    @Column(nullable = false) private boolean active;
    @Column(name = "joining_date") private LocalDate joiningDate;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        active = true;
        if (displayName == null && firstName != null && lastName != null)
            displayName = firstName + " " + lastName;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
