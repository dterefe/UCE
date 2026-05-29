package org.texttechnologylab.uce.common.config;

import java.util.EnumSet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.texttechnologylab.uce.common.security.DocumentAccessContext;
import org.texttechnologylab.uce.common.security.DocumentAccessManager;
import org.texttechnologylab.uce.common.services.DataInterface;

@Configuration
public class DocumentAccessConfig {

    @Bean
    @Scope("prototype")
    public DocumentAccessContext documentAccessContext(String principal, EnumSet<DocumentAccessContext.Role> roles) {
        return new DocumentAccessContext(principal, roles != null ?
                roles : java.util.EnumSet.noneOf(DocumentAccessContext.Role.class));
    }

    @Bean
    public DocumentAccessManager documentAccessManager(DataInterface db) {
        return new DocumentAccessManager(db);
    }
}
