package com.metaml.wbapi.config;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.impl.juel.jakarta.el.CompositeELResolver;
import org.camunda.bpm.impl.juel.jakarta.el.ELContext;
import org.camunda.bpm.impl.juel.jakarta.el.ELResolver;
import org.camunda.bpm.engine.spring.SpringExpressionManager;
import org.camunda.bpm.spring.boot.starter.configuration.impl.AbstractCamundaConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.beans.FeatureDescriptor;
import java.util.Iterator;
import java.util.Map;

/**
 * Generic Camunda engine plugin that prevents process advancement from failing
 * when deployed BPMN definitions reference Spring beans (task listeners,
 * execution listeners, Java delegates) that exist only in the generated target
 * platform's Spring context and not in the Workbench's own context.
 *
 * <p>When the Workbench advances an original process instance (via completeOpenTasks),
 * Camunda evaluates expressions like {@code ${manufTaskCompletionListener}}. Without
 * this resolver those evaluations throw because the bean isn't registered here.
 * This plugin installs a fallback EL resolver at the END of the resolution chain
 * that supplies a no-op listener/delegate for any unresolved top-level bean name,
 * letting the process token advance without side effects.</p>
 *
 * <p>Fully generic: no process-specific logic. Any BPMN with missing beans benefits.
 * Normal Workbench beans and process variables are resolved first (by Spring and
 * Camunda's own resolvers); this fallback fires only when nothing else handles it.</p>
 *
 * <p>Process variables (gateway conditions like {@code ${orderApproved}}) are NOT
 * handled here. Those are set as explicit Camunda process variables at the external-task
 * completion boundary by {@code WorkbenchServiceImpl.completeOpenTasks}, which analyzes
 * the BPMN structure to determine which variables each external task feeds into downstream
 * gateways. A genuinely missing process variable will cause Camunda's own
 * {@code PropertyNotFoundException} — a clear failure, not a silently invented value.</p>
 */
@Component
class LenientElResolverPlugin extends AbstractCamundaConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(LenientElResolverPlugin.class);

    private final ApplicationContext applicationContext;

    LenientElResolverPlugin(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl config) {
        Map<Object, Object> beans = config.getBeans();
        config.setExpressionManager(
                new LenientSpringExpressionManager(applicationContext, beans));
        logger.info("Installed lenient expression resolver — unresolved BPMN bean " +
                "references will resolve to no-op listeners/delegates during process advancement");
    }

    // ---- inner classes ----

    /**
     * Expression manager that appends a no-op fallback resolver after the standard
     * Spring/Camunda resolver chain.
     */
    static class LenientSpringExpressionManager extends SpringExpressionManager {

        LenientSpringExpressionManager(ApplicationContext ctx, Map<Object, Object> beans) {
            super(ctx, beans);
        }

        @Override
        protected ELResolver createElResolver() {
            CompositeELResolver composite = (CompositeELResolver) super.createElResolver();
            composite.add(new NoOpFallbackElResolver());
            return composite;
        }
    }

    /**
     * Fallback EL resolver that catches any top-level bean reference that nothing else
     * in the chain could handle and returns a no-op implementation. Only fires for
     * top-level resolution (base == null) which is how Camunda resolves
     * {@code ${beanName}} expressions.
     */
    static class NoOpFallbackElResolver extends ELResolver {

        private static final Logger log = LoggerFactory.getLogger(NoOpFallbackElResolver.class);

        @Override
        public Object getValue(ELContext context, Object base, Object property) {
            if (base == null && property instanceof String && !context.isPropertyResolved()) {
                String name = (String) property;
                if (looksLikeBeanName(name)) {
                    log.debug("Unresolved BPMN bean '{}' — returning no-op delegate", name);
                    context.setPropertyResolved(true);
                    return NoOpDelegate.INSTANCE;
                }
                // Not a bean name — leave unresolved. Gateway variables (orderApproved,
                // qualityPassed, etc.) are now set as real Camunda process variables at the
                // external-task completion boundary by WorkbenchServiceImpl.completeOpenTasks
                // and advanceTwinActivity, which analyze the BPMN to determine which variables
                // each activity feeds into downstream gateways. A genuinely missing variable
                // will cause PropertyNotFoundException — a clear failure, not a silently
                // invented value.
            }
            return null;
        }

        /** Heuristic: bean names for listeners/delegates/handlers follow camelCase Java conventions. */
        private static boolean looksLikeBeanName(String name) {
            String lower = name.toLowerCase();
            return lower.contains("listener") || lower.contains("delegate")
                    || lower.contains("handler") || lower.contains("service")
                    || lower.contains("factory") || lower.contains("bean")
                    || lower.contains("interceptor") || lower.contains("provider")
                    || lower.contains("resolver") || lower.contains("adapter")
                    || lower.contains("registry") || lower.contains("processor")
                    || lower.contains("executor") || lower.contains("worker");
        }

        @Override public Class<?> getType(ELContext ctx, Object base, Object prop)          { return null; }
        @Override public void setValue(ELContext ctx, Object base, Object prop, Object val)  { /* no-op */ }
        @Override public boolean isReadOnly(ELContext ctx, Object base, Object prop)         { return true; }
        @Override public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext ctx, Object base) { return null; }
        @Override public Class<?> getCommonPropertyType(ELContext ctx, Object base)          { return Object.class; }
    }

    /**
     * Singleton that implements every Camunda delegate/listener interface as a no-op.
     * When the Workbench advances a process and hits an expression that references a
     * target-platform bean, Camunda will invoke this instead — safely doing nothing.
     */
    static class NoOpDelegate implements JavaDelegate, TaskListener, ExecutionListener {
        static final NoOpDelegate INSTANCE = new NoOpDelegate();
        private static final Logger log = LoggerFactory.getLogger(NoOpDelegate.class);

        @Override
        public void execute(DelegateExecution execution) {
            log.debug("No-op JavaDelegate executed for activity {}", execution.getCurrentActivityId());
        }

        @Override
        public void notify(DelegateExecution execution) {
            log.debug("No-op ExecutionListener notified for activity {}", execution.getCurrentActivityId());
        }

        @Override
        public void notify(DelegateTask delegateTask) {
            log.debug("No-op TaskListener notified for task {}", delegateTask.getTaskDefinitionKey());
        }
    }
}
