package io.quarkiverse.jberet.deployment;

import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jberet.creation.ArchiveXmlLoader;
import org.jberet.creation.BatchBeanProducer;
import org.jberet.job.model.Decision;
import org.jberet.job.model.Flow;
import org.jberet.job.model.Job;
import org.jberet.job.model.RefArtifact;
import org.jberet.job.model.Split;
import org.jberet.job.model.Step;
import org.jberet.job.model.Transition;
import org.jberet.tools.MetaInfBatchJobsJobXmlResolver;
import org.jboss.logging.Logger;

import io.quarkiverse.jberet.runtime.JBeretProducer;
import io.quarkiverse.jberet.runtime.JBeretRecorder;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CapabilityBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationSourceValueBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.recording.RecorderContext;
import io.quarkus.deployment.util.GlobUtil;
import io.quarkus.runtime.util.ClassPathUtils;

public class JBeretProcessor {
    private static final Logger log = Logger.getLogger("io.quarkiverse.jberet");

    @BuildStep
    public void registerExtension(BuildProducer<FeatureBuildItem> feature, BuildProducer<CapabilityBuildItem> capability) {
        feature.produce(new FeatureBuildItem("jberet"));
    }

    /**
     * Prevent JobOperatorContext$DefaultHolder from eagerly initializing because it depends on a ServiceLoader
     * entry for the BatchRuntime, which we don't use. With this trigger turned off, it won't ever be initialized.
     */
    @BuildStep
    public RuntimeInitializedClassBuildItem runtimeInitializedDefaultHolder() {
        return new RuntimeInitializedClassBuildItem("org.jberet.spi.JobOperatorContext$DefaultHolder");
    }

    @BuildStep
    public void additionalBeans(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        additionalBeans.produce(new AdditionalBeanBuildItem(BatchBeanProducer.class));
        additionalBeans.produce(new AdditionalBeanBuildItem(JBeretProducer.class));
    }

    @BuildStep
    public void loadJobs(
            JBeretBuildTimeConfig config,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
            BuildProducer<BatchJobBuildItem> batchJobs) throws Exception {

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        MetaInfBatchJobsJobXmlResolver jobXmlResolver = new MetaInfBatchJobsJobXmlResolver();

        ClassPathUtils.consumeAsPaths("META-INF/batch-jobs", path -> {
            Set<String> batchFilesNames = findBatchFilesFromPath(path, toPatterns(config.jobs.includes),
                    toPatterns(config.jobs.excludes));
            List<Job> loadedJobs = new ArrayList<>();

            batchFilesNames.forEach(jobXmlName -> {
                Job job = ArchiveXmlLoader.loadJobXml(jobXmlName, contextClassLoader, loadedJobs, jobXmlResolver);
                job.setJobXmlName(jobXmlName);
                watchedFiles.produce(new HotDeploymentWatchedFileBuildItem("META-INF/batch-jobs/" + jobXmlName + ".xml"));
                batchJobs.produce(new BatchJobBuildItem(job));
                log.debug("Processed job with ID " + job.getId() + "  from file " + jobXmlName);
            });
        });
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    public void registerJobs(
            RecorderContext recorderContext,
            JBeretRecorder recorder,
            List<BatchJobBuildItem> batchJobs) throws Exception {
        registerNonDefaultConstructors(recorderContext);

        recorder.registerJobs(batchJobs.stream().map(BatchJobBuildItem::getJob).collect(toList()));
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    public RunTimeConfigurationSourceValueBuildItem config(JBeretRecorder recorder) {
        return new RunTimeConfigurationSourceValueBuildItem(recorder.config());
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    ServiceStartBuildItem init(JBeretRecorder recorder, BeanContainerBuildItem beanContainer) {

        recorder.initJobOperator(beanContainer.getValue());

        return new ServiceStartBuildItem("jberet");
    }

    // TODO: add @Dependent to detected batch components without CDI scope

    private static void registerNonDefaultConstructors(RecorderContext recorderContext) throws Exception {
        recorderContext.registerNonDefaultConstructor(Job.class.getConstructor(String.class),
                job -> Collections.singletonList(job.getId()));

        recorderContext.registerNonDefaultConstructor(Flow.class.getConstructor(String.class),
                flow -> Collections.singletonList(flow.getId()));

        recorderContext.registerNonDefaultConstructor(Split.class.getConstructor(String.class),
                split -> Collections.singletonList(split.getId()));

        recorderContext.registerNonDefaultConstructor(Step.class.getConstructor(String.class),
                step -> Collections.singletonList(step.getId()));

        recorderContext.registerNonDefaultConstructor(RefArtifact.class.getConstructor(String.class),
                refArtifact -> Collections.singletonList(refArtifact.getRef()));

        recorderContext.registerNonDefaultConstructor(Decision.class.getConstructor(String.class, String.class),
                decision -> Stream.of(decision.getId(), decision.getRef()).collect(toList()));

        recorderContext.registerNonDefaultConstructor(Transition.class.getConstructor(String.class),
                transition -> Collections.singletonList(transition.getOn()));
        recorderContext.registerNonDefaultConstructor(Transition.End.class.getConstructor(String.class),
                end -> Collections.singletonList(end.getOn()));
        recorderContext.registerNonDefaultConstructor(Transition.Fail.class.getConstructor(String.class),
                fail -> Collections.singletonList(fail.getOn()));
        recorderContext.registerNonDefaultConstructor(Transition.Stop.class.getConstructor(String.class, String.class),
                stop -> Stream.of(stop.getOn(), stop.getRestart()).collect(toList()));
        recorderContext.registerNonDefaultConstructor(Transition.Next.class.getConstructor(String.class),
                next -> Collections.singletonList(next.getOn()));
    }

    private static Set<String> findBatchFilesFromPath(Path path, List<Pattern> includes, List<Pattern> excludes) {
        try {
            Stream<String> filePaths = Files.walk(path)
                    .filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(file -> file.endsWith(".xml"));

            if (!includes.isEmpty()) {
                filePaths = filePaths
                        .filter(filePath -> includes.stream().allMatch(pattern -> pattern.matcher(filePath).matches()));
            }

            if (!excludes.isEmpty()) {
                filePaths = filePaths
                        .filter(filePath -> excludes.stream().noneMatch(pattern -> pattern.matcher(filePath).matches()));
            }

            return filePaths.map(file -> file.substring(0, file.length() - 4)).collect(Collectors.toSet());
        } catch (IOException e) {
            return Collections.emptySet();
        }
    }

    private static List<Pattern> toPatterns(Optional<List<String>> pattern) {
        return pattern.map(patterns -> patterns.stream().map(GlobUtil::toRegexPattern).map(Pattern::compile).collect(toList()))
                .orElseGet(ArrayList::new);
    }
}
