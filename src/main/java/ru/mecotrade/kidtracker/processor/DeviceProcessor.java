/*
 * Copyright 2020 Sergey Shadchin (sergei.shadchin@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.mecotrade.kidtracker.processor;

import com.google.common.collect.Streams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.mecotrade.kidtracker.dao.ConfigService;
import ru.mecotrade.kidtracker.dao.ContactService;
import ru.mecotrade.kidtracker.dao.model.ConfigRecord;
import ru.mecotrade.kidtracker.dao.model.ContactRecord;
import ru.mecotrade.kidtracker.dao.model.DeviceInfo;
import ru.mecotrade.kidtracker.exception.KidTrackerConnectionException;
import ru.mecotrade.kidtracker.model.Command;
import ru.mecotrade.kidtracker.model.Config;
import ru.mecotrade.kidtracker.model.Contact;
import ru.mecotrade.kidtracker.model.ContactType;
import ru.mecotrade.kidtracker.model.Report;
import ru.mecotrade.kidtracker.model.Snapshot;
import ru.mecotrade.kidtracker.model.Position;
import ru.mecotrade.kidtracker.dao.MessageService;
import ru.mecotrade.kidtracker.dao.UserService;
import ru.mecotrade.kidtracker.dao.model.KidInfo;
import ru.mecotrade.kidtracker.dao.model.Message;
import ru.mecotrade.kidtracker.dao.model.UserInfo;
import ru.mecotrade.kidtracker.device.Device;
import ru.mecotrade.kidtracker.device.DeviceManager;
import ru.mecotrade.kidtracker.exception.KidTrackerParseException;
import ru.mecotrade.kidtracker.exception.KidTrackerUnknownUserException;
import ru.mecotrade.kidtracker.model.Status;
import ru.mecotrade.kidtracker.util.MessageUtils;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
@Component
public class DeviceProcessor {

    @Autowired
    private UserService userService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private ContactService contactService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private DeviceManager deviceManager;

    private Map<String, Map<LocalDate, Integer>> initPedometers;

    public Collection<Status> status(UserInfo userInfo) {

        Collection<String> deviceIds = userInfo.getKids().stream().map(KidInfo::getDevice).map(DeviceInfo::getId).collect(Collectors.toList());
        Collection<Status> status = deviceIds.stream().map(id -> {
            Date last = deviceManager.last(id);
            return new Status(id, last != null, last);
        }).collect(Collectors.toList());

        Collection<Status> offline = messageService.last(status.stream()
                .filter(s -> !s.isOnline())
                .map(Status::getDeviceId)
                .collect(Collectors.toList()), Message.Source.DEVICE).stream()
                        .map(m -> new Status(m.getDeviceId(), false, m.getTimestamp()))
                        .collect(Collectors.toList());

        Set<String> offlineIds = offline.stream().map(Status::getDeviceId).collect(Collectors.toSet());
        return Streams.concat(status.stream().filter(s -> !offlineIds.contains(s.getDeviceId())), offline.stream()).collect(Collectors.toList());
    }

    public Report report(UserInfo userInfo) {

        Collection<Device> devices = deviceManager.select(userInfo.getKids().stream()
                .map(KidInfo::getDevice)
                .map(DeviceInfo::getId)
                .collect(Collectors.toList()));

        Collection<Position> positions = devices.stream()
                .map(Device::position)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // if there are some devices with unknown last location, try to find it in the database
        if (positions.size() < devices.size()) {
            for (Device device : devices.stream().filter(d -> d.getLocation() == null).collect(Collectors.toList())) {
                synchronized (device) {

                    if (device.getLocation() == null) {
                        Message message = messageService.last(device.getId(), MessageUtils.LOCATION_TYPES, Message.Source.DEVICE);
                        log.debug("[{}] Location not found, use historical message {}", device.getId(), message);
                        if (message != null) {
                            try {
                                device.setLocation(MessageUtils.toLocation(message));
                                positions.add(device.position());
                            } catch (KidTrackerParseException ex) {
                                log.warn("Unable to parse historical location message {}", message);
                            }
                        }
                    }
                }
            }
        }

        Collection<Snapshot> snapshots = devices.stream()
                .map(Device::snapshot)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // if there are some devices with unknown last link, try to find it in the database
        if (snapshots.size() < devices.size()) {
            for (Device device : devices.stream().filter(d -> d.getLink() == null).collect(Collectors.toList())) {
                synchronized (device) {
                    if (device.getLink() == null) {
                        Message message = messageService.last(device.getId(), Collections.singleton(MessageUtils.LINK_TYPE), Message.Source.DEVICE);
                        log.debug("[{}] Link not found, use historical message {}", device.getId(), message);
                        try {
                            device.setLink(MessageUtils.toLink(message));
                            snapshots.add(device.snapshot());
                        } catch (KidTrackerParseException ex) {
                            log.warn("Unable to parse historical link message {}", message);
                        }
                    }
                }
            }
        }

        return Report.builder()
                .positions(positions)
                .snapshots(snapshots)
                .alarms(devices.stream().filter(d -> d.getAlarm().getValue()).map(Device::getId).collect(Collectors.toList()))
                .last(devices.stream().collect(Collectors.toMap(Device::getId, Device::getLast)))
                .build();
    }

    public Collection<Snapshot> lastSnapshots(Long userId, Date timestamp) throws KidTrackerUnknownUserException {
        Optional<UserInfo> user = userService.get(userId);
        if (user.isPresent()) {
            Collection<String> deviceIds = user.get().getKids().stream()
                    .map(KidInfo::getDevice)
                    .map(DeviceInfo::getId)
                    .collect(Collectors.toList());
            return messageService.last(deviceIds,
                    Stream.concat(MessageUtils.LOCATION_TYPES.stream(), Stream.of(MessageUtils.LINK_TYPE)).collect(Collectors.toList()),
                    Message.Source.DEVICE,
                    timestamp).stream().map(MessageUtils::toSnapshot).collect(Collectors.toList());
        } else {
            throw new KidTrackerUnknownUserException(String.valueOf(userId));
        }
    }

    public Optional<Snapshot> lastSnapshot(String deviceId, Long timestamp) {
        return messageService.last(Collections.singletonList(deviceId),
                Stream.concat(MessageUtils.LOCATION_TYPES.stream(), Stream.of(MessageUtils.LINK_TYPE)).collect(Collectors.toList()),
                Message.Source.DEVICE,
                new Date(timestamp)).stream().map(MessageUtils::toSnapshot).findFirst();
    }

    public Collection<Snapshot> snapshots(String deviceId, Long start, Long end) {
        return messageService.slice(deviceId, MessageUtils.SNAPSHOT_TYPES, Message.Source.DEVICE, new Date(start), new Date(end)).stream()
                .map(MessageUtils::toSnapshot)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Collection<Position> path(String deviceId, Long start, Long end) {
        return messageService.slice(deviceId, MessageUtils.LOCATION_TYPES, Message.Source.DEVICE, new Date(start), new Date(end)).stream()
                .map(MessageUtils::toPosition)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Collection<Contact> contacts(String deviceId, ContactType type) {
        return contactService.get(deviceId, type).stream().map(ContactRecord::toContact).collect(Collectors.toList());
    }

    public void updateContact(String deviceId, Contact contact) throws KidTrackerConnectionException {
        contactService.put(deviceId, contact);
        syncContact(deviceId, contact.getType(), contact.getIndex());
    }

    public void removeContact(String deviceId, ContactType type, Integer index) throws KidTrackerConnectionException {
        contactService.remove(deviceId, type, index);
        syncContact(deviceId, type, index);
    }

    public Collection<Config> configs(String deviceId) {
        return configService.get(deviceId).stream().map(ConfigRecord::toConfig).collect(Collectors.toList());
    }

    public void updateConfig(String deviceId, Config config) throws KidTrackerConnectionException {
        configService.put(deviceId, config);
        syncConfig(deviceId, config.getParameter());
    }

    private static String encodeContact(Contact contact) {
        return contact.getPhone() + "," + MessageUtils.toUtf16Hex(contact.getName());
    }

    private void syncContact(String deviceId, ContactType type, Integer index) throws KidTrackerConnectionException {

        Map<Integer, ContactRecord> contacts = contactService.get(deviceId, type).stream()
                .collect(Collectors.toMap(ContactRecord::getIndex, Function.identity()));

        switch (type) {
            case ADMIN: {
                deviceManager.send(deviceId, new Command(index == 0 ? "CENTER" : "SLAVE",
                        Collections.singletonList(contacts.containsKey(index) ? contacts.get(index).getPhone() : "d")));
                break;
            }
            case SOS: {
                deviceManager.send(deviceId, new Command(index == 0 ? "SOS1" : (index == 1 ? "SOS2" : "SOS3"),
                        Collections.singletonList(contacts.containsKey(index) ? contacts.get(index).getPhone() : "")));
                break;
            }
            case PHONEBOOK: {
                int shift = index < 5 ? 0 : 5;
                deviceManager.send(deviceId, new Command(index < 5 ? "PHB" : "PHB2", IntStream.range(shift, shift + 5)
                        .mapToObj(i -> contacts.containsKey(i) ? encodeContact(contacts.get(i).toContact()) : ",")
                        .collect(Collectors.toList())));
                break;
            }
            case WHITELIST: {
                int shift = index < 5 ? 0 : 5;
                deviceManager.send(deviceId, new Command(index < 5 ? "WHITELIST1" : "WHITELIST2", IntStream.range(shift, shift + 5)
                        .mapToObj(i -> contacts.containsKey(i) ? contacts.get(i).getPhone() : ",")
                        .collect(Collectors.toList())));
                break;
            }
            case BUTTON: {
                deviceManager.send(deviceId, new Command(index == 0 ? "TEL1" : "TEL2",
                        Collections.singletonList(contacts.containsKey(index) ? contacts.get(index).getPhone() : "")));
                break;
            }
        }
    }

    private void syncConfig(String deviceId, String parameter) throws KidTrackerConnectionException {
        Config config = configService.get(deviceId, parameter).map(ConfigRecord::toConfig).orElse(null);
        if (config != null) {
            deviceManager.send(deviceId, Command.from(config));
        }
    }
}