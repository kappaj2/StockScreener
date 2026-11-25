package za.co.sfh.stocklistener;

import com.vaadin.flow.component.page.AppShellConfigurator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class StocklistenerApplication implements AppShellConfigurator {

    static void main(String[] args) {
        SpringApplication.run(StocklistenerApplication.class, args);
    }

}
