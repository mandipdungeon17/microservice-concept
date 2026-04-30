package com.equitycart.user.repository;

import com.equitycart.user.entity.KycDetail;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link KycDetail} entities. Provides standard CRUD operations for
 * KYC document management.
 */
public interface KycDetailRepository extends JpaRepository<KycDetail, Long> {}
