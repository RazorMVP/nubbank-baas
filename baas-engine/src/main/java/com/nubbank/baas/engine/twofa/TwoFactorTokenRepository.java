package com.nubbank.baas.engine.twofa;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TwoFactorTokenRepository extends JpaRepository<TwoFactorToken, UUID> {}
