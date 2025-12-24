package com.spendify.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private LoginAttemptService loginAttemptService;
    private final String TEST_KEY = "test@user.com";
    private final String OTHER_KEY = "other@user.com";

    @BeforeEach
    void setUp() {
        // Initialize a new service for each test to ensure a clean cache state
        loginAttemptService = new LoginAttemptService();
    }

    @Test
    void loginSucceeded_shouldRemoveUserFromCache() {
        // Given a user with some failed attempts
        loginAttemptService.loginFailed(TEST_KEY);
        loginAttemptService.loginFailed(TEST_KEY);
        assertThat(loginAttemptService.isBlocked(TEST_KEY)).isFalse(); // Not yet blocked

        // When login succeeds
        loginAttemptService.loginSucceeded(TEST_KEY);

        // Then user should not be blocked and attempts should be cleared
        assertThat(loginAttemptService.isBlocked(TEST_KEY)).isFalse();
        // Indirectly check cache is cleared by failing enough times to block
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPT - 1; i++) {
            loginAttemptService.loginFailed(TEST_KEY);
        }
        assertThat(loginAttemptService.isBlocked(TEST_KEY)).isFalse(); // Should still be false after (MAX_ATTEMPT - 1) failures post-success
    }

    @Test
    void loginFailed_shouldIncrementAttemptCount() {
        // Given a new user
        assertThat(loginAttemptService.isBlocked(TEST_KEY)).isFalse();

        // When login fails once
        loginAttemptService.loginFailed(TEST_KEY);

        // Then user should not be blocked yet
        assertThat(loginAttemptService.isBlocked(TEST_KEY)).isFalse();

        // When login fails again
        loginAttemptService.loginFailed(TEST_KEY);
        assertThat(loginAttemptService.isBlocked(TEST_KEY)).isFalse();
    }

    @Test
    void loginFailed_shouldEventuallyBlockUser() {
        // Given a user with (MAX_ATTEMPT - 1) failed attempts
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPT - 1; i++) {
            loginAttemptService.loginFailed(TEST_KEY);
        }
        assertThat(loginAttemptService.isBlocked(TEST_KEY)).isFalse(); // Not blocked yet

        // When login fails one more time
        loginAttemptService.loginFailed(TEST_KEY);

        // Then user should be blocked
        assertThat(loginAttemptService.isBlocked(TEST_KEY)).isTrue();
    }

    @Test
    void isBlocked_shouldReturnFalseForUnblockedUser() {
        // Given a user with less than MAX_ATTEMPT failed attempts
        loginAttemptService.loginFailed(TEST_KEY); // 1 attempt
        loginAttemptService.loginFailed(TEST_KEY); // 2 attempts
        
        // When checking if blocked
        boolean isBlocked = loginAttemptService.isBlocked(TEST_KEY);

        // Then should be false
        assertThat(isBlocked).isFalse();
    }

    @Test
    void isBlocked_shouldReturnTrueForBlockedUser() {
        // Given a user with MAX_ATTEMPT failed attempts
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPT; i++) {
            loginAttemptService.loginFailed(TEST_KEY);
        }
        
        // When checking if blocked
        boolean isBlocked = loginAttemptService.isBlocked(TEST_KEY);

        // Then should be true
        assertThat(isBlocked).isTrue();
    }
    
    @Test
    void isBlocked_shouldNotAffectOtherUsers() {
        // Given one user is blocked
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPT; i++) {
            loginAttemptService.loginFailed(TEST_KEY);
        }
        assertThat(loginAttemptService.isBlocked(TEST_KEY)).isTrue();

        // When checking another user
        boolean isOtherBlocked = loginAttemptService.isBlocked(OTHER_KEY);

        // Then the other user should not be blocked
        assertThat(isOtherBlocked).isFalse();
    }
}
