package sportground;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// passwords and logins live here
// passwords are hashed, never saved as plain text
public class Auth {
    // logged-in users: sessionId -> userId
    private static final Map<String, String> sessions = new ConcurrentHashMap<>();
    // random salt, mixed into the password before hashing
    public static String genSalt() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16); }

    // turn a password into a hash
    public static String hash(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest((salt + "::" + password).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // check a typed password against the saved hash
    public static boolean verify(String password, String salt, String expected) {
        return hash(password, salt).equals(expected);
    }

    // log a user in, returns the cookie value
    public static String createSession(String userId) {
        String sid = UUID.randomUUID().toString();
        sessions.put(sid, userId);
        return sid;
    }

    // who owns this cookie, null when logged out
    public static String getUserId(String sid) { return sid == null ? null : sessions.get(sid); }
    // logout
    public static void destroy(String sid) { if (sid != null) sessions.remove(sid); }
}
