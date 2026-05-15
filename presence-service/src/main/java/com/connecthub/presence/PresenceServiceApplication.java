package com.connecthub.presence;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication @EnableDiscoveryClient @EnableScheduling
public class PresenceServiceApplication { public static void main(String[] a) { SpringApplication.run(PresenceServiceApplication.class, a); } }
