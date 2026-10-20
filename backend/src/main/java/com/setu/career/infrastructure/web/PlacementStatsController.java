package com.setu.career.infrastructure.web;

import com.setu.career.application.PlacementStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/career/placement-stats")
public class PlacementStatsController {

    private final PlacementStatsService placementStatsService;

    public PlacementStatsController(PlacementStatsService placementStatsService) {
        this.placementStatsService = placementStatsService;
    }

    /** The college's placement figures; the placement officer and the administrator only. */
    @GetMapping
    public PlacementStatsService.Stats stats(@RequestParam UUID tenantId) {
        return placementStatsService.forTenant(tenantId);
    }
}
