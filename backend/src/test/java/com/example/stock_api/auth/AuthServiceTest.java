package com.example.stock_api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.stock_api.common.ApiException;

class AuthServiceTest {

    private final UserMapper users = mock(UserMapper.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AuthService auth = new AuthService(users, encoder);

    private static AuthRequests.Signup signup() {
        return new AuthRequests.Signup("tester", "password1", " 테스터 ");
    }

    @Test
    void passwordIsStoredAsHash() {
        auth.signup(signup());
        ArgumentCaptor<String> hash = forClass(String.class);
        verify(users).insert(eq("tester"), hash.capture(), eq("테스터"));
        assertNotEquals("password1", hash.getValue());
        assertTrue(encoder.matches("password1", hash.getValue()));
    }

    @Test
    void duplicateLoginIdIsRejected() {
        when(users.findByLoginId("tester")).thenReturn(mock(AppUser.class));
        assertEquals("LOGIN_ID_TAKEN", assertThrows(ApiException.class, () -> auth.signup(signup())).getCode());
        verify(users, never()).insert(any(), any(), any());
    }

    @Test
    void simultaneousDuplicateSignupIsRejected() {
        doThrow(new DuplicateKeyException("중복")).when(users).insert(any(), any(), any());
        assertEquals("LOGIN_ID_TAKEN", assertThrows(ApiException.class, () -> auth.signup(signup())).getCode());
    }

    @Test
    void unknownIdAndWrongPasswordGiveSameError() {
        ApiException unknown = assertThrows(ApiException.class,
                () -> auth.login(new AuthRequests.Login("nobody", "password1")));

        AppUser user = mock(AppUser.class);
        when(user.getPasswordHash()).thenReturn(encoder.encode("password1"));
        when(users.findByLoginId("tester")).thenReturn(user);
        ApiException wrong = assertThrows(ApiException.class,
                () -> auth.login(new AuthRequests.Login("tester", "wrong-password")));

        assertEquals("LOGIN_FAILED", unknown.getCode());
        assertEquals(unknown.getCode(), wrong.getCode());
        assertEquals(unknown.getMessage(), wrong.getMessage());
    }
}
