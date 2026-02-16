package org.texttechnologylab.uce.common.config;

import com.google.gson.Gson;
import lombok.Getter;
import lombok.Setter;
import org.texttechnologylab.uce.common.config.corpusConfig.CorpusAnnotationConfig;
import org.texttechnologylab.uce.common.config.corpusConfig.OtherConfig;
import org.texttechnologylab.uce.common.config.corpusConfig.RenderModeConfig;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class CorpusConfig {
    private String name;
    private String author;
    private String language;
    private String description;
    private CorpusAnnotationConfig annotations;
    private boolean addToExistingCorpus;
    private boolean deleteExistingCorpusBeforeImport;
    private OtherConfig other;
    private List<RenderModeConfig> renderModes = new ArrayList<>();

    public static CorpusConfig fromJson(String corpusConfigJson){
        var gson = new Gson();
        var config = gson.fromJson(corpusConfigJson, CorpusConfig.class);
        return config;
    }
}
