package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** oidc_identities 的访问通道；(issuer, subject) 是映射唯一键。 */
public interface OidcIdentityRepository extends JpaRepository<OidcIdentity, Long> {

  Optional<OidcIdentity> findByIssuerAndSubject(String issuer, String subject);

  List<OidcIdentity> findByUsername(String username);

  boolean existsByUsername(String username);
}
