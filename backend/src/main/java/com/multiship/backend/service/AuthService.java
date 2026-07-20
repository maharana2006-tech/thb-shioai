package com.multiship.backend.service;

import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.dto.LoginRequest; // Ensure this is imported
import org.springframework.http.ResponseEntity;

public interface AuthService {
    ResponseEntity<MessageResponse> registerUser(SignupRequest signupRequest);
    ResponseEntity<?> loginUser(LoginRequest loginRequest); // Add this line
    ResponseEntity<MessageResponse> logoutUser(String tokenHeader);
}