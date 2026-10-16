package com.setu.shared.readmodel;

import com.setu.shared.authorization.GuardianDelegationChecker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * Closes the one gap the read model leaves open: its copy of the guardian grants lags a revocation by the seconds it takes the event to be
 * applied, and "revocation takes immediate effect" is a rule, not a target.
 * <ul>
 *   <li>The local copy says <b>no</b>: the answer is no, without asking anyone. (A grant that was only just issued is not honoured until its
 *       event arrives; that lag errs on the side of access being refused, never granted.)</li>
 *   <li>The local copy says <b>yes</b>: the owner is asked. Its yes is a yes; its no is a no, and the copy was behind.</li>
 *   <li>The owner <b>cannot be asked</b>: the answer is no. There is no fallback to the local yes, because that is exactly the stale answer
 *       this check exists to distrust. The guardian sees "not available right now", which is true; nothing is faked.</li>
 * </ul>
 * Students, faculty and administrators never reach this: only the guardian path asks, and only when the local copy already says yes.
 */
public class VerifiedGuardianDelegationChecker implements GuardianDelegationChecker {

    private static final Logger log = LoggerFactory.getLogger(VerifiedGuardianDelegationChecker.class);

    private final GuardianDelegationChecker local;
    private final GuardianGrantVerifier owner;
    private final MeterRegistry meters;

    public VerifiedGuardianDelegationChecker(GuardianDelegationChecker local, GuardianGrantVerifier owner, MeterRegistry meters) {
        this.local = local;
        this.owner = owner;
        this.meters = meters;
    }

    @Override
    public boolean hasActiveDelegatedAccess(UUID guardianUserId, UUID studentProfileId, UUID tenantId, String permissionCode, Instant now) {
        if (!local.hasActiveDelegatedAccess(guardianUserId, studentProfileId, tenantId, permissionCode, now)) {
            return false;
        }
        GuardianGrantVerifier.Verdict verdict = owner.verify(guardianUserId, studentProfileId, tenantId, permissionCode);
        meters.counter("setu.guardian.grant_verification", "outcome", verdict.name().toLowerCase()).increment();
        if (verdict == GuardianGrantVerifier.Verdict.UNAVAILABLE) {
            log.warn("guardian grant could not be confirmed with its owner; access refused rather than trusting the local copy");
        }
        return verdict == GuardianGrantVerifier.Verdict.ACTIVE;
    }
}
