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

/** Registers a fallback EL resolver for unresolved Spring bean expressions during process advancement. */
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


    /** Appends a no-op fallback resolver after standard Spring/Camunda resolver chain. */
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

    /** Fallback EL resolver for top-level bean references unresolved by standard managers. */
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
                // Leave non-bean properties unresolved so missing process variables fail normally.
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
     * No-op delegate implementing JavaDelegate, TaskListener, and ExecutionListener.
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
