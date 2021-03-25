package io.quarkiverse.jberet.rest.deployment;

import io.quarkiverse.jberet.rest.runtime.JBeretRestProducer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CapabilityBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.resteasy.common.spi.ResteasyJaxrsProviderBuildItem;

public class JBeretRestProcessor {
    @BuildStep
    public void registerExtension(BuildProducer<FeatureBuildItem> feature, BuildProducer<CapabilityBuildItem> capability) {
        feature.produce(new FeatureBuildItem("jberet-rest"));
    }

    @BuildStep
    public void additionalBeans(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        additionalBeans.produce(new AdditionalBeanBuildItem(JBeretRestProducer.class));
    }

    @BuildStep
    public void rest(
            BuildProducer<IndexDependencyBuildItem> indexDependency,
            BuildProducer<ResteasyJaxrsProviderBuildItem> providers) {

        indexDependency.produce(new IndexDependencyBuildItem("org.jberet", "jberet-rest-api"));
    }
}
