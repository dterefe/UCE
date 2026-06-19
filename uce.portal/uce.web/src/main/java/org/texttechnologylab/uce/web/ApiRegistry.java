package org.texttechnologylab.uce.web;

import freemarker.template.Configuration;
import org.springframework.context.ApplicationContext;
import org.texttechnologylab.uce.web.routes.*;

import java.util.Map;

public class ApiRegistry {
    private final Map<Class<? extends UceApi>, UceApi> apis;

    public ApiRegistry(ApplicationContext context, Configuration configuration, int DUUIInputCounter) {
        this.apis = Map.ofEntries(
                Map.entry(SearchApi.class, new SearchApi(context, configuration)),
                Map.entry(DocumentApi.class, new DocumentApi(context, configuration)),
                Map.entry(RAGApi.class, new RAGApi(context, configuration)),
                Map.entry(CorpusUniverseApi.class, new CorpusUniverseApi(context, configuration)),
                Map.entry(WikiApi.class, new WikiApi(context, configuration)),
                Map.entry(ImportExportApi.class, new ImportExportApi(context)),
                Map.entry(AnalysisApi.class, new AnalysisApi(context, configuration, DUUIInputCounter)),
                Map.entry(MapApi.class, new MapApi(context, configuration)),
                Map.entry(AuthenticationApi.class, new AuthenticationApi(context, configuration)),
                Map.entry(McpApi.class, new McpApi(context, configuration)),
                Map.entry(DomainApi.class, new DomainApi(context, configuration)),
                Map.entry(DUAVizApi.class, new DUAVizApi(context, configuration))
        );
    }

    @SuppressWarnings("unchecked")
    public <T extends UceApi> T get(Class<T> clazz) {
        return (T) apis.get(clazz);
    }

    public Map<Class<? extends UceApi>, UceApi> getAll() {
        return apis;
    }
}
