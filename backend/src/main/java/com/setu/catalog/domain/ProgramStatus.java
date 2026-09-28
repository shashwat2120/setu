package com.setu.catalog.domain;

/** Matches catalog.programs.status's CHECK constraint (V58, added Sprint 5C Day 6 — V19 originally had none). See V58's own header comment for why this minimal vocabulary was chosen; no docs/state-machines.md entry exists for Program. */
public enum ProgramStatus {
    ACTIVE, INACTIVE
}
