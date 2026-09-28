package com.setu.risk.domain;

/** Matches risk.recommendations.status's DB CHECK constraint and state-machines.md #22 (Recommendation) exactly. */
public enum RecommendationStatus {
    PROPOSED, ACCEPTED, DISMISSED
}
