package cn.wubo.loom.http.core.profile;

public class ProfileNotFoundException extends RuntimeException {
    public ProfileNotFoundException(String name) {
        super("Profile not found: " + name);
    }
}
