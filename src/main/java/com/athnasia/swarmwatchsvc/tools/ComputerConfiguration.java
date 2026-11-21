package com.athnasia.swarmwatchsvc.tools;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
class ComputerConfiguration {
    @Bean(destroyMethod = "close") @Profile("!local")
    ComputerExecutor productionComputerExecutor() { return new PlaywrightComputerExecutor(); }

    @Bean(destroyMethod = "close") @Profile("local")
    ComputerExecutor localComputerExecutor() { return new LocalComputerExecutor(); }
}
