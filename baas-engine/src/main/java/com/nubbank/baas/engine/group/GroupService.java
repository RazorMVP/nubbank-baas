package com.nubbank.baas.engine.group;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.group.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepo;
    private final GroupMemberRepository memberRepo;
    private final CenterRepository centerRepo;
    private final CenterGroupRepository centerGroupRepo;
    private final CustomerRepository customerRepo;

    @Transactional
    public Group createGroup(GroupRequest req) {
        requireContext();
        return groupRepo.save(Group.builder()
            .name(req.name()).externalId(req.externalId())
            .officeId(req.officeId()).staffId(req.staffId())
            .build());
    }

    @Transactional
    public Group executeCommand(UUID id, String command) {
        requireContext();
        Group group = groupRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("GROUP_NOT_FOUND", "Group not found"));
        switch (command.toLowerCase()) {
            case "activate" -> {
                if (group.getStatus() != GroupStatus.PENDING)
                    throw BaasException.badRequest("INVALID_STATUS", "Only PENDING groups can be activated");
                group.setStatus(GroupStatus.ACTIVE);
                group.setActivationDate(LocalDate.now());
            }
            case "close" -> group.setStatus(GroupStatus.CLOSED);
            default -> throw BaasException.badRequest("UNKNOWN_COMMAND", "Unknown command: " + command);
        }
        return groupRepo.save(group);
    }

    @Transactional
    public GroupMember addMember(UUID groupId, UUID customerId) {
        requireContext();
        if (memberRepo.existsByGroupIdAndCustomerId(groupId, customerId))
            throw BaasException.conflict("ALREADY_MEMBER", "Customer is already a member of this group");
        Group group = groupRepo.findById(groupId)
            .orElseThrow(() -> BaasException.notFound("GROUP_NOT_FOUND", "Group not found"));
        var customer = customerRepo.findById(customerId)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
        return memberRepo.save(GroupMember.builder().group(group).customer(customer).build());
    }

    @Transactional
    public void removeMember(UUID groupId, UUID customerId) {
        requireContext();
        memberRepo.findByGroupId(groupId).stream()
            .filter(m -> m.getCustomer().getId().equals(customerId))
            .findFirst()
            .ifPresentOrElse(memberRepo::delete,
                () -> { throw BaasException.notFound("MEMBER_NOT_FOUND", "Customer is not a member"); });
    }

    @Transactional(readOnly = true)
    public Page<Group> listGroups(int page, int size) {
        requireContext();
        return groupRepo.findAll(PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public List<GroupMember> listMembers(UUID groupId) {
        requireContext();
        return memberRepo.findByGroupId(groupId);
    }

    @Transactional
    public Center createCenter(CenterRequest req) {
        requireContext();
        return centerRepo.save(Center.builder()
            .name(req.name()).externalId(req.externalId())
            .officeId(req.officeId()).staffId(req.staffId())
            .meetingTime(req.meetingTime()).build());
    }

    @Transactional
    public Center activateCenter(UUID id) {
        requireContext();
        Center center = centerRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CENTER_NOT_FOUND", "Center not found"));
        center.setStatus(GroupStatus.ACTIVE);
        center.setActivationDate(LocalDate.now());
        return centerRepo.save(center);
    }

    @Transactional
    public CenterGroup addGroupToCenter(UUID centerId, UUID groupId) {
        requireContext();
        Center center = centerRepo.findById(centerId)
            .orElseThrow(() -> BaasException.notFound("CENTER_NOT_FOUND", "Center not found"));
        Group group = groupRepo.findById(groupId)
            .orElseThrow(() -> BaasException.notFound("GROUP_NOT_FOUND", "Group not found"));
        return centerGroupRepo.save(CenterGroup.builder().center(center).group(group).build());
    }

    @Transactional(readOnly = true)
    public List<Center> listCenters() {
        requireContext();
        return centerRepo.findAll();
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
