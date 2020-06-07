package ru.mecotrade.babytracker.device;

import ru.mecotrade.babytracker.exception.BabyTrackerConnectionException;

public interface DeviceSender extends Closeable {

    void send(String payload) throws BabyTrackerConnectionException;
}
