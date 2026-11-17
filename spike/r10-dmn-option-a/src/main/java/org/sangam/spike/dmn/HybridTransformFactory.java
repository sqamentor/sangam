// SPDX-License-Identifier: Apache-2.0
package org.sangam.spike.dmn;

import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionImpl;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnElementTransformHandler;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnTransform;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnTransformFactory;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnTransformer;
import org.operaton.bpm.dmn.engine.impl.transform.DefaultDmnTransform;
import org.operaton.bpm.dmn.engine.impl.transform.DmnTransformException;
import org.operaton.bpm.model.dmn.Dmn;
import org.operaton.bpm.model.dmn.DmnModelInstance;
import org.operaton.bpm.model.dmn.instance.Decision;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Creates {@link HybridTransform}s. */
public class HybridTransformFactory implements DmnTransformFactory {

    /** DMN elements Operaton's DMN engine cannot execute (STANDARDS-PROFILE DMN rows marked Unsupported). */
    static final Set<String> DROOLS_ONLY = Set.of("businessKnowledgeModel", "decisionService", "context",
            "invocation", "functionDefinition", "relation", "list", "conditional", "filter", "for", "every", "some",
            "import");

    private final HybridDmnEngineConfiguration configuration;

    public HybridTransformFactory(HybridDmnEngineConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public DmnTransform createTransform(DmnTransformer transformer) {
        return new HybridTransform(transformer, configuration);
    }

    /** Operaton's transform; a routed model's decisions get {@link DroolsDecisionLogic}. */
    public static class HybridTransform extends DefaultDmnTransform {
        private final HybridDmnEngineConfiguration configuration;
        private byte[] raw;
        private Boolean routed;
        private DMNRuntime runtime;
        private DMNModel droolsModel;

        HybridTransform(DmnTransformer transformer, HybridDmnEngineConfiguration configuration) {
            super(transformer);
            this.configuration = configuration;
        }

        @Override
        public void setModelInstance(InputStream inputStream) {
            if (inputStream == null) {
                super.setModelInstance(inputStream);
                return;
            }
            try (inputStream) {
                raw = inputStream.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            super.setModelInstance(new ByteArrayInputStream(raw));
        }

        @Override
        public void setModelInstance(File file) {
            try {
                setModelInstance(file == null ? null : Files.newInputStream(file.toPath()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void setModelInstance(DmnModelInstance instance) {
            super.setModelInstance(instance);
            if (raw == null && instance != null) {
                raw = Dmn.convertToString(instance).getBytes(StandardCharsets.UTF_8);
            }
        }

        @Override
        protected DmnDecisionImpl transformDecision(Decision decision) {
            if (!routed()) {
                return super.transformDecision(decision);
            }
            DmnElementTransformHandler<Decision, DmnDecisionImpl> handler = handlerRegistry.getHandler(Decision.class);
            DmnDecisionImpl impl = handler.handleElement(this, decision);
            this.decision = impl;
            if (impl.getKey() == null) {
                throw new DmnTransformException("decision without id: " + decision.getName(), null);
            }
            compile();
            String variable = decision.getVariable() != null && decision.getVariable().getName() != null
                    ? decision.getVariable().getName() : decision.getName();
            impl.setDecisionLogic(new DroolsDecisionLogic(runtime, droolsModel, decision.getName(), variable));
            notifyTransformListeners(decision, impl);
            return impl;
        }

        boolean routed() {
            if (routed == null) {
                routed = configuration.routing() == HybridDmnEngineConfiguration.Routing.ALL || usesDroolsOnly(raw);
            }
            return routed;
        }

        private void compile() {
            if (runtime != null) {
                return;
            }
            List<Resource> resources = new ArrayList<>();
            resources.add(resource(raw, "main.dmn"));
            int i = 0;
            for (byte[] c : configuration.companionModels()) {
                resources.add(resource(c, "companion" + (i++) + ".dmn"));
            }
            runtime = DMNRuntimeBuilder.fromDefaults().buildConfiguration().fromResources(resources)
                    .getOrElseThrow(e -> new DmnTransformException("Drools cannot compile the DMN model: " + e, e));
            // strict inputs: a value outside the declared typeRef is an error, not a silent null (SG-34 analogue)
            ((org.kie.dmn.core.impl.DMNRuntimeImpl) runtime)
                    .setOption(new org.kie.dmn.core.compiler.RuntimeTypeCheckOption(true));
            String ns = modelInstance.getDefinitions().getNamespace();
            String name = modelInstance.getDefinitions().getName();
            droolsModel = runtime.getModel(ns, name);
            if (droolsModel == null) {
                throw new DmnTransformException("Drools has no model " + ns + " / " + name, null);
            }
            if (droolsModel.hasErrors()) {
                throw new DmnTransformException("Drools reports model errors: " + droolsModel.getMessages(
                        org.kie.dmn.api.core.DMNMessage.Severity.ERROR), null);
            }
        }

        private static Resource resource(byte[] bytes, String path) {
            Resource r = ResourceFactory.newByteArrayResource(bytes);
            r.setSourcePath(path);
            r.setResourceType(ResourceType.DMN);
            return r;
        }
    }

    static boolean usesDroolsOnly(byte[] xml) {
        if (xml == null) {
            return false;
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList all = doc.getElementsByTagNameNS("*", "*");
            for (int i = 0; i < all.getLength(); i++) {
                if (DROOLS_ONLY.contains(all.item(i).getLocalName())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw new DmnTransformException("cannot read DMN document: " + e.getMessage(), e);
        }
    }
}
