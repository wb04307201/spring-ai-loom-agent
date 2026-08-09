package cn.wubo.spring.ai.loom.agent.user;

import jakarta.servlet.http.HttpServletRequest;

public class UserContextHolder {

 private static final ThreadLocal<String> currentUser = new ThreadLocal<>();

 public static void setCurrentUser(String username) {
 currentUser.set(username);
 }

 public static String getCurrentUser() {
 return currentUser.get();
 }

 public static String getCurrentUser(HttpServletRequest request) {
 return (String) request.getAttribute("LOOM_USER_CONTEXT");
 }

 public static void clear() {
 currentUser.remove();
 }
}
