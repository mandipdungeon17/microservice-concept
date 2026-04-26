package com.equitycart.user.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.user.enums.KycStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "kyc_details")
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class KycDetail extends BaseEntity {

    @Column(nullable = false)
    private String documentType;

    @Column(unique = true, nullable = false)
    private String documentNumber;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private KycStatus kycStatus = KycStatus.PENDING;

    private String rejectionReason;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}