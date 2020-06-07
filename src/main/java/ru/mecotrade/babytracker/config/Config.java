package ru.mecotrade.babytracker.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.mecotrade.babytracker.device.DebugListenerFactory;
import ru.mecotrade.babytracker.device.DeviceServer;
import ru.mecotrade.babytracker.device.MessageListenerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class Config {

    @Autowired
    private MessageListenerFactory messageListenerFactory;

    @Autowired
    private DebugListenerFactory debugListenerFactory;

    @Bean
    public Executor deviceListenerExecutor() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    public DeviceServer messageServer() {
        return new DeviceServer(8001, messageListenerFactory);
    }

    @Bean
    public DeviceServer debugServer() {
        return new DeviceServer(8002, debugListenerFactory);
    }
}
