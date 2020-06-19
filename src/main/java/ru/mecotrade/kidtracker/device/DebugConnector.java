package ru.mecotrade.kidtracker.device;

import lombok.extern.slf4j.Slf4j;
import ru.mecotrade.kidtracker.exception.KidTrackerException;

import java.net.Socket;

@Slf4j
public class DebugConnector extends DeviceConnector {

    public DebugConnector(Socket socket) {
        super(socket);
    }

    @Override
    void process(byte[] data) throws KidTrackerException {
        log.debug("[{}] ### {}", getId(), new String(data));
    }
}
