package com.setu.record.infrastructure.web;

import com.setu.record.application.AcademicRecordService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Transcript / GPA / degree-progress - see AcademicRecordService's javadoc. */
@RestController
@RequestMapping("/record")
public class AcademicRecordController {

    private final AcademicRecordService academicRecordService;

    public AcademicRecordController(AcademicRecordService academicRecordService) {
        this.academicRecordService = academicRecordService;
    }

    @GetMapping("/transcript")
    public ResponseEntity<AcademicRecordService.Transcript> transcript(@RequestParam UUID studentId, @RequestParam UUID tenantId) {
        return ResponseEntity.ok(academicRecordService.transcript(studentId, tenantId));
    }

    @GetMapping("/degree-progress")
    public ResponseEntity<AcademicRecordService.DegreeProgress> degreeProgress(@RequestParam UUID studentId, @RequestParam UUID tenantId) {
        return ResponseEntity.ok(academicRecordService.degreeProgress(studentId, tenantId));
    }
}
