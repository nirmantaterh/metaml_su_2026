package com.example.camundademo.capability;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.metaml.workbench.capability.runtime.CapabilityBinding;
import com.metaml.workbench.capability.runtime.CapabilityBindingCache;

// P7 Step 5: the ONE Workbench request an ordinary boot performs, so provider execution afterward
// never needs one. Runs once, after the Spring context (including the embedded Camunda engine and
// this application's own deployed BPMN) has finished starting; a failure here is logged and never
// prevents the application from starting - a Target Platform with the Workbench unreachable at boot
// still starts and runs, it simply starts with an empty capability binding cache, and any activity
// that genuinely requires one it cannot resolve fails explicitly at that activity (see
// CapabilityBindingRequiredException) rather than here.
//
// A no-op, cleanly, when metaml.capability.workbench-url is unset: RestCapabilityBindingClient is
// then never registered at all (see its own @ConditionalOnProperty), and this class does nothing.
@Component
public class CapabilityBindingBootstrap implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityBindingBootstrap.class);

    private final ObjectProvider<RestCapabilityBindingClient> clientProvider;
    private final CapabilityBindingCache cache;

    public CapabilityBindingBootstrap(ObjectProvider<RestCapabilityBindingClient> clientProvider,
            CapabilityBindingCache cache) {
        this.clientProvider = clientProvider;
        this.cache = cache;
    }

    @Override
    public void run(ApplicationArguments args) {
        RestCapabilityBindingClient client = clientProvider.getIfAvailable();
        if (client == null) {
            logger.info("CAPABILITY BINDING BOOTSTRAP: metaml.capability.workbench-url not configured - "
                    + "starting with an empty capability binding cache, every capability-bound activity "
                    + "will rely on its own process instance's evolvedAgent_* variables only");
            return;
        }
        int total = 0;
        for (Map.Entry<String, List<String>> entry : client.activityIdsByProcessKey().entrySet()) {
            List<CapabilityBinding> bindings = client.resolveAll(entry.getKey(), entry.getValue());
            cache.seed(bindings);
            total += bindings.size();
            logger.info("CAPABILITY BINDING BOOTSTRAP: resolved {} of {} activity binding(s) for process '{}'",
                    bindings.size(), entry.getValue().size(), entry.getKey());
        }
        logger.info("CAPABILITY BINDING BOOTSTRAP: cache warmed with {} Workbench-authoritative binding(s)",
                total);
    }
}
