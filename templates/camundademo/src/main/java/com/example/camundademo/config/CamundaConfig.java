package com.example.camundademo.config;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.spring.ProcessEngineFactoryBean;
import org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import javax.sql.DataSource;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableConfigurationProperties({ CamundaDataSourceProperties.class, CamundaProcessProperties.class })
@AllArgsConstructor
public class CamundaConfig {

    private static final Logger logger = LoggerFactory.getLogger(CamundaConfig.class);

    private final CamundaDataSourceProperties camundaDataSourceProperties;
    private final CamundaProcessProperties camundaProcessProperties;

    @Bean
    public DataSource dataSource() {
        return DataSourceBuilder.create()
                .driverClassName(camundaDataSourceProperties.getDriverClassName())
                .url(camundaDataSourceProperties.getUrl())
                .username(camundaDataSourceProperties.getUsername())
                .password(camundaDataSourceProperties.getPassword())
                .build();
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public SpringProcessEngineConfiguration engineConfiguration(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            @Value("classpath*:*.bpmn") Resource[] deploymentResources) {
        SpringProcessEngineConfiguration configuration = new SpringProcessEngineConfiguration();

        configuration.setProcessEngineName(camundaProcessProperties.getProcessEngineName());
        configuration.setDataSource(dataSource);
        configuration.setTransactionManager(transactionManager);
        configuration.setDatabaseSchemaUpdate(camundaProcessProperties.getDatabaseSchemaUpdate());
        configuration.setJobExecutorActivate(camundaProcessProperties.isJobExecutorActivate());
        configuration.setDeploymentResources(deploymentResources);

        configuration.setSkipIsolationLevelCheck(camundaProcessProperties.isSkipIsolationLevelCheck());
        configuration.setEnforceHistoryTimeToLive(camundaProcessProperties.isEnforceHistoryTimeToLive());

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(camundaProcessProperties.getDeploymentResourcePattern());
            configuration.setDeploymentResources(resources);
        } catch (IOException e) {
            logger.error("Error loading process models from pattern: {}",
                    camundaProcessProperties.getDeploymentResourcePattern(), e);
        }
        return configuration;
    }

    @Bean
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProcessEngine processEngine(ProcessEngineFactoryBean factoryBean) throws Exception {
        return factoryBean.getObject();
    }
}
