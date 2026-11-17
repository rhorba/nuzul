package ma.nuzul;

import org.springframework.boot.SpringApplication;

public class TestNuzulApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(NuzulApiApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
