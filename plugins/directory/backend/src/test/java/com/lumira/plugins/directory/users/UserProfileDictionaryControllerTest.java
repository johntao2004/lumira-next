package com.lumira.plugins.directory.users;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.identity.api.ProfileDictionarySelectionPort;
import com.lumira.platform.identity.api.ProfileDictionarySelectionPort.Host;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class UserProfileDictionaryControllerTest {
    @Test
    void bothLookupRoutesCheckTargetScopeBeforeConsultingPublishedFields() {
        var users = mock(UserService.class);
        var selections = mock(ProfileDictionarySelectionPort.class);
        var controller = new UserProfileDictionaryController(users, selections);
        var auth = new UsernamePasswordAuthenticationToken("operator", "");
        var denied = UUID.randomUUID();
        when(users.find(denied, "operator"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(
                ResponseStatusException.class,
                () -> controller.list(denied, "department", 1, 10, "", auth));
        assertThrows(
                ResponseStatusException.class,
                () -> controller.find(denied, "department", UUID.randomUUID(), auth));
        verifyNoInteractions(selections);
        var permitted = UUID.randomUUID();
        var item = UUID.randomUUID();
        controller.list(permitted, "department", 2, 10, "sales", auth);
        controller.find(permitted, "department", item, auth);
        var order = inOrder(users, selections);
        order.verify(users).find(permitted, "operator");
        order.verify(selections)
                .list(Host.USER_EDITOR, "department", new PageRequest(2, 10, "sales"));
        order.verify(users).find(permitted, "operator");
        order.verify(selections).find(Host.USER_EDITOR, "department", item);
    }
}
