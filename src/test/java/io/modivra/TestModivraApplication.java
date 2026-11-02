package io.modivra;

import org.springframework.boot.SpringApplication;

public class TestModivraApplication {

    public static void main(String[] args) {
        SpringApplication.from(ModivraApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
