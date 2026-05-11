package com.equitycart.ledger.service.impl;

import com.equitycart.ledger.entity.LedgerEntry;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.EntryType;
import com.equitycart.ledger.enums.ReferenceType;
import com.equitycart.ledger.repository.LedgerEntryRepository;
import com.equitycart.ledger.service.api.LedgerService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Double-entry bookkeeping service. Every financial action (trade, reward vest, sell-to-spend)
 * produces a balanced DEBIT+CREDIT pair sharing a single transactionId.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class LedgerServiceImpl implements LedgerService {

  private static final Logger log = LogManager.getLogger(LedgerServiceImpl.class);

  private final LedgerEntryRepository ledgerEntryRepository;

  /** {@inheritDoc} */
  @Override
  public void recordTransaction(
      AccountType debitAccount,
      AccountType creditAccount,
      BigDecimal amount,
      ReferenceType referenceType,
      Long referenceId,
      String description) {

    UUID transactionId = UUID.randomUUID(); // Unique ID for this transaction

    LedgerEntry debitEntry =
        LedgerEntry.builder()
            .transactionId(transactionId)
            .accountType(debitAccount)
            .entryType(EntryType.DEBIT)
            .amount(amount)
            .referenceType(referenceType)
            .referenceId(referenceId)
            .description(description)
            .build();

    LedgerEntry creditEntry =
        LedgerEntry.builder()
            .transactionId(transactionId)
            .accountType(creditAccount)
            .entryType(EntryType.CREDIT)
            .amount(amount)
            .referenceType(referenceType)
            .referenceId(referenceId)
            .description(description)
            .build();

    ledgerEntryRepository.saveAll(List.of(debitEntry, creditEntry));

    log.info(
        "Ledger transaction {} recorded: DEBIT {} / CREDIT {} | amount={} | ref={}:{}",
        transactionId,
        debitAccount,
        creditAccount,
        amount,
        referenceType,
        referenceId);
  }

  /** {@inheritDoc} */
  @Override
  public List<LedgerEntry> getEntriesByReference(ReferenceType referenceType, Long referenceId) {
    return ledgerEntryRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId);
  }
}
