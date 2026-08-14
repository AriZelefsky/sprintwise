package com.example.backend.config;

import com.example.backend.gtfs.GtfsLoader;
import com.example.backend.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.example.backend.service.TransitDataService;
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
