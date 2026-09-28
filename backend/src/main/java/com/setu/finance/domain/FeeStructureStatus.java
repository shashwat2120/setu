package com.setu.finance.domain;

/** Matches finance.fee_structures.status (V43, plain TEXT default 'ACTIVE', no DB CHECK constraint — validated at the domain layer instead). */
public enum FeeStructureStatus {
    ACTIVE, INACTIVE
}
