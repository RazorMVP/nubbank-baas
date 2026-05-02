package com.nubbank.baas.engine.notification;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, UUID> {
    Page<NotificationEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<NotificationEvent> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
}
