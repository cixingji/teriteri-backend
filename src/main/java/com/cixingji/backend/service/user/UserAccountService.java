package com.cixingji.backend.service.user;

import com.cixingji.backend.pojo.entity.CustomResponse;

import java.io.IOException;

public interface UserAccountService {
    CustomResponse register(String username, String password, String confirmedPassword,
                            String deviceName, String ipAddress) throws IOException;

    CustomResponse login(String username, String password, String deviceName, String ipAddress);

    CustomResponse adminLogin(String username, String password, String deviceName, String ipAddress);

    CustomResponse personalInfo();

    CustomResponse adminPersonalInfo();

    void logout();

    void logoutAll();

    void adminLogout();

    CustomResponse updatePassword(String currentPassword, String newPassword);

    CustomResponse setInitialPassword(String newPassword);
}
