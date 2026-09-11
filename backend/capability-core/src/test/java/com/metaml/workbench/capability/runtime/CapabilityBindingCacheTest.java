package com.metaml.workbench.capability.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.ExecutionMode;

// P7 Step 5. Genericity: a synthetic activity/provider invented for this test, no process-specific
// naming anywhere.
class CapabilityBindingCacheTest {

    private static final String ACTIVITY = "Activity_Under_Test";

    private static CapabilityBinding binding(String providerId) {
        return new CapabilityBinding("SyntheticProcess", ACTIVITY, providerId, "synthetic-type", "1.0.0",
                new CapabilityContract("synthetic-capability", Set.of(), Set.of(), ExecutionMode.SYNCHRONOUS,
                        java.util.Map.of(), Set.of()),
                null, Instant.now());
    }

    @Test
    void resolvedBindingIsStickyAndNeverRefetched() {
        AtomicInteger calls = new AtomicInteger();
        CapabilityBindingCache cache = new CapabilityBindingCache(activityId -> {
            calls.incrementAndGet();
            return Optional.of(binding("provider-alpha"));
        });

        Optional<CapabilityBinding> first = cache.get(ACTIVITY);
        Optional<CapabilityBinding> second = cache.get(ACTIVITY);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().providerId()).isEqualTo("provider-alpha");
        assertThat(second.get().providerId()).isEqualTo("provider-alpha");
        // Sticky: the resolver was consulted exactly once, not once per get().
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aMissIsNeverCachedAndIsRetriedEveryTime() {
        AtomicInteger calls = new AtomicInteger();
        CapabilityBindingCache cache = new CapabilityBindingCache(activityId -> {
            calls.incrementAndGet();
            return Optional.empty();
        });

        assertThat(cache.get(ACTIVITY)).isEmpty();
        assertThat(cache.get(ACTIVITY)).isEmpty();
        assertThat(cache.get(ACTIVITY)).isEmpty();

        // Not sticky: every miss retried the resolver, because a legitimately-late binding must
        // become resolvable without restarting anything.
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void aLateBindingBecomesResolvableWithoutRestarting() {
        AtomicInteger calls = new AtomicInteger();
        CapabilityBindingCache cache = new CapabilityBindingCache(activityId -> calls.incrementAndGet() < 3
                ? Optional.empty()
                : Optional.of(binding("provider-late")));

        assertThat(cache.get(ACTIVITY)).isEmpty();
        assertThat(cache.get(ACTIVITY)).isEmpty();
        Optional<CapabilityBinding> resolved = cache.get(ACTIVITY);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().providerId()).isEqualTo("provider-late");
        // And it is sticky from here on.
        assertThat(cache.get(ACTIVITY).get().providerId()).isEqualTo("provider-late");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void seedPopulatesWithoutConsultingTheResolver() {
        CapabilityBindingCache cache = new CapabilityBindingCache(activityId -> {
            throw new AssertionError("resolver must not be consulted for an already-seeded activity");
        });

        cache.seed(List.of(binding("provider-seeded")));

        assertThat(cache.get(ACTIVITY)).isPresent();
        assertThat(cache.get(ACTIVITY).get().providerId()).isEqualTo("provider-seeded");
    }

    @Test
    void aNullResolverBehavesAsNoBindingEverAvailable() {
        CapabilityBindingCache cache = new CapabilityBindingCache(null);

        assertThat(cache.get(ACTIVITY)).isEmpty();
    }

    @Test
    void aResolverThatThrowsIsTreatedAsAMissRatherThanPropagating() {
        CapabilityBindingCache cache = new CapabilityBindingCache(activityId -> {
            throw new RuntimeException("Workbench unreachable");
        });

        assertThat(cache.get(ACTIVITY)).isEmpty();
    }
}
