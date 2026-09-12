package com.example.camundademo.capability;

import java.util.List;

import org.camunda.bpm.engine.RepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.metaml.workbench.automation.ComponentExecutor;
import com.metaml.workbench.capability.runtime.CapabilityBindingCache;
import com.metaml.workbench.capability.runtime.CapabilityBindingResolver;
import com.metaml.workbench.capability.runtime.CapabilityDispatcher;
import com.metaml.workbench.capability.runtime.CapabilityOutputContractSource;

// Makes capability execution available inside this standalone Target Platform (P7 Step 4/5).
//
// This is wiring only. The mechanism itself lives in capability-core, which carries no Spring, no
// persistence and no dependency on the Workbench - so putting it on this application's classpath does
// not make this application need the Workbench to run. Governance, approval, the capability-gap
// lifecycle and the authority to bind a provider all stay with the Workbench; what happens here is
// only the execution of a binding that already exists - either this process instance's own
// evolvedAgent_*/evolvedAgentType_* variables, or (P7 Step 5) a cached Workbench-authoritative
// CapabilityBinding fetched once at boot and again only on a cache miss (see
// CapabilityBindingBootstrap and RestCapabilityBindingClient) - which is what lets ordinary work
// continue with the Workbench switched off after that.
@Configuration
// Provider implementations live outside this application's own package, so they are invisible to the
// application class's default scan. The default points at the reference-providers module; a
// deployment that ships providers in a different package sets
// metaml.capability.provider-packages (comma-separated) instead of changing any code. Spring
// resolves placeholders in basePackages, so this stays configuration rather than a code change.
@ComponentScan(basePackages = "${metaml.capability.provider-packages:com.metaml.workbench.automation}")
public class CapabilityConfig {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityConfig.class);

    // resolver is an ObjectProvider because a Target Platform may legitimately have no Workbench
    // binding endpoint configured at all (metaml.capability.workbench-url unset) - RestCapabilityBindingClient
    // itself is only registered as a bean when that property is present (see its own @ConditionalOnProperty).
    // A cache with no resolver still works: it only ever serves what CapabilityBindingBootstrap seeded
    // at boot, and returns empty for everything else - the same "nothing bound" CapabilityDispatcher
    // already knows how to handle.
    @Bean
    public CapabilityBindingCache capabilityBindingCache(ObjectProvider<CapabilityBindingResolver> resolver) {
        return new CapabilityBindingCache(resolver.getIfAvailable());
    }

    // Every ComponentExecutor on the classpath is offered to the dispatcher, which selects one by
    // the provider identity bound to the activity - never by the activity's or the process's name.
    // An empty list is legal: it means this deployment ships no providers, and any activity that
    // needs one will fail closed rather than proceed on invented state.
    //
    // The contract source is an ObjectProvider because a Target Platform has no catalog of its own.
    // Until an approved binding supplies the provider's declared contract, there is nothing to
    // enforce, which CapabilityOutputPropagator already treats as "enforce nothing" rather than
    // "allow anything" - an undeclared output still cannot become a gateway variable unless the
    // deployed model actually reads it.
    @Bean
    public CapabilityDispatcher capabilityDispatcher(List<ComponentExecutor> executors,
            ObjectProvider<CapabilityOutputContractSource> contractSource,
            RepositoryService repositoryService, CapabilityBindingCache capabilityBindingCache,
            ProviderTechnicalModeRegistry providerTechnicalModes) {
        CapabilityOutputContractSource resolvedContractSource = contractSource.getIfAvailable();
        List<ComponentExecutor> failureModeExecutors = executors.stream()
                .<ComponentExecutor>map(executor -> new FailureModeComponentExecutor(executor, providerTechnicalModes))
                .toList();
        // Logged into this application's OWN log, because "the providers were on the classpath" and
        // "the dispatcher can actually resolve one" are different claims, and only the second is
        // worth anything at runtime. Identities come from the providers themselves, never from a
        // process or activity name.
        logger.info("CAPABILITY RUNTIME READY: dispatcher wired with {} provider executor(s) {} "
                        + "(declared-contract source: {})", executors.size(),
                executors.stream().map(ComponentExecutor::getHandledAgentType).sorted().toList(),
                resolvedContractSource == null ? "none - no contract enforced until a binding supplies one"
                        : resolvedContractSource.getClass().getSimpleName());
        return new CapabilityDispatcher(failureModeExecutors, resolvedContractSource, repositoryService,
                capabilityBindingCache);
    }
}
