package com.sprintwise.config;

import com.sprintwise.gtfs.GtfsLoader;
import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.raptor.RaptorNetwork;
import com.sprintwise.raptor.RaptorNetworkBuilder;
import com.sprintwise.service.RaptorRoutingService;
import com.sprintwise.service.TransitFeedCatalog;
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
    TransitFeedCatalog transitFeedCatalog(GtfsLoader loader, GtfsProperties properties) {
        return new TransitFeedCatalog(loader, properties);
    }

    @Bean
    RaptorNetwork raptorNetwork(TransitFeedCatalog catalog) {
        return new RaptorNetworkBuilder().build(catalog);
    }

    @Bean
    RaptorRoutingService raptorRoutingService(
        TransitFeedCatalog catalog,
        RaptorNetwork network
    ) {
        return new RaptorRoutingService(catalog, network);
    }
}
