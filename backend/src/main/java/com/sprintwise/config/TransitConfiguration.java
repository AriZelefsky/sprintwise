package com.sprintwise.config;

import com.sprintwise.gtfs.GtfsLoader;
import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.service.TransitDataService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GtfsProperties.class)
public class TransitConfiguration {

    @Bean
    GtfsLoader gtfsLoader() {
        return new OneBusAwayGtfsLoader();
    }

    @Bean
    TransitDataService transitDataService(GtfsLoader loader, GtfsProperties properties) {
        return new TransitDataService(loader, properties);
    }
}
