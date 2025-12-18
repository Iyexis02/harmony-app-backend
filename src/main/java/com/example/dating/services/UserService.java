package com.example.dating.services;

import com.example.dating.models.user.domain.User;
import com.example.dating.models.user.common.dto.UserDtoRequest;
import com.example.dating.models.user.common.dto.UserDtoResponse;

public interface UserService {
  UserDtoResponse findOrCreateUser(UserDtoRequest userDto);
  UserDtoResponse userExists(String spotifyId);
  UserDtoResponse getUserBySpotifyId(String spotifyId);
  String getValidSpotifyToken(User user);
  String refreshAndUpdateUserToken(User user);
}