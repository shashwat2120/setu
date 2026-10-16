/**
 * AI facts module (Sprint 6B.3): the data behind the AI advisor's tools and the authorization that guards it, exposed to the advisor only
 * through {@link com.setu.shared.aifacts.AiFacts}. Moved out of {@code aiexplain} so that the advisor itself needs no datasource and no other
 * module, and can run as its own service. Owns no tables; every read goes through another module's already-authorized service.
 * <p>
 * Module boundary rule (TRD section 2): other modules reach this one only through the shared {@code AiFacts} contract.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "AI facts"
)
package com.setu.aifacts;
