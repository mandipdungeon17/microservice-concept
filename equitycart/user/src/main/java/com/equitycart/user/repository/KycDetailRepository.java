package com.equitycart.user.repository;

import com.equitycart.user.entity.KycDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KycDetailRepository extends JpaRepository<KycDetail, Long> {
}
