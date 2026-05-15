package com.connecthub.notification;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableDiscoveryClient
@EnableAsync(proxyTargetClass = true)
@EnableFeignClients
public class NotificationServiceApplication { public static void main(String[] a) { SpringApplication.run(NotificationServiceApplication.class, a); } }
