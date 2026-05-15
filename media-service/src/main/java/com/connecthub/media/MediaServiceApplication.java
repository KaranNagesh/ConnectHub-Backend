package com.connecthub.media;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
@SpringBootApplication @EnableDiscoveryClient
public class MediaServiceApplication { public static void main(String[] a) { SpringApplication.run(MediaServiceApplication.class, a); } }
