package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.MeetingPattern;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MeetingPatternRepository extends JpaRepository<MeetingPattern, UUID> {

    List<MeetingPattern> findBySectionId(UUID sectionId);

    List<MeetingPattern> findBySectionIdIn(Collection<UUID> sectionIds);

    List<MeetingPattern> findByRoomId(UUID roomId);
}
