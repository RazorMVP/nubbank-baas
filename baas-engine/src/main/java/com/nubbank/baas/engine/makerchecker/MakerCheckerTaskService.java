package com.nubbank.baas.engine.makerchecker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubbank.baas.engine.auth.AuthorityResolver;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.partner.PartnerUser;
import com.nubbank.baas.engine.partner.PartnerUserRepository;
import com.nubbank.baas.engine.role.UserRoleRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MakerCheckerTaskService {

    private final MakerCheckerTaskRepository taskRepo;
    private final MakerCheckerConfigRepository configRepo;
    private final MakerCheckerCommandRegistry registry;
    private final AuthorityResolver authorityResolver;
    private final UserRoleRepository userRoleRepo;
    private final PartnerUserRepository partnerUserRepo;
    private final ObjectMapper objectMapper;

    /**
     * Submit-or-defer. Returns empty when the command is NOT guarded (the caller then executes
     * synchronously, today's behaviour). Returns a PENDING task when guarded — nothing enters
     * the domain tables until approval.
     */
    @Transactional
    public Optional<MakerCheckerTask> submitIfGuarded(String commandType, Object payload) {
        MakerCheckerCommandHandler handler = registry.require(commandType);
        if (!isGuarded(commandType)) return Optional.empty();

        // Authorize before validating, so an unauthorized caller cannot probe domain state
        // (e.g. customer existence) via the differing validation error.
        if (!currentAuthorities().contains(handler.requiredAuthorityToSubmit()))
            throw BaasException.forbidden("MISSING_SUBMIT_AUTHORITY",
                "Missing authority " + handler.requiredAuthorityToSubmit());

        handler.validate(payload);   // courtesy validation — non-authoritative

        UUID makerId = currentUserId();

        String json;
        try { json = objectMapper.writeValueAsString(payload); }
        catch (Exception e) { throw BaasException.badRequest("PAYLOAD_SERIALIZATION", "Cannot serialize command payload"); }

        MakerCheckerTask task = MakerCheckerTask.builder()
            .commandType(commandType).payload(json).madeBy(makerId).status(TaskStatus.PENDING).build();
        return Optional.of(taskRepo.save(task));
    }

    private boolean isGuarded(String commandType) {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null || !"PRODUCTION".equals(ctx.environment())) return false;
        return configRepo.findById(commandType).map(MakerCheckerConfig::isEnabled).orElse(false);
    }

    /** Approve: four-eyes + authority re-checks + replay-execute, all in one transaction. */
    @Transactional
    public MakerCheckerTask approve(UUID taskId) {
        MakerCheckerTask task = taskRepo.findByIdForUpdate(taskId)
            .orElseThrow(() -> BaasException.notFound("TASK_NOT_FOUND", "Task " + taskId + " not found"));
        requirePending(task);
        MakerCheckerCommandHandler handler = registry.require(task.getCommandType());
        UUID checkerId = currentUserId();

        if (checkerId.equals(task.getMadeBy()))
            throw BaasException.forbidden("SELF_APPROVAL", "A task cannot be approved by its maker");

        if (!currentAuthorities().contains(handler.requiredAuthorityToApprove()))
            throw BaasException.forbidden("MISSING_APPROVE_AUTHORITY",
                "Missing authority " + handler.requiredAuthorityToApprove());

        boolean makerActive = partnerUserRepo.findById(task.getMadeBy())
            .map(PartnerUser::isActive).orElse(false);
        boolean makerStillAuthorised = authorityResolver.partnerUserAuthorities(task.getMadeBy())
            .contains(handler.requiredAuthorityToSubmit());
        if (!makerActive || !makerStillAuthorised)
            throw BaasException.forbidden("MAKER_NO_LONGER_AUTHORISED",
                "Original maker is no longer active or authorised");

        Object payload = deserialize(task, handler);
        UUID resultId = handler.execute(payload);   // throws → whole tx rolls back, task stays PENDING

        task.setStatus(TaskStatus.APPROVED);
        task.setCheckedBy(checkerId);
        task.setCheckedAt(Instant.now());
        task.setResultId(resultId);
        return taskRepo.save(task);   // @Version guards the double-approve race
    }

    @Transactional
    public MakerCheckerTask reject(UUID taskId, String reason) {
        MakerCheckerTask task = taskRepo.findByIdForUpdate(taskId)
            .orElseThrow(() -> BaasException.notFound("TASK_NOT_FOUND", "Task " + taskId + " not found"));
        requirePending(task);
        MakerCheckerCommandHandler handler = registry.require(task.getCommandType());
        if (!currentAuthorities().contains(handler.requiredAuthorityToApprove()))
            throw BaasException.forbidden("MISSING_APPROVE_AUTHORITY",
                "Missing authority " + handler.requiredAuthorityToApprove());
        task.setStatus(TaskStatus.REJECTED);
        task.setCheckedBy(currentUserId());
        task.setCheckedAt(Instant.now());
        task.setRejectReason(reason);
        return taskRepo.save(task);
    }

    @Transactional
    public MakerCheckerTask withdraw(UUID taskId) {
        MakerCheckerTask task = taskRepo.findByIdForUpdate(taskId)
            .orElseThrow(() -> BaasException.notFound("TASK_NOT_FOUND", "Task " + taskId + " not found"));
        requirePending(task);
        if (!currentUserId().equals(task.getMadeBy()))
            throw BaasException.forbidden("NOT_TASK_MAKER", "Only the maker may withdraw their own task");
        task.setStatus(TaskStatus.WITHDRAWN);
        return taskRepo.save(task);
    }

    @Transactional(readOnly = true)
    public List<MakerCheckerTask> list(TaskStatus status, String commandType) {
        if (status != null && commandType != null)
            return taskRepo.findByStatusAndCommandTypeOrderByMadeAtDesc(status, commandType);
        if (status != null)      return taskRepo.findByStatusOrderByMadeAtDesc(status);
        if (commandType != null) return taskRepo.findByCommandTypeOrderByMadeAtDesc(commandType);
        return taskRepo.findAllByOrderByMadeAtDesc();
    }

    @Transactional(readOnly = true)
    public MakerCheckerTask get(UUID taskId) {
        return taskRepo.findById(taskId)   // tenant-schema isolation makes other-org ids naturally 404
            .orElseThrow(() -> BaasException.notFound("TASK_NOT_FOUND", "Task " + taskId + " not found"));
    }

    /** Dry-run the handler's validation against current state; null = valid, else the failure message. */
    @Transactional(readOnly = true)
    public String dryRunInvalidReason(MakerCheckerTask task) {
        MakerCheckerCommandHandler handler = registry.require(task.getCommandType());
        Object payload = deserialize(task, handler);   // corrupt payload propagates (real error), not masqueraded as a validation reason
        try { handler.validate(payload); return null; }
        catch (BaasException ex) { return ex.getMessage(); }
    }

    @Transactional(readOnly = true)
    public List<MakerCheckerConfig> listConfig() { return configRepo.findAll(); }

    /** Viability guard: cannot enable a command with no eligible approver. */
    @Transactional
    public MakerCheckerConfig updateConfig(String commandType, boolean enabled) {
        MakerCheckerCommandHandler handler = registry.require(commandType);
        if (enabled && countEligibleApprovers(handler.requiredAuthorityToApprove()) < 1)
            throw BaasException.conflict("NO_ELIGIBLE_APPROVER",
                "NO_ELIGIBLE_APPROVER: cannot enable " + commandType
                + " — no user holds " + handler.requiredAuthorityToApprove());
        MakerCheckerConfig cfg = configRepo.findById(commandType)
            .orElseGet(() -> MakerCheckerConfig.builder().commandType(commandType).build());
        cfg.setEnabled(enabled);
        return configRepo.save(cfg);
    }

    private long countEligibleApprovers(String approveAuthority) {
        // A user who is BOTH a superuser and holds the approve permission via another role is
        // counted twice — harmless: this only over-estimates, so it can never wrongly block an
        // enable. It is a viability predicate (>= 1), not an exact approver count.
        return userRoleRepo.countDistinctUsersWithPermission(approveAuthority)
             + userRoleRepo.countDistinctSuperusers();
    }

    private void requirePending(MakerCheckerTask task) {
        if (task.getStatus() != TaskStatus.PENDING)
            throw BaasException.conflict("TASK_NOT_PENDING", "Task is " + task.getStatus() + ", not PENDING");
    }

    private Object deserialize(MakerCheckerTask task, MakerCheckerCommandHandler handler) {
        try { return objectMapper.readValue(task.getPayload(), handler.payloadType()); }
        catch (Exception e) { throw BaasException.badRequest("PAYLOAD_DESERIALIZATION", "Corrupt command payload"); }
    }

    private UUID currentUserId() {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null || ctx.userId() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        try { return UUID.fromString(ctx.userId()); }
        catch (IllegalArgumentException e) {
            throw BaasException.unauthorized("INVALID_PRINCIPAL", "Principal is not a user");
        }
    }

    private Set<String> currentAuthorities() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Set.of();
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
