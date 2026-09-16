package com.metaml.workbench.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;

import com.metaml.workbench.model.ProcessModelArchive;

public interface ProcessModelArchiveRepository extends JpaRepository<ProcessModelArchive, Long> {
    Optional<ProcessModelArchive> findByModelId(String modelId);

    @EntityGraph(attributePaths = {"proxyTwinActivityMappings"})
    @Query("SELECT DISTINCT a FROM ProcessModelArchive a")
    List<ProcessModelArchive> findAllWithProxyTwinActivityMappings();

    @EntityGraph(attributePaths = {"proxyTwinActivityMappings"})
    @Query("SELECT a FROM ProcessModelArchive a WHERE a.modelId = :modelId")
    Optional<ProcessModelArchive> findWithProxyTwinActivityMappingsByModelId(@Param("modelId") String modelId);

    List<ProcessModelArchive> findAllByProjectIdOrderByCreatedAtDesc(Long projectId);

    void deleteByModelId(String modelId);
}
