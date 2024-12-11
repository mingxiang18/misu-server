//package com.misu.user;
//
//import org.springframework.boot.SpringApplication;
//import org.springframework.boot.autoconfigure.SpringBootApplication;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
//import org.springframework.boot.autoconfigure.domain.EntityScan;
//import org.springframework.boot.context.properties.EnableConfigurationProperties;
//import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
//import org.springframework.scheduling.annotation.EnableAsync;
//import org.springframework.scheduling.annotation.EnableScheduling;
//import org.springframework.transaction.annotation.EnableTransactionManagement;
//
//@EnableAsync
//@EnableScheduling
//@EnableConfigurationProperties
//@EntityScan("com.misu.user.domain.entity")
//@EnableJpaRepositories("com.misu.user.dao")
//@EnableTransactionManagement
//@SpringBootApplication(scanBasePackages = {"com.misu.*"})
//@ConditionalOnMissingBean(name = "webApplication")
//public class UserApplication {
//
//    public static void main(String[] args) {
//        SpringApplication.run(UserApplication.class, args);
//    }
//
//}
