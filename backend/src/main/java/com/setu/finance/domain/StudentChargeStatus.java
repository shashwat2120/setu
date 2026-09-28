package com.setu.finance.domain;

/** Matches finance.student_charges.status's DB CHECK constraint exactly (V43). No dedicated state-machines.md entry exists for this entity (confirmed absent — only Payment, #20, is documented for Finance); the transitions below are this module's own minimal, disclosed design: PENDING is the only entry state; PAID and WAIVED are terminal, reached only via an explicit admin/system action; OVERDUE is reached only via an explicit action too (no scheduler/cron infrastructure exists yet in this codebase to derive it automatically from due_date), and itself still permits PAID or WAIVED afterward. */
public enum StudentChargeStatus {
    PENDING, PAID, WAIVED, OVERDUE
}
