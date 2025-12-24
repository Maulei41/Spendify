package com.spendify.backend.service;

import com.spendify.backend.dto.AuthenticationResponse;
import com.spendify.backend.dto.LoginRequest;
import com.spendify.backend.dto.RefreshTokenRequest;
import com.spendify.backend.dto.RegisterRequest;
import com.spendify.backend.entity.RefreshToken;
import com.spendify.backend.entity.User;
import com.spendify.backend.exception.AccountLockedException;
import com.spendify.backend.exception.DuplicateEmailException;
import com.spendify.backend.exception.InvalidCredentialsException;
import com.spendify.backend.exception.ResourceNotFoundException;
import com.spendify.backend.repository.RefreshTokenRepository;
import com.spendify.backend.repository.UserRepository;
import com.spendify.backend.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private LoginAttemptService loginAttemptService;

    @InjectMocks
    private AuthService authService;

    @Test
    void register_whenEmailIsNew_shouldCreateUserAndReturnTokens() {
        // Given
        RegisterRequest request = new RegisterRequest("new@user.com", "password");
        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encodedPassword");

        User savedUser = User.builder().id(1L).email(request.getEmail()).password("encodedPassword").build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        when(jwtTokenProvider.generateToken(any(User.class))).thenReturn("jwt-token");
        
        // When
        AuthenticationResponse response = authService.register(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getRefreshToken()).isNotNull();

        verify(userRepository).save(argThat(user ->
                user.getEmail().equals(request.getEmail()) &&
                user.getPassword().equals("encodedPassword")
        ));
    }

    @Test
    void register_whenEmailAlreadyExists_shouldThrowDuplicateEmailException() {
        // Given
        RegisterRequest request = new RegisterRequest("existing@user.com", "password");
        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(new User()));

        // When & Then
        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessage("Email already taken");

        verify(userRepository, never()).save(any());
    }

    @Test
    void login_whenCredentialsAreValid_shouldReturnTokens() {
        // Given
        LoginRequest request = new LoginRequest("test@user.com", "password");
        User user = User.builder().id(1L).email(request.getEmail()).build();

        when(loginAttemptService.isBlocked(request.getEmail())).thenReturn(false);
        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateToken(any(User.class))).thenReturn("jwt-token");

        // When
        AuthenticationResponse response = authService.login(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getToken()).isEqualTo("jwt-token");
        verify(authenticationManager).authenticate(any());
        verify(loginAttemptService).loginSucceeded(request.getEmail());
    }

    @Test
    void login_whenCredentialsAreInvalid_shouldThrowInvalidCredentialsException() {
        // Given
        LoginRequest request = new LoginRequest("test@user.com", "wrong-password");
        when(loginAttemptService.isBlocked(request.getEmail())).thenReturn(false);
        doThrow(new BadCredentialsException("bad creds")).when(authenticationManager).authenticate(any());

        // When & Then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");

        verify(loginAttemptService).loginFailed(request.getEmail());
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void login_whenAccountIsLocked_shouldThrowAccountLockedException() {
        // Given
        LoginRequest request = new LoginRequest("locked@user.com", "password");
        when(loginAttemptService.isBlocked(request.getEmail())).thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AccountLockedException.class)
                .hasMessage("Account is locked");

        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void getCurrentUser_whenAuthenticated_shouldReturnUserResponse() {
        // Given
        User authenticatedUser = User.builder().id(1L).email("authenticated@user.com").build();

        // Mock SecurityContextHolder
        SecurityContext securityContext = mock(SecurityContext.class);
        Authentication authentication = mock(Authentication.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        when(authentication.getName()).thenReturn(authenticatedUser.getEmail());
        when(userRepository.findByEmail(authenticatedUser.getEmail())).thenReturn(Optional.of(authenticatedUser));

        // When
        var response = authService.getCurrentUser();

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(authenticatedUser.getId());
        assertThat(response.getEmail()).isEqualTo(authenticatedUser.getEmail());
    }

    @Test
    void getCurrentUser_whenUserNotFoundInRepo_shouldThrowResourceNotFoundException() {
        // Given
        String nonExistentEmail = "nonexistent@user.com";

        // Mock SecurityContextHolder
        SecurityContext securityContext = mock(SecurityContext.class);
        Authentication authentication = mock(Authentication.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        when(authentication.getName()).thenReturn(nonExistentEmail);
        when(userRepository.findByEmail(nonExistentEmail)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> authService.getCurrentUser())
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found");
    }


    @Test
    void refreshToken_whenValidToken_shouldReturnNewJwt() {
        // Given
        String oldRefreshTokenString = "old-refresh-token";
        String newJwtToken = "new-jwt-token";
        User user = User.builder().id(1L).email("user@example.com").build();
        RefreshToken oldRefreshToken = RefreshToken.builder()
                .token(oldRefreshTokenString)
                .user(user)
                .expiryDate(LocalDateTime.now().plusDays(1))
                .build();
        
        when(refreshTokenRepository.findByToken(oldRefreshTokenString)).thenReturn(Optional.of(oldRefreshToken));
        when(jwtTokenProvider.generateToken(user)).thenReturn(newJwtToken);

        // When
        AuthenticationResponse response = authService.refreshToken(new RefreshTokenRequest(oldRefreshTokenString));

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getToken()).isEqualTo(newJwtToken);
        assertThat(response.getRefreshToken()).isEqualTo(oldRefreshTokenString);
    }

    @Test
    void refreshToken_whenTokenNotFound_shouldThrowRuntimeException() {
        // Given
        String nonExistentToken = "non-existent-token";
        when(refreshTokenRepository.findByToken(nonExistentToken)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequest(nonExistentToken)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Refresh token not found");
    }

    @Test
    void refreshToken_whenTokenExpired_shouldThrowRuntimeExceptionAndRemoveToken() {
        // Given
        String expiredTokenString = "expired-token";
        User user = User.builder().id(1L).email("user@example.com").build();
        RefreshToken expiredRefreshToken = RefreshToken.builder()
                .token(expiredTokenString)
                .user(user)
                .expiryDate(LocalDateTime.now().minusDays(1)) // Expired
                .build();
        
        when(refreshTokenRepository.findByToken(expiredTokenString)).thenReturn(Optional.of(expiredRefreshToken));

        // When & Then
        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequest(expiredTokenString)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Refresh token expired");
        
        verify(refreshTokenRepository).delete(expiredRefreshToken);
    }

    @Test
    void logout_whenTokenExists_shouldDeleteToken() {
        // Given
        String tokenToDelete = "token-to-delete";
        RefreshToken existingToken = RefreshToken.builder().token(tokenToDelete).build();
        when(refreshTokenRepository.findByToken(tokenToDelete)).thenReturn(Optional.of(existingToken));

        // When
        authService.logout(new RefreshTokenRequest(tokenToDelete));

        // Then
        verify(refreshTokenRepository).delete(existingToken);
    }

    @Test
    void logout_whenTokenNotFound_shouldDoNothing() {
        // Given
        String nonExistentToken = "non-existent-token";
        when(refreshTokenRepository.findByToken(nonExistentToken)).thenReturn(Optional.empty());

        // When
        authService.logout(new RefreshTokenRequest(nonExistentToken));

        // Then
        verify(refreshTokenRepository, never()).delete(any());
    }
}
