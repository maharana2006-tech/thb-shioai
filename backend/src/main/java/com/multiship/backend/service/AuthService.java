package com.multiship.backend.service;

import com.multiship.backend.dto.AcceptInviteRequest;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.dto.LoginRequest; // Ensure this is imported
import org.springframework.http.ResponseEntity;

public interface AuthService {
    ResponseEntity<MessageResponse> registerUser(SignupRequest signupRequest, String remoteIp);
    ResponseEntity<?> loginUser(LoginRequest loginRequest); // Add this line
    ResponseEntity<MessageResponse> logoutUser(String tokenHeader);

    /** Sprint 50 Tier 0.5 PR D — invitee posts token + credentials; server creates verified user. */
    ResponseEntity<MessageResponse> acceptInvite(AcceptInviteRequest request);

    /** Sprint 50 Tier 0.5 PR D — email-verification click-through. */
    ResponseEntity<MessageResponse> verifyEmail(String token);
}