package com.setu.finance.infrastructure.web;

import com.setu.finance.application.StudentAccountService;
import com.setu.finance.domain.StudentAccount;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** Day 12 §Finance: student_accounts — see StudentAccountService's javadoc. */
@RestController
@RequestMapping("/finance/accounts")
public class StudentAccountController {

    private final StudentAccountService studentAccountService;

    public StudentAccountController(StudentAccountService studentAccountService) {
        this.studentAccountService = studentAccountService;
    }

    public record AccountResponse(UUID id, UUID tenantId, UUID studentId, BigDecimal balance, String currency) {
    }

    @GetMapping
    public ResponseEntity<?> get(@RequestParam UUID tenantId, @RequestParam UUID studentId) {
        return studentAccountService.get(tenantId, studentId)
            .map(a -> ResponseEntity.ok(new AccountResponse(a.id(), a.tenantId(), a.studentId(), a.balance(), a.currency())))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
