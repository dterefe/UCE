package org.texttechnologylab.uce.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.texttechnologylab.uce.common.config.SpringConfig;
import org.texttechnologylab.uce.web.render.RendererConfig;

@Configuration
@Import({SpringConfig.class, RendererConfig.class})
public class WebSpringConfig {
}
