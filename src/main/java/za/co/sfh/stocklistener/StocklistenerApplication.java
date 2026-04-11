package za.co.sfh.stocklistener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StocklistenerApplication {

    static void main(String[] args) {
        SpringApplication.run(StocklistenerApplication.class, args);
    }

}
