package com.setu.risk.domain;

/** Matches risk.recommendations.generated_by's DB CHECK constraint (V48) — RULE (the deterministic engine) or AI (ai-explain, Day 14 scope, not yet a real caller). */
public enum GeneratedBy {
    RULE, AI
}
