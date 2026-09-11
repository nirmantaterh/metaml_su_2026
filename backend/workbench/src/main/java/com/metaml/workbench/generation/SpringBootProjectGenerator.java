package com.metaml.workbench.generation;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.EventDefinition;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.Signal;
import org.camunda.bpm.model.bpmn.instance.SignalEventDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.metaml.workbench.bpmn.OperationalTwinGenerator;
import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.codegen.GeneratedWorker;
import com.metaml.workbench.codegen.TargetPlatformMessagingGenerator;
import com.metaml.workbench.codegen.TargetPlatformSourceGenerator;
import com.metaml.workbench.codegen.TargetPlatformTwinMirrorGenerator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Generates the Target Harness Platform from the camundademo template.
@Component
public class SpringBootProjectGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SpringBootProjectGenerator.class);

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

    public static final String DELEGATE_PACKAGE = "com.example.camundademo.delegate";

    private static final String TEMPLATE_BASE_PACKAGE = "com.example.camundademo";
    private static final String TEMPLATE_BASE_PACKAGE_PATH = "com/example/camundademo";

    private static final String TARGET_PLATFORM_BASE_PACKAGE = "com.metaml.targetplatform";

    // Reuse the fixed template package for shared controller generation.
    private static final String TARGET_PLATFORM_BASE_PACKAGE_LITERAL = "com.tp.TargetPlatform";

    private static final String DELEGATE_PACKAGE_PATH = "src/main/java/com/example/camundademo/delegate";
    private static final String CONTROLLER_PACKAGE_PATH = "src/main/java/com/example/camundademo/controller";
    private static final String PROCESSES_PATH = "src/main/resources/processes";

    // Canonical process-key source; avoids inferring identity from BPMN filenames.
    private static final String PROJECT_METADATA_FILE = ".metaml-project.properties";

    private final Path templateDirectory;
    private final Path outputDirectory;
    private final TwinModelGenerator twinModelGenerator;
    private final DelegateClassGenerator delegateClassGenerator;
    private final ExternalTaskWorkerGenerator externalTaskWorkerGenerator;
    private final TargetPlatformSourceGenerator targetPlatformSourceGenerator;
    private final TargetPlatformMessagingGenerator targetPlatformMessagingGenerator;
    private final TargetPlatformTwinMirrorGenerator targetPlatformTwinMirrorGenerator;

    @Autowired
    public SpringBootProjectGenerator(
            @Value("${workbench.generation.template-directory:./templates/camundademo}") String templateDirectory,
            @Value("${workbench.generation.output-directory:./data/generated-projects}") String outputDirectory,
            TwinModelGenerator twinModelGenerator, DelegateClassGenerator delegateClassGenerator,
            ExternalTaskWorkerGenerator externalTaskWorkerGenerator,
            TargetPlatformSourceGenerator targetPlatformSourceGenerator,
            TargetPlatformMessagingGenerator targetPlatformMessagingGenerator,
            TargetPlatformTwinMirrorGenerator targetPlatformTwinMirrorGenerator) {
        this.templateDirectory = Path.of(templateDirectory);
        this.outputDirectory = Path.of(outputDirectory);
        this.twinModelGenerator = twinModelGenerator;
        this.delegateClassGenerator = delegateClassGenerator;
        this.externalTaskWorkerGenerator = externalTaskWorkerGenerator;
        this.targetPlatformSourceGenerator = targetPlatformSourceGenerator;
        this.targetPlatformMessagingGenerator = targetPlatformMessagingGenerator;
        this.targetPlatformTwinMirrorGenerator = targetPlatformTwinMirrorGenerator;
    }

    // Preserves the small direct-construction seam used by the existing generator tests.
    public SpringBootProjectGenerator(String templateDirectory, String outputDirectory,
            TwinModelGenerator twinModelGenerator, DelegateClassGenerator delegateClassGenerator,
            ExternalTaskWorkerGenerator externalTaskWorkerGenerator) {
        this(templateDirectory, outputDirectory, twinModelGenerator, delegateClassGenerator,
                externalTaskWorkerGenerator, new TargetPlatformSourceGenerator(),
                new TargetPlatformMessagingGenerator(), new TargetPlatformTwinMirrorGenerator());
    }

    public GeneratedProject generate(String bpmnXml, List<GeneratedDelegate> delegates) {
        return generate(bpmnXml, delegates, null);
    }

    // Generates a Target Platform project for the given BPMN XML model.
    public GeneratedProject generate(String bpmnXml, List<GeneratedDelegate> delegates, String displayName) {
        if (!Files.isDirectory(templateDirectory)) {
            throw new IllegalStateException("No template project at " + templateDirectory.toAbsolutePath()
                    + " - workbench.generation.template-directory must point at the camundademo template");
        }
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
        if (isTargetPlatformTemplate()) {
            // TargetPlatform/RedCollar template uses structural twin mirroring.
            return generateTargetPlatform(bpmnXml, targetPlatformTwinMirrorGenerator.mirror(bpmnXml), displayName);
        }
        String processKey = extractProcessKey(model);

        // A signal-gated Main gets a derived operational Twin via the same pipeline an attached Twin uses; a plain Main falls through unchanged to the governance/Evolve path below.
        String derivedTwinXml = OperationalTwinGenerator.deriveTwinXml(model, processKey);
        if (derivedTwinXml != null) {
            return generateWithAuthoredTwin(bpmnXml, derivedTwinXml, displayName);
        }

        List<BpmnActivities.Activity> activities = BpmnActivities.eligible(model);
        String projectId = UUID.randomUUID().toString();
        Path projectDir = resolveProjectDirectory(projectId, labelFor(displayName, processKey));
        String basePackage = TARGET_PLATFORM_BASE_PACKAGE + "." + packageSlugFor(processKey);

        copyTemplate(projectDir);
        removeTemplatePlaceholders(projectDir);
        rewritePackage(projectDir, basePackage);
        writeProcessFile(projectDir, processKey, bpmnXml);
        writePairRegistry(projectDir, basePackage);
        writeManufacturingDelegates(projectDir, basePackage, delegates);
        writeJavaClassDelegates(projectDir, delegateClassGenerator.generateFromJavaClass(bpmnXml));
        writeController(projectDir, basePackage, "controller.manufacturing", "GeneratedManufacturingController",
                "/api/v1/manufacturing", processKey, activities, "notifyTwin");
        generateTwinResources(projectDir, basePackage, model, processKey);
        writeProcessStatusController(projectDir, basePackage);
        writeProjectMetadata(projectDir, projectId, processKey, displayName);
        String projectSlug = projectDir.getFileName().toString();
        String appClassName = toJavaClassName(projectSlug);
        rewritePomArtifactId(projectDir, projectSlug);
        rewriteApplicationClassName(projectDir, basePackage, appClassName);

        logger.info(
                "Generated Target Harness Platform {} for process key '{}' with {} manufacturing activity "
                        + "endpoint(s), package {}, at {}",
                projectId, processKey, activities.size(), basePackage, projectDir.toAbsolutePath());
        String effectiveDisplayName = (displayName != null && !displayName.isBlank())
                ? displayName
                : (processKey != null && !processKey.isBlank() ? processKey : "Target Platform");
        return new GeneratedProject(projectId, projectDir, processKey, effectiveDisplayName);
    }

    // Like generate(), but uses an independently authored Twin BPMN instead of deriving one.
    public GeneratedProject generateWithAuthoredTwin(String manufBpmnXml, String twinBpmnXml) {
        return generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml, null);
    }

    public GeneratedProject generateWithAuthoredTwin(String manufBpmnXml, String twinBpmnXml, String displayName) {
        if (!Files.isDirectory(templateDirectory)) {
            throw new IllegalStateException("No template project at " + templateDirectory.toAbsolutePath()
                    + " - workbench.generation.template-directory must point at the camundademo template");
        }
        if (isTargetPlatformTemplate()) {
            return generateTargetPlatform(manufBpmnXml, twinBpmnXml, displayName);
        }

        BpmnModelInstance manufModel = Bpmn.readModelFromStream(
                new ByteArrayInputStream(manufBpmnXml.getBytes(StandardCharsets.UTF_8)));
        BpmnModelInstance twinModel = Bpmn.readModelFromStream(
                new ByteArrayInputStream(twinBpmnXml.getBytes(StandardCharsets.UTF_8)));

        String manufProcessKey = extractProcessKey(manufModel);
        String twinProcessKey = extractProcessKey(twinModel);
        String projectId = UUID.randomUUID().toString();
        Path projectDir = resolveProjectDirectory(projectId, labelFor(displayName, manufProcessKey));
        String basePackage = TARGET_PLATFORM_BASE_PACKAGE + "." + packageSlugFor(manufProcessKey);

        copyTemplate(projectDir);
        removeTemplatePlaceholders(projectDir);
        rewritePackage(projectDir, basePackage);
        writePairRegistry(projectDir, basePackage);

        // Write both authored BPMNs as-is (no Twin derivation)
        writeProcessFile(projectDir, manufProcessKey, manufBpmnXml);
        writeProcessFile(projectDir, twinProcessKey, twinBpmnXml);

        // Generate and write external-task workers
        List<GeneratedWorker> manufWorkers = externalTaskWorkerGenerator.generate(
                manufBpmnXml, basePackage + ".worker.manufacturing", false);
        writeWorkers(projectDir, manufWorkers);

        List<GeneratedWorker> twinWorkers = externalTaskWorkerGenerator.generate(
                twinBpmnXml, basePackage + ".worker.twin", true);
        writeWorkers(projectDir, twinWorkers);

        writeJavaClassDelegates(projectDir, delegateClassGenerator.generateFromJavaClass(manufBpmnXml));
        writeJavaClassDelegates(projectDir, delegateClassGenerator.generateFromJavaClass(twinBpmnXml));

        // Generate controllers for both processes
        List<BpmnActivities.Activity> manufActivities = BpmnActivities.eligible(manufModel);
        writeController(projectDir, basePackage, "controller.manufacturing", "GeneratedManufacturingController",
                "/api/v1/manufacturing", manufProcessKey, manufActivities, "notifyTwin");

        List<BpmnActivities.Activity> twinActivities = BpmnActivities.eligible(twinModel);
        writeController(projectDir, basePackage, "controller.twin", "GeneratedTwinController",
                "/api/v1/twin", twinProcessKey, twinActivities, "notifyManufacturing");

        Set<String> manufSignalNames = extractSignalNames(manufModel);
        Set<String> twinSignalNames = extractSignalNames(twinModel);
        Set<String> allSignals = new LinkedHashSet<>(manufSignalNames);
        allSignals.addAll(twinSignalNames);
        List<String> twinTopics = twinWorkers.stream().map(GeneratedWorker::topic).distinct().toList();
        Map<String, String> signalToGatedTwinTopic = mapSignalToGatedTwinTopic(twinModel);
        String messagingNamespace = namespaceRootFor(manufProcessKey, twinProcessKey) + "." + projectId;
        if (!allSignals.isEmpty()) {
            writeRabbitMqMessaging(projectDir, basePackage, messagingNamespace, twinTopics, signalToGatedTwinTopic);
            writeSignalBroadcaster(projectDir, basePackage, allSignals);
        }

        // Stubs for delegateExpression beans the BPMNs reference.
        Set<String> listenerBeanNames = new LinkedHashSet<>();
        listenerBeanNames.addAll(extractExecutionListenerBeanNames(manufBpmnXml));
        listenerBeanNames.addAll(extractExecutionListenerBeanNames(twinBpmnXml));
        for (String beanName : listenerBeanNames) {
            writeExecutionListenerStub(projectDir, basePackage, beanName);
        }

        // Written unconditionally; a BPMN pair without signals still needs the poller.
        writeWorkerInterface(projectDir, basePackage);
        if (!twinWorkers.isEmpty()) {
            writeTwinDecisionAgentInterface(projectDir, basePackage + ".worker.twin");
        }
        writeExternalTaskPoller(projectDir, basePackage);
        writeSchedulingConfig(projectDir, basePackage);
        writeProcessStatusController(projectDir, basePackage);
        writeProjectMetadata(projectDir, projectId, manufProcessKey, displayName);
        String projectSlug = projectDir.getFileName().toString();
        String appClassName = toJavaClassName(projectSlug);
        rewritePomArtifactId(projectDir, projectSlug);
        rewriteApplicationClassName(projectDir, basePackage, appClassName);

        logger.info("Generated Target Harness Platform {} with authored Twin for process keys '{}' + '{}', "
                + "{} manufacturing workers, {} twin workers, {} total signals, {} Main<->Twin communication "
                + "activities ({} task queue(s) + {} response queue(s) = {} total), package {}, at {}",
                projectId, manufProcessKey, twinProcessKey, manufWorkers.size(), twinWorkers.size(),
                allSignals.size(), twinTopics.size(), twinTopics.size(), twinTopics.size(), twinTopics.size() * 2,
                basePackage, projectDir.toAbsolutePath());

        String effectiveDisplayName = (displayName != null && !displayName.isBlank())
                ? displayName
                : (manufProcessKey != null && !manufProcessKey.isBlank() ? manufProcessKey : "Target Platform");
        return new GeneratedProject(projectId, projectDir, manufProcessKey, effectiveDisplayName);
    }

    // The RedCollar-derived template deliberately keeps its package fixed at com.tp.TargetPlatform. It owns its proxy/twin controllers and configuration already; generation only supplies the two BPMNs and the source beans those BPMNs name.
    private boolean isTargetPlatformTemplate() {
        return Files.isDirectory(templateDirectory.resolve("src/main/java/com/tp/TargetPlatform"));
    }

    private GeneratedProject generateTargetPlatform(String proxyBpmnXml, String twinBpmnXml, String displayName) {
        BpmnModelInstance proxyModel = Bpmn.readModelFromStream(
                new ByteArrayInputStream(proxyBpmnXml.getBytes(StandardCharsets.UTF_8)));
        BpmnModelInstance twinModel = Bpmn.readModelFromStream(
                new ByteArrayInputStream(twinBpmnXml.getBytes(StandardCharsets.UTF_8)));
        String proxyKey = extractProcessKey(proxyModel);
        String twinKey = extractProcessKey(twinModel);
        String projectId = UUID.randomUUID().toString();
        Path projectDir = resolveProjectDirectory(projectId, labelFor(displayName, proxyKey));
        copyTemplate(projectDir);
        clearTargetPlatformGeneratedSources(projectDir);

        TargetPlatformSourceGenerator.Result proxy = targetPlatformSourceGenerator.generate(proxyBpmnXml, false);
        TargetPlatformSourceGenerator.Result twin = targetPlatformSourceGenerator.generate(twinBpmnXml, true,
                proxy.syncActivityIds());
        writeProcessFile(projectDir, proxyKey, proxy.bpmnXml());
        writeProcessFile(projectDir, twinKey, twin.bpmnXml());
        writeTargetPlatformSources(projectDir, proxy.sources());
        writeTargetPlatformSources(projectDir, twin.sources());

        // A serviceTask with camunda:type="external" is a wait state TargetPlatformSourceGenerator never
        // touches - it only recognises delegateExpression/class - so without a worker on its topic the token
        // parks there forever and nothing downstream of it ever runs.
        // The worker package must be "<interface package>.<side>": ExternalTaskWorkerGenerator derives its
        // GeneratedExternalTaskWorker import by stripping the last segment off packageName.
        List<GeneratedWorker> proxyWorkers = externalTaskWorkerGenerator.generate(proxyBpmnXml,
                TARGET_PLATFORM_BASE_PACKAGE_LITERAL + ".worker.proxy", false);
        List<GeneratedWorker> twinWorkers = externalTaskWorkerGenerator.generate(twinBpmnXml,
                TARGET_PLATFORM_BASE_PACKAGE_LITERAL + ".worker.twin", true);
        clearTargetPlatformGeneratedWorkers(projectDir);
        writeWorkers(projectDir, proxyWorkers);
        writeWorkers(projectDir, twinWorkers);
        writeWorkerInterface(projectDir, TARGET_PLATFORM_BASE_PACKAGE_LITERAL);
        if (!twinWorkers.isEmpty()) {
            writeTwinDecisionAgentInterface(projectDir, TARGET_PLATFORM_BASE_PACKAGE_LITERAL + ".worker.twin");
        }
        writeExternalTaskPoller(projectDir, TARGET_PLATFORM_BASE_PACKAGE_LITERAL);

        // proxy<->twin synchronization: signals come from two sources - 1. Original BPMN signal declarations (the old external-task pipeline's signal gates) 2. Lockstep sync signals injected by TargetPlatformSourceGenerator for delegate- expression BPMNs (sync_<activityId> on both proxy and twin sides) Both sets are combined so SignalBroadcaster polls every signal that either side waits on.
        Set<String> proxySignals = extractSignalNames(proxyModel);
        proxySignals.addAll(proxy.syncSignalNames());
        Set<String> twinSignals = extractSignalNames(twinModel);
        twinSignals.addAll(twin.syncSignalNames());
        Set<String> sharedSignals = new LinkedHashSet<>(proxySignals);
        sharedSignals.retainAll(twinSignals);
        Set<String> allSignals = new LinkedHashSet<>(proxySignals);
        allSignals.addAll(twinSignals);
        String messagingNamespace = packageSlugFor(proxyKey) + "." + projectId;
        List<TargetPlatformMessagingGenerator.GeneratedSource> messagingSources = targetPlatformMessagingGenerator
                .generate(messagingNamespace, sharedSignals, allSignals, proxyKey, twinKey);
        writeTargetPlatformMessagingSources(projectDir, messagingSources);
        writeSchedulingConfig(projectDir, TARGET_PLATFORM_BASE_PACKAGE_LITERAL);
        writeProcessStatusController(projectDir, TARGET_PLATFORM_BASE_PACKAGE_LITERAL);

        writeProjectMetadata(projectDir, projectId, proxyKey, displayName);
        String projectSlug = projectDir.getFileName().toString();
        String appClassName = toJavaClassName(projectSlug);
        rewritePomArtifactId(projectDir, projectSlug);
        rewriteApplicationClassName(projectDir, TARGET_PLATFORM_BASE_PACKAGE_LITERAL, appClassName);
        logger.info("Generated TargetPlatform {} at {} with {} proxy and {} twin source bean(s), {} shared "
                        + "sync signal(s) of {} total", projectId, projectDir.toAbsolutePath(), proxy.sources().size(),
                twin.sources().size(), sharedSignals.size(), allSignals.size());
        String effectiveDisplayName = (displayName != null && !displayName.isBlank())
                ? displayName
                : (proxyKey != null && !proxyKey.isBlank() ? proxyKey : "Target Platform");
        return new GeneratedProject(projectId, projectDir, proxyKey, effectiveDisplayName);
    }

    private void clearTargetPlatformGeneratedSources(Path projectDir) {
        List<Path> roots = List.of(
                projectDir.resolve("src/main/java/com/tp/TargetPlatform/proxy/delegates"),
                projectDir.resolve("src/main/java/com/tp/TargetPlatform/proxy/events"),
                projectDir.resolve("src/main/java/com/tp/TargetPlatform/proxy/listeners"),
                projectDir.resolve("src/main/java/com/tp/TargetPlatform/twin/delegates"),
                projectDir.resolve("src/main/java/com/tp/TargetPlatform/twin/events"),
                projectDir.resolve("src/main/java/com/tp/TargetPlatform/twin/listeners"));
        for (Path root : roots) {
            try {
                if (Files.exists(root)) {
                    try (Stream<Path> files = Files.walk(root)) {
                        files.filter(path -> path.toString().endsWith(".java"))
                                .forEach(SpringBootProjectGenerator::deleteIfExists);
                    }
                }
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not prepare TargetPlatform generated-source directory " + root, e);
            }
        }
    }

    private void clearTargetPlatformGeneratedWorkers(Path projectDir) {
        List<Path> roots = List.of(
                projectDir.resolve("src/main/java/com/tp/TargetPlatform/worker/proxy"),
                projectDir.resolve("src/main/java/com/tp/TargetPlatform/worker/twin"));
        for (Path root : roots) {
            try {
                if (Files.exists(root)) {
                    try (Stream<Path> files = Files.walk(root)) {
                        files.filter(path -> path.toString().endsWith(".java"))
                                .forEach(SpringBootProjectGenerator::deleteIfExists);
                    }
                }
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not prepare TargetPlatform generated-worker directory " + root, e);
            }
        }
    }

    private void writeTargetPlatformSources(Path projectDir, List<TargetPlatformSourceGenerator.GeneratedSource> sources) {
        for (TargetPlatformSourceGenerator.GeneratedSource source : sources) {
            Path output = projectDir.resolve("src/main/java/com/tp/TargetPlatform")
                    .resolve(source.relativeDirectory()).resolve(source.className() + ".java");
            // Do not overwrite a source file if a future template intentionally supplies one.
            if (!Files.exists(output)) {
                writeFile(output, source.source());
            }
        }
    }

    // Unlike writeTargetPlatformSources above, these always overwrite: the synchronization layer and the proxy/twin controllers are structural to this generation mode, not a per-activity stub a future template might intentionally hand-supply - and the controllers specifically bake in this generation's own process keys, so a stale copy from a previous generate() would silently start the WRONG process definition.
    private void writeTargetPlatformMessagingSources(Path projectDir,
            List<TargetPlatformMessagingGenerator.GeneratedSource> sources) {
        for (TargetPlatformMessagingGenerator.GeneratedSource source : sources) {
            Path output = projectDir.resolve("src/main/java/com/tp/TargetPlatform")
                    .resolve(source.relativeDirectory()).resolve(source.className() + ".java");
            writeFile(output, source.source());
        }
    }

    private void generateTwinResources(Path projectDir, String basePackage, BpmnModelInstance originalModel,
            String processKey) {
        BpmnModelInstance twinModel = twinModelGenerator.generate(originalModel);
        String twinBpmnXml = Bpmn.convertToString(twinModel);
        String twinProcessKey = processKey + "_twin";
        writeFile(projectDir.resolve(PROCESSES_PATH).resolve(twinProcessKey + ".bpmn"), twinBpmnXml);

        List<GeneratedDelegate> twinDelegates =
                delegateClassGenerator.generate(twinBpmnXml, basePackage + ".delegate.twin");
        writeTwinDelegates(projectDir, basePackage, twinDelegates);
        writeJavaClassDelegates(projectDir, delegateClassGenerator.generateFromJavaClass(twinBpmnXml));

        List<BpmnActivities.Activity> twinActivities = BpmnActivities.eligible(twinModel);
        writeController(projectDir, basePackage, "controller.twin", "GeneratedTwinController", "/api/v1/twin",
                twinProcessKey, twinActivities, "notifyManufacturing");
    }

    // Digit-leading or empty keys fall back to "generated<slug>".
    private static String packageSlugFor(String processKey) {
        String slug = processKey == null ? "" : processKey.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (slug.isEmpty() || Character.isDigit(slug.charAt(0))) {
            slug = "generated" + slug;
        }
        return slug;
    }

    // Returns the segment before the first dot, or null.
    private static String dotPrefix(String processKey) {
        if (processKey == null) {
            return null;
        }
        int dot = processKey.indexOf('.');
        return dot > 0 ? processKey.substring(0, dot) : null;
    }

    // Shared dot-prefix of both keys, or packageSlugFor(manufProcessKey) if none.
    private static String namespaceRootFor(String manufProcessKey, String twinProcessKey) {
        String manufPrefix = dotPrefix(manufProcessKey);
        String twinPrefix = dotPrefix(twinProcessKey);
        if (manufPrefix != null && manufPrefix.equalsIgnoreCase(twinPrefix)) {
            return packageSlugFor(manufPrefix);
        }
        return packageSlugFor(manufProcessKey);
    }

    // Rewrites the template's com/example/camundademo tree to the project-specific package; non-Java resources unchanged.
    private void rewritePackage(Path projectDir, String basePackage) {
        // Both source roots: copied test sources reference template classes; rewriting main only breaks test-compile.
        rewritePackageUnder(projectDir.resolve("src/main/java"), basePackage);
        rewritePackageUnder(projectDir.resolve("src/test/java"), basePackage);
    }

    private void rewritePackageUnder(Path sourceRoot, String basePackage) {
        Path oldRoot = sourceRoot.resolve(TEMPLATE_BASE_PACKAGE_PATH);
        if (!Files.isDirectory(oldRoot)) {
            return;
        }
        Path newRoot = sourceRoot.resolve(basePackage.replace('.', '/'));
        try (Stream<Path> walk = Files.walk(oldRoot)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path source : files) {
                Path relative = oldRoot.relativize(source);
                Path target = newRoot.resolve(relative);
                Files.createDirectories(target.getParent());
                String content = Files.readString(source, StandardCharsets.UTF_8);
                String rewritten = content.replace(TEMPLATE_BASE_PACKAGE, basePackage);
                Files.writeString(target, rewritten, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not rewrite template package under " + oldRoot.toAbsolutePath(),
                    e);
        }
        deleteTree(oldRoot);
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            logger.warn("Could not fully remove pre-rewrite package tree {}: {}", root.toAbsolutePath(),
                    e.toString());
        }
    }

    // NotificationBridge ships with the template alongside the messaging package - where the professor said RabbitMQ belongs: "we'll add the rabbit in Q to the template repository. And then that becomes your updated generated set." copyTemplate() + rewritePackage() handle the rest; nothing is generated here.

    // Human-readable directory name (slug of the display name, falling back to the process key), with a
    // numeric suffix when that name is already taken. Collisions here are cosmetic: projectId stays the
    // real identity everywhere else, and PROJECT_METADATA_FILE records it so the directory can be found
    // again without the folder name having to equal it.
    private Path resolveProjectDirectory(String projectId, String label) {
        Path root = outputDirectory.toAbsolutePath().normalize();
        String base = slugify(label);
        if (base.isEmpty()) {
            base = "target-platform";
        }
        Path candidate = safeChildOf(root, base);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = safeChildOf(root, base + "-" + suffix);
            suffix++;
        }
        return candidate;
    }

    // label is a human display name and therefore user input, so it is never trusted as a raw path segment. Strips anything outside the safe charset, then strips leading dots/hyphens too so a label that would otherwise slugify to ".." (or "-", "-2", ...) can never be mistaken for a filesystem special segment; safeChildOf below is the second, independent check on the same risk.
    private static String slugify(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.-]+", "")
                .replaceAll("[.-]+$", "");
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    public static String toJavaClassName(String rawLabel) {
        if (rawLabel == null || rawLabel.isBlank()) {
            return "TargetPlatformApplication";
        }
        String cleaned = rawLabel.trim();
        String[] parts = cleaned.split("[^A-Za-z0-9]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (part.equals(part.toUpperCase()) && part.length() <= 4 && part.matches("[A-Za-z]+")) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                sb.append(part.substring(1).toLowerCase());
            } else {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
        }
        if (sb.length() == 0) {
            return "TargetPlatformApplication";
        }
        if (!Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, "App");
        }
        if (!sb.toString().endsWith("Application")) {
            sb.append("Application");
        }
        String className = sb.toString();
        StringBuilder valid = new StringBuilder();
        for (int i = 0; i < className.length(); i++) {
            char ch = className.charAt(i);
            if (i == 0) {
                valid.append(Character.isJavaIdentifierStart(ch) ? ch : 'A');
            } else {
                valid.append(Character.isJavaIdentifierPart(ch) ? ch : '_');
            }
        }
        return valid.toString();
    }

    private void rewritePomArtifactId(Path projectDir, String artifactId) {
        Path pom = projectDir.resolve("pom.xml");
        if (!Files.exists(pom)) {
            return;
        }
        try {
            String content = Files.readString(pom, StandardCharsets.UTF_8);
            String updated = content.replaceAll("<artifactId>(camundademo|TargetPlatform|red-collar-tp)</artifactId>",
                    "<artifactId>" + artifactId + "</artifactId>");
            Files.writeString(pom, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Could not rewrite pom.xml artifactId in {}: {}", projectDir, e.toString());
        }
    }

    private void rewriteApplicationClassName(Path projectDir, String basePackage, String newClassName) {
        if (newClassName == null || newClassName.isBlank() || "CamundademoApplication".equals(newClassName) || "TargetPlatformApplication".equals(newClassName)) {
            return;
        }
        List<Path> roots = List.of(
                projectDir.resolve("src/main/java"),
                projectDir.resolve("src/test/java"));

        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            Path pkgDir = isTargetPlatformTemplate()
                    ? root.resolve("com/tp/TargetPlatform")
                    : root.resolve(basePackage.replace('.', '/'));
            if (!Files.isDirectory(pkgDir)) continue;

            try (Stream<Path> stream = Files.list(pkgDir)) {
                List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith("Application.java")
                        || p.getFileName().toString().endsWith("ApplicationTests.java")).toList();
                for (Path appFile : files) {
                    String oldFileName = appFile.getFileName().toString();
                    String oldClassName = oldFileName.substring(0, oldFileName.length() - ".java".length());
                    String targetClassName = oldClassName.endsWith("Tests") ? newClassName + "Tests" : newClassName;
                    Path targetFile = appFile.getParent().resolve(targetClassName + ".java");

                    String content = Files.readString(appFile, StandardCharsets.UTF_8);
                    String updatedContent = content.replace(oldClassName, targetClassName);
                    Files.writeString(targetFile, updatedContent, StandardCharsets.UTF_8);
                    if (!appFile.equals(targetFile)) {
                        Files.deleteIfExists(appFile);
                    }
                }
            } catch (IOException e) {
                logger.warn("Could not rewrite Application class name in {}: {}", pkgDir, e.toString());
            }
        }
    }

    private static String labelFor(String displayName, String processKeyFallback) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        if (processKeyFallback != null && !processKeyFallback.isBlank()) {
            return processKeyFallback;
        }
        return "Target Platform";
    }

    // Same guard resolveProjectDirectory always had for the bare-projectId folder name, applied here to a slug instead: cheap, and "trusted for now" is exactly the kind of assumption that stops being true the next time a caller changes.
    private static Path safeChildOf(Path root, String segment) {
        Path resolved = root.resolve(segment).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException(
                    "Generated project folder name must not resolve outside the output directory: " + segment);
        }
        return resolved;
    }

    // Rebuilt from disk rather than a separate store that could drift. Directories without exactly one
    // non-twin .bpmn are skipped, not guessed at. projectId comes from PROJECT_METADATA_FILE now that the
    // folder name is a human label, falling back to the folder name for directories predating that field.
    public List<GeneratedProject> scanExisting() {
        if (!Files.isDirectory(outputDirectory)) {
            // nothing has ever been generated against this output directory - a fresh install, or one still on defaults, not an error
            return List.of();
        }
        List<GeneratedProject> found = new ArrayList<>();
        try (Stream<Path> children = Files.list(outputDirectory)) {
            for (Path projectDir : (Iterable<Path>) children.filter(Files::isDirectory)::iterator) {
                String declaredProjectId = readDeclaredProjectId(projectDir);
                String projectId = declaredProjectId != null ? declaredProjectId : projectDir.getFileName().toString();
                String processKey = findProcessKey(projectDir);
                if (processKey == null) {
                    logger.warn("Skipping {} while restoring generated projects - could not determine a single "
                            + "process key under {}", projectId, projectDir.resolve(PROCESSES_PATH));
                    continue;
                }
                String declaredDisplayName = readDeclaredDisplayName(projectDir);
                String displayName = declaredDisplayName != null ? declaredDisplayName : processKey;
                found.add(new GeneratedProject(projectId, projectDir, processKey, displayName));
            }
        } catch (IOException e) {
            logger.warn("Could not scan {} for existing generated projects: {}", outputDirectory.toAbsolutePath(),
                    e.toString());
            return List.of();
        }
        return found;
    }

    // Lookup by declared identity, since the folder name is now a human label rather than the projectId.
    // Only ever returns a direct child of outputDirectory, keeping the path-traversal guarantee the old
    // root.resolve(projectId) had.
    Path findProjectDirectoryById(String projectId) {
        Path root = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> children = Files.list(root)) {
            for (Path candidate : (Iterable<Path>) children.filter(Files::isDirectory)::iterator) {
                String declared = readDeclaredProjectId(candidate);
                String effectiveId = declared != null ? declared : candidate.getFileName().toString();
                if (projectId.equals(effectiveId)) {
                    return candidate;
                }
            }
        } catch (IOException e) {
            logger.warn("Could not scan {} while resolving generated project '{}': {}",
                    root.toAbsolutePath(), projectId, e.toString());
        }
        return null;
    }

    // Takes projectId, not Path - resolves it through findProjectDirectoryById (declared identity, not the folder name) and validates the direct-child relationship before deletion to prevent path traversal (id comes from a persisted event; "../../data" would otherwise escape the tree).
    public boolean delete(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        Path root = outputDirectory.toAbsolutePath().normalize();
        Path projectDir = findProjectDirectoryById(projectId);
        if (projectDir == null) {
            return false;
        }
        if (!root.equals(projectDir.getParent())) {
            logger.warn("Refusing to delete generated project '{}' - {} is not a direct child of {}",
                    projectId, projectDir, root);
            return false;
        }
        if (!Files.isDirectory(projectDir)) {
            // already gone (deleted by hand, or a previous cleanup got there first) - not an error, the caller's intended end state is exactly what's already true
            return false;
        }
        try (Stream<Path> walk = Files.walk(projectDir)) {
            // deepest-first, so a directory is only removed once it's already empty
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // A generated project holds no state anything depends on, so a partial delete doesn't fail the triggering operation - scanExisting() above already skips directories with no resolvable process key, which is exactly the shape a half-deleted one has.
            logger.warn("Could not fully delete generated project {} at {}: {}", projectId, projectDir, e.toString());
            return false;
        }
        logger.info("Deleted superseded generated project {} at {}", projectId, projectDir);
        return true;
    }

    // Writes this project's own declared identity, generic across every generation mode. See PROJECT_METADATA_FILE's own comment for why this exists instead of inferring identity from the .bpmn files a mode happens to write. Root of the project, not under src/, so it is never touched by rewritePackage() and never ships as part of the generated application itself.
    private void writeProjectMetadata(Path projectDir, String projectId, String processKey) {
        writeProjectMetadata(projectDir, projectId, processKey, null);
    }

    private void writeProjectMetadata(Path projectDir, String projectId, String processKey, String displayName) {
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("processKey", processKey);
        properties.setProperty("projectId", projectId);
        if (displayName != null && !displayName.isBlank()) {
            properties.setProperty("displayName", displayName);
        }
        Path target = projectDir.resolve(PROJECT_METADATA_FILE);
        try (java.io.Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            properties.store(writer, "Generated by SpringBootProjectGenerator - do not hand-edit");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write project metadata to " + target.toAbsolutePath(), e);
        }
    }

    // Prefers the project's declared identity over guessing from the .bpmn files under processes/, so one
    // BPMN, two authored BPMNs and any future mode all work without a mode-specific filename rule.
    // A declared key is only trusted when its own processes/<key>.bpmn is still present - a directory
    // whose real artifact was deleted must still read as unrecoverable.
    // Falls back to the older "exactly one non-twin .bpmn" heuristic for projects generated before this
    // metadata existed, rather than orphaning them.
    private static String findProcessKey(Path projectDir) {
        String declared = readDeclaredProcessKey(projectDir);
        if (declared != null) {
            Path declaredBpmnFile = projectDir.resolve(PROCESSES_PATH).resolve(declared + ".bpmn");
            if (Files.isRegularFile(declaredBpmnFile)) {
                return declared;
            }
            return null;
        }
        Path processesDir = projectDir.resolve(PROCESSES_PATH);
        if (!Files.isDirectory(processesDir)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(processesDir)) {
            List<Path> bpmnFiles = entries.filter(p -> p.getFileName().toString().endsWith(".bpmn"))
                    .filter(p -> !p.getFileName().toString().endsWith("_twin.bpmn"))
                    .toList();
            // zero means this directory was never finished (or was cleared out); more than one is a shape the pre-metadata generate() itself never produced - either way, no safe single answer without the metadata file this project predates
            if (bpmnFiles.size() != 1) {
                return null;
            }
            String fileName = bpmnFiles.get(0).getFileName().toString();
            return fileName.substring(0, fileName.length() - ".bpmn".length());
        } catch (IOException e) {
            return null;
        }
    }

    private static String readDeclaredProcessKey(Path projectDir) {
        java.util.Properties properties = loadProjectMetadata(projectDir);
        if (properties == null) {
            return null;
        }
        String processKey = properties.getProperty("processKey");
        return (processKey == null || processKey.isBlank()) ? null : processKey;
    }

    // Absent for a directory generated before PROJECT_METADATA_FILE carried a projectId - callers (scanExisting/findProjectDirectoryById) fall back to the folder name in that case, which for one of those older directories still literally IS its id (see resolveProjectDirectory).
    private static String readDeclaredProjectId(Path projectDir) {
        java.util.Properties properties = loadProjectMetadata(projectDir);
        if (properties == null) {
            return null;
        }
        String projectId = properties.getProperty("projectId");
        return (projectId == null || projectId.isBlank()) ? null : projectId;
    }

    private static String readDeclaredDisplayName(Path projectDir) {
        java.util.Properties properties = loadProjectMetadata(projectDir);
        if (properties == null) {
            return null;
        }
        String displayName = properties.getProperty("displayName");
        return (displayName == null || displayName.isBlank()) ? null : displayName;
    }

    private static java.util.Properties loadProjectMetadata(Path projectDir) {
        Path metadataFile = projectDir.resolve(PROJECT_METADATA_FILE);
        if (!Files.isRegularFile(metadataFile)) {
            return null;
        }
        java.util.Properties properties = new java.util.Properties();
        try (java.io.Reader reader = Files.newBufferedReader(metadataFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            logger.warn("Could not read project metadata at {}: {}", metadataFile.toAbsolutePath(), e.toString());
            return null;
        }
        return properties;
    }

    private static String extractProcessKey(BpmnModelInstance model) {
        return model.getModelElementsByType(Process.class).stream()
                .findFirst()
                .map(Process::getId)
                .orElseThrow(() -> new IllegalArgumentException("BPMN has no process element to read a key from"));
    }

    // Skips the template project's own build output/VCS metadata - copying target/ verbatim (e.g. a stale template/target/classes/processes/*.bpmn from whenever the template itself was last built) would let a leftover class-output resource get auto-deployed alongside every generated project's real process(es), which is exactly the kind of accidental cross-talk this generator otherwise goes out of its way to avoid.
    private static final Set<String> TEMPLATE_COPY_EXCLUDED_DIR_NAMES = Set.of("target", ".git");

    private void copyTemplate(Path projectDir) {
        try (Stream<Path> walk = Files.walk(templateDirectory)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                if (isUnderExcludedDir(templateDirectory, source)) {
                    continue;
                }
                Path relative = templateDirectory.relativize(source);
                Path target = projectDir.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    // Files.copy doesn't carry the executable bit on its own - every generated project needs its own mvnw runnable, not just the template's.
                    if ("mvnw".equals(target.getFileName().toString())) {
                        target.toFile().setExecutable(true);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not copy template from " + templateDirectory.toAbsolutePath(), e);
        }
    }

    private static boolean isUnderExcludedDir(Path root, Path candidate) {
        for (Path segment : root.relativize(candidate)) {
            if (TEMPLATE_COPY_EXCLUDED_DIR_NAMES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    // Remove the template's loanApproval worked example; writeController() replaces the controller. LoanApplicationContext and BPMNProcessRESTMappings exist only to support the removed pair.
    private void removeTemplatePlaceholders(Path projectDir) {
        deleteIfExists(projectDir.resolve(PROCESSES_PATH).resolve("loanApproval.bpmn"));
        deleteIfExists(projectDir.resolve(DELEGATE_PACKAGE_PATH).resolve("CalculateInterestService.java"));
        deleteIfExists(projectDir.resolve(CONTROLLER_PACKAGE_PATH).resolve("Camundacontroller.java"));
        deleteIfExists(projectDir.resolve("src/main/java/com/example/camundademo/context/LoanApplicationContext.java"));
        deleteIfExists(projectDir.resolve(
                "src/main/java/com/example/camundademo/utils/restmappings/BPMNProcessRESTMappings.java"));
    }

    private void writeProcessFile(Path projectDir, String processKey, String bpmnXml) {
        writeFile(projectDir.resolve(PROCESSES_PATH).resolve(processKey + ".bpmn"), bpmnXml);
    }

    // Pre-rendered delegates still reference DELEGATE_PACKAGE; rewrite to the manufacturing subpackage at write time.
    private void writeManufacturingDelegates(Path projectDir, String basePackage, List<GeneratedDelegate> delegates) {
        String manufacturingPackage = basePackage + ".delegate.manufacturing";
        Path packageDir = projectDir.resolve("src/main/java").resolve(manufacturingPackage.replace('.', '/'));
        for (GeneratedDelegate delegate : delegates) {
            String rewrittenSource = delegate.sourceCode().replace(DELEGATE_PACKAGE, manufacturingPackage);
            writeDelegateFile(packageDir, delegate.className(), rewrittenSource, delegate.beanName(),
                    delegate.bpmnElementId());
        }
    }

    private void writeTwinDelegates(Path projectDir, String basePackage, List<GeneratedDelegate> delegates) {
        Path packageDir = projectDir.resolve("src/main/java")
                .resolve((basePackage + ".delegate.twin").replace('.', '/'));
        for (GeneratedDelegate delegate : delegates) {
            writeDelegateFile(packageDir, delegate.className(), delegate.sourceCode(), delegate.beanName(),
                    delegate.bpmnElementId());
        }
    }

    // camunda:class delegates carry their own package in sourceCode (see DelegateClassGenerator.generateFromJavaClass), so the write path is derived from that instead of a caller-supplied basePackage.
    private void writeJavaClassDelegates(Path projectDir, List<GeneratedDelegate> delegates) {
        for (GeneratedDelegate delegate : delegates) {
            String packageLine = delegate.sourceCode().lines()
                    .filter(l -> l.startsWith("package ")).findFirst().orElse("");
            String packageName = packageLine.replace("package ", "").replace(";", "").trim();
            Path packageDir = projectDir.resolve("src/main/java").resolve(packageName.replace('.', '/'));
            writeDelegateFile(packageDir, delegate.className(), delegate.sourceCode(), delegate.beanName(),
                    delegate.bpmnElementId());
        }
    }

    private void writeWorkers(Path projectDir, List<GeneratedWorker> workers) {
        Set<Path> writtenFiles = new LinkedHashSet<>();
        for (GeneratedWorker worker : workers) {
            // Each worker's sourceCode contains a package declaration whose path we derive here
            String packageLine = worker.sourceCode().lines()
                    .filter(l -> l.startsWith("package ")).findFirst().orElse("");
            String packageName = packageLine.replace("package ", "").replace(";", "").trim();
            Path packageDir = projectDir.resolve("src/main/java").resolve(packageName.replace('.', '/'));
            Path targetFile = packageDir.resolve(worker.className() + ".java");
            if (!writtenFiles.add(targetFile.toAbsolutePath().normalize())) {
                throw new IllegalStateException("Duplicate worker file path would overwrite existing worker: " + targetFile);
            }
            writeFile(targetFile, worker.sourceCode());
        }
    }

    private static Set<String> extractSignalNames(BpmnModelInstance model) {
        Set<String> names = new LinkedHashSet<>();
        for (Signal signal : model.getModelElementsByType(Signal.class)) {
            String name = signal.getName();
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    // Escapes a string for safe embedding as a Java string literal in generated source. BPMN signal names are author-controlled data, not something this generator can constrain - a name containing a quote or backslash must not corrupt (or inject code into) the file being generated.
    private static String escapeJavaStringLiteral(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Turns an arbitrary BPMN-authored identifier (a camunda:topic, typically PascalCase, e.g. "SamplingTwin") into kebab-case ("sampling-twin") - the convention this platform's RabbitMQ queue names use. Generic word-boundary splitting, not a lookup table: it works the same for any topic name, RedCollar's or anyone else's.
    private static String pascalCaseToKebabCase(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String withHyphens = raw
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2");
        return withHyphens.toLowerCase();
    }

    // Restricts an already-kebab-cased string to safe, valid RabbitMQ identifier characters: lowercase letters, digits, hyphens. BPMN topic names are author-controlled - a stray character must not produce an invalid queue name.
    private static String sanitizeForKebabSegment(String raw) {
        String slug = raw == null ? "" : raw.toLowerCase()
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "activity";
        }
        // one segment of a longer name (see assignActivityQueueIdentities) - capped well under RabbitMQ's own 255-byte name limit even for a pathologically long topic name
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    // Maps each BPMN signal name to the Twin external-task topic its catch event gates entry into.
    // Neither model has a signal throw event, so the catch event's outgoing flow into an external-task
    // service task is the only place that relationship is expressed in the BPMN at all.
    // Scans every SequenceFlow's sourceRef rather than calling getOutgoing(), which only returns flows
    // listed in an explicit <outgoing> child - optional per the spec, so a conformant BPMN written
    // without it would silently match nothing.
    private static Map<String, String> mapSignalToGatedTwinTopic(BpmnModelInstance twinModel) {
        Map<String, String> result = new LinkedHashMap<>();
        for (IntermediateCatchEvent catchEvent : twinModel.getModelElementsByType(IntermediateCatchEvent.class)) {
            String signalName = null;
            for (EventDefinition definition : catchEvent.getEventDefinitions()) {
                if (definition instanceof SignalEventDefinition signalDefinition
                        && signalDefinition.getSignal() != null) {
                    signalName = signalDefinition.getSignal().getName();
                }
            }
            if (signalName == null || signalName.isBlank()) {
                continue;
            }
            for (SequenceFlow flow : twinModel.getModelElementsByType(SequenceFlow.class)) {
                if (!catchEvent.equals(flow.getSource())) {
                    continue;
                }
                FlowNode target = flow.getTarget();
                if (target instanceof Activity activity
                        && "external".equals(activity.getAttributeValueNs(CAMUNDA_NS, "type"))) {
                    String topic = activity.getAttributeValueNs(CAMUNDA_NS, "topic");
                    if (topic != null && !topic.isBlank()) {
                        result.put(signalName, topic);
                    }
                }
            }
        }
        return result;
    }

    // One task queue plus one response queue per Main<->Twin communication activity. Scoped by
    // messagingNamespace (process-key slug plus generated projectId) so two independently generated
    // projects can never share a queue even with identically named activities.
    private record ActivityQueueIdentity(String topic, String taskQueueName, String taskRoutingKey,
            String responseQueueName, String responseRoutingKey, String javaIdentifier) {
    }

    // Two topics that kebab-case to the same slug (e.g. "Order Ready" and "OrderReady") are disambiguated with a numeric suffix, assigned in the caller's own iteration order - stable because callers always pass the topic list in BPMN document order.
    private static List<ActivityQueueIdentity> assignActivityQueueIdentities(String messagingNamespace,
            List<String> twinTopics) {
        List<ActivityQueueIdentity> identities = new ArrayList<>();
        Set<String> usedSlugs = new java.util.HashSet<>();
        int index = 0;
        for (String topic : twinTopics) {
            String base = sanitizeForKebabSegment(pascalCaseToKebabCase(topic));
            String slug = base;
            int suffix = 2;
            while (!usedSlugs.add(slug)) {
                slug = base + "-" + suffix++;
            }
            String taskQueue = messagingNamespace + ".tasks." + slug;
            String responseQueue = messagingNamespace + ".tasks.responses." + slug;
            identities.add(new ActivityQueueIdentity(topic, taskQueue, "tasks." + slug, responseQueue,
                    "tasks.responses." + slug, "q" + (index++) + "_" + slug.replace('-', '_')));
        }
        return identities;
    }

    // Generates the RabbitMQ messaging layer as readable Java source rather than embedded string
    // literals: RabbitMqConfig for topology, plus task- and response-queue publisher/listener pairs.
    // SignalBroadcaster still decides WHEN a handoff happens - REQUEST routes through the gated
    // activity's task queue, RESPONSE through its response queue.
    // Every Twin activity gets a queue pair whether or not a signal gates it, so the topology is always
    // complete and inspectable at the broker.
    private void writeRabbitMqMessaging(Path projectDir, String basePackage, String messagingNamespace,
            List<String> twinTopics, Map<String, String> signalToGatedTwinTopic) {
        String subPackage = basePackage + ".messaging";
        List<ActivityQueueIdentity> queues = assignActivityQueueIdentities(messagingNamespace, twinTopics);
        String exchangeName = messagingNamespace + ".exchange";

        String taskQueueEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.topic()) + "\", \"" + q.taskQueueName() + "\")")
                .collect(Collectors.joining(",\n            "));
        String responseQueueEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.topic()) + "\", \"" + q.responseQueueName()
                        + "\")")
                .collect(Collectors.joining(",\n            "));
        String taskRoutingKeyEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.topic()) + "\", \"" + q.taskRoutingKey() + "\")")
                .collect(Collectors.joining(",\n            "));
        String responseRoutingKeyEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.topic()) + "\", \"" + q.responseRoutingKey()
                        + "\")")
                .collect(Collectors.joining(",\n            "));
        String topicBySignalEntries = signalToGatedTwinTopic.entrySet().stream()
                .map(e -> "Map.entry(\"" + escapeJavaStringLiteral(e.getKey()) + "\", \""
                        + escapeJavaStringLiteral(e.getValue()) + "\")")
                .collect(Collectors.joining(",\n            "));
        String queueBeans = queues.stream()
                .map(q -> """

                        @Bean
                        public Queue %1$sTaskQueue() {
                            return new Queue("%2$s");
                        }

                        @Bean
                        public Binding %1$sTaskBinding() {
                            return BindingBuilder.bind(%1$sTaskQueue()).to(messagingExchange()).with("%3$s");
                        }

                        @Bean
                        public Queue %1$sResponseQueue() {
                            return new Queue("%4$s");
                        }

                        @Bean
                        public Binding %1$sResponseBinding() {
                            return BindingBuilder.bind(%1$sResponseQueue()).to(messagingExchange()).with("%5$s");
                        }
                        """.formatted(q.javaIdentifier(), q.taskQueueName(), q.taskRoutingKey(),
                        q.responseQueueName(), q.responseRoutingKey()))
                .collect(Collectors.joining());

        String configSource = """
                package %s;

                import java.util.Map;

                import org.springframework.amqp.core.Binding;
                import org.springframework.amqp.core.BindingBuilder;
                import org.springframework.amqp.core.DirectExchange;
                import org.springframework.amqp.core.Queue;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                // RabbitMQ topology for this generated platform's Main<->Twin communication: one task queue and one response queue per Twin external-task activity (see SpringBootProjectGenerator.assignActivityQueueIdentities), scoped to this generated project so two independently generated platforms can never physically share a queue. Queue/exchange names are derived entirely from the Twin BPMN's own external-task topics and this project's generated id - nothing here is hard-coded to any particular process. Enabled only with metaml.messaging.enabled=true, matching every other messaging component in this platform.
                @Configuration
                @ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
                public class RabbitMqConfig {

                    public static final String EXCHANGE = "%s";

                    // Twin external-task topic -> its dedicated task queue name (Main asks Twin to run this activity) - the single source of truth for which activities have RabbitMQ queues at all.
                    public static final Map<String, String> TASK_QUEUE_BY_TOPIC = Map.ofEntries(
                            %s
                    );

                    // Twin external-task topic -> its dedicated response queue name (Twin reports the activity's completion back to Main).
                    public static final Map<String, String> RESPONSE_QUEUE_BY_TOPIC = Map.ofEntries(
                            %s
                    );

                    // Topic -> the routing key its task queue is bound with. Equal to the queue's own unique slug, so publisher and consumer always agree without parsing queue names back apart.
                    public static final Map<String, String> TASK_ROUTING_KEY_BY_TOPIC = Map.ofEntries(
                            %s
                    );

                    // Topic -> the routing key its response queue is bound with.
                    public static final Map<String, String> RESPONSE_ROUTING_KEY_BY_TOPIC = Map.ofEntries(
                            %s
                    );

                    // BPMN signal name -> the Twin activity topic its release actually gates entry into (see SpringBootProjectGenerator.mapSignalToGatedTwinTopic) - how SignalBroadcaster's own REQUEST/RESPONSE handoff (unchanged) knows which activity's queue pair to route a given signal's delivery through.
                    public static final Map<String, String> TOPIC_BY_SIGNAL = Map.ofEntries(
                            %s
                    );

                    @Bean
                    public DirectExchange messagingExchange() {
                        return new DirectExchange(EXCHANGE);
                    }
                    %s
                }
                """.formatted(subPackage, exchangeName, taskQueueEntries, responseQueueEntries,
                taskRoutingKeyEntries, responseRoutingKeyEntries, topicBySignalEntries, queueBeans);

        String taskPublisherSource = """
                package %s;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.amqp.rabbit.core.RabbitTemplate;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.stereotype.Component;

                // Publishes a "run this Twin activity now" task message to that activity's own dedicated task queue (see RabbitMqConfig.TASK_QUEUE_BY_TOPIC) - TaskQueueListener performs the actual Camunda signal delivery that releases Twin's waiting execution, on consume. Always present as a bean, but isEnabled() returns false unless metaml.messaging.enabled=true (no broker required by default). Payload is pipe-delimited (signalName|executionId|processInstanceId|businessKey), not JSON, independent of the pre-existing messaging package's Jackson configuration.
                @Component
                public class TaskQueuePublisher {

                    private static final Logger logger = LoggerFactory.getLogger(TaskQueuePublisher.class);

                    private final RabbitTemplate rabbitTemplate;
                    private final boolean enabled;

                    public TaskQueuePublisher(RabbitTemplate rabbitTemplate,
                            @Value("${metaml.messaging.enabled:false}") boolean enabled) {
                        this.rabbitTemplate = rabbitTemplate;
                        this.enabled = enabled;
                    }

                    public boolean isEnabled() {
                        return enabled;
                    }

                    // True for a Twin activity that has its own dedicated task queue - every activity discovered from the Twin BPMN's own external-task topics has one (see RabbitMqConfig.TASK_QUEUE_BY_TOPIC).
                    public boolean isEligible(String twinTopic) {
                        return RabbitMqConfig.TASK_QUEUE_BY_TOPIC.containsKey(twinTopic);
                    }

                    public void publish(String twinTopic, String signalName, String executionId,
                            String processInstanceId, String businessKey) {
                        String routingKey = RabbitMqConfig.TASK_ROUTING_KEY_BY_TOPIC.get(twinTopic);
                        if (routingKey == null) {
                            throw new IllegalArgumentException("No task queue is declared for Twin activity '"
                                    + twinTopic + "' - callers must check isEligible(twinTopic) first");
                        }
                        String payload = signalName + "|" + executionId + "|" + processInstanceId
                                + "|" + (businessKey == null ? "" : businessKey);
                        rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, routingKey, payload);
                        logger.info("TASK: published activity '{}' (signal '{}') to RabbitMQ exchange '{}' key "
                                + "'{}' (queue '{}') for execution {} (processInstanceId={}, businessKey={})",
                                twinTopic, signalName, RabbitMqConfig.EXCHANGE, routingKey,
                                RabbitMqConfig.TASK_QUEUE_BY_TOPIC.get(twinTopic), executionId, processInstanceId,
                                businessKey);
                    }
                }
                """.formatted(subPackage);

        String responsePublisherSource = """
                package %s;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.amqp.rabbit.core.RabbitTemplate;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.stereotype.Component;

                // Publishes a "this Twin activity finished" response message to that activity's own dedicated response queue (see RabbitMqConfig.RESPONSE_QUEUE_BY_TOPIC) - ResponseQueueListener performs the actual Camunda signal delivery that releases Main's waiting execution, on consume. Always present as a bean, but isEnabled() returns false unless metaml.messaging.enabled=true. Payload format mirrors TaskQueuePublisher's own.
                @Component
                public class ResponseQueuePublisher {

                    private static final Logger logger = LoggerFactory.getLogger(ResponseQueuePublisher.class);

                    private final RabbitTemplate rabbitTemplate;
                    private final boolean enabled;

                    public ResponseQueuePublisher(RabbitTemplate rabbitTemplate,
                            @Value("${metaml.messaging.enabled:false}") boolean enabled) {
                        this.rabbitTemplate = rabbitTemplate;
                        this.enabled = enabled;
                    }

                    public boolean isEnabled() {
                        return enabled;
                    }

                    public boolean isEligible(String twinTopic) {
                        return RabbitMqConfig.RESPONSE_QUEUE_BY_TOPIC.containsKey(twinTopic);
                    }

                    public void publish(String twinTopic, String signalName, String executionId,
                            String processInstanceId, String businessKey) {
                        String routingKey = RabbitMqConfig.RESPONSE_ROUTING_KEY_BY_TOPIC.get(twinTopic);
                        if (routingKey == null) {
                            throw new IllegalArgumentException("No response queue is declared for Twin activity '"
                                    + twinTopic + "' - callers must check isEligible(twinTopic) first");
                        }
                        String payload = signalName + "|" + executionId + "|" + processInstanceId
                                + "|" + (businessKey == null ? "" : businessKey);
                        rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, routingKey, payload);
                        logger.info("RESPONSE: published activity '{}' (signal '{}') to RabbitMQ exchange '{}' "
                                + "key '{}' (queue '{}') for execution {} (processInstanceId={}, businessKey={})",
                                twinTopic, signalName, RabbitMqConfig.EXCHANGE, routingKey,
                                RabbitMqConfig.RESPONSE_QUEUE_BY_TOPIC.get(twinTopic), executionId,
                                processInstanceId, businessKey);
                    }
                }
                """.formatted(subPackage);

        String listenerImports = queues.isEmpty()
                ? """
                        import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                        import org.springframework.stereotype.Component;
                        """
                : """
                        import org.camunda.bpm.engine.RuntimeService;
                        import org.slf4j.Logger;
                        import org.slf4j.LoggerFactory;
                        import org.springframework.amqp.rabbit.annotation.RabbitListener;
                        import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                        import org.springframework.stereotype.Component;
                        """;

        // No Twin external-task topic exists when queues is empty, so there is no Main<->Twin communication activity to declare a RabbitMQ consumer for - the class is still generated (present unconditionally, like every other piece here), it just has nothing to listen on.
        String noActivitiesComment = "    // No Twin external-task activity was found in this project's BPMNs, so\n"
                + "    // there is no Main<->Twin communication activity to declare a RabbitMQ consumer for.\n";

        String taskListenerFields = queues.isEmpty() ? ""
                : "    private static final Logger logger = LoggerFactory.getLogger(TaskQueueListener.class);\n\n";
        String taskListenerBody = queues.isEmpty()
                ? noActivitiesComment
                : """
                            private final RuntimeService runtimeService;

                            public TaskQueueListener(RuntimeService runtimeService) {
                                this.runtimeService = runtimeService;
                            }

                            @RabbitListener(queues = { %s })
                            public void onTaskMessage(String payload) {
                                String[] parts = payload.split("\\\\|", -1);
                                if (parts.length != 4) {
                                    logger.error("[task-queue] discarding malformed message: {}", payload);
                                    return;
                                }
                                String signalName = parts[0];
                                String executionId = parts[1];
                                String processInstanceId = parts[2];
                                String businessKey = parts[3];
                                try {
                                    runtimeService.signalEventReceived(signalName, executionId);
                                    logger.info("TASK: delivered signal '{}' to execution {} (processInstanceId={}, "
                                            + "businessKey={}) via RabbitMQ", signalName, executionId,
                                            processInstanceId, businessKey);
                                } catch (Exception e) {
                                    // Expected during normal operation - the execution may already have moved on (e.g. another delivery reached it first).
                                    logger.info("TASK: signal '{}' delivery to execution {} skipped (already "
                                            + "advanced?): {}", signalName, executionId, e.toString());
                                }
                            }
                        """.formatted(queues.stream()
                                .map(q -> "\"" + q.taskQueueName() + "\"")
                                .collect(Collectors.joining(", ")));

        String responseListenerFields = queues.isEmpty() ? ""
                : "    private static final Logger logger = LoggerFactory.getLogger(ResponseQueueListener.class);\n\n";
        String responseListenerBody = queues.isEmpty()
                ? noActivitiesComment
                : """
                            private final RuntimeService runtimeService;

                            public ResponseQueueListener(RuntimeService runtimeService) {
                                this.runtimeService = runtimeService;
                            }

                            @RabbitListener(queues = { %s })
                            public void onResponseMessage(String payload) {
                                String[] parts = payload.split("\\\\|", -1);
                                if (parts.length != 4) {
                                    logger.error("[response-queue] discarding malformed message: {}", payload);
                                    return;
                                }
                                String signalName = parts[0];
                                String executionId = parts[1];
                                String processInstanceId = parts[2];
                                String businessKey = parts[3];
                                try {
                                    runtimeService.signalEventReceived(signalName, executionId);
                                    logger.info("RESPONSE: delivered signal '{}' to execution {} "
                                            + "(processInstanceId={}, businessKey={}) via RabbitMQ", signalName,
                                            executionId, processInstanceId, businessKey);
                                } catch (Exception e) {
                                    // Expected during normal operation - the execution may already have moved on (e.g. another delivery reached it first).
                                    logger.info("RESPONSE: signal '{}' delivery to execution {} skipped (already "
                                            + "advanced?): {}", signalName, executionId, e.toString());
                                }
                            }
                        """.formatted(queues.stream()
                                .map(q -> "\"" + q.responseQueueName() + "\"")
                                .collect(Collectors.joining(", ")));

        String taskListenerSource = """
                package %s;

                %s
                // The real consumer for task messages, one queue per Main<->Twin communication activity (see RabbitMqConfig). This is what makes RabbitMQ the actual Main->Twin transport rather than an audit log next to a direct call: the Camunda signal delivery that releases Twin's waiting execution happens here, triggered by consuming the message, not by SignalBroadcaster's own scheduled tick. Enabled only with metaml.messaging.enabled=true; when disabled, SignalBroadcaster delivers signals directly instead.
                @Component
                @ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
                public class TaskQueueListener {

                %s%s
                }
                """.formatted(subPackage, listenerImports, taskListenerFields, taskListenerBody);

        String responseListenerSource = """
                package %s;

                %s
                // The real consumer for response messages, one queue per Main<->Twin communication activity (see RabbitMqConfig). This is what makes RabbitMQ the actual Twin->Main transport: the Camunda signal delivery that releases Main's waiting execution happens here, triggered by consuming the message. Enabled only with metaml.messaging.enabled=true; when disabled, SignalBroadcaster delivers signals directly instead.
                @Component
                @ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
                public class ResponseQueueListener {

                %s%s
                }
                """.formatted(subPackage, listenerImports, responseListenerFields, responseListenerBody);

        Path packageDir = projectDir.resolve("src/main/java").resolve(subPackage.replace('.', '/'));
        writeFile(packageDir.resolve("RabbitMqConfig.java"), configSource);
        writeFile(packageDir.resolve("TaskQueuePublisher.java"), taskPublisherSource);
        writeFile(packageDir.resolve("TaskQueueListener.java"), taskListenerSource);
        writeFile(packageDir.resolve("ResponseQueuePublisher.java"), responsePublisherSource);
        writeFile(packageDir.resolve("ResponseQueueListener.java"), responseListenerSource);
    }

    // Generates a Spring component that periodically broadcasts all BPMN-defined signals so that intermediate signal catch events in both processes can advance. Both Manufacturing and Twin use catch events with shared signal names; neither throws, so signals must be broadcast externally. Signal names are extracted from the actual BPMNs, not hard-coded.
    private void writeSignalBroadcaster(Path projectDir, String basePackage, Set<String> signalNames) {
        String subPackage = basePackage + ".signal";
        String coordinationPackage = basePackage + ".coordination";
        String messagingPackage = basePackage + ".messaging";
        String messagingImports = """
                import %1$s.RabbitMqConfig;
                import %1$s.TaskQueuePublisher;
                import %1$s.ResponseQueuePublisher;""".formatted(messagingPackage);
        String signalList = signalNames.stream()
                .map(s -> "\"" + escapeJavaStringLiteral(s) + "\"")
                .collect(Collectors.joining(", "));

        String source = """
                package %s;

                import java.util.List;
                import java.util.Map;
                import java.util.Set;
                import java.util.concurrent.ConcurrentHashMap;

                import org.camunda.bpm.engine.RuntimeService;
                import org.camunda.bpm.engine.runtime.EventSubscription;
                import org.camunda.bpm.engine.runtime.ProcessInstance;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;
                import org.springframework.scheduling.annotation.Scheduled;

                import %s.PairRegistry;
                %s

                // Delivers BPMN-defined signals to the specific executions currently waiting on each one, so intermediate signal catch events in both processes can advance. Both Manufacturing and Twin use catch events with shared signal names; neither throws, so signals must be delivered externally. Signal names are derived from the actual BPMN signal definitions, not hard-coded. For a paired Main+Twin (same business key - see PairRegistry and the generated /start endpoints), each shared signal becomes a genuine two-step, targeted handoff instead of an undifferentiated broadcast: 1. REQUEST (Main -> Twin): once the initiator ("Main") and responder ("Twin") side of a pair are simultaneously waiting on the same signal, only the responder's execution is released. What runs next in its own BPMN - the Twin's delegate/external-task worker - is the simulated agent invocation. 2. RESPONSE (Twin -> Main): the initiator's execution is deliberately left waiting until the responder is observed to have moved on - subscribed to a different signal, or completed entirely - proving its gated task actually ran, not merely that the signal arrived. Only then is the initiator's execution released. This uses only generic runtime state (event subscriptions, business keys, process instance activity), never a process-specific name - the same class handles any BPMN pair, not just this one. Neither supplied process model has a signal throw event, so this stays the only way either direction can be delivered at all. An execution with no business key, or whose partner is not currently waiting on the same signal (unpaired, or a rework loop revisiting a signal its partner already passed for good), is delivered to immediately - exactly the prior, pre-pairing behavior. This is additive: nothing changes for a caller that never uses business keys. Scheduling itself (@EnableScheduling + the thread pool) is enabled by the always-generated SchedulingConfig, not here - this class must not be the thing that turns @Scheduled on: a BPMN pair with external tasks but no signals still needs its ExternalTaskPoller to run, and that must not depend on whether this class happens to exist.
                @Component
                public class SignalBroadcaster {

                    private static final Logger logger = LoggerFactory.getLogger(SignalBroadcaster.class);
                    private static final List<String> SIGNAL_NAMES = List.of(%s);

                    private final RuntimeService runtimeService;
                    private final PairRegistry pairRegistry;
                    private final TaskQueuePublisher taskQueuePublisher;
                    private final ResponseQueuePublisher responseQueuePublisher;
                    // (businessKey + "|" + signalName) currently past step 1, awaiting proof of step 2 before the initiator is released. Only this scheduled method ever touches these fields (Spring never overlaps two runs of the same @Scheduled method), but they stay concurrent collections defensively rather than relying on that alone.
                    private final Set<String> awaitingResponse = ConcurrentHashMap.newKeySet();
                    // (processInstanceId + "|" + signalName) that this broadcaster has ever actually delivered signalName to, by any path. Distinguishes "partner has not reached this signal YET" (may still arrive) from "partner already received this signal and moved on" (a rework-loop revisit, or the partner's earlier unpaired delivery before the other side ever registered - either way it is not coming back to this exact signal). Never cleared - this is a short-lived generated harness, not a long-running service, so unbounded growth for the process's lifetime is fine.
                    private final Set<String> everDelivered = ConcurrentHashMap.newKeySet();
                    // (waiting execution's processInstanceId + "|" + signalName) -> how many ticks it has been seen waiting here with its partner neither co-waiting nor already past this signal (see MAX_PARTNER_ARRIVAL_TICKS). Bounds the "the partner may just not have arrived yet" wait: a pair's shared signals resolve this way within one or two ticks in practice, but a signal that exists in only ONE side's BPMN (e.g. RedCollar's own Manuf-only orderVerifySignal) has a real, registered partner that will structurally never co-wait on it - everDelivered can never record that in advance, so without a bound this execution would wait forever. Cleared once resolved either way.
                    private final Map<String, Integer> partnerArrivalTicks = new ConcurrentHashMap<>();
                    private static final int MAX_PARTNER_ARRIVAL_TICKS = 5;

                    public SignalBroadcaster(RuntimeService runtimeService, PairRegistry pairRegistry,
                            TaskQueuePublisher taskQueuePublisher, ResponseQueuePublisher responseQueuePublisher) {
                        this.runtimeService = runtimeService;
                        this.pairRegistry = pairRegistry;
                        this.taskQueuePublisher = taskQueuePublisher;
                        this.responseQueuePublisher = responseQueuePublisher;
                    }

                    @Scheduled(fixedDelayString = "${metaml.broadcaster.fixed-delay:1000}")
                    public void broadcastSignals() {
                        for (String signalName : SIGNAL_NAMES) {
                            List<EventSubscription> waiting = runtimeService.createEventSubscriptionQuery()
                                    .eventType("signal")
                                    .eventName(signalName)
                                    .list();
                            for (EventSubscription subscription : waiting) {
                                handle(signalName, subscription, waiting);
                            }
                        }
                    }

                    private void handle(String signalName, EventSubscription subscription,
                            List<EventSubscription> waitingForSameSignal) {
                        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                                .processInstanceId(subscription.getProcessInstanceId())
                                .singleResult();
                        String businessKey = instance == null ? null : instance.getBusinessKey();
                        String role = pairRegistry.roleOf(businessKey, subscription.getProcessInstanceId());
                        String partnerInstanceId = pairRegistry.partnerOf(businessKey, subscription.getProcessInstanceId());

                        if (role == null || partnerInstanceId == null) {
                            deliverTo(signalName, subscription, businessKey, "DELIVERED");
                            return;
                        }
                        boolean partnerWaitingNow = waitingForSameSignal.stream()
                                .anyMatch(s -> s.getProcessInstanceId().equals(partnerInstanceId));

                        String waitKey = subscription.getProcessInstanceId() + "|" + signalName;

                        if ("responder".equals(role)) {
                            // Normally never self-releases - the initiator's own turn through this method (below) performs the actual handoff. If the initiator is not co-waiting yet, fall back to immediate delivery once partnerNotComing below says so; otherwise wait for a later tick instead of racing ahead.
                            if (partnerWaitingNow) {
                                partnerArrivalTicks.remove(waitKey);
                            } else if (partnerNotComing(waitKey, partnerInstanceId, signalName)) {
                                deliverTo(signalName, subscription, businessKey, "DELIVERED");
                            }
                            return;
                        }

                        String handoffKey = businessKey + "|" + signalName;
                        if (awaitingResponse.contains(handoffKey)) {
                            if (responderHasAdvancedPast(signalName, partnerInstanceId)) {
                                awaitingResponse.remove(handoffKey);
                                deliverTo(signalName, subscription, businessKey, "RESPONSE");
                            }
                            return;
                        }

                        if (partnerWaitingNow) {
                            partnerArrivalTicks.remove(waitKey);
                            EventSubscription responderSubscription = waitingForSameSignal.stream()
                                    .filter(s -> s.getProcessInstanceId().equals(partnerInstanceId))
                                    .findFirst()
                                    .orElse(null);
                            if (responderSubscription != null) {
                                deliverTo(signalName, responderSubscription, businessKey, "REQUEST");
                                awaitingResponse.add(handoffKey);
                            }
                            return;
                        }

                        // Partner (the responder) is not currently waiting on this exact signal and no handoff is in flight for it. Same distinction as the responder branch above: fall back to immediate delivery once partnerNotComing says so - a rework-loop revisit, or a signal that exists in only this side's BPMN at all (the responder is a real, registered partner that will simply never co-wait on it). Otherwise the responder may simply not have reached this signal yet - wait rather than race ahead of it.
                        if (partnerNotComing(waitKey, partnerInstanceId, signalName)) {
                            deliverTo(signalName, subscription, businessKey, "DELIVERED");
                        }
                    }

                    // True once waiting for the partner has gone on long enough to conclude it is not coming to THIS exact signal - either because it already has (everDelivered), or because MAX_PARTNER_ARRIVAL_TICKS consecutive ticks have passed without it showing up (see partnerArrivalTicks's own field comment for why a bound is needed at all). waitKey identifies the WAITING execution+signal, not the partner, so concurrent pairs and different signals never share a counter.
                    private boolean partnerNotComing(String waitKey, String partnerInstanceId, String signalName) {
                        if (everDelivered.contains(partnerInstanceId + "|" + signalName)) {
                            partnerArrivalTicks.remove(waitKey);
                            return true;
                        }
                        int ticks = partnerArrivalTicks.merge(waitKey, 1, Integer::sum);
                        if (ticks >= MAX_PARTNER_ARRIVAL_TICKS) {
                            partnerArrivalTicks.remove(waitKey);
                            return true;
                        }
                        return false;
                    }

                    // True once the responder has provably moved past the gated task behind signalName - subscribed to a different signal, or completed entirely - rather than merely having received the signal itself, which happens before its gated task ever runs. Relies on the responder's own JavaDelegate.execute() running synchronously, inside the same Camunda command/transaction as the signal delivery that triggers it - only that makes "no longer subscribed to signalName" (checked below via a separate query, on a later broadcaster tick) proof that the gated task actually finished, rather than merely that it started. A delegate that hands work to another thread and returns early would make this method return true before the real work is done.
                    private boolean responderHasAdvancedPast(String signalName, String responderInstanceId) {
                        ProcessInstance stillActive = runtimeService.createProcessInstanceQuery()
                                .processInstanceId(responderInstanceId)
                                .singleResult();
                        if (stillActive == null) {
                            return true;
                        }
                        List<EventSubscription> responderSignals = runtimeService.createEventSubscriptionQuery()
                                .processInstanceId(responderInstanceId)
                                .eventType("signal")
                                .list();
                        boolean stillOnSameSignal = responderSignals.stream()
                                .anyMatch(s -> s.getEventName().equals(signalName));
                        if (stillOnSameSignal) {
                            return false;
                        }
                        return !responderSignals.isEmpty();
                    }

                    // Routes a REQUEST through the gated Twin activity's own task queue and a RESPONSE through that same activity's response queue (see RabbitMqConfig.TOPIC_BY_SIGNAL), when messaging is enabled and this signal is provably gated to one - i.e. it is a genuine Main<->Twin communication activity, not a signal declared on only one side. DELIVERED (the unpaired/fallback phase) always delivers directly: it does not represent a real cross-process handoff, so it must never touch RabbitMQ even when messaging is enabled. A missed delivery because the execution already moved on is normal - not an error.
                    private void deliverTo(String signalName, EventSubscription subscription, String businessKey,
                            String phase) {
                        String gatedTopic = RabbitMqConfig.TOPIC_BY_SIGNAL.get(signalName);
                        if (gatedTopic != null) {
                            if ("REQUEST".equals(phase) && taskQueuePublisher.isEnabled()
                                    && taskQueuePublisher.isEligible(gatedTopic)) {
                                taskQueuePublisher.publish(gatedTopic, signalName, subscription.getExecutionId(),
                                        subscription.getProcessInstanceId(), businessKey);
                                everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
                                return;
                            }
                            if ("RESPONSE".equals(phase) && responseQueuePublisher.isEnabled()
                                    && responseQueuePublisher.isEligible(gatedTopic)) {
                                responseQueuePublisher.publish(gatedTopic, signalName, subscription.getExecutionId(),
                                        subscription.getProcessInstanceId(), businessKey);
                                everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
                                return;
                            }
                        }
                        try {
                            runtimeService.signalEventReceived(signalName, subscription.getExecutionId());
                            everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
                            logger.info("{}: delivered signal '{}' to execution {} (processInstanceId={}, "
                                    + "businessKey={})", phase, signalName, subscription.getExecutionId(),
                                    subscription.getProcessInstanceId(), businessKey);
                        } catch (Exception e) {
                            // Expected during normal operation - see this method's own comment.
                        }
                    }
                }
                """.formatted(subPackage, coordinationPackage, messagingImports, signalList);

        Path packageDir = projectDir.resolve("src/main/java").resolve(subPackage.replace('.', '/'));
        writeFile(packageDir.resolve("SignalBroadcaster.java"), source);
    }

    // Generic Main/Twin pairing keyed only on the caller-supplied business key every generated /start
    // endpoint already accepts - no process-specific knowledge. The first instance to register a key is
    // the initiator, the next one to register the same key is the responder.
    private void writePairRegistry(Path projectDir, String basePackage) {
        String subPackage = basePackage + ".coordination";
        String source = """
                package %s;

                import java.util.concurrent.ConcurrentHashMap;
                import java.util.concurrent.ConcurrentMap;

                import org.springframework.stereotype.Component;

                // See SpringBootProjectGenerator.writePairRegistry for the rationale behind this class.
                @Component
                public class PairRegistry {

                    private final ConcurrentMap<String, String> initiators = new ConcurrentHashMap<>();
                    private final ConcurrentMap<String, String> responders = new ConcurrentHashMap<>();

                    // Returns "initiator" for the first instance registered under businessKey, "responder" for the second, and null for a blank key or a third-or-later instance sharing an already-claimed key (outside what this registry pairs; callers should treat that as unpaired and fall back to their own default behavior).
                    public String registerAndClassify(String businessKey, String processInstanceId) {
                        if (businessKey == null || businessKey.isBlank()) {
                            return null;
                        }
                        String initiator = initiators.putIfAbsent(businessKey, processInstanceId);
                        if (initiator == null || initiator.equals(processInstanceId)) {
                            return "initiator";
                        }
                        String responder = responders.putIfAbsent(businessKey, processInstanceId);
                        if (responder == null || responder.equals(processInstanceId)) {
                            return "responder";
                        }
                        return null;
                    }

                    // The other half of the pair for this business key, or null if unpaired (only one instance has registered so far, or this instance/key isn't tracked at all).
                    public String partnerOf(String businessKey, String processInstanceId) {
                        if (businessKey == null || businessKey.isBlank()) {
                            return null;
                        }
                        String initiator = initiators.get(businessKey);
                        String responder = responders.get(businessKey);
                        if (processInstanceId.equals(initiator)) {
                            return responder;
                        }
                        if (processInstanceId.equals(responder)) {
                            return initiator;
                        }
                        return null;
                    }

                    public String roleOf(String businessKey, String processInstanceId) {
                        if (businessKey == null || businessKey.isBlank()) {
                            return null;
                        }
                        if (processInstanceId.equals(initiators.get(businessKey))) {
                            return "initiator";
                        }
                        if (processInstanceId.equals(responders.get(businessKey))) {
                            return "responder";
                        }
                        return null;
                    }
                }
                """.formatted(subPackage);

        Path packageDir = projectDir.resolve("src/main/java").resolve(subPackage.replace('.', '/'));
        writeFile(packageDir.resolve("PairRegistry.java"), source);
    }

    // Generates the GeneratedExternalTaskWorker interface that all generated workers implement. This replaces the external-task client's ExternalTaskHandler — workers use the embedded engine's ExternalTaskService directly, avoiding the REST/Jersey incompatibility with SB 4.x.
    private void writeWorkerInterface(Path projectDir, String basePackage) {
        String workerPackage = basePackage + ".worker";
        String source = """
                package %s;

                import org.camunda.bpm.engine.ExternalTaskService;
                import org.camunda.bpm.engine.externaltask.LockedExternalTask;

                // Contract for generated external-task workers. Each worker handles one topic via the embedded engine's ExternalTaskService API (not the HTTP-based external-task client, which requires Jersey and is incompatible with Spring Boot 4.x).
                public interface GeneratedExternalTaskWorker {

                    String topic();

                    void execute(LockedExternalTask task, ExternalTaskService externalTaskService);
                }
                """.formatted(workerPackage);
        Path packageDir = projectDir.resolve("src/main/java").resolve(workerPackage.replace('.', '/'));
        writeFile(packageDir.resolve("GeneratedExternalTaskWorker.java"), source);
    }

    // The pluggable decision boundary every Twin worker calls instead of hardcoding a simulation inline.
    // Lands in the twin workers' own package so the generated worker needs no import for it.
    // Deliberately not a @ConditionalOnMissingBean default @Component: that is only honoured reliably
    // inside @Configuration classes, so on a component-scanned bean the fallback never registers at all
    // and the generated app fails to start.
    private void writeTwinDecisionAgentInterface(Path projectDir, String twinWorkerPackage) {
        String interfaceSource = """
                package %1$s;

                import java.util.Map;

                import org.camunda.bpm.engine.externaltask.LockedExternalTask;

                // Pluggable decision boundary for every Twin worker. With no implementation registered, the generated worker falls back to synthetic, non-deterministic output so the twin process can still run standalone (see the worker's own execute() - it injects this via ObjectProvider, not directly, precisely so zero implementations is a supported, non-fatal case). To have the twin mirror real business intelligence - e.g. a risk-scoring model that predicts what the real (proxy) process would decide - register your own @Component implementing this interface; every generated Twin worker starts calling it instead, with no generated code to change.
                public interface TwinDecisionAgent {

                    // topic: the external-task topic being completed (e.g. "VerifyOrderTwin") - lets one implementation branch on which BPMN activity it's deciding for. task: the locked external task itself, for id/businessKey/variable access. Returns the process variables to complete the task with. If this topic feeds an exclusive gateway's condition, include that variable in the result if you can - the calling worker fills in any the agent leaves out with a non-deterministic fallback so the process never throws PropertyNotFoundException, but a real implementation should be the one deciding it.
                    Map<String, Object> decide(String topic, LockedExternalTask task);
                }
                """.formatted(twinWorkerPackage);

        Path packageDir = projectDir.resolve("src/main/java").resolve(twinWorkerPackage.replace('.', '/'));
        writeFile(packageDir.resolve("TwinDecisionAgent.java"), interfaceSource);
    }

    // Generates a scheduled poller that drives all GeneratedExternalTaskWorker beans. On each tick it calls fetchAndLock for every registered topic, dispatches locked tasks to the matching worker, and catches per-task exceptions so one failure doesn't stall the others.
    private void writeExternalTaskPoller(Path projectDir, String basePackage) {
        String workerPackage = basePackage + ".worker";
        boolean targetPlatformPortal = isTargetPlatformTemplate();
        String portalImports = targetPlatformPortal ? """
                import %s.portal.RunExecutionGate;
                import %s.portal.StandaloneCapabilityResolver;

                """.formatted(basePackage, basePackage) : "";
        String portalFields = targetPlatformPortal ? """
                    private final RunExecutionGate executionGate;
                    private final StandaloneCapabilityResolver capabilityResolver;
                """ : "";
        String portalConstructorParameters = targetPlatformPortal ? """
                            RunExecutionGate executionGate,
                            StandaloneCapabilityResolver capabilityResolver,
                """ : "";
        String portalConstructorAssignments = targetPlatformPortal ? """
                        this.executionGate = executionGate;
                        this.capabilityResolver = capabilityResolver;
                """ : "";
        String beforeWorkerExecution = targetPlatformPortal ? """
                                        if (!executionGate.mayExecute(task)) {
                                            externalTaskService.unlock(task.getId());
                                            continue;
                                        }
                                        capabilityResolver.resolveIfUnambiguous(task);
                """ : "";
        String afterWorkerExecution = targetPlatformPortal ? "executionGate.afterExecution(task);" : "";
        String source = """
                package %s;

                import java.util.List;

                import org.camunda.bpm.engine.ExternalTaskService;
                import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                %s

                // Polls all registered external-task topics and dispatches locked tasks to the matching GeneratedExternalTaskWorker. Uses the embedded engine's ExternalTaskService directly (fetchAndLock + complete) instead of the HTTP-based external-task client starter, which depends on Jersey — incompatible with Spring Boot 4.x.
                @Component
                public class ExternalTaskPoller {

                    private static final Logger logger = LoggerFactory.getLogger(ExternalTaskPoller.class);
                    private static final String WORKER_ID = "generated-worker";
                    private static final long LOCK_DURATION_MS = 10_000L;
                    // Backoff between retries of one task, and the delay before the poller's own next tick picks it back up - no job executor is running, so this poller's own polling cadence IS the retry mechanism (see handleWorkerFailure below).
                    private static final long RETRY_BACKOFF_MS = 2_000L;

                    private final ExternalTaskService externalTaskService;
                    private final List<GeneratedExternalTaskWorker> workers;
                %s
                    private final int maxRetries;

                    public ExternalTaskPoller(ExternalTaskService externalTaskService,
                            List<GeneratedExternalTaskWorker> workers,
                %s
                            @org.springframework.beans.factory.annotation.Value(
                                    "${metaml.worker.max-retries:3}") int maxRetries) {
                        this.externalTaskService = externalTaskService;
                        this.workers = workers;
                %s
                        this.maxRetries = maxRetries;
                        logger.info("ExternalTaskPoller initialized with {} worker(s): {} (maxRetries={})",
                                workers.size(), workers.stream().map(GeneratedExternalTaskWorker::topic).toList(),
                                maxRetries);
                    }

                    @Scheduled(fixedDelay = 500)
                    public void poll() {
                        for (GeneratedExternalTaskWorker worker : workers) {
                            try {
                                List<LockedExternalTask> tasks = externalTaskService.fetchAndLock(10, WORKER_ID)
                                        .topic(worker.topic(), LOCK_DURATION_MS)
                                        .execute();
                                for (LockedExternalTask task : tasks) {
                                    try {
                %s
                                        worker.execute(task, externalTaskService);
                                        %s
                                    } catch (Exception e) {
                                        handleWorkerFailure(worker, task, e);
                                    }
                                }
                            } catch (Exception e) {
                                // Topic may not have any pending tasks — expected during normal operation
                            }
                        }
                    }

                    // A task's current remaining retries is null until its first failure (Camunda's own convention), at which point maxRetries is the starting budget. Each subsequent failure decrements it by one. At zero, Camunda marks the task an incident and this poller's own fetchAndLock naturally stops returning it - the same "no job executor" reasoning that lets a positive retry count self-heal (its lockExpirationTime is pushed out by RETRY_BACKOFF_MS, and this poller's next tick past that point re-fetches it on its own, with no separate retry-timer infrastructure needed) also makes zero retries a real, generic dead-letter state rather than a silent stall: it is visible via ExternalTaskService/ExternalTaskQuery, not merely logged.
                    private void handleWorkerFailure(GeneratedExternalTaskWorker worker, LockedExternalTask task,
                            Exception e) {
                        Integer currentRetries = task.getRetries();
                        int remaining = (currentRetries == null ? maxRetries : currentRetries) - 1;
                        if (remaining > 0) {
                            logger.warn("Worker {} failed on task {} ({} retries remaining): {}",
                                    worker.topic(), task.getId(), remaining, e.getMessage(), e);
                        } else {
                            logger.error("Worker {} failed on task {} - no retries remaining, task now has an "
                                    + "incident: {}", worker.topic(), task.getId(), e.getMessage(), e);
                        }
                        externalTaskService.handleFailure(task.getId(), WORKER_ID, e.getMessage(),
                                Math.max(remaining, 0), RETRY_BACKOFF_MS);
                    }
                }
                """.formatted(workerPackage, portalImports, portalFields, portalConstructorParameters,
                portalConstructorAssignments, beforeWorkerExecution, afterWorkerExecution);
        Path packageDir = projectDir.resolve("src/main/java").resolve(workerPackage.replace('.', '/'));
        writeFile(packageDir.resolve("ExternalTaskPoller.java"), source);
    }

    // Enables scheduling for the generated platform with a small configurable pool (metaml.scheduling.
    // pool-size) rather than Spring's default single thread - otherwise ExternalTaskPoller and
    // SignalBroadcaster serialise, and one slow topic delays every other topic and all broadcasting.
    private void writeSchedulingConfig(Path projectDir, String basePackage) {
        String workerPackage = basePackage + ".worker";
        String source = """
                package %s;

                import java.util.concurrent.Executors;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.TaskScheduler;
                import org.springframework.scheduling.annotation.EnableScheduling;
                import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

                // Turns on @Scheduled generically for the whole generated platform (ExternalTaskPoller, SignalBroadcaster when present, and any future scheduled component), with a small configurable thread pool rather than Spring Boot's single-thread scheduler default.
                @Configuration
                @EnableScheduling
                public class SchedulingConfig {

                    @Bean
                    public TaskScheduler taskScheduler(
                            @Value("${metaml.scheduling.pool-size:4}") int poolSize) {
                        return new ConcurrentTaskScheduler(Executors.newScheduledThreadPool(poolSize));
                    }
                }
                """.formatted(workerPackage);
        Path packageDir = projectDir.resolve("src/main/java").resolve(workerPackage.replace('.', '/'));
        writeFile(packageDir.resolve("SchedulingConfig.java"), source);
    }

    // Read-only introspection for any process instance in the generated engine, so tests and callers can
    // assert against real runtime state - active activity ids and variables from RuntimeService - rather
    // than log text. Generated once per project, since it takes a processInstanceId at call time.
    private void writeProcessStatusController(Path projectDir, String basePackage) {
        String source = """
                package %1$s.status;

                import java.util.HashMap;
                import java.util.List;
                import java.util.Map;

                import org.camunda.bpm.engine.ExternalTaskService;
                import org.camunda.bpm.engine.HistoryService;
                import org.camunda.bpm.engine.RuntimeService;
                import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                import org.camunda.bpm.engine.runtime.ProcessInstance;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                // Generic, read-only process-instance introspection - works for any deployed process, not just one this project's own generated controllers know about. Backs causal test assertions against real engine state (active activities, process variables, business key) rather than log text. Reliability hardening (Pass 2): also exposes genuine Camunda incident state (not simulated) - both to read it (incidents(), the same real state SignalBroadcaster.awaitingResponse's stuck-partner detection now checks) and, since this generated project has no camunda-bpm-spring-boot-starter-rest dependency of its own (no /engine-rest to reach for this), to deliberately induce and later resolve a REAL incident for failure-injection testing (failExternalTaskPermanently / retryExternalTask). Neither touches Proxy/Twin synchronization logic itself - both operate on whatever process instance/external task the caller names.
                @RestController
                @RequestMapping("/api/v1/process")
                public class GeneratedProcessStatusController {

                    private final RuntimeService runtimeService;
                    private final HistoryService historyService;
                    private final ExternalTaskService externalTaskService;

                    public GeneratedProcessStatusController(RuntimeService runtimeService,
                            HistoryService historyService, ExternalTaskService externalTaskService) {
                        this.runtimeService = runtimeService;
                        this.historyService = historyService;
                        this.externalTaskService = externalTaskService;
                    }

                    @GetMapping("/{processInstanceId}/status")
                    public ResponseEntity<Map<String, Object>> status(@PathVariable String processInstanceId) {
                        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                                .processInstanceId(processInstanceId)
                                .singleResult();
                        if (instance == null) {
                            Map<String, Object> inactive = new HashMap<>();
                            inactive.put("active", false);
                            return ResponseEntity.ok(inactive);
                        }
                        List<String> activeActivityIds = runtimeService.getActiveActivityIds(processInstanceId);
                        Map<String, Object> variables = runtimeService.getVariables(processInstanceId);
                        Map<String, Object> body = new HashMap<>();
                        body.put("active", true);
                        body.put("activeActivityIds", activeActivityIds);
                        body.put("variables", variables);
                        body.put("businessKey", instance.getBusinessKey());
                        return ResponseEntity.ok(body);
                    }

                    // How many times this process instance has ever entered the given BPMN activity, whether still active or long since completed - authoritative proof of a rework loop (or any other repeat visit), independent of process definition or activity shape. Uses HistoryService (camunda.bpm.history-level=full by default), not the in-memory active-activity view, precisely because a repeat visit's earlier instances are no longer "active" by the time anyone asks.
                    @GetMapping("/{processInstanceId}/activity-history/{activityId}/count")
                    public ResponseEntity<Map<String, Object>> activityVisitCount(
                            @PathVariable String processInstanceId, @PathVariable String activityId) {
                        long count = historyService.createHistoricActivityInstanceQuery()
                                .processInstanceId(processInstanceId)
                                .activityId(activityId)
                                .count();
                        Map<String, Object> body = new HashMap<>();
                        body.put("processInstanceId", processInstanceId);
                        body.put("activityId", activityId);
                        body.put("visitCount", count);
                        return ResponseEntity.ok(body);
                    }

                    // Real Camunda incident state for a process instance - the same query SignalBroadcaster's own stuck-partner detection (Pass 2) runs, exposed read-only so a caller (a test, an operator) can see it too instead of only inferring it from logs.
                    @GetMapping("/{processInstanceId}/incidents/count")
                    public ResponseEntity<Map<String, Object>> incidentCount(
                            @PathVariable String processInstanceId) {
                        long count = runtimeService.createIncidentQuery()
                                .processInstanceId(processInstanceId)
                                .count();
                        Map<String, Object> body = new HashMap<>();
                        body.put("processInstanceId", processInstanceId);
                        body.put("incidentCount", count);
                        return ResponseEntity.ok(body);
                    }

                    // Test-support: deliberately fails a real, currently-lockable external task for the given topic on the given process instance, with retries=0 - this is a genuine Camunda incident (job retries exhausted), not a simulated one, produced through the same ExternalTaskService API a real worker uses, just reporting failure instead of completing. Exists because this generated project has no /engine-rest of its own to do this from outside the JVM.
                    @PostMapping("/{processInstanceId}/external-task/{topic}/fail-permanently")
                    public ResponseEntity<Map<String, Object>> failExternalTaskPermanently(
                            @PathVariable String processInstanceId, @PathVariable String topic) {
                        // ExternalTaskQueryTopicBuilder has no processInstanceId filter of its own (only businessKey/processDefinitionId/Key) - resolving the instance's own business key first and filtering on THAT is what actually scopes this to the right instance, not just the right topic (which alone would still work for a single pair, but not when more than one pair shares a topic name concurrently).
                        ProcessInstance target = runtimeService.createProcessInstanceQuery()
                                .processInstanceId(processInstanceId)
                                .singleResult();
                        if (target == null) {
                            Map<String, Object> notFound = new HashMap<>();
                            notFound.put("error", "no active process instance " + processInstanceId);
                            return ResponseEntity.status(404).body(notFound);
                        }
                        List<LockedExternalTask> locked = externalTaskService
                                .fetchAndLock(1, "test-failure-injector")
                                .topic(topic, 60000)
                                .businessKey(target.getBusinessKey())
                                .execute();
                        if (locked.isEmpty()) {
                            Map<String, Object> notFound = new HashMap<>();
                            notFound.put("error", "no lockable external task for topic '" + topic
                                    + "' on process instance " + processInstanceId);
                            return ResponseEntity.status(404).body(notFound);
                        }
                        String externalTaskId = locked.get(0).getId();
                        externalTaskService.handleFailure(externalTaskId, "test-failure-injector",
                                "Deliberately failed by a test to produce a real Camunda incident", 0, 0L);
                        Map<String, Object> body = new HashMap<>();
                        body.put("externalTaskId", externalTaskId);
                        body.put("topic", topic);
                        body.put("processInstanceId", processInstanceId);
                        return ResponseEntity.ok(body);
                    }

                    // Test-support: the real recovery path for the incident failExternalTaskPermanently produces - restoring retries is what lets Camunda's own job executor pick the external task back up and, since the generated worker's own logic never deliberately fails, complete it normally on the next attempt. Genuine recovery through real Camunda mechanics, not a test-only shortcut that pretends the task completed.
                    @PostMapping("/external-task/{externalTaskId}/retry")
                    public ResponseEntity<Map<String, Object>> retryExternalTask(
                            @PathVariable String externalTaskId) {
                        externalTaskService.setRetries(externalTaskId, 3);
                        Map<String, Object> body = new HashMap<>();
                        body.put("externalTaskId", externalTaskId);
                        body.put("retriesSet", 3);
                        return ResponseEntity.ok(body);
                    }
                }
                """.formatted(basePackage);
        Path packageDir = projectDir.resolve("src/main/java").resolve((basePackage + ".status").replace('.', '/'));
        writeFile(packageDir.resolve("GeneratedProcessStatusController.java"), source);
    }

    private void writeDelegateFile(Path packageDir, String className, String sourceCode, String beanName,
            String bpmnElementId) {
        Path target = packageDir.resolve(className + ".java");
        // not the shared writeFile() helper below - a failure here is attributable to this one delegate specifically, which writeFile's own generic UncheckedIOException has no way to say (see DelegateWriteException's own comment)
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, sourceCode, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DelegateWriteException("Could not write " + target.toAbsolutePath(), e, beanName,
                    bpmnElementId);
        }
    }

    // One endpoint per activity; bridgeMethod selects the NotificationBridge side.
    private void writeController(Path projectDir, String basePackage, String subPackage, String className,
            String requestMapping, String processKey, List<BpmnActivities.Activity> activities,
            String bridgeMethod) {
        Set<BpmnActivities.Trigger> triggers = activities.stream()
                .map(BpmnActivities.Activity::trigger)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        StringBuilder endpoints = new StringBuilder();
        for (BpmnActivities.Activity activity : activities) {
            endpoints.append(renderActivityEndpoint(activity, bridgeMethod));
        }

        // Include only helpers the activity triggers actually use.
        StringBuilder helpers = new StringBuilder();
        if (triggers.contains(BpmnActivities.Trigger.USER_TASK)) {
            helpers.append(USER_TASK_HELPER);
        }
        if (triggers.contains(BpmnActivities.Trigger.RECEIVE_TASK)) {
            helpers.append(RECEIVE_TASK_HELPER);
        }
        if (triggers.contains(BpmnActivities.Trigger.EXTERNAL_TASK)) {
            helpers.append(EXTERNAL_TASK_HELPER);
        }
        if (!activities.isEmpty()) {
            helpers.append(RESPOND_HELPER);
        }

        // Unresolvable unused bean fails startup; inject only when needed.
        boolean needsExternalTaskService = triggers.contains(BpmnActivities.Trigger.EXTERNAL_TASK);
        String externalImport = needsExternalTaskService
                ? """
                        import org.camunda.bpm.engine.ExternalTaskService;
                        import org.camunda.bpm.engine.externaltask.ExternalTask;
                        """
                : "";
        String executionImport = triggers.contains(BpmnActivities.Trigger.RECEIVE_TASK)
                ? "import org.camunda.bpm.engine.runtime.Execution;\n"
                : "";
        String taskImport = triggers.contains(BpmnActivities.Trigger.USER_TASK)
                ? "import org.camunda.bpm.engine.task.Task;\n"
                : "";
        String externalField = needsExternalTaskService
                ? "    private final ExternalTaskService externalTaskService;\n"
                : "";
        String externalParam = needsExternalTaskService ? ", ExternalTaskService externalTaskService" : "";
        String externalAssignment = needsExternalTaskService
                ? "        this.externalTaskService = externalTaskService;\n"
                : "";

        String source = """
                package %s.%s;

                import java.util.ArrayList;
                import java.util.HashMap;
                import java.util.List;
                import java.util.Map;

                import org.camunda.bpm.engine.RuntimeService;
                import org.camunda.bpm.engine.TaskService;
                %simport org.camunda.bpm.engine.runtime.ProcessInstance;
                %s%simport org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                import %s.bridge.NotificationBridge;
                import %s.coordination.PairRegistry;

                // Generated for process key "%s" - not hand-written, don't hand-edit; regenerate instead. One endpoint per externally-triggerable BPMN activity, generated from the model itself. Calls NotificationBridge.%s after completing each activity (see NotificationBridge).
                @RestController
                @RequestMapping("%s")
                public class %s {

                    private final RuntimeService runtimeService;
                    private final TaskService taskService;
                    private final NotificationBridge notificationBridge;
                    private final PairRegistry pairRegistry;
                %s
                    public %s(RuntimeService runtimeService, TaskService taskService,
                            NotificationBridge notificationBridge, PairRegistry pairRegistry%s) {
                        this.runtimeService = runtimeService;
                        this.taskService = taskService;
                        this.notificationBridge = notificationBridge;
                        this.pairRegistry = pairRegistry;
                %s    }

                    // businessKey is optional and generic - it is not a BPMN concept, it is how a caller that is starting a Main+Twin PAIR can make that pairing explicit and queryable (start Main, then start Twin with the same key). Omitting it preserves the exact previous behavior (an unkeyed instance) for callers that only need one instance. "role" in the response comes from PairRegistry, derived purely from arrival order under a shared business key - the first instance to register a key is "initiator", the next is "responder". It is omitted when no business key is supplied. This is pairing/observability metadata only, not the communication mechanism itself - see PairRegistry and SignalBroadcaster for how initiator/responder roles turn each shared signal into a real, targeted Main -> Twin -> Main handoff.
                    @PostMapping("/start")
                    public ResponseEntity<Map<String, String>> start(
                            @RequestParam(required = false) String businessKey) {
                        ProcessInstance instance = (businessKey == null || businessKey.isBlank())
                                ? runtimeService.startProcessInstanceByKey("%s")
                                : runtimeService.startProcessInstanceByKey("%s", businessKey);
                        Map<String, String> body = new HashMap<>();
                        body.put("processInstanceId", instance.getId());
                        body.put("businessKey", instance.getBusinessKey());
                        String role = pairRegistry.registerAndClassify(instance.getBusinessKey(), instance.getId());
                        if (role != null) {
                            body.put("role", role);
                        }
                        return ResponseEntity.ok(body);
                    }
                %s%s}
                """.formatted(basePackage, subPackage, externalImport, executionImport, taskImport, basePackage,
                basePackage, processKey, bridgeMethod, requestMapping, className, externalField, className,
                externalParam, externalAssignment, processKey, processKey, endpoints, helpers);
        Path packageDir = projectDir.resolve("src/main/java")
                .resolve((basePackage + "." + subPackage).replace('.', '/'));
        writeFile(packageDir.resolve(className + ".java"), source);
    }

    // One endpoint per activity; the activity ID is a literal so a slug mismatch cannot reach the wrong task.
    private static String renderActivityEndpoint(BpmnActivities.Activity activity, String bridgeMethod) {
        String label = activity.name() == null || activity.name().isBlank()
                ? "(unnamed activity)"
                : sanitizeForComment(activity.name());
        return """

                    // BPMN activity "%s" (id: %s), triggered as %s
                    @PostMapping("/{processInstanceId}/%s/complete")
                    public ResponseEntity<Map<String, List<String>>> complete%s(@PathVariable String processInstanceId) {
                        ResponseEntity<Map<String, List<String>>> response = %s(processInstanceId, "%s");
                        notificationBridge.%s(processInstanceId, "%s");
                        return response;
                    }
                """.formatted(label, activity.id(), activity.trigger(), activity.endpointSlug(),
                activity.methodSuffix(), helperMethodFor(activity.trigger()), activity.id(), bridgeMethod,
                activity.id());
    }

    private static String helperMethodFor(BpmnActivities.Trigger trigger) {
        return switch (trigger) {
            case USER_TASK -> "completeUserTask";
            case RECEIVE_TASK -> "signalReceiveTask";
            case EXTERNAL_TASK -> "completeExternalTask";
        };
    }

    // BPMN names can contain embedded newlines (loanApproval.bpmn: "Calculate\nInterest"); collapse to spaces for inline comments.
    private static String sanitizeForComment(String label) {
        return label.replaceAll("\\s+", " ").trim();
    }

    // Query by BPMN element ID; multi-instance activities may have multiple open tasks.
    private static final String USER_TASK_HELPER = """

                private ResponseEntity<Map<String, List<String>>> completeUserTask(String processInstanceId,
                        String activityId) {
                    List<Task> openTasks = taskService.createTaskQuery()
                            .processInstanceId(processInstanceId)
                            .taskDefinitionKey(activityId)
                            .list();
                    List<String> completed = new ArrayList<>();
                    for (Task task : openTasks) {
                        taskService.complete(task.getId());
                        completed.add(task.getId());
                    }
                    return respond(completed);
                }
            """;

    // Receive tasks have no Task row; signal the parked execution by activity ID.
    private static final String RECEIVE_TASK_HELPER = """

                private ResponseEntity<Map<String, List<String>>> signalReceiveTask(String processInstanceId,
                        String activityId) {
                    List<Execution> waiting = runtimeService.createExecutionQuery()
                            .processInstanceId(processInstanceId)
                            .activityId(activityId)
                            .list();
                    List<String> triggered = new ArrayList<>();
                    for (Execution execution : waiting) {
                        runtimeService.signal(execution.getId());
                        triggered.add(execution.getId());
                    }
                    return respond(triggered);
                }
            """;

    // External tasks require the worker to hold the lock before completion.
    private static final String EXTERNAL_TASK_HELPER = """

                // this controller is the worker, so the id only has to be stable and identifiable
                private static final String WORKER_ID = "generated-process-controller";
                // long enough to survive the two calls below, short enough that a crash between them frees the task again rather than stranding it
                private static final long LOCK_MILLIS = 10_000L;

                private ResponseEntity<Map<String, List<String>>> completeExternalTask(String processInstanceId,
                        String activityId) {
                    List<ExternalTask> pending = externalTaskService.createExternalTaskQuery()
                            .processInstanceId(processInstanceId)
                            .activityId(activityId)
                            .list();
                    List<String> completed = new ArrayList<>();
                    for (ExternalTask task : pending) {
                        externalTaskService.lock(task.getId(), WORKER_ID, LOCK_MILLIS);
                        externalTaskService.complete(task.getId(), WORKER_ID);
                        completed.add(task.getId());
                    }
                    return respond(completed);
                }
            """;

    // No matching task means the process is not currently at this activity.
    private static final String RESPOND_HELPER = """

                private ResponseEntity<Map<String, List<String>>> respond(List<String> touched) {
                    if (touched.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("completed", touched));
                    }
                    return ResponseEntity.ok(Map.of("completed", touched));
                }
            """;

    private static Set<String> extractExecutionListenerBeanNames(String bpmnXml) {
        Set<String> beanNames = new LinkedHashSet<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "delegateExpression=\"\\$\\{(\\w+)\\}\"");
        java.util.regex.Matcher matcher = pattern.matcher(bpmnXml);
        while (matcher.find()) {
            beanNames.add(matcher.group(1));
        }
        return beanNames;
    }

    private void writeExecutionListenerStub(Path projectDir, String basePackage, String beanName) {
        String listenerPackage = basePackage + ".listener";
        String className = Character.toUpperCase(beanName.charAt(0)) + beanName.substring(1);
        String source = """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateExecution;
                import org.camunda.bpm.engine.delegate.ExecutionListener;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                // Stub for BPMN delegateExpression "${%s}". Replace with real implementation.
                @Component("%s")
                public class %s implements ExecutionListener {

                    private static final Logger logger = LoggerFactory.getLogger(%s.class);

                    @Override
                    public void notify(DelegateExecution execution) throws Exception {
                        logger.info("Execution listener '%s' fired for activity '{}' in process instance {}",
                                execution.getCurrentActivityName(), execution.getProcessInstanceId());
                    }
                }
                """.formatted(listenerPackage, beanName, beanName, className, className, beanName);
        Path packageDir = projectDir.resolve("src/main/java").resolve(listenerPackage.replace('.', '/'));
        writeFile(packageDir.resolve(className + ".java"), source);
    }

    private static void writeFile(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + target.toAbsolutePath(), e);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("Could not remove template placeholder {}: {}", path.toAbsolutePath(), e.toString());
        }
    }
}
