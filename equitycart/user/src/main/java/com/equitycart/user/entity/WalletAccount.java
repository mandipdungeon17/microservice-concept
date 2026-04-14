package com.equitycart.user.entity;

import com.equitycart.commons.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "wallet_accounts")
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class WalletAccount extends BaseEntity {
    @Column(precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal cashBalance = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal stockBackBalance = BigDecimal.ZERO;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="user_id", nullable = false)
    private User user;
}