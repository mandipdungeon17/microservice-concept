package com.equitycart.user.repository;

import com.equitycart.user.entity.WalletAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link WalletAccount} entities. Provides CRUD operations and
 * user-based wallet lookup.
 */
public interface WalletAccountRepository extends JpaRepository<WalletAccount, Long> {

  /**
   * Finds the wallet account associated with the given user.
   *
   * @param userId the ID of the user
   * @return an {@link Optional} containing the wallet if found
   */
  Optional<WalletAccount> findByUserId(Long userId);
}
