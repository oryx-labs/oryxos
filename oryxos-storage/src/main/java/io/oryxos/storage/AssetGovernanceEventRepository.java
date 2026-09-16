package io.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** asset_governance_events 追加型仓储；业务层禁止 update/delete。 */
public interface AssetGovernanceEventRepository extends JpaRepository<AssetGovernanceEvent, Long> {}
