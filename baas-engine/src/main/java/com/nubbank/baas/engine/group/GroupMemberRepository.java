package com.nubbank.baas.engine.group;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {
    List<GroupMember> findByGroupId(UUID groupId);
    boolean existsByGroupIdAndCustomerId(UUID groupId, UUID customerId);
}
