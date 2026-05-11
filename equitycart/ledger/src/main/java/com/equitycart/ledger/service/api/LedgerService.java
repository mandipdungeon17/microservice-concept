package com.equitycart.ledger.service.api;

import com.equitycart.ledger.entity.LedgerEntry;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.ReferenceType;
import java.math.BigDecimal;
import java.util.List;

/**
 * Contract for double-entry bookkeeping operations. Every call to {@code recordTransaction} creates
 * a balanced DEBIT+CREDIT pair.
 */
public interface LedgerService {

  /**
   * Records a balanced double-entry transaction. Creates exactly two {@link LedgerEntry} rows — one
   * DEBIT and one CREDIT — sharing a generated transactionId. Both entries store a positive amount;
   * the sign is conveyed by the entry type.
   *
   * @param debitAccount the account to debit (asset increase or expense)
   * @param creditAccount the account to credit (liability increase or income)
   * @param amount the positive monetary value of the transaction
   * @param referenceType the business action that triggered this entry
   * @param referenceId the ID of the triggering entity (orderId, tradeId, rewardId)
   * @param description human-readable audit note (e.g. "BUY 10 AAPL @ $150.00")
   */
  void recordTransaction(
      AccountType debitAccount,
      AccountType creditAccount,
      BigDecimal amount,
      ReferenceType referenceType,
      Long referenceId,
      String description);

  /**
   * Retrieves all ledger entries associated with a specific business action. Used for audit trail
   * lookups (e.g. "show me all ledger entries for Order #42").
   *
   * @param referenceType the type of business action to filter by
   * @param referenceId the ID of the specific entity
   * @return all ledger entries matching the reference, typically a balanced DEBIT+CREDIT pair
   */
  List<LedgerEntry> getEntriesByReference(ReferenceType referenceType, Long referenceId);
}
