package org.texttechnologylab.uce.web.render;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.texttechnologylab.uce.web.render.feedback.FeedbackPaneRenderer;

@Configuration
public class RendererConfig {

    @Bean
    public RendererRegistry rendererRegistry() {
        return new RendererRegistry()
                .register(DefaultPaneRenderer.HANDLER_KEY, new DefaultPaneRenderer())
                .register(FeedbackPaneRenderer.HANDLER_KEY, new FeedbackPaneRenderer());
    }
}
