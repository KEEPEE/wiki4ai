package com.wiki4ai.repository;

import com.wiki4ai.model.VaultEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VaultEntryRepository extends JpaRepository<VaultEntry, Long> {

    List<VaultEntry> findByUserId(Long userId);

    List<VaultEntry> findByUserIdAndGroupPathContaining(Long userId, String path);
}
