package org.example.repository;

import org.example.entity.SettlementLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementLineRepository extends JpaRepository<SettlementLine, Long> {
}
