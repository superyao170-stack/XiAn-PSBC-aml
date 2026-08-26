
package com.datagraph.bank;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@MapperScan("com.datagraph.bank.mapper")
public class BankGraphApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankGraphApplication.class, args);
    }
}
