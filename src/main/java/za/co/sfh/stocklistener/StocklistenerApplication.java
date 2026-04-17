package za.co.sfh.stocklistener;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.lumo.Lumo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("themes/stocklistener/styles.css")
@SpringBootApplication
@EnableScheduling
public class StocklistenerApplication implements AppShellConfigurator {

    static void main(String[] args) {
        SpringApplication.run(StocklistenerApplication.class, args);
    }

}
