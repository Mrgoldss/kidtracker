package ru.mecotrade.kidtracker.controller.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Kid {

    private String deviceId;

    private String name;

    private String thumb;

}
