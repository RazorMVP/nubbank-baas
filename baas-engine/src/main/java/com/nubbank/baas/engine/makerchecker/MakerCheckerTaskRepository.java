package com.nubbank.baas.engine.makerchecker;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MakerCheckerTaskRepository extends JpaRepository<MakerCheckerTask, UUID> {

    List<MakerCheckerTask> findAllByOrderByMadeAtDesc();
    List<MakerCheckerTask> findByStatusOrderByMadeAtDesc(TaskStatus status);
    List<MakerCheckerTask> findByCommandTypeOrderByMadeAtDesc(String commandType);
    List<MakerCheckerTask> findByStatusAndCommandTypeOrderByMadeAtDesc(TaskStatus status, String commandType);

    /** SELECT ... FOR UPDATE — serializes concurrent approve/withdraw on the same task. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MakerCheckerTask t where t.id = :id")
    Optional<MakerCheckerTask> findByIdForUpdate(UUID id);
}
