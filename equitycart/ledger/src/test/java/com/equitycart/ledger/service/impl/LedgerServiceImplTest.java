package com.equitycart.ledger.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.ledger.entity.LedgerEntry;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.EntryType;
import com.equitycart.ledger.enums.ReferenceType;
import com.equitycart.ledger.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerServiceImplTest {

  @Mock private LedgerEntryRepository ledgerEntryRepository;
  @InjectMocks private LedgerServiceImpl ledgerService;

  @Test
  void recordTransactionShouldPersistBalancedDebitAndCreditEntries() {
    ledgerService.recordTransaction(
        AccountType.CASH,
        AccountType.TRADING_PNL,
        new BigDecimal("12.50"),
        ReferenceType.ORDER,
        99L,
        "Order paid");

    ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
    verify(ledgerEntryRepository).saveAll(captor.capture());

    List<LedgerEntry> entries = captor.getValue();
    assertEquals(2, entries.size());
    assertEquals(EntryType.DEBIT, entries.get(0).getEntryType());
    assertEquals(EntryType.CREDIT, entries.get(1).getEntryType());
    assertEquals(entries.get(0).getTransactionId(), entries.get(1).getTransactionId());
  }

  @Test
  void getEntriesByReferenceShouldDelegateToRepository() {
    List<LedgerEntry> expected = List.of(LedgerEntry.builder().build());
    when(ledgerEntryRepository.findByReferenceTypeAndReferenceId(ReferenceType.ORDER, 77L))
        .thenReturn(expected);

    List<LedgerEntry> actual = ledgerService.getEntriesByReference(ReferenceType.ORDER, 77L);

    assertEquals(expected, actual);
  }
}
