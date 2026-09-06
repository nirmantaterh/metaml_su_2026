package com.metaml.wbapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.camunda.bpm.impl.juel.jakarta.el.ELContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

// Tests the NoOpFallbackElResolver's behavior directly — no Camunda engine needed.
// Verifies the contract: bean-like names → NoOpDelegate, everything else → unresolved.
class LenientElResolverPluginTest {

    private final LenientElResolverPlugin.NoOpFallbackElResolver resolver =
            new LenientElResolverPlugin.NoOpFallbackElResolver();

    // A minimal ELContext that tracks whether a property was resolved.
    private static ELContext freshContext() {
        return new ELContext() {
            @Override public org.camunda.bpm.impl.juel.jakarta.el.ELResolver getELResolver() { return null; }
            @Override public org.camunda.bpm.impl.juel.jakarta.el.FunctionMapper getFunctionMapper() { return null; }
            @Override public org.camunda.bpm.impl.juel.jakarta.el.VariableMapper getVariableMapper() { return null; }
        };
    }

    // 1. Bean-like names resolve to NoOpDelegate
    @Test
    void beanLikeNameResolvesToNoOpDelegate() {
        for (String beanName : new String[]{
                "manufTaskCompletionListener", "agentExecutionDelegate",
                "orderHandler", "paymentService", "validationFactory"}) {
            ELContext ctx = freshContext();
            Object result = resolver.getValue(ctx, null, beanName);
            assertThat(ctx.isPropertyResolved())
                    .as("bean name '%s' should be resolved", beanName)
                    .isTrue();
            assertThat(result).isSameAs(LenientElResolverPlugin.NoOpDelegate.INSTANCE);
        }
    }

    // 2. Process variable names are NOT resolved — left for Camunda's own resolver
    @Test
    void processVariableNameIsNotResolved() {
        for (String varName : new String[]{
                "orderApproved", "qualityPassed", "amount", "status", "retryCount"}) {
            ELContext ctx = freshContext();
            Object result = resolver.getValue(ctx, null, varName);
            assertThat(ctx.isPropertyResolved())
                    .as("variable name '%s' should NOT be resolved", varName)
                    .isFalse();
            assertThat(result).isNull();
        }
    }

    // 3. Non-top-level resolution (base != null) is always ignored
    @Test
    void nonTopLevelPropertyIsIgnored() {
        ELContext ctx = freshContext();
        Object result = resolver.getValue(ctx, new Object(), "someListener");
        assertThat(ctx.isPropertyResolved()).isFalse();
        assertThat(result).isNull();
    }

    // 4. Already-resolved properties are not touched
    @Test
    void alreadyResolvedPropertyIsNotTouched() {
        ELContext ctx = freshContext();
        ctx.setPropertyResolved(true);
        Object result = resolver.getValue(ctx, null, "someDelegate");
        // Should still be resolved, and result is null (the resolver saw isPropertyResolved=true
        // and returned null without doing anything)
        assertThat(ctx.isPropertyResolved()).isTrue();
        assertThat(result).isNull();
    }

    // 5. Resolver is read-only
    @Test
    void resolverIsReadOnly() {
        assertThat(resolver.isReadOnly(freshContext(), null, "anything")).isTrue();
    }

    // 6. NoOpDelegate implements all three Camunda delegate interfaces
    @Test
    void noOpDelegateImplementsAllInterfaces() {
        LenientElResolverPlugin.NoOpDelegate delegate = LenientElResolverPlugin.NoOpDelegate.INSTANCE;
        assertThat(delegate).isInstanceOf(org.camunda.bpm.engine.delegate.JavaDelegate.class);
        assertThat(delegate).isInstanceOf(org.camunda.bpm.engine.delegate.TaskListener.class);
        assertThat(delegate).isInstanceOf(org.camunda.bpm.engine.delegate.ExecutionListener.class);
    }
}
