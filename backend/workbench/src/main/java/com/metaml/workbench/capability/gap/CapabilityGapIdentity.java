package com.metaml.workbench.capability.gap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;

// Deterministic CapabilityGap identity (MetaML Scope 6, Phase 5, section 6).
//
// gapId = sha256(processDefinitionId, activityId, activityInstanceId, sortedUnsatisfiedNames),
// using a canonical, length-prefixed serialization: each field is written as "<charLength>:<value>"
// before being concatenated. A length prefix removes any need for a delimiter character search -
// no value, of any content, can be mistaken for a field boundary, because the reader (here, the
// writer, since this is a one-way digest) always knows exactly how many characters the field
// contributes before the next length prefix begins. A null activityInstanceId is written as a
// dedicated zero-length sentinel field distinct from an empty string, so "no activity instance" and
// "an activity instance with an empty id" - which cannot occur in practice, but the encoding does
// not rely on that - can never collide.
//
// Deliberately excluded from identity: timestamps, random UUIDs, provider ordering, business
// values, JVM identity, and hash-map iteration order (unsatisfiedNames is sorted into a TreeSet
// before hashing, so the caller's original ordering/collection type never affects the result).
public final class CapabilityGapIdentity {

    private CapabilityGapIdentity() {
    }

    public static String gapId(String processDefinitionId, String activityId, String activityInstanceId,
            Collection<String> unsatisfiedOutputNames) {
        Objects.requireNonNull(processDefinitionId, "processDefinitionId must not be null");
        Objects.requireNonNull(activityId, "activityId must not be null");

        TreeSet<String> sortedNames = new TreeSet<>();
        if (unsatisfiedOutputNames != null) {
            for (String name : unsatisfiedOutputNames) {
                if (name != null) {
                    sortedNames.add(name);
                }
            }
        }

        StringBuilder canonical = new StringBuilder();
        appendField(canonical, processDefinitionId);
        appendField(canonical, activityId);
        if (activityInstanceId == null) {
            canonical.append("N;");
        } else {
            canonical.append("S");
            appendField(canonical, activityInstanceId);
        }
        canonical.append(sortedNames.size()).append(';');
        for (String name : sortedNames) {
            appendField(canonical, name);
        }

        return sha256Hex(canonical.toString());
    }

    // Writes "<charLength>:<value>;" - the trailing separator after the length prefix's colon and
    // after the value keeps a field's own digits from running into the next field's length prefix.
    private static void appendField(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JCA algorithm on every supported JVM; this cannot happen outside
            // of a broken runtime, which is not a condition this pure utility should degrade for.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
