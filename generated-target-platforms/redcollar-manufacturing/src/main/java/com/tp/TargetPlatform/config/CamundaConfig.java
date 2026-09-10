package com.tp.TargetPlatform.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ CamundaDataSourceProperties.class, CamundaProcessProperties.class })
public class CamundaConfig {
}
