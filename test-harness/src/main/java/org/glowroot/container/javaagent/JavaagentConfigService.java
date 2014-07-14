/*
 * Copyright 2011-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.container.javaagent;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.container.common.ObjectMappers;
import org.glowroot.container.config.AdvancedConfig;
import org.glowroot.container.config.ConfigService;
import org.glowroot.container.config.GeneralConfig;
import org.glowroot.container.config.OutlierProfilingConfig;
import org.glowroot.container.config.PluginConfig;
import org.glowroot.container.config.PointcutConfig;
import org.glowroot.container.config.ProfilingConfig;
import org.glowroot.container.config.StorageConfig;
import org.glowroot.container.config.UserInterfaceConfig;
import org.glowroot.container.config.UserTracingConfig;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class JavaagentConfigService implements ConfigService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final JavaagentHttpClient httpClient;
    private final GetUiPortCommand getUiPortCommand;

    JavaagentConfigService(JavaagentHttpClient httpClient, GetUiPortCommand getUiPortCommand) {
        this.httpClient = httpClient;
        this.getUiPortCommand = getUiPortCommand;
    }

    @Override
    public void setPluginProperty(String pluginId, String propertyName,
            @Nullable Object propertyValue) throws Exception {
        PluginConfig config = getPluginConfig(pluginId);
        if (config == null) {
            throw new IllegalStateException("Plugin not found for pluginId: " + pluginId);
        }
        config.setProperty(propertyName, propertyValue);
        updatePluginConfig(pluginId, config);
    }

    @Override
    public GeneralConfig getGeneralConfig() throws Exception {
        return getConfig("/backend/config/general", GeneralConfig.class);
    }

    @Override
    public void updateGeneralConfig(GeneralConfig config) throws Exception {
        httpClient.post("/backend/config/general", mapper.writeValueAsString(config));
    }

    @Override
    public ProfilingConfig getProfilingConfig() throws Exception {
        return getConfig("/backend/config/profiling", ProfilingConfig.class);
    }

    @Override
    public void updateProfilingConfig(ProfilingConfig config) throws Exception {
        httpClient.post("/backend/config/profiling", mapper.writeValueAsString(config));
    }

    @Override
    public OutlierProfilingConfig getOutlierProfilingConfig() throws Exception {
        return getConfig("/backend/config/outlier-profiling", OutlierProfilingConfig.class);
    }

    @Override
    public void updateOutlierProfilingConfig(OutlierProfilingConfig config) throws Exception {
        httpClient.post("/backend/config/outlier-profiling", mapper.writeValueAsString(config));
    }

    @Override
    public UserTracingConfig getUserTracingConfig() throws Exception {
        return getConfig("/backend/config/user-tracing", UserTracingConfig.class);
    }

    @Override
    public void updateUserTracingConfig(UserTracingConfig config) throws Exception {
        httpClient.post("/backend/config/user-tracing", mapper.writeValueAsString(config));
    }

    @Override
    public StorageConfig getStorageConfig() throws Exception {
        return getConfig("/backend/config/storage", StorageConfig.class);
    }

    @Override
    public void updateStorageConfig(StorageConfig config) throws Exception {
        httpClient.post("/backend/config/storage", mapper.writeValueAsString(config));
    }

    @Override
    public UserInterfaceConfig getUserInterfaceConfig() throws Exception {
        return getConfig("/backend/config/user-interface", UserInterfaceConfig.class);
    }

    @Override
    public void updateUserInterfaceConfig(UserInterfaceConfig config) throws Exception {
        String response = httpClient.post("/backend/config/user-interface",
                mapper.writeValueAsString(config));
        JsonNode node = mapper.readTree(response);
        JsonNode currentPasswordIncorrectNode = node.get("currentPasswordIncorrect");
        if (currentPasswordIncorrectNode != null && currentPasswordIncorrectNode.asBoolean()) {
            throw new CurrentPasswordIncorrectException();
        }
        JsonNode portChangeFailedNode = node.get("portChangeFailed");
        if (portChangeFailedNode != null && portChangeFailedNode.asBoolean()) {
            throw new PortChangeFailedException();
        }
        httpClient.updateUiPort(getUiPortCommand.getUiPort());
    }

    @Override
    public List<PointcutConfig> getPointcutConfigs() throws Exception {
        String response = httpClient.get("/backend/config/pointcut");
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode configsNode = ObjectMappers.getRequiredChildNode(rootNode, "configs");
        return mapper.readValue(mapper.treeAsTokens(configsNode),
                new TypeReference<List<PointcutConfig>>() {});
    }

    // returns new version
    @Override
    public String addPointcutConfig(PointcutConfig pointcutConfig) throws Exception {
        String response = httpClient.post("/backend/config/pointcut/+",
                mapper.writeValueAsString(pointcutConfig));
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode versionNode = ObjectMappers.getRequiredChildNode(rootNode, "version");
        return versionNode.asText();
    }

    @Override
    public void updatePointcutConfig(String version, PointcutConfig pointcutConfig)
            throws Exception {
        httpClient.post("/backend/config/pointcut/" + version,
                mapper.writeValueAsString(pointcutConfig));
    }

    @Override
    public void removePointcutConfig(String version) throws Exception {
        httpClient.post("/backend/config/pointcut/-", mapper.writeValueAsString(version));
    }

    @Override
    public AdvancedConfig getAdvancedConfig() throws Exception {
        return getConfig("/backend/config/advanced", AdvancedConfig.class);
    }

    @Override
    public void updateAdvancedConfig(AdvancedConfig config) throws Exception {
        httpClient.post("/backend/config/advanced", mapper.writeValueAsString(config));
    }

    @Override
    @Nullable
    public PluginConfig getPluginConfig(String pluginId) throws Exception {
        return getConfig("/backend/config/plugin/" + pluginId, PluginConfig.class);
    }

    @Override
    public void updatePluginConfig(String pluginId, PluginConfig config) throws Exception {
        httpClient.post("/backend/config/plugin/" + pluginId, mapper.writeValueAsString(config));
    }

    @Override
    public int reweavePointcuts() throws Exception {
        String response = httpClient.post("/backend/admin/reweave-pointcuts", "");
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode classesNode = ObjectMappers.getRequiredChildNode(rootNode, "classes");
        return classesNode.asInt();
    }

    @Override
    public void compactData() throws Exception {
        httpClient.post("/backend/admin/compact-data", "");
    }

    void resetAllConfig() throws Exception {
        httpClient.post("/backend/admin/reset-all-config", "");
        // storeThresholdMillis=0 is by far the most useful setting for testing
        setStoreThresholdMillis(0);
    }

    void setStoreThresholdMillis(int storeThresholdMillis) throws Exception {
        GeneralConfig generalConfig = getGeneralConfig();
        generalConfig.setStoreThresholdMillis(storeThresholdMillis);
        updateGeneralConfig(generalConfig);
    }

    private <T> T getConfig(String url, Class<T> type) throws Exception {
        String response = httpClient.get(url);
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode configNode = ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.treeToValue(configNode, type);
    }

    interface GetUiPortCommand {
        int getUiPort() throws Exception;
    }
}
