package ar.cetobhb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CetoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CetoBackendApplication.class, args);
    }
}
