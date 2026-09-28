package com.setu.career.infrastructure.web;

import com.setu.career.application.StudentSkillService;
import com.setu.career.domain.ProficiencyLevel;
import com.setu.career.domain.StudentSkill;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/career/student-skills")
public class StudentSkillController {

    private final StudentSkillService studentSkillService;

    public StudentSkillController(StudentSkillService studentSkillService) {
        this.studentSkillService = studentSkillService;
    }

    public record AddRequest(UUID studentId, UUID skillId, ProficiencyLevel proficiencyLevel, String evidenceRef) {
    }

    public record StudentSkillResponse(UUID id, UUID tenantId, UUID studentId, UUID skillId, ProficiencyLevel proficiencyLevel, String evidenceRef) {
    }

    private static StudentSkillResponse toResponse(StudentSkill s) {
        return new StudentSkillResponse(s.id(), s.tenantId(), s.studentId(), s.skillId(), s.proficiencyLevel(), s.evidenceRef());
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody AddRequest request) {
        var result = studentSkillService.add(request.studentId(), request.skillId(), request.proficiencyLevel(), request.evidenceRef());
        return switch (result) {
            case StudentSkillService.AddResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(studentSkillService.listForStudent(request.studentId()).stream()
                    .filter(s -> s.id().equals(r.studentSkillId())).findFirst().map(StudentSkillController::toResponse).orElseThrow());
            case StudentSkillService.AddResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
            case StudentSkillService.AddResult.SkillNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("skill not found"));
            case StudentSkillService.AddResult.AlreadyAdded ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this skill is already on the student's profile"));
        };
    }

    @GetMapping
    public ResponseEntity<List<StudentSkillResponse>> listForStudent(@RequestParam UUID studentId) {
        return ResponseEntity.ok(studentSkillService.listForStudent(studentId).stream().map(StudentSkillController::toResponse).toList());
    }

    @DeleteMapping("/{studentSkillId}")
    public ResponseEntity<?> remove(@PathVariable UUID studentSkillId) {
        return switch (studentSkillService.remove(studentSkillId)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
