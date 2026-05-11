package com.equitycart.ledger.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.EntryType;
import com.equitycart.ledger.enums.ReferenceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable audit record for double-entry bookkeeping. Every financial action produces exactly two
 * entries (DEBIT + CREDIT) sharing the same {@code transactionId}. Entries are append-only — never
 * updated or deleted.
 */
@Entity
@Table(name = "ledger_entries")
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Builder
public class LedgerEntry extends BaseEntity {

  @Column(nullable = false)
  private UUID transactionId;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private AccountType accountType;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private EntryType entryType;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private ReferenceType referenceType;

  @Column(precision = 19, scale = 4, nullable = false)
  private BigDecimal amount;

  private Long referenceId;

  private String description;
}
