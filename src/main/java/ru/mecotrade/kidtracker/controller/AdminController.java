package ru.mecotrade.kidtracker.controller;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import ru.mecotrade.kidtracker.exception.KidTrackerInvalidOperationException;
import ru.mecotrade.kidtracker.model.Response;
import ru.mecotrade.kidtracker.model.User;
import ru.mecotrade.kidtracker.processor.UserProcessor;
import ru.mecotrade.kidtracker.security.UserPrincipal;
import ru.mecotrade.kidtracker.util.ValidationUtils;

@Controller
@Slf4j
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private UserProcessor userProcessor;

    @PostMapping("/user")
    @ResponseBody HttpEntity<Response> create(@RequestBody User user, Authentication authentication) {

        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal) {
            if (isValid(user)) {
                UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
                try {
                    userProcessor.addUser(userPrincipal, user);
                    return ResponseEntity.noContent().build();
                } catch (KidTrackerInvalidOperationException ex) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new Response(ex.getMessage()));
                }
            } else {
                log.warn("{} can't be created since it is not valid", user);
                return ResponseEntity.unprocessableEntity().build();
            }
        } else {
            log.warn("Unauthorized request for user creation");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    private boolean isValid(User user) {
        return ValidationUtils.isValidPhone(user.getPhone())
                && user.getCredentials() != null
                && StringUtils.isNotBlank(user.getCredentials().getUsername())
                && StringUtils.isNotBlank(user.getCredentials().getPassword())
                && StringUtils.isNotBlank(user.getName());
    }
}
