package com.equitycart.ledger.repository;

import com.equitycart.ledger.entity.LedgerEntry;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.EntryType;
import com.equitycart.ledger.enums.ReferenceType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for append-only ledger entries. Supports audit-trail lookups by
 * transaction, reference, and account type.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

  /** Retrieves the balanced DEBIT+CREDIT pair for a given transaction. */
  List<LedgerEntry> findByTransactionId(UUID transactionId);

  /** Finds all ledger entries linked to a specific business action (e.g. ORDER #42). */
  List<LedgerEntry> findByReferenceTypeAndReferenceId(
      ReferenceType referenceType, Long referenceId);

  /** Finds all entries for a specific account type and side (e.g. all CASH DEBITs). */
  List<LedgerEntry> findByAccountTypeAndEntryType(AccountType accountType, EntryType entryType);
}
