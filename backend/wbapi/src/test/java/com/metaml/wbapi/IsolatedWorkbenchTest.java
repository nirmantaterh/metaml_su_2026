package com.metaml.wbapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * Isolates full-context tests from development data directories by routing stores to test paths.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(properties = {
        // every persistent store, disabled - keep this list exhaustive
        "workbench.state.persist=false",
        "workbench.workflow.persist=false",
        "workbench.governance.approval.persist=false",
        "workbench.governance.tenant-policy.persist=false",
        // the two stores that write real files rather than toggling a flag
        "workbench.models.directory=./target/test-data/models",
        "workbench.generation.output-directory=./target/test-data/generated-projects",
        // read-only input, not isolation: generation needs the real template to copy from
        "workbench.generation.template-directory=../../templates/camundademo"
})
public @interface IsolatedWorkbenchTest {
}
